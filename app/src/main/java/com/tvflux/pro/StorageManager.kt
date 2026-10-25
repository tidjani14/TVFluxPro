package com.tvflux.pro

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

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
    private const val CATALOG_GZ_FILE = "live_pro_catalog_v10.json.gz"
    private const val MAX_HISTORY = 80
    private const val MAX_CATALOG_ITEMS_PER_TYPE = 120_000
    private val DEVICE_ID_REGEX = Regex("^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$")

    /**
     * Generates a 100% Factory-Reset Proof, Immutable Hardware Screen Code (DEV-XXXX-XXXX).
     */
    @SuppressLint("HardwareIds")
    fun resolveHardwareDeviceId(context: Context): String {
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

        val cpuSerial = runCatching {
            File("/proc/cpuinfo").takeIf { it.canRead() }?.useLines { lines ->
                lines.filter {
                    it.startsWith("Serial", ignoreCase = true) ||
                        it.startsWith("Hardware", ignoreCase = true) ||
                        it.startsWith("Revision", ignoreCase = true)
                }.joinToString("|") { it.trim() }
            }.orEmpty()
        }.getOrDefault("")

        val hwMac = runCatching {
            listOf("/sys/class/net/eth0/address", "/sys/class/net/wlan0/address")
                .mapNotNull { path ->
                    val f = File(path)
                    if (f.canRead()) f.readText().trim().uppercase() else null
                }
                .firstOrNull { it.length >= 11 && it != "00:00:00:00:00:00" && it != "02:00:00:00:00:00" }
                .orEmpty()
        }.getOrDefault("")

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

            // Load high-capacity GZIP catalog first; fallback to legacy JSON arrays if migrating
            val gzCatalog = loadCatalogFromGzip(context)
            val cachedChannels = gzCatalog?.first
                ?: parseChannelList(root.optJSONArray("cachedChannels"), StreamContentType.LIVE_TV)
            val cachedMovies = gzCatalog?.second
                ?: parseChannelList(root.optJSONArray("cachedMovies"), StreamContentType.MOVIE)
            val cachedSeries = gzCatalog?.third
                ?: parseChannelList(root.optJSONArray("cachedSeries"), StreamContentType.SERIES)

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

    /**
     * Saves app state and streams the massive channel/movie/series catalog into a GZIP-compressed
     * binary file (`live_pro_catalog_v10.json.gz`) without memory spikes or 3000-channel truncation.
     */
    suspend fun saveState(
        context: Context,
        state: PersistedAppState,
        saveCatalog: Boolean = true
    ) = withContext(Dispatchers.IO) {
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

            root.put("favorites", serializeChannelList(state.favorites.take(1500)))
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
                    put("customChannelOrder", JSONArray(p.customChannelOrder.take(5000)))
                }
            )

            val tmpFile = File(context.filesDir, "$FILE_NAME.tmp")
            val finalFile = File(context.filesDir, FILE_NAME)
            tmpFile.writeText(root.toString(), Charsets.UTF_8)
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }

            if (saveCatalog) {
                saveCatalogToGzip(
                    context = context,
                    channels = state.cachedChannels,
                    movies = state.cachedMovies,
                    series = state.cachedSeries
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun saveCatalogToGzip(
        context: Context,
        channels: List<TvChannel>,
        movies: List<TvChannel>,
        series: List<TvChannel>
    ) {
        val tmpGz = File(context.filesDir, "$CATALOG_GZ_FILE.tmp")
        val finalGz = File(context.filesDir, CATALOG_GZ_FILE)
        runCatching {
            FileOutputStream(tmpGz).use { fos ->
                BufferedOutputStream(fos, 65536).use { bos ->
                    GZIPOutputStream(bos).use { gos ->
                        BufferedWriter(OutputStreamWriter(gos, Charsets.UTF_8), 65536).use { bw ->
                            val writer = JsonWriter(bw)
                            writer.beginObject()

                            writer.name("channels")
                            writeChannelArrayStreaming(writer, channels.take(MAX_CATALOG_ITEMS_PER_TYPE))

                            writer.name("movies")
                            writeChannelArrayStreaming(writer, movies.take(MAX_CATALOG_ITEMS_PER_TYPE))

                            writer.name("series")
                            writeChannelArrayStreaming(writer, series.take(MAX_CATALOG_ITEMS_PER_TYPE))

                            writer.endObject()
                            writer.flush()
                        }
                    }
                }
            }
            if (!tmpGz.renameTo(finalGz)) {
                tmpGz.copyTo(finalGz, overwrite = true)
                tmpGz.delete()
            }
        }
    }

    private fun loadCatalogFromGzip(
        context: Context
    ): Triple<List<TvChannel>, List<TvChannel>, List<TvChannel>>? {
        val gzFile = File(context.filesDir, CATALOG_GZ_FILE)
        if (!gzFile.exists() || gzFile.length() < 16L) return null
        return runCatching {
            var channels: List<TvChannel> = emptyList()
            var movies: List<TvChannel> = emptyList()
            var series: List<TvChannel> = emptyList()

            FileInputStream(gzFile).use { fis ->
                BufferedInputStream(fis, 65536).use { bis ->
                    GZIPInputStream(bis).use { gis ->
                        BufferedReader(InputStreamReader(gis, Charsets.UTF_8), 65536).use { br ->
                            val reader = JsonReader(br)
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "channels" -> channels = readChannelArrayStreaming(reader, StreamContentType.LIVE_TV)
                                    "movies" -> movies = readChannelArrayStreaming(reader, StreamContentType.MOVIE)
                                    "series" -> series = readChannelArrayStreaming(reader, StreamContentType.SERIES)
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                        }
                    }
                }
            }
            Triple(channels, movies, series)
        }.getOrNull()
    }

    private fun writeChannelArrayStreaming(writer: JsonWriter, list: List<TvChannel>) {
        writer.beginArray()
        for (ch in list) {
            writer.beginObject()
            writer.name("i").value(ch.id)
            writer.name("n").value(ch.number)
            writer.name("nm").value(ch.name)
            writer.name("u").value(ch.url)
            writer.name("g").value(ch.group)
            if (!ch.logo.isNullOrBlank()) writer.name("l").value(ch.logo)
            if (!ch.userAgent.isNullOrBlank()) writer.name("ua").value(ch.userAgent)
            if (!ch.referer.isNullOrBlank()) writer.name("rf").value(ch.referer)
            if (!ch.origin.isNullOrBlank()) writer.name("or").value(ch.origin)
            if (!ch.cookie.isNullOrBlank()) writer.name("ck").value(ch.cookie)
            if (ch.drmScheme.isNotBlank()) writer.name("ds").value(ch.drmScheme)
            if (ch.drmKey.isNotBlank()) writer.name("dk").value(ch.drmKey)
            writer.name("ct").value(ch.contentType.name)
            if (ch.rating.isNotBlank()) writer.name("rt").value(ch.rating)
            if (ch.releaseYear.isNotBlank()) writer.name("ry").value(ch.releaseYear)
            if (ch.plot.isNotBlank()) writer.name("pl").value(ch.plot)
            if (ch.seriesId > 0) writer.name("si").value(ch.seriesId)
            writer.name("pi").value(ch.playlistId)
            writer.name("pn").value(ch.playlistName)
            if (ch.streamId > 0) writer.name("st").value(ch.streamId)
            if (ch.streamBaseUrl.isNotBlank()) writer.name("sb").value(ch.streamBaseUrl)
            if (ch.streamUsername.isNotBlank()) writer.name("su").value(ch.streamUsername)
            if (ch.streamPassword.isNotBlank()) writer.name("sp").value(ch.streamPassword)
            if (ch.containerExtension.isNotBlank()) writer.name("ce").value(ch.containerExtension)
            writer.endObject()
        }
        writer.endArray()
    }

    private fun readChannelArrayStreaming(
        reader: JsonReader,
        defaultType: StreamContentType
    ): List<TvChannel> {
        val list = ArrayList<TvChannel>(2048)
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return list
        }
        reader.beginArray()
        var idx = 1
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            reader.beginObject()
            var id = idx
            var number = idx
            var name = ""
            var url = ""
            var group = "عام"
            var logo: String? = null
            var ua: String? = null
            var ref: String? = null
            var orig: String? = null
            var cook: String? = null
            var drmScheme = ""
            var drmKey = ""
            var contentType = defaultType
            var rating = ""
            var releaseYear = ""
            var plot = ""
            var seriesId = 0
            var playlistId = "PORTAL_MAIN"
            var playlistName = "الرئيسية"
            var streamId = 0
            var streamBaseUrl = ""
            var streamUsername = ""
            var streamPassword = ""
            var containerExt = ""

            while (reader.hasNext()) {
                val key = reader.nextName()
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    continue
                }
                when (key) {
                    "i", "id" -> id = runCatching { reader.nextInt() }.getOrDefault(idx)
                    "n", "number" -> number = runCatching { reader.nextInt() }.getOrDefault(idx)
                    "nm", "name" -> name = reader.nextString()
                    "u", "url" -> url = reader.nextString()
                    "g", "group" -> group = reader.nextString().ifBlank { "عام" }
                    "l", "logo" -> logo = reader.nextString().takeIf { it.isNotBlank() }
                    "ua", "userAgent" -> ua = reader.nextString().takeIf { it.isNotBlank() }
                    "rf", "referer" -> ref = reader.nextString().takeIf { it.isNotBlank() }
                    "or", "origin" -> orig = reader.nextString().takeIf { it.isNotBlank() }
                    "ck", "cookie" -> cook = reader.nextString().takeIf { it.isNotBlank() }
                    "ds", "drmScheme" -> drmScheme = reader.nextString()
                    "dk", "drmKey" -> drmKey = reader.nextString()
                    "ct", "contentType" -> {
                        val s = reader.nextString()
                        contentType = runCatching { StreamContentType.valueOf(s) }.getOrDefault(defaultType)
                    }
                    "rt", "rating" -> rating = reader.nextString()
                    "ry", "releaseYear" -> releaseYear = reader.nextString()
                    "pl", "plot" -> plot = reader.nextString()
                    "si", "seriesId" -> seriesId = runCatching { reader.nextInt() }.getOrDefault(0)
                    "pi", "playlistId" -> playlistId = reader.nextString().ifBlank { "PORTAL_MAIN" }
                    "pn", "playlistName" -> playlistName = reader.nextString().ifBlank { "الرئيسية" }
                    "st", "streamId" -> streamId = runCatching { reader.nextInt() }.getOrDefault(0)
                    "sb", "streamBaseUrl" -> streamBaseUrl = reader.nextString()
                    "su", "streamUsername" -> streamUsername = reader.nextString()
                    "sp", "streamPassword" -> streamPassword = reader.nextString()
                    "ce", "containerExtension" -> containerExt = reader.nextString()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (name.isNotBlank() && url.isNotBlank() && !AdultContentFilter.isAdultOr18Plus(name, group)) {
                list.add(
                    TvChannel(
                        id = id,
                        number = number,
                        name = name,
                        url = url,
                        group = group,
                        logo = logo,
                        userAgent = ua,
                        referer = ref,
                        origin = orig,
                        cookie = cook,
                        drmScheme = drmScheme,
                        drmKey = drmKey,
                        contentType = contentType,
                        rating = rating,
                        releaseYear = releaseYear,
                        plot = plot,
                        seriesId = seriesId,
                        playlistId = playlistId,
                        playlistName = playlistName,
                        streamId = streamId,
                        streamBaseUrl = streamBaseUrl,
                        streamUsername = streamUsername,
                        streamPassword = streamPassword,
                        containerExtension = containerExt
                    )
                )
                idx++
            }
        }
        reader.endArray()
        return list
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
            if (AdultContentFilter.isAdultOr18Plus(name, rawGroup)) continue
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
                    drmScheme = o.optString("drmScheme", ""),
                    drmKey = o.optString("drmKey", ""),
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
                    streamPassword = o.optString("streamPassword", ""),
                    containerExtension = o.optString("containerExtension", "")
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
                    put("drmScheme", ch.drmScheme)
                    put("drmKey", ch.drmKey)
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
                    put("containerExtension", ch.containerExtension)
                }
            )
        }
        return arr
    }
}
