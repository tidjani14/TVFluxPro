package com.tvflux.pro

import androidx.compose.ui.unit.LayoutDirection
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
        "دمج جميع القوائم معاً في قائمة موحدة",
        "Fusionner toutes les listes ensemble",
        "Merge all playlists together"
    ),
    SEPARATE_LISTS(
        "فصل كل قائمة على حدة (المستخدم يختار القائمة)",
        "Séparer chaque liste individuellement",
        "Separate playlists (Switch per playlist)"
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
        "ثبات فائق ضد الهزات — Adaptive Shield (افتراضي)",
        "يتجاوب فوراً مع خط الزبون ويمتص هزات الإنترنت دون تقطيع (حتى 40MB)",
        24_000, 75_000, 900, 2_200, 40 * 1024 * 1024, 20_000
    ),
    ULTRA_STABLE(
        "حماية قصوى للإنترنت الضعيف والمتذبذب (Max Anti-Freeze)",
        "وسادة تخزين عميقة حتى 110 ثانية (56MB) تمنع توقف الصورة عند هبوط السرعة",
        36_000, 110_000, 1_400, 3_200, 56 * 1024 * 1024, 25_000
    ),
    BALANCED(
        "متوازن ذكي (Smart Dynamic HD)",
        "توازن مثالي بين سرعة الاستجابة وثبات البث المباشر والأفلام (28MB)",
        16_000, 50_000, 800, 1_800, 28 * 1024 * 1024, 15_000
    ),
    TURBO_INSTANT(
        "استجابة فورية للخطوط السريعة (Turbo Zapping)",
        "فتح لحظي للقنوات والأفلام على خطوط الألياف والاتصال المستقر (20MB)",
        10_000, 32_000, 550, 1_200, 20 * 1024 * 1024, 12_000
    )
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
    val durationLabel: String = ""
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
    val containerExtension: String = ""
) {
    val stableKey: String
        get() = "${playlistId}_${contentType.name}_${id}_${number}"

    /**
     * Builds a comprehensive list of candidate stream URLs:
     * - For LIVE_TV: tests primary URL, then .m3u8, .ts, and raw streamId endpoints.
     * - For MOVIE / SERIES: supports all VOD video containers (.mp4, .mkv, .avi, .mov, .webm, .flv, .ts, .m3u8)
     *   so movies & series open smoothly regardless of server container format.
     */
    fun buildFormatCandidateUrls(): List<String> {
        val candidates = LinkedHashSet<String>()
        val primary = url.trim()
        if (primary.isNotBlank()) {
            candidates.add(primary)
        }

        val baseWithoutQuery = primary.substringBefore("?")
        val querySuffix = if (primary.contains("?")) "?" + primary.substringAfter("?") else ""

        if (contentType == StreamContentType.LIVE_TV) {
            if (streamId > 0 && streamBaseUrl.isNotBlank() && streamUsername.isNotBlank() && streamPassword.isNotBlank()) {
                val base = streamBaseUrl.removeSuffix("/")
                candidates.add("$base/live/$streamUsername/$streamPassword/$streamId.m3u8")
                candidates.add("$base/live/$streamUsername/$streamPassword/$streamId.ts")
                candidates.add("$base/$streamUsername/$streamPassword/$streamId")
            }
            when {
                baseWithoutQuery.endsWith(".ts", ignoreCase = true) -> {
                    val prefix = baseWithoutQuery.dropLast(3)
                    candidates.add("$prefix.m3u8$querySuffix")
                }
                baseWithoutQuery.endsWith(".m3u8", ignoreCase = true) -> {
                    val prefix = baseWithoutQuery.dropLast(5)
                    candidates.add("$prefix.ts$querySuffix")
                }
                !baseWithoutQuery.endsWith(".mp4", ignoreCase = true) &&
                    !baseWithoutQuery.endsWith(".mkv", ignoreCase = true) -> {
                    candidates.add("$baseWithoutQuery.m3u8$querySuffix")
                    candidates.add("$baseWithoutQuery.ts$querySuffix")
                }
            }
        } else {
            // VOD Movies & Series Episodes: full multi-container fallback (.mp4, .mkv, .avi, .mov, .webm, .ts, .m3u8)
            if (streamId > 0 && streamBaseUrl.isNotBlank() && streamUsername.isNotBlank() && streamPassword.isNotBlank()) {
                val base = streamBaseUrl.removeSuffix("/")
                val folder = if (contentType == StreamContentType.SERIES) "series" else "movie"
                val extPref = containerExtension.trim().lowercase().removePrefix(".")
                val vodExts = listOfNotNull(extPref.takeIf { it.isNotBlank() }, "mp4", "mkv", "avi", "mov", "webm", "ts", "m3u8").distinct()
                for (ext in vodExts) {
                    candidates.add("$base/$folder/$streamUsername/$streamPassword/$streamId.$ext")
                }
            }
            val dotIdx = baseWithoutQuery.lastIndexOf('.')
            val slashIdx = baseWithoutQuery.lastIndexOf('/')
            if (dotIdx > slashIdx + 1) {
                val prefix = baseWithoutQuery.substring(0, dotIdx)
                for (ext in listOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m3u8")) {
                    candidates.add("$prefix.$ext$querySuffix")
                }
            }
        }

        return candidates.filter { it.isNotBlank() }.take(6)
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
    val expDate: String = ""
) {
    val isConfigured: Boolean
        get() = (serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()) || m3uUrl.isNotBlank()
}

data class XtreamFullSyncResult(
    val account: XtreamAccountConfig,
    val liveChannels: List<TvChannel>,
    val movies: List<TvChannel>,
    val series: List<TvChannel>
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
    val remoteXtreamLists: List<XtreamAccountConfig> = emptyList()
)

data class CloudflareTunnelStatus(
    val connected: Boolean = false,
    val edgeColo: String = "WARP",
    val clientIp: String = "",
    val latencyMs: Long = 0L,
    val systemVpnActive: Boolean = false,
    val statusText: String = "جارٍ التحقق من نفق الـ VPN الإجباري..."
)

data class PlayerPreferences(
    val appLanguage: AppUiLanguage = AppUiLanguage.AR,
    val playlistDisplayMode: PlaylistDisplayMode = PlaylistDisplayMode.MERGE_ALL,
    val activePlaylistFilterId: String = "ALL",
    val playlistOrderIds: List<String> = emptyList(),
    val bufferProfile: BufferProfile = BufferProfile.XPOLA_ZERO_LAG,
    val resizeMode: VideoResizeMode = VideoResizeMode.FILL,
    val autoReconnect: Boolean = true,
    val maxReconnectRetries: Int = 6,
    val preferSoftwareDecoder: Boolean = false,
    val useGridLayout: Boolean = true,
    val showBouquetSidebar: Boolean = true,
    val customBouquetOrder: List<String> = emptyList(),
    val hiddenBouquets: Set<String> = emptySet(),
    val customChannelOrder: List<String> = emptyList()
)
