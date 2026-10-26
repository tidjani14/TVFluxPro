package com.tvflux.pro

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StringReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Customer Bouquet & Channel Ordering Helper (D-Pad friendly up/down/top/hide).
 * Preserves 100% exact native server order when customOrder is empty.
 */
object CustomOrderHelper {

    fun sortBouquets(
        rawBouquets: List<BouquetSummary>,
        customOrder: List<String>,
        hiddenBouquets: Set<String>,
        includeHidden: Boolean = false
    ): List<BouquetSummary> {
        val safeBouquets = rawBouquets.filterNot { AdultContentFilter.isAdultOr18Plus(it.name, it.name) }
        val visible = if (includeHidden) {
            safeBouquets
        } else {
            safeBouquets.filter {
                it.isFavoritesSpecial || it.name == "الكل" || it.name !in hiddenBouquets
            }
        }
        if (customOrder.isEmpty()) return visible
        val orderMap = customOrder.withIndex().associate { it.value to it.index }
        return visible.sortedWith(
            compareBy<BouquetSummary> { bq ->
                when {
                    bq.name == "الكل" || bq.name == "Tout" || bq.name == "All" -> -2
                    bq.isFavoritesSpecial -> -1
                    else -> orderMap[bq.name] ?: (10_000 + rawBouquets.indexOf(bq))
                }
            }
        )
    }

    fun sortChannels(
        rawChannels: List<TvChannel>,
        customChannelOrder: List<String>,
        hiddenBouquets: Set<String>
    ): List<TvChannel> {
        if (customChannelOrder.isEmpty() && hiddenBouquets.isEmpty()) return rawChannels
        val filtered = if (hiddenBouquets.isEmpty()) {
            rawChannels
        } else {
            rawChannels.filter { it.group !in hiddenBouquets }
        }
        if (customChannelOrder.isEmpty()) return filtered
        val orderMap = customChannelOrder.withIndex().associate { it.value to it.index }
        return filtered.sortedWith(
            compareBy<TvChannel> { ch ->
                orderMap[ch.stableKey] ?: (100_000 + ch.number)
            }
        )
    }

    fun <T> moveItem(list: List<T>, fromIndex: Int, toIndex: Int): List<T> {
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return list
        val mutable = list.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        return mutable
    }
}

/**
 * Universal High-Capacity Streaming Xtream Codes, Panel API & M3U Engine (v12.0):
 * - Preserves exact native Xtream server category and stream order via LinkedHashMap.
 * - Uses streaming JsonReader & BufferedReader directly over GZIP network sockets
 *   so servers with 150,000+ channels/movies/series load quickly with zero OOM.
 * - Supports player_api.php, panel_api.php, get.php, M3U/M3U8/MPD/TS/VOD, #EXTVLCOPT & #KODIPROP DRM.
 */
object XtreamRepository {

    private val IPTV_USER_AGENTS = listOf(
        "VLC/3.0.20 LibVLC/3.0.20",
        "TiviMate/4.7.0 (Android 14; TV)",
        "IPTVSmartersPlayer/6.0 (LIVE-PRO-UNIVERSAL/12.0)",
        "Lavf/60.16.100",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )

