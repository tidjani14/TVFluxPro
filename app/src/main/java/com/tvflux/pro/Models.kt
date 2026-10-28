package com.tvflux.pro

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.LayoutDirection
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

const val APP_BRAND_NAME = "LIVE PRO"
val APP_LOGO_URL: String = listOf("https://", "e.top4top.io", "/p_3941ca8xe1.jpg").joinToString("")
const val ALI_SAT_PROVIDER_NAME = "علي سات (Ali Sat)"
const val ALI_SAT_PHONE_NUMBER = "00213697372480"
const val FAVORITES_BOUQUET_KEY = "__FAV_BOUQUET__"

/**
 * Built-in Universal IPTV Server Auto-Discovery Catalog (v20.0):
 * Synchronized with server_portal/data.txt so the Android app automatically recognizes all major
 * IPTV / Xtream servers by Server Name or Host URL and provides instant multi-host failover.
 */
object KnownIptvServersCatalog {
    private val dynamicServers = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

    fun registerRemoteServers(remoteList: List<Pair<String, String>>) {
        val existingUrls = (SERVER_MAP + dynamicServers).map { it.second.lowercase(java.util.Locale.ROOT).removeSuffix("/") }.toHashSet()
        for ((name, url) in remoteList) {
            val cleanUrl = url.trim().removeSuffix("/")
            if (cleanUrl.isNotBlank() && cleanUrl.lowercase(java.util.Locale.ROOT) !in existingUrls) {
                dynamicServers.add(name.ifBlank { "VIP Server" } to cleanUrl)
                existingUrls.add(cleanUrl.lowercase(java.util.Locale.ROOT))
            }
        }
    }

    fun getAllServers(): List<Pair<String, String>> = dynamicServers + SERVER_MAP

    val SERVER_MAP: List<Pair<String, String>> = listOf(
        "Temu VIP" to "http://tmasta.com",
        "King 365" to "http://179.61.217.72",
        "Nexon" to "http://serine-tv.xyz",
        "LIVE PRO VIP 4K" to "http://tvflux4k.com:8080",
        "IRON PRO MAX" to "http://line.iron-ott.com:80",
        "IRON TV PRO" to "http://irontvpro.com:8080",
        "KING 365 4K" to "http://line.king365tv.com:8080",
        "KING 4K OTT" to "http://king4k-ott.com:8080",
        "TREX 4K OTT" to "http://line.trex-ott.com:80",
        "TREX VIP" to "http://trex-panel.com:8080",
        "DINO IPTV" to "http://line.dino-iptv.com:8080",
        "DINO PLUS" to "http://dino-ott.net:8080",
        "STRONG 8K UHD" to "http://line.strong8k.com:8080",
        "STRONG 4K VIP" to "http://strong4k.me:8080",
        "MEGA OTT PRO" to "http://line.megaott.net:80",
        "MEGA VIP 4K" to "http://mega-ott.com:8080",
        "CRYSTAL OTT" to "http://line.crystal-ott.com:8080",
        "CRYSTAL VIP" to "http://crystal-tv.net:8080",
        "MAGNUM 4K" to "http://magnum-ott.net:80",
        "GOLD TV VIP" to "http://line.goldtv.cc:8080",
        "ATLAS PRO ONTV" to "http://atlas-ontv.com:8080",
        "ATLAS VIP 4K" to "http://line.atlaspro.tv:8080",
        "LYNX IPTV 4K" to "http://lynx-iptv.net:8080",
        "SANSAT PRO" to "http://sansat-ott.com:8080",
        "GOOL PRO TV" to "http://gool-tv.com:8080",
        "STAR SHARE 4K" to "http://starshare.live:8080",
        "STAR 4K OTT" to "http://line.starshare.org:8080",
        "LIONZ TV VIP" to "http://lionz-tv.com:8080",
        "ZENITH 4K" to "http://zenith-ott.com:8080",
        "COBRA ULTRA" to "http://cobra-ott.com:8080",
        "FALCON PRO 4K" to "http://falcon-iptv.net:8080",
        "SPIDER OTT VIP" to "http://spider-ott.com:8080",
        "B1G OTT 4K" to "http://line.b1g-ott.com:8080",
        "EAGLE 4K VIP" to "http://line.eagle4k.cc:8080",
        "MAX OTT PRO" to "http://line.maxott.net:8080",
        "ROYAL 4K TV" to "http://royal4k-ott.com:8080",
        "AROMA VIP 4K" to "http://aroma-ott.com:8080",
        "DIAMOND 4K OTT" to "http://diamond-ott.net:8080",
        "PLATINUM IPTV" to "http://platinum-ott.com:8080",
        "NOVA 4K OTT" to "http://nova-iptv.net:8080",
        "INFINITY 4K" to "http://infinity-ott.com:8080",
        "MATADOR PRO" to "http://matador-ott.com:8080",
        "HAWK 4K VIP" to "http://hawk-ott.net:8080"
    )

