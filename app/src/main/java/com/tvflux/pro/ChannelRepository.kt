package com.tvflux.pro

import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object ChannelRepository {
    private val INTERNAL_ENDPOINT: String by lazy {
        listOf("https://", "tvflux4k", ".com", "/p2p/", "api.php").joinToString("")
    }

    val CUSTOMER_PORTAL_ENDPOINT: String by lazy {
        listOf("https://", "tvflux4k", ".com", "/p2p/", "customer_portal.php").joinToString("")
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun OkHttpClient.Builder.applyUniversalSslCompatibility(): OkHttpClient.Builder {
        return runCatching {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            this.sslSocketFactory(sslContext.socketFactory, trustAll)
            this.hostnameVerifier { _, _ -> true }
            this.connectionSpecs(
                listOf(
                    ConnectionSpec.MODERN_TLS,
                    ConnectionSpec.COMPATIBLE_TLS,
                    ConnectionSpec.CLEARTEXT
                )
            )
            this
        }.getOrDefault(this)
    }

    val bootstrapHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyUniversalSslCompatibility()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyUniversalSslCompatibility()
            .dns(CloudflareDohManager.customDns)
            .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * High-capacity client for syncing massive Xtream / M3U servers (supports up to 150,000+ items).
     */
    val xtreamHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyUniversalSslCompatibility()
            .dns(CloudflareDohManager.customDns)
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val streamHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyUniversalSslCompatibility()
            .dns(CloudflareDohManager.customDns)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .connectTimeout(9, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun cancelPendingStreamRequests() {
        runCatching {
            streamHttpClient.dispatcher.cancelAll()
            streamHttpClient.connectionPool.evictAll()
        }
    }

    /**
     * Lightweight subscription & remote playlist metadata check (< 1 KB).
     * Used on app startup when the full channel catalog is already cached in local app data,
     * so opening the app is instant and does NOT re-download the heavy channel catalog unless requested!
     */
    suspend fun checkSubscriptionAndListsQuick(
        deviceId: String,
        deviceName: String
    ): Pair<SubscriptionInfo, List<XtreamAccountConfig>> = withContext(Dispatchers.IO) {
        val url = "$INTERNAL_ENDPOINT?action=check_subscription&device_id=${enc(deviceId)}&device_name=${enc(deviceName)}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "LIVE-PRO/10.0 (Android; Universal)")
            .header("Accept", "application/json")
            .build()

        val rawBody = httpClient.newCall(req).execute().use { resp ->
            resp.body?.string().orEmpty()
        }
        if (!rawBody.trim().startsWith("{")) {
            error("تعذّر التحقق السريع من حالة الاشتراك")
        }
        val root = JSONObject(rawBody.trim())
        val dataNode = root.optJSONObject("data")
        val subObj = dataNode?.optJSONObject("subscription")
            ?: root.optJSONObject("subscription")
            ?: dataNode
            ?: root
        val hasExplicitActiveField = subObj.has("active")
        val reasonStr = subObj.optString("reason", "ok").lowercase()
        val expDateStr = first(subObj, "expire", "expire_date", "expires_at", "exp_date")
        val msgStr = subObj.optString("message", root.optString("error", ""))
        val explicitlyExpired = (
            (hasExplicitActiveField && !subObj.optBoolean("active", true) &&
                (reasonStr == "expired" || reasonStr == "disabled" || ExpiryAlertHelper.isDateAlreadyExpired(expDateStr))) ||
                ExpiryAlertHelper.isDateAlreadyExpired(expDateStr)
            )
        val activeFlag = !explicitlyExpired
        val days = subObj.optInt("days_left", if (activeFlag) 30 else 0)

        val sub = SubscriptionInfo(
            active = activeFlag,
            isExpired = explicitlyExpired,
            isTrial = subObj.optBoolean("is_trial", reasonStr == "trial"),
            reason = if (explicitlyExpired) "expired" else reasonStr.ifBlank { "ok" },
            expireDate = expDateStr,
            daysLeft = if (explicitlyExpired) 0 else days.coerceAtLeast(1),
            deviceName = subObj.optString("device_name", deviceName),
            message = msgStr
        )

        val remoteLists = if (activeFlag) fetchListsFromCustomerPortal(deviceId) else emptyList()
        Pair(sub, remoteLists)
    }

    /**
     * Pushes customer Xtream playlist to both customer_portal.php and api.php.
     */
    suspend fun pushCustomerXtreamToPortal(
        deviceId: String,
        deviceName: String,
        account: XtreamAccountConfig
    ) = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("action", "save_xtream")
            .add("device_id", deviceId)
            .add("device_name", deviceName)
            .add("playlist_id", account.id)
            .add("server_name", account.serverName)
            .add("server_url", account.serverUrl)
            .add("xtream_user", account.username)
            .add("xtream_pass", account.password)
            .add("m3u_url", account.m3uUrl)
            .add("exp_date", account.expDate)
            .build()

        runCatching {
            val reqPortal = Request.Builder()
                .url(CUSTOMER_PORTAL_ENDPOINT)
                .post(form)
                .header("User-Agent", "LIVE-PRO/10.0 (Android; TV)")
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(reqPortal).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (body.startsWith("{")) {
                    val json = JSONObject(body)
                    if (!json.optBoolean("success", true)) {
                        val err = json.optString("error", "انتهت صلاحية المشغل. يرجى التجديد عبر علي سات: $ALI_SAT_PHONE_NUMBER")
                        error(err)
                    }
                }
            }
        }.onFailure { e ->
            if (e.message?.contains("صلاحية") == true || e.message?.contains(ALI_SAT_PHONE_NUMBER) == true) {
                throw e
            }
        }

        runCatching {
            val reqMain = Request.Builder()
                .url(INTERNAL_ENDPOINT)
                .post(form)
                .header("User-Agent", "LIVE-PRO/10.0 (Android; TV)")
                .build()
            httpClient.newCall(reqMain).execute().close()
        }
    }

    suspend fun deleteCustomerXtreamFromPortal(
        deviceId: String,
        playlistId: String
    ) = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("action", "delete_xtream")
            .add("device_id", deviceId)
            .add("playlist_id", playlistId)
            .build()
        runCatching {
            httpClient.newCall(Request.Builder().url(CUSTOMER_PORTAL_ENDPOINT).post(form).build()).execute().close()
        }
        runCatching {
            httpClient.newCall(Request.Builder().url(INTERNAL_ENDPOINT).post(form).build()).execute().close()
        }
    }

    private fun fetchListsFromCustomerPortal(deviceId: String): List<XtreamAccountConfig> {
        return try {
            val url = "$CUSTOMER_PORTAL_ENDPOINT?action=get_xtream_lists&device_id=${enc(deviceId)}"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "LIVE-PRO/10.0 (Android; TV)")
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string()?.trim().orEmpty()
                if (!body.startsWith("{")) return emptyList()
                val json = JSONObject(body)
                val arr = json.optJSONArray("xtream_lists") ?: json.optJSONObject("data")?.optJSONArray("xtream_lists") ?: return emptyList()
                val list = mutableListOf<XtreamAccountConfig>()
                for (i in 0 until arr.length()) {
                    val xo = arr.optJSONObject(i) ?: continue
                    parseRemoteXtreamObject(xo, i)?.let { list.add(it) }
                }
                list
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun redeemActivationCode(
        deviceId: String,
        deviceName: String,
        rawCode: String
    ): ApiSyncResult = withContext(Dispatchers.IO) {
        val rawClean = rawCode.trim().uppercase().removePrefix("DEV-")
        val cleanCode = if (Regex("^[A-Z0-9]{8}$").matches(rawClean)) {
            "${rawClean.substring(0, 4)}-${rawClean.substring(4, 8)}"
        } else {
            rawClean
        }
        require(Regex("^[A-Z0-9]{4}-[A-Z0-9]{4}$").matches(cleanCode)) {
            "صيغة رمز القسيمة غير صحيحة (مثال: A1B2-C3D4)"
        }

        val form = FormBody.Builder()
            .add("action", "activate_card")
            .add("device_id", deviceId)
            .add("device_name", deviceName)
            .add("card_code", cleanCode)
            .add("code", cleanCode)
            .build()

        val req = Request.Builder()
            .url(INTERNAL_ENDPOINT)
            .post(form)
            .header("User-Agent", "LIVE-PRO/10.0 (Android; TV)")
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string()?.trim().orEmpty()
            if (body.startsWith("{")) {
                val json = JSONObject(body)
                val ok = json.optBoolean("success", false)
                if (!ok) {
                    val errCode = json.optString("error_code", "")
                    val serverErr = json.optString("error", "تعذّر تفعيل القسيمة")
                    val finalMsg = when (errCode) {
                        "CARD_USED" -> "⛔ هذه القسيمة مستخدمة مسبقاً ولا يمكن إعادة استخدامها نهائياً"
                        "CARD_EXPIRED" -> "⛔ انتهت صلاحية هذه القسيمة"
                        "CARD_NOT_FOUND" -> "❌ رمز القسيمة غير موجود"
                        "RATE_LIMITED" -> "⏳ عدد المحاولات كبير، يرجى المحاولة لاحقاً"
                        else -> serverErr
                    }
                    error(finalMsg)
                }
            } else if (!resp.isSuccessful) {
                error("فشل الاتصال بخادم التفعيل (رمز ${resp.code})")
            }
        }

        syncWithServer(deviceId, deviceName, "")
    }

    suspend fun syncWithServer(
        deviceId: String,
        deviceName: String,
        activationCode: String = "",
        knownCatalogHash: String = ""
    ): ApiSyncResult = withContext(Dispatchers.IO) {
        val codeParam = if (activationCode.isNotBlank()) "&code=${enc(activationCode)}" else ""
        val hashParam = if (knownCatalogHash.isNotBlank()) "&client_hash=${enc(knownCatalogHash)}" else ""
        val url = "$INTERNAL_ENDPOINT?action=get_channels&device_id=${enc(deviceId)}&device_name=${enc(deviceName)}$codeParam$hashParam"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "LIVE-PRO/10.0 (Android; TV)")
            .header("Accept", "application/json")
            .build()

        val (statusCode, rawBody) = try {
            xtreamHttpClient.newCall(req).execute().use { resp ->
                Pair(resp.code, resp.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            error("تعذّر الاتصال بخادم البث. تحقّق من الإنترنت.")
        }

        if (rawBody.isBlank()) {
            error("استجابة غير مكتملة من الخادم (رمز $statusCode)")
        }

        val root = try {
            JSONObject(rawBody.trim())
        } catch (_: Exception) {
            error("تعذّر قراءة بيانات الخادم حالياً. حاول مجدداً.")
        }

        val success = root.optBoolean("success", false)
        val dataObj = root.optJSONObject("data")

        val subObj = dataObj?.optJSONObject("subscription") ?: root.optJSONObject("subscription")
        val rawErr = root.optString("error")
        val topError = if (rawErr.isNotBlank()) rawErr else subObj?.optString("message").orEmpty()

        val remoteXtreamLists = mutableListOf<XtreamAccountConfig>()

        val multiArrays = listOfNotNull(
            dataObj?.optJSONArray("xtream_lists"),
            dataObj?.optJSONArray("playlists"),
            dataObj?.optJSONArray("xtream_accounts"),
            root.optJSONArray("xtream_lists"),
            root.optJSONArray("playlists"),
            subObj?.optJSONArray("xtream_lists")
        )
        for (arr in multiArrays) {
            for (i in 0 until arr.length()) {
                val xo = arr.optJSONObject(i) ?: continue
                parseRemoteXtreamObject(xo, i)?.let { cfg ->
                    if (remoteXtreamLists.none { it.serverUrl == cfg.serverUrl && it.username == cfg.username && it.m3uUrl == cfg.m3uUrl }) {
                        remoteXtreamLists.add(cfg)
                    }
                }
            }
        }

        val singleXtreamObj = dataObj?.optJSONObject("xtream")
            ?: root.optJSONObject("xtream")
            ?: subObj?.optJSONObject("xtream")
        if (singleXtreamObj != null) {
            parseRemoteXtreamObject(singleXtreamObj, remoteXtreamLists.size)?.let { cfg ->
                if (remoteXtreamLists.none { it.serverUrl == cfg.serverUrl && it.username == cfg.username }) {
                    remoteXtreamLists.add(cfg)
                }
            }
        }

        val portalLists = fetchListsFromCustomerPortal(deviceId)
        for (pl in portalLists) {
            if (remoteXtreamLists.none { it.id == pl.id || (it.serverUrl == pl.serverUrl && it.username == pl.username) }) {
                remoteXtreamLists.add(pl)
            }
        }

        val subscription = if (subObj != null) {
            val reasonStr = subObj.optString("reason", if (success) "ok" else "").lowercase()
            val expDateStr = first(subObj, "expire", "expire_date", "expires_at", "exp_date")
            val msgStr = subObj.optString("message", topError)
            val explicitlyExpired = (
                (!subObj.optBoolean("active", true) &&
                    (reasonStr == "expired" || reasonStr == "disabled" || statusCode == 403 || ExpiryAlertHelper.isDateAlreadyExpired(expDateStr))) ||
                    ExpiryAlertHelper.isDateAlreadyExpired(expDateStr)
                )
            val activeFlag = !explicitlyExpired
            val days = subObj.optInt("days_left", if (activeFlag) 30 else 0)

            SubscriptionInfo(
                active = activeFlag,
                isExpired = explicitlyExpired,
                isTrial = subObj.optBoolean("is_trial", reasonStr == "trial"),
                reason = if (explicitlyExpired) "expired" else reasonStr.ifBlank { "ok" },
                expireDate = expDateStr,
                daysLeft = if (explicitlyExpired) 0 else days.coerceAtLeast(1),
                deviceName = subObj.optString("device_name", deviceName),
                message = msgStr
            )
        } else {
            val explicitlyExpired = (statusCode == 403 && (topError.contains("انته") || topError.contains("منته") || topError.contains("تعطيل")))
            SubscriptionInfo(
                active = !explicitlyExpired,
                isExpired = explicitlyExpired,
                reason = if (explicitlyExpired) "expired" else "ok",
                daysLeft = if (explicitlyExpired) 0 else 30,
                message = topError
            )
        }

        if (!success || !subscription.active) {
            val fallbackMsg = when {
                subscription.message.isNotBlank() -> subscription.message
                topError.isNotBlank() -> topError
                else -> "انتهت صلاحية المشغل للشاشة ($deviceId). يرجى التجديد عبر التواصل مع علي سات : $ALI_SAT_PHONE_NUMBER"
            }
            return@withContext ApiSyncResult(
                channels = emptyList(),
                subscription = subscription.copy(
                    active = false,
                    isExpired = true,
                    message = fallbackMsg
                ),
                remoteXtreamLists = emptyList()
            )
        }

        val channelsArr: JSONArray = when {
            dataObj?.opt("channels") is JSONArray -> dataObj.getJSONArray("channels")
            root.opt("data") is JSONArray -> root.getJSONArray("data")
            root.opt("channels") is JSONArray -> root.getJSONArray("channels")
            else -> JSONArray()
        }

        val parsedChannels = ArrayList<TvChannel>(channelsArr.length())
        val parsedMovies = ArrayList<TvChannel>()
        val parsedSeries = ArrayList<TvChannel>()

        for (i in 0 until channelsArr.length()) {
            val o = channelsArr.optJSONObject(i) ?: continue
            val rawStreamUrl = first(
                o,
                "original_url",
                "encrypted_url",
                "url",
                "stream_url",
                "play_url",
                "link"
            )
            if (rawStreamUrl.isBlank()) continue

            val pipe = splitPipeHeaders(rawStreamUrl)
            val id = o.optInt("id", i + 1)
            val number = o.optInt("number", i + 1)
            val rawName = first(o, "name", "title", "channel_name")
            val name = if (rawName.isNotBlank()) rawName else "قناة $number"
            val rawCat = first(o, "category", "group", "category_name")
            val category = if (rawCat.isNotBlank()) rawCat else "عام"
            if (AdultContentFilter.isAdultOr18Plus(name, category)) continue

            val logo = first(o, "logo_url", "logo", "stream_icon", "icon")
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }

            val rawType = first(o, "type", "stream_type", "content_type").lowercase()
            val lowCat = category.lowercase()
            val lowUrl = pipe.cleanUrl.lowercase()

            val lowName = name.lowercase()
            val cleanLowUrl = lowUrl.substringBefore("?")
            val isSeriesItem = rawType == "series" ||
                lowCat.contains("مسلسل") || lowCat.contains("series") || lowCat.contains("série") ||
                cleanLowUrl.contains("/series/") ||
                Regex("s\\d{1,2}\\s*[eex]\\s*\\d{1,3}", RegexOption.IGNORE_CASE).containsMatchIn(lowName) ||
                lowName.contains("حلقة")
            val isMovieItem = !isSeriesItem && (
                rawType == "movie" || rawType == "vod" ||
                    lowCat.contains("افلام") || lowCat.contains("أفلام") ||
                    lowCat.contains("movie") || lowCat.contains("film") || lowCat.contains("vod") ||
                    cleanLowUrl.contains("/movie/") ||
                    cleanLowUrl.endsWith(".mp4") || cleanLowUrl.endsWith(".mkv") ||
                    cleanLowUrl.endsWith(".avi") || cleanLowUrl.endsWith(".mov") ||
                    cleanLowUrl.endsWith(".webm") || cleanLowUrl.endsWith(".flv")
                )
            val contentType = when {
                isSeriesItem -> StreamContentType.SERIES
                isMovieItem -> StreamContentType.MOVIE
                else -> StreamContentType.LIVE_TV
            }

            val item = TvChannel(
                id = id,
                number = number,
                name = name,
                url = pipe.cleanUrl,
                group = category,
                logo = logo,
                userAgent = first(o, "user_agent", "userAgent").takeIf { it.isNotBlank() } ?: pipe.userAgent,
                referer = first(o, "referer", "referrer").takeIf { it.isNotBlank() } ?: pipe.referer,
                origin = first(o, "origin").takeIf { it.isNotBlank() } ?: pipe.origin,
                cookie = first(o, "cookie").takeIf { it.isNotBlank() } ?: pipe.cookie,
                contentType = contentType,
                playlistId = "PORTAL_MAIN",
                playlistName = "قائمة الخادم الرئيسية"
            )

            when (contentType) {
                StreamContentType.MOVIE -> parsedMovies.add(item)
                StreamContentType.SERIES -> parsedSeries.add(item)
                StreamContentType.LIVE_TV -> parsedChannels.add(item)
            }
        }

        parsedChannels.sortBy { it.number }

        val serverHash = dataObj?.optString("catalog_hash", "").orEmpty()
        val unchangedFlag = dataObj?.optBoolean("is_unchanged", false) == true
        ApiSyncResult(
            channels = parsedChannels,
            movies = parsedMovies,
            series = parsedSeries,
            subscription = subscription,
            remoteXtreamLists = remoteXtreamLists,
            serverCatalogHash = serverHash,
            isDifferentialUnchanged = unchangedFlag
        )
    }

    private fun parseRemoteXtreamObject(xo: JSONObject, index: Int): XtreamAccountConfig? {
        val sUrl = first(xo, "server_url", "url", "host", "portal_url")
        val sUser = first(xo, "username", "user", "xtream_user")
        val sPass = first(xo, "password", "pass", "xtream_pass")
        val m3u = first(xo, "m3u_url", "m3u", "playlist_url")
        val sName = first(xo, "server_name", "name", "title").ifBlank { "قائمة XTREAM #${index + 1}" }
        val id = first(xo, "id", "playlist_id").ifBlank { "REMOTE_${index + 1}_${sUrl.hashCode()}" }

        return if ((sUrl.isNotBlank() && sUser.isNotBlank() && sPass.isNotBlank()) || m3u.isNotBlank()) {
            XtreamAccountConfig(
                id = id,
                serverName = sName,
                serverUrl = sUrl,
                username = sUser,
                password = sPass,
                m3uUrl = m3u,
                enabled = xo.optBoolean("enabled", true),
                isRemoteFromPortal = true,
                orderIndex = index,
                expDate = first(xo, "exp_date", "expire_date", "expire")
            )
        } else null
    }

    private data class PipeHeaderResult(
        val cleanUrl: String,
        val userAgent: String? = null,
        val referer: String? = null,
        val origin: String? = null,
        val cookie: String? = null
    )

    private fun splitPipeHeaders(rawUrl: String): PipeHeaderResult {
        if (!rawUrl.contains('|')) return PipeHeaderResult(rawUrl.trim())
        val mainUrl = rawUrl.substringBefore('|').trim()
        val headerPart = rawUrl.substringAfter('|').trim()
        var ua: String? = null
        var ref: String? = null
        var orig: String? = null
        var cook: String? = null

        val pairs = headerPart.split('&', '|')
        for (pair in pairs) {
            val k = pair.substringBefore('=').trim().lowercase()
            val v = pair.substringAfter('=', "").trim()
            if (v.isBlank()) continue
            when (k) {
                "user-agent", "user_agent" -> ua = v
                "referer", "referrer" -> ref = v
                "origin" -> orig = v
                "cookie" -> cook = v
            }
        }
        return PipeHeaderResult(mainUrl, ua, ref, orig, cook)
    }

    private fun first(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.opt(k)
            if (v != null && v != JSONObject.NULL) {
                val s = v.toString().trim()
                if (s.isNotBlank() && s != "null") return s
            }
        }
        return ""
    }

    private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())
}