    fun normalizeServerUrl(raw: String): String {
        var clean = raw.trim().removeSuffix("/")
        if (clean.isBlank()) return ""
        if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            clean = "http://$clean"
        }
        for (suffix in listOf("/player_api.php", "/panel_api.php", "/get.php", "/c/", "/c", "/stalker_portal/c")) {
            if (clean.contains(suffix, ignoreCase = true)) {
                clean = clean.substringBefore(suffix)
            }
        }
        return clean.removeSuffix("/")
    }

    private fun swapScheme(url: String): String {
        return when {
            url.startsWith("https://", ignoreCase = true) -> "http://" + url.substring(8)
            url.startsWith("http://", ignoreCase = true) -> "https://" + url.substring(7)
            else -> url
        }
    }

    /**
     * Lightweight real-time Xtream account validity & expiry date check (< 1 KB).
     * Called automatically every time the app opens or resumes so the user always sees
     * the real, up-to-date Xtream server expiry date without re-downloading channels!
     */
    suspend fun probeAccountExpiryQuick(config: XtreamAccountConfig): Pair<String, Boolean> =
        withContext(Dispatchers.IO) {
            val autoSplit = SmartM3uParser.tryExtractXtreamCredentials(config.serverUrl)
                ?: SmartM3uParser.tryExtractXtreamCredentials(config.m3uUrl)
            val baseUrl = normalizeServerUrl(autoSplit?.serverUrl ?: config.serverUrl)
            val user = (autoSplit?.username ?: config.username).trim()
            val pass = (autoSplit?.password ?: config.password).trim()
            if (baseUrl.isBlank() || user.isBlank() || pass.isBlank()) {
                return@withContext Pair(config.expDate, true)
            }
            val uEnc = URLEncoder.encode(user, "UTF-8")
            val pEnc = URLEncoder.encode(pass, "UTF-8")
            val apiBase = "$baseUrl/player_api.php?username=$uEnc&password=$pEnc"
            val raw = fetchUrlTextFast(apiBase).ifBlank {
                fetchUrlTextFast("${swapScheme(baseUrl)}/player_api.php?username=$uEnc&password=$pEnc")
            }
            if (raw.startsWith("{")) {
                runCatching {
                    val root = JSONObject(raw)
                    val userInfo = root.optJSONObject("user_info")
                    if (userInfo != null) {
                        val auth = userInfo.optInt("auth", 1)
                        val status = userInfo.optString("status", "Active")
                        val isActive = auth != 0 && !status.equals("Expired", ignoreCase = true) && !status.equals("Banned", ignoreCase = true)
                        val expSec = userInfo.optString("exp_date", "").toLongOrNull()
                        val expDateStr = if (expSec != null && expSec > 0L) {
                            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(expSec * 1000L))
                        } else {
                            config.expDate
                        }
                        return@withContext Pair(expDateStr, isActive)
                    }
                }
            }
            Pair(config.expDate, true)
        }

    suspend fun syncXtreamAccount(
        config: XtreamAccountConfig,
        onProgress: (SyncProgressState) -> Unit = {}
    ): XtreamFullSyncResult =
        withContext(Dispatchers.IO) {
            val serverLabel = config.serverName.trim().ifBlank { "XTREAM" }
            val playlistId = config.id.ifBlank { "XTREAM_1" }

            val autoSplit = SmartM3uParser.tryExtractXtreamCredentials(config.serverUrl)
                ?: SmartM3uParser.tryExtractXtreamCredentials(config.m3uUrl)

            // Direct M3U URL mode (when no Xtream username/password can be extracted)
            if (autoSplit == null && config.m3uUrl.isNotBlank() && (config.serverUrl.isBlank() || config.username.isBlank())) {
                val parsed = fetchAndParseM3uStream(config.m3uUrl.trim(), serverLabel, playlistId, serverLabel)
                val live = parsed.filter { it.contentType == StreamContentType.LIVE_TV }
                val movies = parsed.filter { it.contentType == StreamContentType.MOVIE }
                val series = parsed.filter { it.contentType == StreamContentType.SERIES }
                if (live.isEmpty() && movies.isEmpty() && series.isEmpty()) {
                    error("تعذّر قراءة ملف M3U من الرابط المحدد")
                }
                return@withContext XtreamFullSyncResult(
                    account = config.copy(
                        enabled = true,
                        liveCount = live.size,
                        movieCount = movies.size,
                        seriesCount = series.size,
                        lastSyncTimestamp = System.currentTimeMillis(),
                        statusNote = "متصل ✓ (${live.size} قناة • ${movies.size} فيلم)"
                    ),
                    liveChannels = live,
                    movies = movies,
                    series = series
                )
            }

            var baseUrl = normalizeServerUrl(autoSplit?.serverUrl ?: config.serverUrl)
            val user = (autoSplit?.username ?: config.username).trim()
            val pass = (autoSplit?.password ?: config.password).trim()

            // If user pasted a direct non-Xtream M3U URL into serverUrl without username/password
            if (baseUrl.isNotBlank() && (user.isBlank() || pass.isBlank()) &&
                (config.serverUrl.contains(".m3u", ignoreCase = true) || config.serverUrl.contains("playlist", ignoreCase = true))
            ) {
                val parsed = fetchAndParseM3uStream(config.serverUrl.trim(), serverLabel, playlistId, serverLabel)
                val live = parsed.filter { it.contentType == StreamContentType.LIVE_TV }
                val movies = parsed.filter { it.contentType == StreamContentType.MOVIE }
                val series = parsed.filter { it.contentType == StreamContentType.SERIES }
                if (live.isNotEmpty() || movies.isNotEmpty() || series.isNotEmpty()) {
                    return@withContext XtreamFullSyncResult(
                        account = config.copy(
                            enabled = true,
                            liveCount = live.size,
                            movieCount = movies.size,
                            seriesCount = series.size,
                            lastSyncTimestamp = System.currentTimeMillis(),
                            statusNote = "متصل ✓ (${live.size} قناة • ${movies.size} فيلم)"
                        ),
                        liveChannels = live,
                        movies = movies,
                        series = series
                    )
                }
            }

            require(baseUrl.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
                "يرجى إدخال رابط سيرفر XTREAM واسم المستخدم وكلمة المرور (أو لصق رابط M3U كامل)"
            }

            val uEnc = URLEncoder.encode(user, "UTF-8")
            val pEnc = URLEncoder.encode(pass, "UTF-8")

            // Probe player_api.php on primary scheme, or automatically swap http <-> https if needed
            var apiBase = "$baseUrl/player_api.php?username=$uEnc&password=$pEnc"
            var authJsonStr = fetchUrlTextWithFallback(apiBase)
            if (authJsonStr.isBlank()) {
                val altBase = swapScheme(baseUrl)
                val altApiBase = "$altBase/player_api.php?username=$uEnc&password=$pEnc"
                val altAuth = fetchUrlTextWithFallback(altApiBase)
                if (altAuth.isNotBlank()) {
                    baseUrl = altBase
                    apiBase = altApiBase
                    authJsonStr = altAuth
                }
            }

            var expLabel = ""
            if (authJsonStr.startsWith("{")) {
                runCatching {
                    val root = JSONObject(authJsonStr)
                    val userInfo = root.optJSONObject("user_info")
                    if (userInfo != null) {
                        val auth = userInfo.optInt("auth", 1)
                        val status = userInfo.optString("status", "Active")
                        if (auth == 0 || status.equals("Expired", ignoreCase = true) || status.equals("Banned", ignoreCase = true)) {
                            error("حساب XTREAM غير نشط أو منتهي الصلاحية ($status)")
                        }
                        val expSec = userInfo.optString("exp_date", "").toLongOrNull()
                        if (expSec != null && expSec > 0L) {
                            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            expLabel = sdf.format(Date(expSec * 1000L))
                        }
                    }
                }.onFailure { e ->
                    if (e.message?.contains("حساب XTREAM") == true) throw e
                }
            }

            onProgress(
                SyncProgressState(
                    isSyncing = true,
                    sourceId = playlistId,
                    sourceName = serverLabel,
                    phaseLabel = "🚀 جارٍ السحب المتوازي فائق السرعة للقنوات والأفلام والمسلسلات...",
                    progressPercent = 30
                )
            )
            coroutineScope {
                val liveCatsDeferred = async { fetchCategoryMapStreaming("$apiBase&action=get_live_categories") }
                val vodCatsDeferred = async { fetchCategoryMapStreaming("$apiBase&action=get_vod_categories") }
                val seriesCatsDeferred = async { fetchCategoryMapStreaming("$apiBase&action=get_series_categories") }

                val liveCats = liveCatsDeferred.await()
                val vodCats = vodCatsDeferred.await()
                val seriesCats = seriesCatsDeferred.await()

                val liveDeferred = async {
                    streamParseLiveChannels(
                        url = "$apiBase&action=get_live_streams",
                        liveCats = liveCats,
                        serverLabel = serverLabel,
                        playlistId = playlistId,
                        baseUrl = baseUrl,
                        uEnc = uEnc,
                        pEnc = pEnc
                    ).toMutableList()
                }
                val vodDeferred = async {
                    streamParseVodMovies(
                        url = "$apiBase&action=get_vod_streams",
                        vodCats = vodCats,
                        serverLabel = serverLabel,
                        playlistId = playlistId,
                        baseUrl = baseUrl,
                        uEnc = uEnc,
                        pEnc = pEnc
                    ).toMutableList()
                }
                val seriesDeferred = async {
                    streamParseSeriesItems(
                        url = "$apiBase&action=get_series",
                        seriesCats = seriesCats,
                        serverLabel = serverLabel,
                        playlistId = playlistId,
                        apiBase = apiBase,
                        baseUrl = baseUrl,
                        uEnc = uEnc,
                        pEnc = pEnc
                    ).toMutableList()
                }

                val liveList = liveDeferred.await()
                val movieList = vodDeferred.await()
                val seriesList = seriesDeferred.await()

                // Stage 2 Fallback: Try panel_api.php if player_api.php returned no channels
                if (liveList.isEmpty() && movieList.isEmpty() && seriesList.isEmpty()) {
                    val panelUrl = "$baseUrl/panel_api.php?username=$uEnc&password=$pEnc"
                    val panelParsed = parsePanelApiFallback(panelUrl, serverLabel, playlistId, baseUrl, uEnc, pEnc)
                    liveList.addAll(panelParsed)
                }

                // Stage 3 Fallback: Stream M3U via get.php (ts first for VLC parity, then m3u8)
                if (liveList.isEmpty() && movieList.isEmpty() && seriesList.isEmpty()) {
                    val candidateM3uUrls = listOf(
                        "$baseUrl/get.php?username=$uEnc&password=$pEnc&type=m3u_plus&output=ts",
                        "$baseUrl/get.php?username=$uEnc&password=$pEnc&type=m3u_plus&output=m3u8",
                        "$baseUrl/get.php?username=$uEnc&password=$pEnc&type=m3u&output=ts",
                        "${swapScheme(baseUrl)}/get.php?username=$uEnc&password=$pEnc&type=m3u_plus&output=ts"
                    )
                    for (m3uUrl in candidateM3uUrls) {
                        val parsedM3u = fetchAndParseM3uStream(m3uUrl, serverLabel, playlistId, serverLabel)
                        if (parsedM3u.isNotEmpty()) {
                            liveList.addAll(parsedM3u.filter { it.contentType == StreamContentType.LIVE_TV })
                            movieList.addAll(parsedM3u.filter { it.contentType == StreamContentType.MOVIE })
                            seriesList.addAll(parsedM3u.filter { it.contentType == StreamContentType.SERIES })
                            break
                        }
                    }
                }

                if (liveList.isEmpty() && movieList.isEmpty() && seriesList.isEmpty()) {
                    error("تم الاتصال بالسيرفر ولكن لم يتم العثور على قوائم. تأكد من الرابط وبيانات الحساب.")
                }

                // Preserve exact server bouquet order from get_*_categories while keeping exact stream arrival order within each bouquet!
                if (liveCats.isNotEmpty() && liveList.size > 1) {
                    val catRank = LinkedHashMap<String, Int>(liveCats.size * 2)
                    var r = 0
                    for (catName in liveCats.values) {
                        if (!catRank.containsKey(catName)) {
                            catRank[catName] = r++
                        }
                    }
                    val indexed = liveList.withIndex().toList()
                    liveList.clear()
                    indexed.sortedWith(
                        compareBy<IndexedValue<TvChannel>> { catRank[it.value.group] ?: (100_000 + it.index) }
                            .thenBy { it.index }
                    ).forEach { liveList.add(it.value) }
                }

                if (vodCats.isNotEmpty() && movieList.size > 1) {
                    val catRank = LinkedHashMap<String, Int>(vodCats.size * 2)
                    var r = 0
                    for (catName in vodCats.values) {
                        if (!catRank.containsKey(catName)) {
                            catRank[catName] = r++
                        }
                    }
                    val indexed = movieList.withIndex().toList()
                    movieList.clear()
                    indexed.sortedWith(
                        compareBy<IndexedValue<TvChannel>> { catRank[it.value.group] ?: (100_000 + it.index) }
                            .thenBy { it.index }
                    ).forEach { movieList.add(it.value) }
                }

                if (seriesCats.isNotEmpty() && seriesList.size > 1) {
                    val catRank = LinkedHashMap<String, Int>(seriesCats.size * 2)
                    var r = 0
                    for (catName in seriesCats.values) {
                        if (!catRank.containsKey(catName)) {
                            catRank[catName] = r++
                        }
                    }
                    val indexed = seriesList.withIndex().toList()
                    seriesList.clear()
                    indexed.sortedWith(
                        compareBy<IndexedValue<TvChannel>> { catRank[it.value.group] ?: (100_000 + it.index) }
                            .thenBy { it.index }
                    ).forEach { seriesList.add(it.value) }
                }

                onProgress(
                    SyncProgressState(
                        isSyncing = true,
                        sourceId = playlistId,
                        sourceName = serverLabel,
                        phaseLabel = "جارٍ الفهرسة التفاضلية السريعة في قاعدة بيانات SQLite...",
                        progressPercent = 85,
                        fetchedLiveCount = liveList.size,
                        fetchedMovieCount = movieList.size,
                        fetchedSeriesCount = seriesList.size
                    )
                )
                val updatedConfig = config.copy(
                    serverUrl = baseUrl,
                    username = user,
                    password = pass,
                    enabled = true,
                    liveCount = liveList.size,
                    movieCount = movieList.size,
                    seriesCount = seriesList.size,
                    expDate = expLabel,
                    lastSyncTimestamp = System.currentTimeMillis(),
                    statusNote = "متصل ✓ (${liveList.size} قناة • ${movieList.size} فيلم • ${seriesList.size} مسلسل)"
                )

                XtreamFullSyncResult(
                    account = updatedConfig,
                    liveChannels = liveList,
                    movies = movieList,
                    series = seriesList
                )
            }
        }

    /**
     * Uses LinkedHashMap to preserve the exact category order sent by the Xtream server.
     */
    private fun fetchCategoryMapStreaming(url: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>(256)
        for (ua in IPTV_USER_AGENTS.take(2)) {
            val ok = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Accept", "application/json, */*")
                    .build()
                ChannelRepository.xtreamHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val body = resp.body ?: return@use false
                    BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8), 131072).use { br ->
                        val reader = JsonReader(br)
                        reader.isLenient = true
                        when (reader.peek()) {
                            JsonToken.BEGIN_ARRAY -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    readSingleCategoryObject(reader, map)
                                }
                                reader.endArray()
                            }
                            JsonToken.BEGIN_OBJECT -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    reader.nextName()
                                    readSingleCategoryObject(reader, map)
                                }
                                reader.endObject()
                            }
                            else -> {}
                        }
                    }
                    true
                }
            }.getOrDefault(false)
            if (ok && map.isNotEmpty()) break
        }
        return map
    }

    private fun readSingleCategoryObject(reader: JsonReader, map: MutableMap<String, String>) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        var id = ""
        var name = ""
        reader.beginObject()
        while (reader.hasNext()) {
            val k = reader.nextName()
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                continue
            }
            when (k) {
                "category_id", "id" -> id = reader.nextString().trim()
                "category_name", "name", "title" -> name = reader.nextString().trim()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (id.isNotBlank() && name.isNotBlank() && !AdultContentFilter.isAdultOr18Plus(name, name)) {
            map[id] = name
        }
    }

    private fun streamParseLiveChannels(
        url: String,
        liveCats: Map<String, String>,
        serverLabel: String,
        playlistId: String,
        baseUrl: String,
        uEnc: String,
        pEnc: String
    ): List<TvChannel> {
        val list = ArrayList<TvChannel>(4096)
        for (ua in IPTV_USER_AGENTS.take(2)) {
            val ok = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Accept", "application/json, */*")
                    .build()
                ChannelRepository.xtreamHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val body = resp.body ?: return@use false
                    BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8), 262144).use { br ->
                        val reader = JsonReader(br)
                        reader.isLenient = true
                        var index = 1
                        val parseItem: () -> Unit = {
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                reader.beginObject()
                                var streamId = 0
                                var num = index
                                var name = ""
                                var catId = ""
                                var categoryName = ""
                                var icon = ""
                                var directSource = ""
                                while (reader.hasNext()) {
                                    val k = reader.nextName()
                                    if (reader.peek() == JsonToken.NULL) {
                                        reader.nextNull()
                                        continue
                                    }
                                    when (k) {
                                        "stream_id", "id" -> streamId = runCatching { reader.nextInt() }.getOrElse { reader.nextString().toIntOrNull() ?: 0 }
                                        "num" -> num = runCatching { reader.nextInt() }.getOrElse { reader.nextString().toIntOrNull() ?: index }
                                        "name", "title" -> name = reader.nextString().trim()
                                        "category_id" -> catId = reader.nextString().trim()
                                        "category_name" -> categoryName = reader.nextString().trim()
                                        "stream_icon", "logo" -> icon = reader.nextString().trim()
                                        "direct_source" -> directSource = reader.nextString().trim()
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                if (streamId > 0 || directSource.isNotBlank()) {
                                    val groupName = categoryName.ifBlank { liveCats[catId] ?: catId.ifBlank { "باقات $serverLabel" } }
                                    val finalName = name.ifBlank { "قناة $index" }
                                    if (!AdultContentFilter.isAdultOr18Plus(finalName, groupName)) {
                                        val streamUrl = directSource.takeIf { it.startsWith("http") }
                                            ?: "$baseUrl/live/$uEnc/$pEnc/$streamId.ts"
                                        list.add(
                                            TvChannel(
                                                id = 500_000 + index,
                                                number = if (num > 0) num else index,
                                                name = finalName,
                                                url = streamUrl,
                                                group = groupName,
                                                logo = icon.takeIf { it.startsWith("http") },
                                                contentType = StreamContentType.LIVE_TV,
                                                playlistId = playlistId,
                                                playlistName = serverLabel,
                                                streamId = streamId,
                                                streamBaseUrl = baseUrl,
                                                streamUsername = uEnc,
                                                streamPassword = pEnc
                                            )
                                        )
                                        index++
                                    }
                                }
                            } else {
                                reader.skipValue()
                            }
                        }

                        when (reader.peek()) {
                            JsonToken.BEGIN_ARRAY -> {
                                reader.beginArray()
                                while (reader.hasNext()) parseItem()
                                reader.endArray()
                            }
                            JsonToken.BEGIN_OBJECT -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    reader.nextName()
                                    parseItem()
                                }
                                reader.endObject()
                            }
                            else -> {}
                        }
                    }
                    true
                }
            }.getOrDefault(false)
            if (ok && list.isNotEmpty()) break
        }
        return list
    }

    private fun streamParseVodMovies(
        url: String,
        vodCats: Map<String, String>,
        serverLabel: String,
        playlistId: String,
        baseUrl: String,
        uEnc: String,
        pEnc: String
    ): List<TvChannel> {
        val list = ArrayList<TvChannel>(4096)
        for (ua in IPTV_USER_AGENTS.take(2)) {
            val ok = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Accept", "application/json, */*")
                    .build()
                ChannelRepository.xtreamHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val body = resp.body ?: return@use false
                    BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8), 262144).use { br ->
                        val reader = JsonReader(br)
                        reader.isLenient = true
                        var index = 1
                        val parseItem: () -> Unit = {
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                reader.beginObject()
                                var streamId = 0
                                var name = ""
                                var catId = ""
                                var icon = ""
                                var ext = "mp4"
                                var rating = ""
                                var directSource = ""
                                while (reader.hasNext()) {
                                    val k = reader.nextName()
                                    if (reader.peek() == JsonToken.NULL) {
                                        reader.nextNull()
                                        continue
                                    }
                                    when (k) {
                                        "stream_id", "id" -> streamId = runCatching { reader.nextInt() }.getOrElse { reader.nextString().toIntOrNull() ?: 0 }
                                        "name", "title" -> name = reader.nextString().trim()
                                        "category_id" -> catId = reader.nextString().trim()
                                        "stream_icon", "cover" -> icon = reader.nextString().trim()
                                        "container_extension" -> ext = reader.nextString().trim().lowercase().removePrefix(".").ifBlank { "mp4" }
                                        "rating" -> rating = reader.nextString().trim()
                                        "direct_source" -> directSource = reader.nextString().trim()
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                if (streamId > 0 || directSource.isNotBlank()) {
                                    val groupName = vodCats[catId] ?: catId.ifBlank { "أفلام $serverLabel" }
                                    val finalName = name.ifBlank { "فيلم $index" }
                                    if (!AdultContentFilter.isAdultOr18Plus(finalName, groupName)) {
                                        val streamUrl = directSource.takeIf { it.startsWith("http") }
                                            ?: "$baseUrl/movie/$uEnc/$pEnc/$streamId.$ext"
                                        list.add(
                                            TvChannel(
                                                id = 700_000 + index,
                                                number = index,
                                                name = finalName,
                                                url = streamUrl,
                                                group = groupName,
                                                logo = icon.takeIf { it.startsWith("http") },
                                                contentType = StreamContentType.MOVIE,
                                                rating = rating,
                                                playlistId = playlistId,
                                                playlistName = serverLabel,
                                                streamId = streamId,
                                                streamBaseUrl = baseUrl,
                                                streamUsername = uEnc,
                                                streamPassword = pEnc,
                                                containerExtension = ext
                                            )
                                        )
                                        index++
                                    }
                                }
                            } else {
                                reader.skipValue()
                            }
                        }

                        when (reader.peek()) {
                            JsonToken.BEGIN_ARRAY -> {
                                reader.beginArray()
                                while (reader.hasNext()) parseItem()
                                reader.endArray()
                            }
                            JsonToken.BEGIN_OBJECT -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    reader.nextName()
                                    parseItem()
                                }
                                reader.endObject()
                            }
                            else -> {}
                        }
                    }
                    true
                }
            }.getOrDefault(false)
            if (ok && list.isNotEmpty()) break
        }
        return list
    }

    private fun streamParseSeriesItems(
        url: String,
        seriesCats: Map<String, String>,
        serverLabel: String,
        playlistId: String,
        apiBase: String,
        baseUrl: String,
        uEnc: String,
        pEnc: String
    ): List<TvChannel> {
        val list = ArrayList<TvChannel>(2048)
        for (ua in IPTV_USER_AGENTS.take(2)) {
            val ok = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Accept", "application/json, */*")
                    .build()
                ChannelRepository.xtreamHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val body = resp.body ?: return@use false
                    BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8), 262144).use { br ->
                        val reader = JsonReader(br)
                        reader.isLenient = true
                        var index = 1
                        val parseItem: () -> Unit = {
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                reader.beginObject()
                                var seriesId = 0
                                var name = ""
                                var catId = ""
                                var cover = ""
                                var rating = ""
                                var plot = ""
                                var releaseDate = ""
                                while (reader.hasNext()) {
                                    val k = reader.nextName()
                                    if (reader.peek() == JsonToken.NULL) {
                                        reader.nextNull()
                                        continue
                                    }
                                    when (k) {
                                        "series_id", "id" -> seriesId = runCatching { reader.nextInt() }.getOrElse { reader.nextString().toIntOrNull() ?: 0 }
                                        "name", "title" -> name = reader.nextString().trim()
                                        "category_id" -> catId = reader.nextString().trim()
                                        "cover", "stream_icon" -> cover = reader.nextString().trim()
                                        "rating" -> rating = reader.nextString().trim()
                                        "plot" -> plot = reader.nextString().trim()
                                        "releaseDate", "year" -> releaseDate = reader.nextString().trim()
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                if (seriesId > 0) {
                                    val groupName = seriesCats[catId] ?: catId.ifBlank { "مسلسلات $serverLabel" }
                                    val finalName = name.ifBlank { "مسلسل $index" }
                                    if (!AdultContentFilter.isAdultOr18Plus(finalName, groupName)) {
                                        val seriesInfoUrl = "$apiBase&action=get_series_info&series_id=$seriesId"
                                        list.add(
                                            TvChannel(
                                                id = 900_000 + index,
                                                number = index,
                                                name = finalName,
                                                url = seriesInfoUrl,
                                                group = groupName,
                                                logo = cover.takeIf { it.startsWith("http") },
                                                contentType = StreamContentType.SERIES,
                                                rating = rating,
                                                releaseYear = releaseDate,
                                                plot = plot,
                                                seriesId = seriesId,
                                                playlistId = playlistId,
                                                playlistName = serverLabel,
                                                streamBaseUrl = baseUrl,
                                                streamUsername = uEnc,
                                                streamPassword = pEnc
                                            )
                                        )
                                        index++
                                    }
                                }
                            } else {
                                reader.skipValue()
                            }
                        }

                        when (reader.peek()) {
                            JsonToken.BEGIN_ARRAY -> {
                                reader.beginArray()
                                while (reader.hasNext()) parseItem()
                                reader.endArray()
                            }
                            JsonToken.BEGIN_OBJECT -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    reader.nextName()
                                    parseItem()
                                }
                                reader.endObject()
                            }
                            else -> {}
                        }
                    }
                    true
                }
            }.getOrDefault(false)
            if (ok && list.isNotEmpty()) break
        }
        return list
    }

    private fun parsePanelApiFallback(
        panelUrl: String,
        serverLabel: String,
        playlistId: String,
        baseUrl: String,
        uEnc: String,
        pEnc: String
    ): List<TvChannel> {
        val raw = fetchUrlTextWithFallback(panelUrl)
        if (!raw.startsWith("{")) return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            val avail = root.optJSONObject("available_channels") ?: return@runCatching emptyList()
            val list = ArrayList<TvChannel>(avail.length())
            val keys = avail.keys()
            var idx = 1
            while (keys.hasNext()) {
                val key = keys.next()
                val o = avail.optJSONObject(key) ?: continue
                val streamId = o.optInt("stream_id", key.toIntOrNull() ?: idx)
                val channelName = o.optString("name", "قناة $idx").trim().ifBlank { "قناة $idx" }
                val group = o.optString("category_name", "باقات $serverLabel").trim().ifBlank { "باقات $serverLabel" }
                if (AdultContentFilter.isAdultOr18Plus(channelName, group)) continue
                val icon = o.optString("stream_icon", "").trim().takeIf { it.startsWith("http") }
                val panelStreamUrl = "$baseUrl/live/$uEnc/$pEnc/$streamId.ts"
                list.add(
                    TvChannel(
                        id = 500_000 + idx,
                        number = o.optInt("num", idx).let { if (it > 0) it else idx },
                        name = channelName,
                        url = panelStreamUrl,
                        group = group,
                        logo = icon,
                        contentType = StreamContentType.LIVE_TV,
                        playlistId = playlistId,
                        playlistName = serverLabel,
                        streamId = streamId,
                        streamBaseUrl = baseUrl,
                        streamUsername = uEnc,
                        streamPassword = pEnc
                    )
                )
                idx++
            }
            list
        }.getOrDefault(emptyList())
    }

    suspend fun fetchSeriesEpisodes(
        seriesChannel: TvChannel,
        xtreamLists: List<XtreamAccountConfig>
    ): List<SeriesEpisode> = withContext(Dispatchers.IO) {
        if (seriesChannel.episodes.isNotEmpty()) return@withContext seriesChannel.episodes
        val matchingAccount = xtreamLists.find { it.id == seriesChannel.playlistId }
            ?: xtreamLists.firstOrNull { it.isConfigured }
        val baseUrl = seriesChannel.streamBaseUrl.ifBlank {
            matchingAccount?.let { normalizeServerUrl(it.serverUrl) }.orEmpty()
        }
        val user = seriesChannel.streamUsername.ifBlank {
            matchingAccount?.username?.trim()?.let { URLEncoder.encode(it, "UTF-8") }.orEmpty()
        }
        val pass = seriesChannel.streamPassword.ifBlank {
            matchingAccount?.password?.trim()?.let { URLEncoder.encode(it, "UTF-8") }.orEmpty()
        }
        if (baseUrl.isBlank() || user.isBlank() || pass.isBlank() || seriesChannel.seriesId <= 0) {
            return@withContext emptyList()
        }

        val infoUrl = "$baseUrl/player_api.php?username=$user&password=$pass&action=get_series_info&series_id=${seriesChannel.seriesId}"

        try {
            val raw = fetchUrlTextWithFallback(infoUrl)
            if (!raw.startsWith("{")) return@withContext emptyList()
            val root = JSONObject(raw)
            val episodesObj = root.optJSONObject("episodes") ?: return@withContext emptyList()
            val result = mutableListOf<SeriesEpisode>()
            val seasonKeys = episodesObj.keys()
            while (seasonKeys.hasNext()) {
                val sKey = seasonKeys.next()
                val sNum = sKey.toIntOrNull() ?: 1
                val epArr = episodesObj.optJSONArray(sKey) ?: continue
                for (i in 0 until epArr.length()) {
                    val ep = epArr.optJSONObject(i) ?: continue
                    val epId = ep.optString("id", "").trim()
                    if (epId.isBlank()) continue
                    val epNum = ep.optInt("episode_num", i + 1)
                    val epTitle = ep.optString("title", "الحلقة $epNum").trim()
                    val ext = ep.optString("container_extension", "mp4").trim().lowercase().removePrefix(".").ifBlank { "mp4" }
                    val dur = ep.optJSONObject("info")?.optString("duration", "").orEmpty()
                    val playUrl = "$baseUrl/series/$user/$pass/$epId.$ext"
                    result.add(
                        SeriesEpisode(
                            id = epId,
                            seasonNumber = sNum,
                            episodeNumber = epNum,
                            title = if (epTitle.isNotBlank()) epTitle else "الموسم $sNum - الحلقة $epNum",
                            url = playUrl,
                            durationLabel = dur,
                            containerExtension = ext
                        )
                    )
                }
            }
            result.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchUrlTextFast(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", IPTV_USER_AGENTS.first())
            .header("Accept", "application/json, */*")
            .header("Cache-Control", "no-cache, no-store, max-age=0")
            .build()
        val firstTry = runCatching {
            ChannelRepository.bootstrapHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string()?.trim().orEmpty() else ""
            }
        }.getOrDefault("")
        if (firstTry.isNotBlank()) return firstTry
        return runCatching {
            ChannelRepository.httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string()?.trim().orEmpty() else ""
            }
        }.getOrDefault("")
    }

    private fun fetchUrlTextWithFallback(url: String): String {
        for (ua in IPTV_USER_AGENTS.take(2)) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Accept", "application/json, */*")
                .build()
            for (client in listOf(ChannelRepository.xtreamHttpClient, ChannelRepository.bootstrapHttpClient)) {
                try {
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val txt = resp.body?.string()?.trim().orEmpty()
                            if (txt.isNotBlank()) return txt
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        return ""
    }

    private fun fetchAndParseM3uStream(
        url: String,
        defaultGroup: String,
        playlistId: String,
        playlistName: String
    ): List<TvChannel> {
        for (ua in IPTV_USER_AGENTS.take(3)) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Accept", "*/*")
                    .build()
                ChannelRepository.xtreamHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body ?: return@use
                    BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8), 262144).use { br ->
                        val parsed = parseM3uBufferedReader(br, defaultGroup, playlistId, playlistName)
                        if (parsed.isNotEmpty()) return parsed
                    }
                }
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    fun parseM3uPlaylist(
        m3u: String,
        defaultGroup: String,
        playlistId: String,
        playlistName: String
    ): List<TvChannel> {
        if (!m3u.contains("#EXTINF", ignoreCase = true)) return emptyList()
        return BufferedReader(StringReader(m3u), 131072).use { br ->
            parseM3uBufferedReader(br, defaultGroup, playlistId, playlistName)
        }
    }

    private fun parseM3uBufferedReader(
        reader: BufferedReader,
        defaultGroup: String,
        playlistId: String,
        playlistName: String
    ): List<TvChannel> {
        val list = ArrayList<TvChannel>(4096)
        var currentName = ""
        var currentGroup = defaultGroup
        var currentLogo: String? = null
        var currentUa: String? = null
        var currentReferer: String? = null
        var currentOrigin: String? = null
        var currentCookie: String? = null
        var currentDrmScheme = ""
        var currentDrmKey = ""
        var counter = 1

        var rawLine: String?
        while (reader.readLine().also { rawLine = it } != null) {
            val line = rawLine!!.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                currentName = line.substringAfterLast(",", "قناة $counter").trim()
                currentGroup = extractAttr(line, "group-title").ifBlank { defaultGroup }
                currentLogo = extractAttr(line, "tvg-logo").takeIf { it.startsWith("http") }
                currentUa = extractAttr(line, "user-agent").ifBlank { extractAttr(line, "http-user-agent") }.takeIf { it.isNotBlank() }
                currentReferer = extractAttr(line, "referer").ifBlank { extractAttr(line, "http-referrer") }.takeIf { it.isNotBlank() }
                currentOrigin = null
                currentCookie = null
                currentDrmScheme = ""
                currentDrmKey = ""
            } else if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                val grp = line.substringAfter(":").trim()
                if (grp.isNotBlank()) currentGroup = grp
            } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                val opt = line.substringAfter(":").trim()
                when {
                    opt.startsWith("http-user-agent=", ignoreCase = true) ->
                        currentUa = opt.substringAfter("=").trim().takeIf { it.isNotBlank() }
                    opt.startsWith("http-referrer=", ignoreCase = true) || opt.startsWith("http-referer=", ignoreCase = true) ->
                        currentReferer = opt.substringAfter("=").trim().takeIf { it.isNotBlank() }
                    opt.startsWith("http-origin=", ignoreCase = true) ->
                        currentOrigin = opt.substringAfter("=").trim().takeIf { it.isNotBlank() }
                    opt.startsWith("http-cookie=", ignoreCase = true) ->
                        currentCookie = opt.substringAfter("=").trim().takeIf { it.isNotBlank() }
                }
            } else if (line.startsWith("#KODIPROP:", ignoreCase = true)) {
                val prop = line.substringAfter(":").trim()
                when {
                    prop.startsWith("inputstream.adaptive.license_type=", ignoreCase = true) ->
                        currentDrmScheme = prop.substringAfter("=").trim().lowercase()
                    prop.startsWith("inputstream.adaptive.license_key=", ignoreCase = true) ->
                        currentDrmKey = prop.substringAfter("=").trim()
                }
            } else if (!line.startsWith("#") &&
                (line.startsWith("http://", ignoreCase = true) ||
                    line.startsWith("https://", ignoreCase = true) ||
                    line.startsWith("rtsp://", ignoreCase = true) ||
                    line.startsWith("rtmp://", ignoreCase = true) ||
                    line.startsWith("rtmps://", ignoreCase = true) ||
                    line.startsWith("udp://", ignoreCase = true) ||
                    line.startsWith("rtp://", ignoreCase = true) ||
                    line.startsWith("mms://", ignoreCase = true))
            ) {
                if (AdultContentFilter.isAdultOr18Plus(currentName, currentGroup)) {
                    continue
                }
                val cleanUrl = line.substringBefore("|").trim()
                if (line.contains("|")) {
                    val pipePart = line.substringAfter("|")
                    pipePart.split("&", "|").forEach { kv ->
                        val k = kv.substringBefore("=").trim().lowercase()
                        val v = kv.substringAfter("=", "").trim()
                        if (v.isNotBlank()) {
                            when (k) {
                                "user-agent", "user_agent" -> currentUa = v
                                "referer", "referrer" -> currentReferer = v
                                "origin" -> currentOrigin = v
                                "cookie" -> currentCookie = v
                            }
                        }
                    }
                }
                val lowUrl = cleanUrl.lowercase().substringBefore("?")
                val lowGroup = currentGroup.lowercase()
                val lowName = currentName.lowercase()
                val isSeriesPattern = lowUrl.contains("/series/") ||
                    lowGroup.contains("مسلسل") || lowGroup.contains("series") || lowGroup.contains("série") ||
                    Regex("s\\d{1,2}\\s*[eex]\\s*\\d{1,3}", RegexOption.IGNORE_CASE).containsMatchIn(lowName) ||
                    lowName.contains("حلقة") || lowName.contains("موسم")
                val isMoviePattern = !isSeriesPattern && (
                    lowUrl.contains("/movie/") || lowUrl.contains("/vod/") ||
                        lowGroup.contains("افلام") || lowGroup.contains("أفلام") ||
                        lowGroup.contains("movie") || lowGroup.contains("film") || lowGroup.contains("vod") ||
                        lowUrl.endsWith(".mp4") || lowUrl.endsWith(".mkv") ||
                        lowUrl.endsWith(".avi") || lowUrl.endsWith(".mov") ||
                        lowUrl.endsWith(".webm") || lowUrl.endsWith(".flv")
                    )
                val type = when {
                    isSeriesPattern -> StreamContentType.SERIES
                    isMoviePattern -> StreamContentType.MOVIE
                    else -> StreamContentType.LIVE_TV
                }
                val resolvedName = currentName.ifBlank { "قناة $counter" }
                list.add(
                    TvChannel(
                        id = 600_000 + counter,
                        number = counter,
                        name = resolvedName,
                        url = cleanUrl,
                        group = currentGroup,
                        logo = currentLogo,
                        userAgent = currentUa,
                        referer = currentReferer,
                        origin = currentOrigin,
                        cookie = currentCookie,
                        drmScheme = currentDrmScheme,
                        drmKey = currentDrmKey,
                        contentType = type,
                        playlistId = playlistId,
                        playlistName = playlistName
                    )
                )
                counter++
            }
        }
        return list
    }

    private fun extractAttr(extInf: String, attr: String): String {
        val regex = Regex("$attr\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        return regex.find(extInf)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }
}