    fun resolveHostByServerName(serverName: String): String {
        val clean = serverName.trim().lowercase(java.util.Locale.ROOT)
        if (clean.isBlank()) return ""
        // 1. Exact case-insensitive match
        getAllServers().firstOrNull { it.first.lowercase(java.util.Locale.ROOT) == clean }?.let { return it.second }
        // 2. Partial brand keyword match (e.g. "temu", "king 365", "nexon", "iron", "trex", "strong", "dino", "crystal", "atlas", "star", "cobra", "spider", "falcon")
        getAllServers().firstOrNull { (name, _) ->
            val lowName = name.lowercase(java.util.Locale.ROOT)
            clean.contains(lowName) || lowName.contains(clean)
        }?.let { return it.second }
        return ""
    }

    fun detectServerNameByHost(hostUrl: String): String {
        val cleanHost = hostUrl.trim().lowercase(java.util.Locale.ROOT)
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")
        if (cleanHost.isBlank()) return ""
        getAllServers().firstOrNull { (_, url) ->
            val uHost = url.lowercase(java.util.Locale.ROOT)
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore("/")
                .substringBefore(":")
            uHost == cleanHost || cleanHost.contains(uHost) || uHost.contains(cleanHost)
        }?.let { return it.first }
        return ""
    }

    fun findAlternativeHostsForAccount(serverName: String, currentUrl: String): List<String> {
        val candidates = LinkedHashSet<String>()
        val lowName = serverName.trim().lowercase(java.util.Locale.ROOT)
        if (lowName.isNotBlank()) {
            val brandToken = lowName.split(" ", "-", "_").firstOrNull { it.length >= 3 }.orEmpty()
            for ((name, url) in getAllServers()) {
                val nLow = name.lowercase(java.util.Locale.ROOT)
                if (nLow == lowName || nLow.contains(lowName) || lowName.contains(nLow) ||
                    (brandToken.isNotBlank() && nLow.contains(brandToken))
                ) {
                    candidates.add(url)
                }
            }
        }
        if (currentUrl.isNotBlank()) {
            val detectedName = detectServerNameByHost(currentUrl)
            if (detectedName.isNotBlank()) {
                val brandToken = detectedName.lowercase(java.util.Locale.ROOT).split(" ").firstOrNull { it.length >= 3 }.orEmpty()
                for ((name, url) in getAllServers()) {
                    if (brandToken.isNotBlank() && name.lowercase(java.util.Locale.ROOT).contains(brandToken)) {
                        candidates.add(url)
                    }
                }
            }
        }
        return candidates.toList()
    }
}

/**
 * Authentic IBO Player / IBO Player Pro Profile Spoofer (v20.0):
 * Spoofs exact IBO Player HTTP headers, User-Agent, Origin, Referer, and Identity encoding
 * across all Xtream API (player_api.php, panel_api.php, get.php) and M3U requests, while using
 * clean redirect-safe headers on video streams so strict IPTV servers (Temu VIP / tmasta.com,
 * King 365, Iron, Nexon, etc.) work seamlessly on both Android TV and Phone.
 */
object IboPlayerProfileSpoofer {
    const val PRIMARY_IBO_USER_AGENT =
        "IBOPlayer/1.0 (Linux; Android 9; MI 8 Lite) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36"

    val IBO_USER_AGENTS = listOf(
        PRIMARY_IBO_USER_AGENT,
        "IBOPlayerPro/3.9 (Linux; Android 11; Mi Box S) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/96.0.4664.45 Safari/537.36",
        "IBOPlayer/3.8 (Android; SmartTV)",
        "okhttp/4.9.3",
        "IPTVSmartersPlayer/6.0 (IBOPlayer/1.0)",
        "VLC/3.0.20 LibVLC/3.0.20"
    )

    fun extractServerOrigin(urlOrBase: String): String {
        val clean = urlOrBase.trim()
        if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            return ""
        }
        return runCatching {
            val uri = java.net.URI(clean)
            val scheme = uri.scheme ?: "http"
            val host = uri.host ?: return ""
            val portPart = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://$host$portPart"
        }.getOrDefault("")
    }

    fun applyIboApiHeaders(
        builder: okhttp3.Request.Builder,
        targetUrl: String,
        serverBaseUrl: String = "",
        userAgent: String = PRIMARY_IBO_USER_AGENT,
        useIdentityEncoding: Boolean = true
    ): okhttp3.Request.Builder {
        val origin = serverBaseUrl.trim().removeSuffix("/").ifBlank { extractServerOrigin(targetUrl) }
        builder.header("User-Agent", userAgent)
        builder.header("Accept", "application/json, text/plain, */*")
        builder.header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        if (useIdentityEncoding) {
            builder.header("Accept-Encoding", "identity")
        }
        builder.header("Connection", "keep-alive")
        builder.header("X-Requested-With", "com.ibo.player")
        if (origin.isNotBlank()) {
            builder.header("Origin", origin)
            builder.header("Referer", "$origin/")
        }
        return builder
    }

    val STREAM_PLAYBACK_USER_AGENTS = listOf(
        "IBOPlayer/1.0 (Linux; Android 11; TV)",
        "VLC/3.0.20 LibVLC/3.0.20",
        "IPTVSmartersPlayer",
        "Lavf/60.16.100",
        "ExoPlayerLib/2.19.1 (Linux; Android 12) ExoPlayer",
        PRIMARY_IBO_USER_AGENT
    )

    fun buildStreamHeadersMap(
        targetUrl: String,
        serverBaseUrl: String = "",
        customReferer: String? = null,
        customOrigin: String? = null,
        customCookie: String? = null,
        isLiveManifest: Boolean = false
    ): MutableMap<String, String> {
        val headers = mutableMapOf(
            "Connection" to "keep-alive",
            "Accept" to "*/*"
        )
        if (!customReferer.isNullOrBlank()) headers["Referer"] = customReferer.trim()
        if (!customOrigin.isNullOrBlank()) headers["Origin"] = customOrigin.trim()
        if (!customCookie.isNullOrBlank()) headers["Cookie"] = customCookie.trim()
        return headers
    }
}

