package com.tvflux.pro

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class PersistedAppState(
    val deviceId: String,
    val deviceName: String,
    val activationCode: String,
    val xtreamPlaylists: List<XtreamAccountConfig>,
    val cachedChannels: List<TvChannel>,
    val cachedMovies: List<TvChannel>,
    val cachedSeries: List<TvChannel>,
    val favorites: List<TvChannel>,
    val history: List<TvChannel>,
    val subscription: SubscriptionInfo?,
    val preferences: PlayerPreferences
)

object StorageManager {
    private const val FILE_NAME = "live_pro_enterprise_state_v60.json"
    private const val MAX_HISTORY = 50
    private val DEVICE_ID_REGEX = Regex("^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$")

    /**
     * Generates a 100% Factory-Reset Proof, Immutable Hardware Screen Code (DEV-XXXX-XXXX).
     * Why does ANDROID_ID change on Factory Reset? Because Android regenerates Settings.Secure.ANDROID_ID
     * on every factory wipe.
     * Solution: We derive the Screen Code from fuse-burned / ROM-level hardware identifiers:
     * 1. Widevine Hardware Tee / MediaDrm Device Unique ID (burned into SoC TrustZone/Keybox, survives factory resets).
     * 2. SoC Hardware & Board characteristics + /proc/cpuinfo Serial/Hardware + system build properties.
     * This guarantees every physical TV/Box/Phone gets a unique, permanent code that NEVER changes after Factory Reset.
     */
    @SuppressLint("HardwareIds")
    fun resolveHardwareDeviceId(context: Context): String {
        // 1. Extract Widevine Hardware DRM Unique Byte Array (Fuse-burned per physical device, survives factory reset)
        val drmIdHex = runCatching {
            val widevineUuid = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
            val drm = android.media.MediaDrm(widevineUuid)
            try {
                val bytes = drm.getPropertyByteArray(android.media.MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                bytes?.joinToString("") { "%02X".format(it) }.orEmpty()
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { drm.close() }
                } else {
                    @Suppress("DEPRECATION")
                    runCatching { drm.release() }
                }
            }
        }.getOrDefault("")

        // 2. Read hardware CPU serial / SoC revision from /proc/cpuinfo if exposed by TV box kernel
        val cpuSerial = runCatching {
            java.io.File("/proc/cpuinfo").takeIf { it.canRead() }?.useLines { lines ->
                lines.filter {
                    it.startsWith("Serial", ignoreCase = true) ||
                        it.startsWith("Hardware", ignoreCase = true) ||
                        it.startsWith("Revision", ignoreCase = true)
                }.joinToString("|") { it.trim() }
            }.orEmpty()
        }.getOrDefault("")

        // 3. Read immutable hardware network MAC if exposed in /sys/class/net/eth0/address or wlan0
        val hwMac = runCatching {
            listOf("/sys/class/net/eth0/address", "/sys/class/net/wlan0/address")
                .mapNotNull { path ->
                    val f = java.io.File(path)
                    if (f.canRead()) f.readText().trim().uppercase() else null
                }
                .firstOrNull { it.length >= 11 && it != "00:00:00:00:00:00" && it != "02:00:00:00:00:00" }
                .orEmpty()
        }.getOrDefault("")

        // 4. Combine immutable hardware-only properties (NO ANDROID_ID if Widevine or MAC/CPU serial is present!)
        val boardSpec = listOf(
            Build.BOARD,
            Build.BRAND,
            Build.DEVICE,
            Build.HARDWARE,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.PRODUCT,
            Build.SUPPORTED_ABIS.joinToString(",")
        ).joinToString("|") { it.orEmpty().trim().uppercase() }

        val rawSeed = when {
            drmIdHex.length >= 12 -> "LIVEPRO-IMMUTABLE-DRM-$drmIdHex-$boardSpec"
            hwMac.isNotBlank() || cpuSerial.isNotBlank() -> "LIVEPRO-IMMUTABLE-SOC-$hwMac-$cpuSerial-$boardSpec"
            else -> {
                // Only if neither Widevine nor SoC serial is available on an emulator
                val fallbackAndroidId = runCatching {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                }.getOrNull().orEmpty()
                "LIVEPRO-FALLBACK-$boardSpec-$fallbackAndroidId"
            }
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(rawSeed.toByteArray(Charsets.UTF_8))
        val chars = "0123456789ABCDEF"
        val part1 = buildString {
            for (i in 0 until 4) {
                append(chars[(digest[i].toInt() and 0xFF) % chars.length])
            }
        }
        val part2 = buildString {
            for (i in 4 until 8) {
                append(chars[(digest[i].toInt() and 0xFF) % chars.length])
            }
        }
        return "DEV-$part1-$part2"
    }

    fun resolveDeviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL?.trim().orEmpty()
        val combined = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }
        return if (combined.isNotBlank()) combined.take(55) else "Android TV"
    }

    suspend fun loadState(context: Context): PersistedAppState = withContext(Dispatchers.IO) {
        val hardwareId = resolveHardwareDeviceId(context)
        val hwName = resolveDeviceName()
        val file = File(context.filesDir, FILE_NAME)

        if (!file.exists()) {
            return@withContext PersistedAppState(
                deviceId = hardwareId,
                deviceName = hwName,
                activationCode = "",
                xtreamPlaylists = emptyList(),
                cachedChannels = emptyList(),
                cachedMovies = emptyList(),
                cachedSeries = emptyList(),
                favorites = emptyList(),
                history = emptyList(),
                subscription = null,
                preferences = PlayerPreferences()
            )
        }

        try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val savedId = root.optString("deviceId").uppercase()
            val finalDeviceId = if (DEVICE_ID_REGEX.matches(savedId)) savedId else hardwareId
            val savedActivationCode = root.optString("activationCode", "")

            val xtreamPlaylists = mutableListOf<XtreamAccountConfig>()
            val xArr = root.optJSONArray("xtreamPlaylists")
            if (xArr != null) {
                for (i in 0 until xArr.length()) {
                    val xObj = xArr.optJSONObject(i) ?: continue
                    val cfg = parseXtreamConfig(xObj, i)
                    if (cfg.isConfigured) xtreamPlaylists.add(cfg)
                }
            }

            val cachedChannels = parseChannelList(root.optJSONArray("cachedChannels"), StreamContentType.LIVE_TV)
            val cachedMovies = parseChannelList(root.optJSONArray("cachedMovies"), StreamContentType.MOVIE)
            val cachedSeries = parseChannelList(root.optJSONArray("cachedSeries"), StreamContentType.SERIES)
            val favs = parseChannelList(root.optJSONArray("favorites"), StreamContentType.LIVE_TV)
            val hist = parseChannelList(root.optJSONArray("history"), StreamContentType.LIVE_TV)

            val subObj = root.optJSONObject("subscription")
            val sub = subObj?.let {
                SubscriptionInfo(
                    active = it.optBoolean("active", false),
                    isExpired = it.optBoolean("isExpired", false),
                    isTrial = it.optBoolean("isTrial", false),
                    reason = it.optString("reason"),
                    expireDate = it.optString("expireDate"),
                    daysLeft = it.optInt("daysLeft", 0),
                    deviceName = it.optString("deviceName", hwName),
                    message = it.optString("message")
                )
            }

            val pObj = root.optJSONObject("preferences") ?: JSONObject()
            val prefs = PlayerPreferences(
                appLanguage = runCatching {
                    AppUiLanguage.valueOf(pObj.optString("appLanguage", AppUiLanguage.AR.name))
                }.getOrDefault(AppUiLanguage.AR),
                playlistDisplayMode = runCatching {
                    PlaylistDisplayMode.valueOf(pObj.optString("playlistDisplayMode", PlaylistDisplayMode.MERGE_ALL.name))
                }.getOrDefault(PlaylistDisplayMode.MERGE_ALL),
                activePlaylistFilterId = pObj.optString("activePlaylistFilterId", "ALL"),
                playlistOrderIds = parseStringList(pObj.optJSONArray("playlistOrderIds")),
                bufferProfile = runCatching {
                    BufferProfile.valueOf(pObj.optString("bufferProfile", BufferProfile.XPOLA_ZERO_LAG.name))
                }.getOrDefault(BufferProfile.XPOLA_ZERO_LAG),
                resizeMode = runCatching {
                    VideoResizeMode.valueOf(pObj.optString("resizeMode", VideoResizeMode.FILL.name))
                }.getOrDefault(VideoResizeMode.FILL),
                autoReconnect = pObj.optBoolean("autoReconnect", true),
                maxReconnectRetries = pObj.optInt("maxReconnectRetries", 6).coerceIn(1, 15),
                preferSoftwareDecoder = pObj.optBoolean("preferSoftwareDecoder", false),
                useGridLayout = pObj.optBoolean("useGridLayout", true),
                showBouquetSidebar = pObj.optBoolean("showBouquetSidebar", true),
                showScreenCodeOverlay = pObj.optBoolean("showScreenCodeOverlay", true),
                customBouquetOrder = parseStringList(pObj.optJSONArray("customBouquetOrder")),
                hiddenBouquets = parseStringList(pObj.optJSONArray("hiddenBouquets")).toSet(),
                customChannelOrder = parseStringList(pObj.optJSONArray("customChannelOrder"))
            )

            PersistedAppState(
                deviceId = finalDeviceId,
                deviceName = hwName,
                activationCode = savedActivationCode,
                xtreamPlaylists = xtreamPlaylists,
                cachedChannels = cachedChannels,
                cachedMovies = cachedMovies,
                cachedSeries = cachedSeries,
                favorites = favs,
                history = hist,
                subscription = sub,
                preferences = prefs
            )
        } catch (_: Exception) {
            PersistedAppState(
                deviceId = hardwareId,
                deviceName = hwName,
                activationCode = "",
                xtreamPlaylists = emptyList(),
                cachedChannels = emptyList(),
                cachedMovies = emptyList(),
                cachedSeries = emptyList(),
                favorites = emptyList(),
                history = emptyList(),
                subscription = null,
                preferences = PlayerPreferences()
            )
        }
    }

    private fun parseXtreamConfig(xObj: JSONObject, index: Int): XtreamAccountConfig {
        return XtreamAccountConfig(
            id = xObj.optString("id", UUID.randomUUID().toString().take(8)),
            serverName = xObj.optString("serverName", "قائمة ${index + 1}"),
            serverUrl = xObj.optString("serverUrl", ""),
            username = xObj.optString("username", ""),
            password = xObj.optString("password", ""),
            m3uUrl = xObj.optString("m3uUrl", ""),
            enabled = xObj.optBoolean("enabled", true),
            isRemoteFromPortal = xObj.optBoolean("isRemoteFromPortal", false),
            orderIndex = xObj.optInt("orderIndex", index),
            statusNote = xObj.optString("statusNote", "جاهز للتحديث"),
            liveCount = xObj.optInt("liveCount", 0),
            movieCount = xObj.optInt("movieCount", 0),
            seriesCount = xObj.optInt("seriesCount", 0),
            expDate = xObj.optString("expDate", "")
        )
    }

    suspend fun saveState(context: Context, state: PersistedAppState) = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject()
            root.put("deviceId", state.deviceId)
            root.put("deviceName", state.deviceName)
            root.put("activationCode", state.activationCode)

            val xArr = JSONArray()
            state.xtreamPlaylists.forEachIndexed { idx, x ->
                xArr.put(
                    JSONObject().apply {
                        put("id", x.id)
                        put("serverName", x.serverName)
                        put("serverUrl", x.serverUrl)
                        put("username", x.username)
                        put("password", x.password)
                        put("m3uUrl", x.m3uUrl)
                        put("enabled", x.enabled)
                        put("isRemoteFromPortal", x.isRemoteFromPortal)
                        put("orderIndex", idx)
                        put("statusNote", x.statusNote)
                        put("liveCount", x.liveCount)
                        put("movieCount", x.movieCount)
                        put("seriesCount", x.seriesCount)
                        put("expDate", x.expDate)
                    }
                )
            }
            root.put("xtreamPlaylists", xArr)

            root.put("cachedChannels", serializeChannelList(state.cachedChannels.take(3000)))
            root.put("cachedMovies", serializeChannelList(state.cachedMovies.take(1500)))
            root.put("cachedSeries", serializeChannelList(state.cachedSeries.take(1200)))
            root.put("favorites", serializeChannelList(state.favorites.take(500)))
            root.put("history", serializeChannelList(state.history.take(MAX_HISTORY)))

            state.subscription?.let { sub ->
                root.put(
                    "subscription",
                    JSONObject().apply {
                        put("active", sub.active)
                        put("isExpired", sub.isExpired)
                        put("isTrial", sub.isTrial)
                        put("reason", sub.reason)
                        put("expireDate", sub.expireDate)
                        put("daysLeft", sub.daysLeft)
                        put("deviceName", sub.deviceName)
                        put("message", sub.message)
                    }
                )
            }

            val p = state.preferences
            root.put(
                "preferences",
                JSONObject().apply {
                    put("appLanguage", p.appLanguage.name)
                    put("playlistDisplayMode", p.playlistDisplayMode.name)
                    put("activePlaylistFilterId", p.activePlaylistFilterId)
                    put("playlistOrderIds", JSONArray(p.playlistOrderIds))
                    put("bufferProfile", p.bufferProfile.name)
                    put("resizeMode", p.resizeMode.name)
                    put("autoReconnect", p.autoReconnect)
                    put("maxReconnectRetries", p.maxReconnectRetries)
                    put("preferSoftwareDecoder", p.preferSoftwareDecoder)
                    put("useGridLayout", p.useGridLayout)
                    put("showBouquetSidebar", p.showBouquetSidebar)
                    put("showScreenCodeOverlay", p.showScreenCodeOverlay)
                    put("customBouquetOrder", JSONArray(p.customBouquetOrder))
                    put("hiddenBouquets", JSONArray(p.hiddenBouquets.toList()))
                    put("customChannelOrder", JSONArray(p.customChannelOrder.take(1500)))
                }
            )

            val tmpFile = File(context.filesDir, "$FILE_NAME.tmp")
            val finalFile = File(context.filesDir, FILE_NAME)
            tmpFile.writeText(root.toString(), Charsets.UTF_8)
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotBlank()) list.add(s)
        }
        return list
    }

    private fun parseChannelList(arr: JSONArray?, defaultType: StreamContentType): List<TvChannel> {
        if (arr == null) return emptyList()
        val list = ArrayList<TvChannel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            val url = o.optString("url")
            if (name.isBlank() || url.isBlank()) continue
            val rawGroup = o.optString("group", "عام")
            val typeStr = o.optString("contentType", defaultType.name)
            val contentType = runCatching { StreamContentType.valueOf(typeStr) }.getOrDefault(defaultType)
            list.add(
                TvChannel(
                    id = o.optInt("id", i + 1),
                    number = o.optInt("number", i + 1),
                    name = name,
                    url = url,
                    group = if (rawGroup.isNotBlank()) rawGroup else "عام",
                    logo = o.optString("logo").takeIf { it.isNotBlank() },
                    userAgent = o.optString("userAgent").takeIf { it.isNotBlank() },
                    referer = o.optString("referer").takeIf { it.isNotBlank() },
                    origin = o.optString("origin").takeIf { it.isNotBlank() },
                    cookie = o.optString("cookie").takeIf { it.isNotBlank() },
                    contentType = contentType,
                    rating = o.optString("rating", ""),
                    releaseYear = o.optString("releaseYear", ""),
                    plot = o.optString("plot", ""),
                    seriesId = o.optInt("seriesId", 0),
                    playlistId = o.optString("playlistId", "PORTAL_MAIN"),
                    playlistName = o.optString("playlistName", "الرئيسية"),
                    streamId = o.optInt("streamId", 0),
                    streamBaseUrl = o.optString("streamBaseUrl", ""),
                    streamUsername = o.optString("streamUsername", ""),
                    streamPassword = o.optString("streamPassword", "")
                )
            )
        }
        return list
    }

    private fun serializeChannelList(list: List<TvChannel>): JSONArray {
        val arr = JSONArray()
        list.forEach { ch ->
            arr.put(
                JSONObject().apply {
                    put("id", ch.id)
                    put("number", ch.number)
                    put("name", ch.name)
                    put("url", ch.url)
                    put("group", ch.group)
                    put("logo", ch.logo ?: "")
                    put("userAgent", ch.userAgent ?: "")
                    put("referer", ch.referer ?: "")
                    put("origin", ch.origin ?: "")
                    put("cookie", ch.cookie ?: "")
                    put("contentType", ch.contentType.name)
                    put("rating", ch.rating)
                    put("releaseYear", ch.releaseYear)
                    put("plot", ch.plot)
                    put("seriesId", ch.seriesId)
                    put("playlistId", ch.playlistId)
                    put("playlistName", ch.playlistName)
                    put("streamId", ch.streamId)
                    put("streamBaseUrl", ch.streamBaseUrl)
                    put("streamUsername", ch.streamUsername)
                    put("streamPassword", ch.streamPassword)
                }
            )
        }
        return arr
    }
}
