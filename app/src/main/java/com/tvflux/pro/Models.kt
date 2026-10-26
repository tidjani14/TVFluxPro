package com.tvflux.pro

import androidx.compose.ui.unit.LayoutDirection
import java.net.URLEncoder
import java.util.UUID

const val APP_BRAND_NAME = "LIVE PRO"
val APP_LOGO_URL: String = listOf("https://", "e.top4top.io", "/p_3941ca8xe1.jpg").joinToString("")
const val ALI_SAT_PROVIDER_NAME = "علي سات (Ali Sat)"
const val ALI_SAT_PHONE_NUMBER = "00213697372480"
const val FAVORITES_BOUQUET_KEY = "__FAV_BOUQUET__"

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
    private val adultRegex = Regex(
        "(\\+18|18\\+|_18\\+|\\+21|21\\+|\\badult\\b|\\badults\\b|\\bxxx\\b|\\bporn\\b|\\bporno\\b|\\bsex\\b|\\berotic\\b|\\bnsfw\\b|\\bplayboy\\b|\\bbrazzers\\b|\\bxvideos\\b|\\bxnxx\\b|\\bonlyfans\\b|\\bhentai\\b|إباحي|اباحي|اباحية|إباحية|للكبار فقط|قنوات الكبار)",
        RegexOption.IGNORE_CASE
    )

    fun isAdultOr18Plus(name: String, group: String = ""): Boolean {
        return adultRegex.containsMatchIn("$name $group")
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
                    val v = java.net.URLDecoder.decode(it.substringAfter("=", ""), "UTF-8")
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
                val u = java.net.URLDecoder.decode(m.groupValues[1], "UTF-8").trim()
                val p = java.net.URLDecoder.decode(m.groupValues[2], "UTF-8").trim()
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
    val backBufferMs: Int = 18_000
) {
    XPOLA_ZERO_LAG(
        "ثبات فائق ومنع التقطيع — Rocket VLC Shield (افتراضي)",
        "فتح صاروخي للقنوات (350ms) مع وسادة تخزين ذكية تمتص هزات الإنترنت وتمنع التقطيع نهائياً (حتى 48MB)",
        22_000, 80_000, 350, 1_100, 48 * 1024 * 1024, 22_000
    ),
    ULTRA_STABLE(
        "حماية قصوى للإنترنت الضعيف والمتذبذب (Max Anti-Freeze)",
        "وسادة تخزين عميقة حتى 120 ثانية (60MB) تمنع توقف الصورة أو التقطيع عند هبوط السرعة",
        38_000, 120_000, 800, 2_000, 60 * 1024 * 1024, 28_000
    ),
    BALANCED(
        "متوازن ذكي (Smart Dynamic HD)",
        "توازن مثالي بين سرعة الاستجابة وثبات البث المباشر والأفلام (32MB)",
        14_000, 52_000, 320, 950, 32 * 1024 * 1024, 16_000
    ),
    TURBO_INSTANT(
        "استجابة صاروخية للخطوط السريعة وملفات MPD (Rocket Zapping)",
        "فتح لحظي فوري (250ms) للقنوات وملفات MPD السريعة والأفلام (24MB)",
        8_000, 36_000, 250, 750, 24 * 1024 * 1024, 14_000
    )
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

data class SeriesEpisode(
    val id: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val url: String,
    val durationLabel: String = "",
    val containerExtension: String = "mp4"
)

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
     * Collision-free unique key across all playlists, bouquets, channels, movies, and series.
     */
    val stableKey: String
        get() = "${playlistId}_${contentType.name}_${if (streamId > 0) streamId else id}_${number}_${group.hashCode()}_${url.hashCode()}"

    /**
     * VLC-Parity Candidate URL Builder:
     * - Prioritizes raw MPEG-TS (.ts) and HLS (.m3u8) and extensionless Xtream streams in the exact order
     *   VLC & TiviMate use, while safely URL-encoding credentials.
     */
    fun buildFormatCandidateUrls(activeAccount: XtreamAccountConfig? = null): List<String> {
        val candidates = LinkedHashSet<String>()
        val effBase = (activeAccount?.serverUrl?.takeIf { it.isNotBlank() } ?: streamBaseUrl)
            .trim()
            .removeSuffix("/")
        val effUser = activeAccount?.username?.takeIf { it.isNotBlank() }
            ?.let { runCatching { URLEncoder.encode(it.trim(), "UTF-8") }.getOrDefault(it.trim()) }
            ?: streamUsername.trim()
        val effPass = activeAccount?.password?.takeIf { it.isNotBlank() }
            ?.let { runCatching { URLEncoder.encode(it.trim(), "UTF-8") }.getOrDefault(it.trim()) }
            ?: streamPassword.trim()

        fun addProtocolVariant(u: String) {
            if (u.startsWith("https://", ignoreCase = true)) {
                candidates.add("http://" + u.substring(8))
            } else if (u.startsWith("http://", ignoreCase = true)) {
                candidates.add("https://" + u.substring(7))
            }
        }

        val primary = url.trim()

        if (contentType == StreamContentType.LIVE_TV) {
            // If primary URL is a custom non-Xtream direct_source, keep it first
            val isStandardXtreamUrl = primary.contains("/live/") || (effBase.isNotBlank() && primary.startsWith(effBase, ignoreCase = true))
            if (primary.isNotBlank() && !isStandardXtreamUrl) {
                candidates.add(primary)
            }
            if (streamId > 0 && effBase.isNotBlank() && effUser.isNotBlank() && effPass.isNotBlank()) {
                // 1. Raw MPEG-TS (.ts) and extensionless (VLC default for Xtream Codes panels) + HLS (.m3u8)
                candidates.add("$effBase/live/$effUser/$effPass/$streamId.ts")
                candidates.add("$effBase/live/$effUser/$effPass/$streamId.m3u8")
                candidates.add("$effBase/$effUser/$effPass/$streamId")
                candidates.add("$effBase/live/$effUser/$effPass/$streamId")
                candidates.add("$effBase/$effUser/$effPass/$streamId.ts")
                candidates.add("$effBase/$effUser/$effPass/$streamId.m3u8")
                if (effBase.startsWith("https://", ignoreCase = true)) {
                    val httpBase = "http://" + effBase.substring(8)
                    candidates.add("$httpBase/live/$effUser/$effPass/$streamId.ts")
                    candidates.add("$httpBase/live/$effUser/$effPass/$streamId.m3u8")
                }
            }
            if (primary.isNotBlank()) {
                candidates.add(primary)
            }
            val baseWithoutQuery = primary.substringBefore("?")
            val querySuffix = if (primary.contains("?")) "?" + primary.substringAfter("?") else ""
            when {
                baseWithoutQuery.endsWith(".mpd", ignoreCase = true) -> {
                    addProtocolVariant(primary)
                }
                baseWithoutQuery.endsWith(".ts", ignoreCase = true) -> {
                    val prefix = baseWithoutQuery.dropLast(3)
                    candidates.add("$prefix.m3u8$querySuffix")
                    candidates.add("$prefix$querySuffix")
                    addProtocolVariant(primary)
                }
                baseWithoutQuery.endsWith(".m3u8", ignoreCase = true) -> {
                    val prefix = baseWithoutQuery.dropLast(5)
                    candidates.add("$prefix.ts$querySuffix")
                    candidates.add("$prefix$querySuffix")
                    addProtocolVariant(primary)
                }
                primary.isNotBlank() &&
                    !baseWithoutQuery.endsWith(".mp4", ignoreCase = true) &&
                    !baseWithoutQuery.endsWith(".mkv", ignoreCase = true) -> {
                    addProtocolVariant(primary)
                    candidates.add("$baseWithoutQuery.ts$querySuffix")
                    candidates.add("$baseWithoutQuery.m3u8$querySuffix")
                }
            }
        } else {
            // VOD Movies & Series Episodes: primary + renewed from streamId + full multi-container fallback
            if (primary.isNotBlank()) {
                candidates.add(primary)
            }
            if (streamId > 0 && effBase.isNotBlank() && effUser.isNotBlank() && effPass.isNotBlank()) {
                val folder = if (contentType == StreamContentType.SERIES) "series" else "movie"
                val extPref = containerExtension.trim().lowercase().removePrefix(".")
                val vodExts = listOfNotNull(extPref.takeIf { it.isNotBlank() }, "mp4", "mkv", "webm", "avi", "mov", "flv", "ts", "m3u8").distinct()
                for (ext in vodExts) {
                    candidates.add("$effBase/$folder/$effUser/$effPass/$streamId.$ext")
                }
            }
            if (primary.isNotBlank()) {
                addProtocolVariant(primary)
            }
            val baseWithoutQuery = primary.substringBefore("?")
            val querySuffix = if (primary.contains("?")) "?" + primary.substringAfter("?") else ""
            val dotIdx = baseWithoutQuery.lastIndexOf('.')
            val slashIdx = baseWithoutQuery.lastIndexOf('/')
            if (dotIdx > slashIdx + 1) {
                val prefix = baseWithoutQuery.substring(0, dotIdx)
                for (ext in listOf("mp4", "mkv", "webm", "avi", "mov", "flv", "ts", "m3u8")) {
                    candidates.add("$prefix.$ext$querySuffix")
                }
            }
        }

        return candidates.filter { it.isNotBlank() }.take(8)
    }
}

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
        get() = (serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()) || m3uUrl.isNotBlank()
}

data class XtreamFullSyncResult(
    val account: XtreamAccountConfig,
    val liveChannels: List<TvChannel>,
    val movies: List<TvChannel>,
    val series: List<TvChannel>,
    val isDifferentialUnchanged: Boolean = false,
    val catalogHash: String = ""
)

data class BouquetSummary(
    val name: String,
    val count: Int,
    val sampleLogos: List<String> = emptyList(),
    val isFavoritesSpecial: Boolean = false
)

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

data class CloudflareTunnelStatus(
    val connected: Boolean = false,
    val edgeColo: String = "WARP",
    val clientIp: String = "",
    val latencyMs: Long = 0L,
    val systemVpnActive: Boolean = false,
    val statusText: String = "جارٍ التحقق من نفق الـ VPN الداخلي للتطبيق..."
)

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
    val customChannelOrder: List<String> = emptyList()
)