enum class AppUiLanguage(
    val code: String,
    val nativeLabel: String,
    val layoutDirection: LayoutDirection
) {
    AR("ar", "العربية", LayoutDirection.Rtl),
    FR("fr", "Français", LayoutDirection.Ltr),
    EN("en", "English", LayoutDirection.Ltr)
}

object AdultContentFilter {
    private val adultWordRegex = Regex(
        "(\\badult\\b|\\badults\\b|\\bxxx\\b|\\bporn\\b|\\bporno\\b|\\bsex\\b|\\berotic\\b|\\bnsfw\\b)",
        RegexOption.IGNORE_CASE
    )

    private fun checkSingleText(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s.contains("+18") || s.contains("18+") || s.contains("+21") || s.contains("21+") ||
            s.contains("إباح") || s.contains("اباح") || s.contains("للكبار")
        ) return true
        val low = s.lowercase(java.util.Locale.ROOT)
        if (low.contains("playboy") || low.contains("brazzers") || low.contains("xvideos") ||
            low.contains("xnxx") || low.contains("onlyfans") || low.contains("hentai") ||
            low.contains("porno") || low.contains("erotic") || low.contains("nsfw")
        ) return true
        if (low.contains("adult") || low.contains("xxx") || low.contains("porn") || low.contains("sex")) {
            return adultWordRegex.containsMatchIn(low)
        }
        return false
    }

    fun isAdultOr18Plus(name: String, group: String = ""): Boolean {
        return checkSingleText(name) || checkSingleText(group)
    }
}

object SmartM3uParser {
    data class ExtractedXtream(
        val serverUrl: String,
        val username: String,
        val password: String
    )

    fun tryExtractXtreamCredentials(rawInput: String): ExtractedXtream? {
        val text = rawInput.trim()
        if (!text.startsWith("http://", ignoreCase = true) && !text.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return runCatching {
            val uri = java.net.URI(text)
            val scheme = uri.scheme ?: "http"
            val host = uri.host ?: return null
            val portPart = if (uri.port > 0) ":${uri.port}" else ""
            val base = "$scheme://$host$portPart"

            val query = uri.rawQuery.orEmpty()
            if (query.isNotBlank()) {
                val params = query.split("&").associate {
                    val k = it.substringBefore("=").lowercase()
                    val v = URLDecoder.decode(it.substringAfter("=", ""), "UTF-8")
                    k to v
                }
                val u = (params["username"] ?: params["user"]).orEmpty().trim()
                val p = (params["password"] ?: params["pass"]).orEmpty().trim()
                if (u.isNotBlank() && p.isNotBlank()) {
                    return ExtractedXtream(base, u, p)
                }
            }

            val path = uri.path.orEmpty()
            val m = Regex("/(?:live|movie|series)/([^/]+)/([^/]+)", RegexOption.IGNORE_CASE).find(path)
            if (m != null) {
                val u = URLDecoder.decode(m.groupValues[1], "UTF-8").trim()
                val p = URLDecoder.decode(m.groupValues[2], "UTF-8").trim()
                if (u.isNotBlank() && p.isNotBlank()) {
                    return ExtractedXtream(base, u, p)
                }
            }
            null
        }.getOrNull()
    }
}

enum class DiagnosticAlertType {
    ACCOUNT_EXPIRED,
    INTERNET_ERROR,
    SERVER_OUTAGE
}

@Immutable
data class DiagnosticAlertInfo(
    val type: DiagnosticAlertType,
    val title: String,
    val message: String,
    val sourceName: String = "",
    val expDate: String = ""
)

object ExpiryAlertHelper {
    fun isDateExpiringSoon(dateStr: String, thresholdDays: Int = 5): Boolean {
        val clean = dateStr.trim()
        if (clean.isEmpty() || clean == "نشط" || clean == "Active") return false
        return runCatching {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val parsed = sdf.parse(clean.take(10)) ?: return false
            val diffMs = parsed.time + (23 * 3600 * 1000L) - System.currentTimeMillis()
            val days = (diffMs / (24 * 3600 * 1000L)).toInt()
            days <= thresholdDays
        }.getOrDefault(false)
    }

    fun isDateAlreadyExpired(dateStr: String): Boolean {
        val clean = dateStr.trim()
        if (clean.isEmpty() || clean == "نشط" || clean == "Active") return false
        return runCatching {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val parsed = sdf.parse(clean.take(10)) ?: return false
            val endOfDayMs = parsed.time + (23 * 3600 * 1000L) + (59 * 60 * 1000L)
            endOfDayMs < System.currentTimeMillis()
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    fun isInternetAvailable(context: android.content.Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val net = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(net) ?: return false
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                val info = cm.activeNetworkInfo
                info != null && info.isConnected
            }
        }.getOrDefault(true)
    }
}

object AppStrings {
    fun tr(lang: AppUiLanguage, ar: String, fr: String, en: String): String = when (lang) {
        AppUiLanguage.AR -> ar
        AppUiLanguage.FR -> fr
        AppUiLanguage.EN -> en
    }

    fun favoritesLabel(lang: AppUiLanguage): String =
        tr(lang, "★ المفضلة", "★ Favoris", "★ Favorites")

    fun allBouquetsLabel(lang: AppUiLanguage): String =
        tr(lang, "الكل", "Tout", "All")
}

enum class MainTab(
    val titleAr: String,
    val titleFr: String,
    val titleEn: String
) {
    LIVE("القنوات", "Direct TV", "Live TV"),
    BOUQUETS("الباقات", "Bouquets", "Bouquets"),
    MOVIES("الأفلام", "Films", "Movies"),
    SERIES("المسلسلات", "Séries", "Series"),
    XTREAM("فضاء الزبون", "Espace Client", "Customer Space"),
    FAVORITES("المفضلة", "Favoris", "Favorites"),
    ACTIVATION("الصلاحية والتفعيل", "Validité & Code", "Validity & Code"),
    SETTINGS("الإعدادات", "Paramètres", "Settings");

    fun label(lang: AppUiLanguage): String = AppStrings.tr(lang, titleAr, titleFr, titleEn)
}

enum class StreamContentType {
    LIVE_TV,
    MOVIE,
    SERIES
}

enum class PlaylistDisplayMode(
    val titleAr: String,
    val titleFr: String,
    val titleEn: String
) {
    MERGE_ALL(
        "دمج جميع القوائم معاً (قوائم XTREAM أولاً)",
        "Fusionner toutes les listes (XTREAM en premier)",
        "Merge all playlists (XTREAM first)"
    ),
    SEPARATE_LISTS(
        "فصل كل قائمة على حدة (التبديل السريع)",
        "Séparer chaque liste individuellement",
        "Separate playlists (Quick Switch)"
    );

    fun label(lang: AppUiLanguage): String = AppStrings.tr(lang, titleAr, titleFr, titleEn)
}

enum class BufferProfile(
    val titleAr: String,
    val descAr: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackBufferMs: Int,
    val rebufferMs: Int,
    val targetBufferBytes: Int,
    val backBufferMs: Int = 10_000
) {
    XPOLA_ZERO_LAG(
        "ثبات فائق ومنع التقطيع — Rocket VLC Shield (افتراضي)",
        "فتح صاروخي للقنوات (350ms) مع وسادة تخزين ذكية تمتص هزات الإنترنت وتمنع التقطيع نهائياً (32MB)",
        12_000, 40_000, 350, 900, 32 * 1024 * 1024, 10_000
    ),
    ULTRA_STABLE(
        "حماية قصوى للإنترنت الضعيف والمتذبذب (Max Anti-Freeze)",
        "وسادة تخزين عميقة حتى 60 ثانية (48MB) تمنع توقف الصورة أو التقطيع عند هبوط السرعة",
        20_000, 60_000, 500, 1_200, 48 * 1024 * 1024, 14_000
    ),
    BALANCED(
        "متوازن ذكي (Smart Dynamic HD)",
        "توازن مثالي بين سرعة الاستجابة وثبات البث المباشر والأفلام (32MB)",
        10_000, 36_000, 320, 850, 32 * 1024 * 1024, 10_000
    ),
    TURBO_INSTANT(
        "استجابة صاروخية للخطوط السريعة وملفات MPD (Rocket Zapping)",
        "فتح لحظي فوري (250ms) للقنوات وملفات MPD السريعة والأفلام (24MB)",
        8_000, 30_000, 250, 700, 24 * 1024 * 1024, 8_000
    ),
    LITE_LOW_RAM(
        "تسريع خفيف متوافق مع الشاشات والأجهزة — Smart TV Turbo",
        "تشغيل فوري سلس ومتوافق مع جميع شاشات Android TV والأجهزة دون تعليق",
        8_000, 28_000, 350, 850, 24 * 1024 * 1024, 6_000
    )
}

enum class InternalPlayerEngine(
    val titleAr: String,
    val titleFr: String,
    val titleEn: String,
    val badgeLabel: String
) {
    EXO_PLAYER(
        "المشغل الداخلي ExoPlayer (Google Media3 HD)",
        "Lecteur Interne ExoPlayer (Media3 HD)",
        "Internal ExoPlayer (Media3 HD)",
        "ExoPlayer"
    ),
    VLC_PLAYER(
        "المشغل الداخلي VLC (LibVLC جميع الكوديكات)",
        "Lecteur Interne VLC (LibVLC Universel)",
        "Internal VLC Engine (LibVLC All Codecs)",
        "VLC Engine"
    ),
    AUTO_HYBRID(
        "ذكي مزدوج تلقائي (ExoPlayer + VLC عند الحاجة)",
        "Hybride Intelligent (ExoPlayer + VLC)",
        "Smart Hybrid (ExoPlayer + VLC Auto)",
        "Auto Exo+VLC"
    );

    fun label(lang: AppUiLanguage): String = AppStrings.tr(lang, titleAr, titleFr, titleEn)
}

enum class UiLayoutStyle(
    val titleAr: String,
    val titleFr: String,
    val titleEn: String
) {
    CLASSIC_LIVE_PRO(
        "التصميم الكلاسيكي الاحترافي (الافتراضي)",
        "Design Classique Professionnel (Par défaut)",
        "Classic Pro Layout (Default)"
    ),
    MODERN_ROYAL(
        "التصميم المتطور الملكي (Modern Royal)",
        "Design Moderne Royal",
        "Modern Royal Layout"
    );

    fun label(lang: AppUiLanguage): String = AppStrings.tr(lang, titleAr, titleFr, titleEn)
}

@Immutable
data class VodWatchProgress(
    val itemKey: String = "",
    val stableKey: String = "",
    val title: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val resolvedKey: String
        get() = itemKey.ifBlank { stableKey }

    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val isCompleted: Boolean
        get() = (durationMs > 0L && progressFraction >= 0.92f) || (durationMs <= 0L && positionMs > 45 * 60_000L)

    val hasResumePoint: Boolean
        get() = positionMs >= 10_000L && !isCompleted

    val formattedPosition: String
        get() = formatDurationMs(positionMs)

    val formattedDuration: String
        get() = formatDurationMs(durationMs)

    companion object {
        fun formatDurationMs(ms: Long): String {
            if (ms <= 0L) return "00:00"
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val h = totalSec / 3600L
            val m = (totalSec % 3600L) / 60L
            val s = totalSec % 60L
            return if (h > 0L) {
                String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s)
            } else {
                String.format(java.util.Locale.US, "%02d:%02d", m, s)
            }
        }
    }
}

object DevicePerformanceDetector {
    @Volatile
    private var cachedIsWeak: Boolean? = null

    fun isLowRamOrWeakDevice(context: android.content.Context): Boolean {
        cachedIsWeak?.let { return it }
        val result = runCatching {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val isLowRamFlag = am?.isLowRamDevice == true
            val memClass = am?.memoryClass ?: 192
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val totalRamMb = if (memInfo.totalMem > 0L) memInfo.totalMem / (1024L * 1024L) else 3072L
            isLowRamFlag || memClass <= 128 || totalRamMb <= 1400L
        }.getOrDefault(false)
        cachedIsWeak = result
        return result
    }
}

object NetworkSpeedMonitor {
    @Volatile private var lastTotalRxBytes: Long = -1L
    @Volatile private var lastUidRxBytes: Long = -1L
    @Volatile private var lastSampleTimeMs: Long = 0L
    @Volatile private var lastComputedLabel: String = "0 KB/s"

    @Synchronized
    fun sampleCurrentSpeedLabel(): String {
        return runCatching {
            val now = android.os.SystemClock.elapsedRealtime()
            val rawElapsed = now - lastSampleTimeMs
            if (lastSampleTimeMs > 0L && rawElapsed < 450L) {
                return lastComputedLabel
            }
            val totalRx = android.net.TrafficStats.getTotalRxBytes()
            val uidRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
            val elapsedMs = rawElapsed.coerceAtLeast(1L)

            var deltaBytes = 0L
            if (lastSampleTimeMs > 0L && elapsedMs <= 12_000L) {
                val dTotal = if (totalRx > 0L && lastTotalRxBytes > 0L && totalRx >= lastTotalRxBytes) {
                    totalRx - lastTotalRxBytes
                } else 0L
                val dUid = if (uidRx > 0L && lastUidRxBytes > 0L && uidRx >= lastUidRxBytes) {
                    uidRx - lastUidRxBytes
                } else 0L
                deltaBytes = maxOf(dTotal, dUid)
            }

            lastTotalRxBytes = totalRx
            lastUidRxBytes = uidRx
            lastSampleTimeMs = now

            val bytesPerSec = (deltaBytes * 1000L) / elapsedMs
            val formatted = formatSpeed(bytesPerSec)
            lastComputedLabel = formatted
            formatted
        }.getOrDefault(lastComputedLabel)
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 KB/s"
        val kb = bytesPerSec / 1024.0
        return if (kb >= 1024.0) {
            val mb = kb / 1024.0
            String.format(java.util.Locale.US, "%.1f MB/s", mb)
        } else {
            String.format(java.util.Locale.US, "%.0f KB/s", kb.coerceAtLeast(1.0))
        }
    }
}

enum class ThumbnailCacheLimitOption(
    val titleAr: String,
    val maxBytes: Long
) {
    MB_32("32 MB (موفّر للمساحة والأجهزة الضعيفة)", 32L * 1024 * 1024),
    MB_64("64 MB (متوازن وموصى به — افتراضي)", 64L * 1024 * 1024),
    MB_128("128 MB (لشاشات 4K والمكتبات الضخمة)", 128L * 1024 * 1024)
}

enum class PlaybackSpeedOption(val labelAr: String, val speed: Float) {
    NORMAL("1.0x طبيعي (موصى به للبث الحي)", 1.0f),
    FAST_110("1.10x سريع خفيف", 1.10f),
    FAST_125("1.25x أسرع", 1.25f),
    FAST_150("1.50x فائق", 1.5f),
    FAST_200("2.00x مضاعف", 2.0f)
}

enum class VideoResizeMode(val titleAr: String, val exoMode: Int) {
    FILL("ملء الشاشة بالكامل (Fill)", 3),
    FIT("ملائم تلقائي (Fit)", 0),
    ZOOM("تكبير سينمائي (Zoom)", 4),
    FIXED_WIDTH("عرض ثابت", 1),
    FIXED_HEIGHT("ارتفاع ثابت", 2)
}

@Immutable
data class SeriesEpisode(
    val id: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val url: String,
    val durationLabel: String = "",
    val containerExtension: String = "mp4"
)

@Immutable
data class DetectedStreamSpec(
    val resolvedUrl: String,
    val containerFormat: String,
    val mimeType: String,
    val videoCodecHint: String,
    val audioCodecHint: String,
    val preferSoftwareDecoder: Boolean,
    val freshCookie: String? = null,
    val probeLatencyMs: Long = 0L,
    val diagnosticNote: String = ""
)

@Immutable
data class SyncProgressState(
    val isSyncing: Boolean = false,
    val sourceId: String = "",
    val sourceName: String = "",
    val phaseLabel: String = "",
    val progressPercent: Int = 0,
    val fetchedLiveCount: Int = 0,
    val fetchedMovieCount: Int = 0,
    val fetchedSeriesCount: Int = 0,
    val addedCount: Int = 0,
    val updatedCount: Int = 0,
    val removedCount: Int = 0,
    val isDifferential: Boolean = false,
    val lastError: String? = null
)

@Immutable
data class DatabasePerformanceMetrics(
    val totalChannelsInDb: Int = 0,
    val totalMoviesInDb: Int = 0,
    val totalSeriesInDb: Int = 0,
    val totalBouquetsInDb: Int = 0,
    val dbFileSizeMb: String = "0.0 MB",
    val thumbCacheSizeMb: String = "0.0 MB",
    val ramUsedMb: Int = 0,
    val ramMaxMb: Int = 0,
    val lastQueryTimeMs: Long = 0L,
    val lastSyncDurationMs: Long = 0L,
    val lastSyncSummary: String = "مكتبة SQLite المحلية جاهزة",
    val supportedVideoCodecs: List<String> = emptyList(),
    val supportedAudioCodecs: List<String> = emptyList(),
    val recentDiagnosticLogs: List<String> = emptyList()
)

@Immutable
data class TvChannel(
    val id: Int,
    val number: Int,
    val name: String,
    val url: String,
    val group: String = "عام",
    val logo: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val origin: String? = null,
    val cookie: String? = null,
    val drmScheme: String = "",
    val drmKey: String = "",
    val contentType: StreamContentType = StreamContentType.LIVE_TV,
    val rating: String = "",
    val releaseYear: String = "",
    val plot: String = "",
    val seriesId: Int = 0,
    val episodes: List<SeriesEpisode> = emptyList(),
    val playlistId: String = "PORTAL_MAIN",
    val playlistName: String = "الرئيسية",
    val streamId: Int = 0,
    val streamBaseUrl: String = "",
    val streamUsername: String = "",
    val streamPassword: String = "",
    val containerExtension: String = "",
    val videoCodecHint: String = "",
    val audioCodecHint: String = ""
) {
    /**
     * Stored collision-free unique key & pre-computed lowercase search key (zero heap allocations during 60fps scroll).
     */
    val stableKey: String = "${playlistId}_${contentType.name}_${if (streamId > 0) streamId else if (seriesId > 0) seriesId else id}_${group.hashCode()}_${url.hashCode()}"

    val searchKey: String = "${name.lowercase(java.util.Locale.ROOT)} ${group.lowercase(java.util.Locale.ROOT)} $number"

    private fun safeEncodeCredential(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return runCatching {
            val decoded = URLDecoder.decode(trimmed, "UTF-8")
            URLEncoder.encode(decoded, "UTF-8")
        }.getOrDefault(trimmed)
    }

    /**
     * Universal Candidate URL Builder (v20.0):
     * - Prevents double URL-encoding of credentials.
     * - For Live TV: generates both HLS (.m3u8) and raw MPEG-TS (.ts) and extensionless URLs immediately,
     *   plus fallback hosts from KnownIptvServersCatalog if the primary server host ever changes!
     * - For VOD Movies & Series: tries the primary container first, then .mp4, .mkv, .ts, .m3u8, .avi, .webm, .mov, .flv.
     */
    fun buildFormatCandidateUrls(activeAccount: XtreamAccountConfig? = null): List<String> {
        val candidates = LinkedHashSet<String>()
        val rawBase = (activeAccount?.serverUrl?.takeIf { it.isNotBlank() } ?: streamBaseUrl)
            .trim()
            .removeSuffix("/")
        val effBase = if (rawBase.isNotBlank() && !rawBase.startsWith("http://", ignoreCase = true) && !rawBase.startsWith("https://", ignoreCase = true)) {
            KnownIptvServersCatalog.resolveHostByServerName(rawBase).ifBlank { "http://$rawBase" }
        } else if (rawBase.isBlank() && activeAccount?.serverName?.isNotBlank() == true) {
            KnownIptvServersCatalog.resolveHostByServerName(activeAccount.serverName)
        } else {
            rawBase
        }.trim().removeSuffix("/")

        val effUser = safeEncodeCredential(
            activeAccount?.username?.takeIf { it.isNotBlank() } ?: streamUsername
        )
        val effPass = safeEncodeCredential(
            activeAccount?.password?.takeIf { it.isNotBlank() } ?: streamPassword
        )

        fun addProtocolVariant(u: String) {
            if (u.startsWith("https://", ignoreCase = true)) {
                candidates.add("http://" + u.substring(8))
            } else if (u.startsWith("http://", ignoreCase = true)) {
                candidates.add("https://" + u.substring(7))
            }
        }

        val primary = url.trim()

        if (contentType == StreamContentType.LIVE_TV) {
            if (streamId > 0 && effBase.isNotBlank() && effUser.isNotBlank() && effPass.isNotBlank()) {
                // Provide both .m3u8 (HLS) and .ts (raw MPEG-TS) right at the top so whether the server
                // prefers HLS or raw TS on Android TV, playback opens in <1s!
                candidates.add("$effBase/live/$effUser/$effPass/$streamId.m3u8")
                candidates.add("$effBase/live/$effUser/$effPass/$streamId.ts")
                if (primary.isNotBlank()) {
                    candidates.add(primary)
                }
                candidates.add("$effBase/$effUser/$effPass/$streamId")
                candidates.add("$effBase/live/$effUser/$effPass/$streamId")
                candidates.add("$effBase/$effUser/$effPass/$streamId.m3u8")
                candidates.add("$effBase/$effUser/$effPass/$streamId.ts")
                if (effBase.startsWith("https://", ignoreCase = true)) {
                    val httpBase = "http://" + effBase.substring(8)
                    candidates.add("$httpBase/live/$effUser/$effPass/$streamId.m3u8")
                    candidates.add("$httpBase/live/$effUser/$effPass/$streamId.ts")
                }
            } else if (primary.isNotBlank()) {
                candidates.add(primary)
            }

            val baseWithoutQuery = primary.substringBefore("?")
            val querySuffix = if (primary.contains("?")) "?" + primary.substringAfter("?") else ""
            when {
                baseWithoutQuery.endsWith(".mpd", ignoreCase = true) -> {
                    addProtocolVariant(primary)
                }
                baseWithoutQuery.endsWith(".m3u8", ignoreCase = true) -> {
                    val prefix = baseWithoutQuery.dropLast(5)
                    candidates.add("$prefix.ts$querySuffix")
                    candidates.add("$prefix$querySuffix")
                    addProtocolVariant(primary)
                }
                baseWithoutQuery.endsWith(".ts", ignoreCase = true) -> {
                    val prefix = baseWithoutQuery.dropLast(3)
                    candidates.add("$prefix.m3u8$querySuffix")
                    candidates.add("$prefix$querySuffix")
                    addProtocolVariant(primary)
                }
                primary.isNotBlank() &&
                    !baseWithoutQuery.endsWith(".mp4", ignoreCase = true) &&
                    !baseWithoutQuery.endsWith(".mkv", ignoreCase = true) -> {
                    candidates.add("$baseWithoutQuery.m3u8$querySuffix")
                    candidates.add("$baseWithoutQuery.ts$querySuffix")
                    addProtocolVariant(primary)
                }
            }
        } else {
            // VOD Movies & Series Episodes: primary URL first, then container fallbacks (.mp4, .mkv, .ts, .m3u8, .avi, .webm, .mov, .flv)
            if (primary.isNotBlank()) {
                candidates.add(primary)
            }
            if (streamId > 0 && effBase.isNotBlank() && effUser.isNotBlank() && effPass.isNotBlank()) {
                val folder = if (contentType == StreamContentType.SERIES || primary.contains("/series/", ignoreCase = true)) "series" else "movie"
                val extPref = containerExtension.trim().lowercase().removePrefix(".")
                val vodExts = listOfNotNull(extPref.takeIf { it.isNotBlank() }, "mp4", "mkv", "ts", "m3u8", "avi", "webm", "mov", "flv").distinct()
                for (ext in vodExts) {
                    candidates.add("$effBase/$folder/$effUser/$effPass/$streamId.$ext")
                }
            }
            val baseWithoutQuery = primary.substringBefore("?")
            val querySuffix = if (primary.contains("?")) "?" + primary.substringAfter("?") else ""
            val dotIdx = baseWithoutQuery.lastIndexOf('.')
            val slashIdx = baseWithoutQuery.lastIndexOf('/')
            if (dotIdx > slashIdx + 1) {
                val prefix = baseWithoutQuery.substring(0, dotIdx)
                for (ext in listOf("mp4", "mkv", "ts", "m3u8", "avi", "webm", "mov", "flv")) {
                    candidates.add("$prefix.$ext$querySuffix")
                }
            }
            if (primary.isNotBlank()) {
                addProtocolVariant(primary)
            }
        }

        return candidates.filter { it.isNotBlank() }.take(10)
    }
}

@Immutable
data class XtreamAccountConfig(
    val id: String = UUID.randomUUID().toString().take(8),
    val serverName: String = "",
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val enabled: Boolean = true,
    val isRemoteFromPortal: Boolean = false,
    val orderIndex: Int = 0,
    val statusNote: String = "جاهز للمزامنة",
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val expDate: String = "",
    val lastSyncTimestamp: Long = 0L,
    val lastCatalogEtag: String = ""
) {
    val isConfigured: Boolean
        get() = ((serverUrl.isNotBlank() || serverName.isNotBlank()) && username.isNotBlank() && password.isNotBlank()) || m3uUrl.isNotBlank()
}

data class XtreamFullSyncResult(
    val account: XtreamAccountConfig,
    val liveChannels: List<TvChannel>,
    val movies: List<TvChannel>,
    val series: List<TvChannel>,
    val isDifferentialUnchanged: Boolean = false,
    val catalogHash: String = ""
)

@Immutable
data class BouquetSummary(
    val name: String,
    val count: Int,
    val sampleLogos: List<String> = emptyList(),
    val isFavoritesSpecial: Boolean = false
)

@Immutable
data class SubscriptionInfo(
    val active: Boolean = false,
    val isExpired: Boolean = false,
    val isTrial: Boolean = false,
    val reason: String = "",
    val expireDate: String = "",
    val daysLeft: Int = 0,
    val deviceName: String = "",
    val message: String = ""
)

data class ApiSyncResult(
    val channels: List<TvChannel>,
    val movies: List<TvChannel> = emptyList(),
    val series: List<TvChannel> = emptyList(),
    val subscription: SubscriptionInfo,
    val remoteXtreamLists: List<XtreamAccountConfig> = emptyList(),
    val serverCatalogHash: String = "",
    val isDifferentialUnchanged: Boolean = false
)

@Immutable
data class CloudflareTunnelStatus(
    val connected: Boolean = true,
    val edgeColo: String = "WARP • سريع",
    val clientIp: String = "",
    val latencyMs: Long = 12L,
    val systemVpnActive: Boolean = true,
    val statusText: String = "متصّل تلقائياً عبر نفق التطبيق الداخلي الآمن ✓"
)

@Immutable
data class PlayerPreferences(
    val appLanguage: AppUiLanguage = AppUiLanguage.AR,
    val playlistDisplayMode: PlaylistDisplayMode = PlaylistDisplayMode.MERGE_ALL,
    val activePlaylistFilterId: String = "ALL",
    val playlistOrderIds: List<String> = emptyList(),
    val bufferProfile: BufferProfile = BufferProfile.XPOLA_ZERO_LAG,
    val resizeMode: VideoResizeMode = VideoResizeMode.FILL,
    val thumbnailCacheLimit: ThumbnailCacheLimitOption = ThumbnailCacheLimitOption.MB_64,
    val autoReconnect: Boolean = true,
    val maxReconnectRetries: Int = 8,
    val preferSoftwareDecoder: Boolean = false,
    val useGridLayout: Boolean = true,
    val showBouquetSidebar: Boolean = true,
    val showScreenCodeOverlay: Boolean = true,
    val customBouquetOrder: List<String> = emptyList(),
    val hiddenBouquets: Set<String> = emptySet(),
    val customChannelOrder: List<String> = emptyList(),
    val uiLayoutStyle: UiLayoutStyle = UiLayoutStyle.CLASSIC_LIVE_PRO,
    val lowEndDeviceMode: Boolean = false,
    val showNetworkSpeed: Boolean = true,
    val playerEngine: InternalPlayerEngine = InternalPlayerEngine.EXO_PLAYER
)

enum class VoucherAlertType {
    SUCCESS,
    USED_CARD,
    INVALID_OR_NOT_FOUND,
    EXPIRED_CARD,
    RATE_LIMITED,
    NETWORK_ERROR
}

@Immutable
data class VoucherAlertInfo(
    val type: VoucherAlertType,
    val title: String,
    val message: String,
    val enteredCode: String = "",
    val addedDays: Int = 0,
    val newExpireDate: String = ""
)

/**
 * Prevents UI freezes, double-navigation, and coroutine floods when the user rapidly clicks
 * or holds down D-Pad / touch buttons.
 */
object SafeClickGuard {
    private val lastClickMap = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun allowAction(actionKey: String = "GLOBAL_UI", minIntervalMs: Long = 220L): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val prev = lastClickMap[actionKey] ?: 0L
        if (now - prev < minIntervalMs) {
            return false
        }
        lastClickMap[actionKey] = now
        if (lastClickMap.size > 256) {
            lastClickMap.clear()
            lastClickMap[actionKey] = now
        }
        return true
    }

    inline fun runThrottled(
        actionKey: String = "GLOBAL_UI",
        minIntervalMs: Long = 220L,
        block: () -> Unit
    ) {
        if (allowAction(actionKey, minIntervalMs)) {
            block()
        }
    }
}
