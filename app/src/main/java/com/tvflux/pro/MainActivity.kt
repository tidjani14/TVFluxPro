package com.tvflux.pro

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.VpnService
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// ============================================================================
// Global Platform Ultra-Dark Eye-Comfort Royal Palette (v12.0 - Max Eye Comfort)
// ============================================================================
val BeinPrimaryPurple = Color(0xFF240E42)     // Ultra-Calm Deep Velvet Purple
val BeinHighlightPurple = Color(0xFF4C1D95)   // Soft Eye-Comfort Royal Violet Highlight
val BeinFocusGlow = Color(0xFFFFFFFF)         // Crisp Pure White Remote Focus Ring
val BeinDarkBg = Color(0xFF020105)            // Pure OLED Pitch-Dark Night Background
val BeinSurface = Color(0xFF06030D)           // Deep Velvet Obsidian Surface
val BeinCard = Color(0xFF0B0516)              // Ultra-Dark Eye-Rest Card Surface
val BeinBorder = Color(0xFF190C2E)            // Subtle Dark Amethyst Hairline Border
val EmeraldLive = Color(0xFF10B981)           // Active Status Accent
val CrimsonAlert = Color(0xFFEF4444)          // Used ONLY for Expiring Soon (<=5d) / Expired Alert Frame

private data class CatalogViewSnapshot(
    val allChannels: List<TvChannel> = emptyList(),
    val channelsByBouquet: Map<String, List<TvChannel>> = emptyMap(),
    val rawBouquetSummaries: List<BouquetSummary> = emptyList(),
    val bouquetSummaries: List<BouquetSummary> = emptyList(),
    val allMovies: List<TvChannel> = emptyList(),
    val moviesByCategory: Map<String, List<TvChannel>> = emptyMap(),
    val movieCategories: List<BouquetSummary> = emptyList(),
    val allSeries: List<TvChannel> = emptyList(),
    val seriesByCategory: Map<String, List<TvChannel>> = emptyMap(),
    val seriesCategories: List<BouquetSummary> = emptyList()
)

val Gold = BeinHighlightPurple
val ObsidianBg = BeinDarkBg
val SurfaceDark = BeinSurface
val CardDark = BeinCard

private val BeinTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 19.sp
    )
)

class MainActivity : ComponentActivity() {
    companion object {
        var resumeCheckTrigger by mutableStateOf(0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val msg = throwable.message.orEmpty()
            val stackStr = throwable.stackTraceToString()
            val isRecoverableUiException = (throwable is IllegalStateException || throwable is IllegalArgumentException || throwable is IndexOutOfBoundsException) &&
                (stackStr.contains("androidx.compose") || msg.contains("FocusRequester") || msg.contains("Key") || msg.contains("LayoutNode"))
            if (isRecoverableUiException && thread.name.contains("main", ignoreCase = true)) {
                TiviMateDiagnostics.logEvent("🛡️ تم امتصاص خطأ واجهة سريع دون إغلاق التطبيق: $msg")
                try {
                    android.os.Looper.loop()
                } catch (_: Throwable) {
                }
            } else {
                prevHandler?.uncaughtException(thread, throwable)
            }
        }
        runCatching { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
        setContent {
            LiveProEnterpriseApp()
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCheckTrigger += 1
    }

    override fun onStop() {
        super.onStop()
        runCatching {
            TiviMateLocalDatabase.getInstance(applicationContext).checkpointWalNow()
        }
    }
}

@Composable
private fun LiveProEnterpriseApp() {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var initialized by remember { mutableStateOf(false) }
    var deviceId by remember { mutableStateOf(StorageManager.resolveHardwareDeviceId(context)) }
    var deviceName by remember { mutableStateOf(StorageManager.resolveDeviceName()) }
    var savedActivationCode by remember { mutableStateOf("") }

    var xtreamPlaylists by remember { mutableStateOf<List<XtreamAccountConfig>>(emptyList()) }

    var vpnStatus by remember { mutableStateOf(CloudflareTunnelStatus()) }
    var isConnectingVpn by remember { mutableStateOf(false) }
    var isVpnPermissionDialogShowing by remember { mutableStateOf(false) }
    var vpnPermissionAskedInSession by remember { mutableStateOf(false) }
    var autoVpnPrompted by remember { mutableStateOf(false) }
    var liveNetworkSpeed by remember { mutableStateOf("0 KB/s") }
    var vodWatchProgressMap by remember { mutableStateOf<Map<String, VodWatchProgress>>(emptyMap()) }
    var pendingResumeChannel by remember { mutableStateOf<TvChannel?>(null) }
    var activeResumePositionMs by remember { mutableStateOf(0L) }
    var classicIboShowDashboard by remember { mutableStateOf(true) }

    var portalChannels by remember { mutableStateOf<List<TvChannel>>(emptyList()) }
    var portalMovies by remember { mutableStateOf<List<TvChannel>>(emptyList()) }
    var portalSeries by remember { mutableStateOf<List<TvChannel>>(emptyList()) }

    var xtreamChannelsMap by remember { mutableStateOf<Map<String, List<TvChannel>>>(emptyMap()) }
    var xtreamMoviesMap by remember { mutableStateOf<Map<String, List<TvChannel>>>(emptyMap()) }
    var xtreamSeriesMap by remember { mutableStateOf<Map<String, List<TvChannel>>>(emptyMap()) }

    var favorites by remember { mutableStateOf<List<TvChannel>>(emptyList()) }
    var history by remember { mutableStateOf<List<TvChannel>>(emptyList()) }
    var subscription by remember {
        mutableStateOf<SubscriptionInfo?>(
            SubscriptionInfo(active = true, isExpired = false, daysLeft = 30, reason = "ok")
        )
    }
    var preferences by remember { mutableStateOf(PlayerPreferences()) }
    var diagnosticAlert by remember { mutableStateOf<DiagnosticAlertInfo?>(null) }
    var voucherAlert by remember { mutableStateOf<VoucherAlertInfo?>(null) }
    var isSortingLocalLibrary by remember { mutableStateOf(true) }
    var saveStateJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    var activeTab by remember { mutableStateOf(MainTab.LIVE) }
    var selectedCategory by remember { mutableStateOf("الكل") }
    var selectedMovieCategory by remember { mutableStateOf("الكل") }
    var selectedSeriesCategory by remember { mutableStateOf("الكل") }
    var searchQuery by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("مكتبة SQLite المحلية جاهزة") }
    var isLoading by remember { mutableStateOf(false) }
    var isSyncingXtream by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableStateOf(SyncProgressState()) }
    var browseOfflineAllowed by remember { mutableStateOf(false) }
    val sqliteDb = remember { TiviMateLocalDatabase.getInstance(context) }
    var playingChannel by remember { mutableStateOf<TvChannel?>(null) }
    var selectedSeriesForEpisodes by remember { mutableStateOf<TvChannel?>(null) }

    // Track the exact last-watched item key & scroll states so returning from Player
    // keeps the D-Pad focus on the exact channel/movie/series the user was watching!
    var lastWatchedStableKey by remember { mutableStateOf<String?>(null) }
    var restoreFocusTrigger by remember { mutableStateOf(0) }
    val liveGridState = remember { LazyGridState() }
    val liveListState = remember { LazyListState() }
    val bouquetSidebarListState = remember { LazyListState() }
    val moviesGridState = remember { LazyGridState() }
    val seriesGridState = remember { LazyGridState() }

    var showExitConfirmation by remember { mutableStateOf(false) }
    var showUnifiedValidityModal by remember { mutableStateOf(false) }
    var showMultiPlaylistSelectorModal by remember { mutableStateOf(false) }
    var isFirstTimeSelectorMode by remember { mutableStateOf(false) }
    var isCheckingValidityOnly by remember { mutableStateOf(false) }

    val lang = preferences.appLanguage

    val orderedPlaylists = remember(xtreamPlaylists, preferences.playlistOrderIds) {
        if (preferences.playlistOrderIds.isEmpty()) {
            xtreamPlaylists
        } else {
            val map = preferences.playlistOrderIds.withIndex().associate { it.value to it.index }
            xtreamPlaylists.sortedBy { map[it.id] ?: (1000 + it.orderIndex) }
        }
    }

    val favBouquetTitle = AppStrings.favoritesLabel(lang)
    val allBouquetTitle = AppStrings.allBouquetsLabel(lang)

    // Background-indexed catalog snapshot (runs 100% on Dispatchers.Default so Main UI Thread never freezes!)
    // Enforces XTREAM playlists & bouquets FIRST in their exact native server order, followed by PORTAL_MAIN!
    val catalogSnapshot by produceState(
        initialValue = CatalogViewSnapshot(),
        portalChannels,
        portalMovies,
        portalSeries,
        xtreamChannelsMap,
        xtreamMoviesMap,
        xtreamSeriesMap,
        orderedPlaylists,
        preferences.playlistDisplayMode,
        preferences.activePlaylistFilterId,
        preferences.customChannelOrder,
        preferences.customBouquetOrder,
        preferences.hiddenBouquets,
        lang
    ) {
        val totalRawItems = portalChannels.size + xtreamChannelsMap.values.sumOf { it.size }
        if (totalRawItems > 500) {
            isSortingLocalLibrary = true
        }
        value = withContext(Dispatchers.Default) {
            val disabledIds = orderedPlaylists.filter { !it.enabled }.map { it.id }.toSet()
            val primaryEnabledIds = orderedPlaylists.filter { it.enabled }.map { it.id }
            val extraCachedIds = (xtreamChannelsMap.keys + xtreamMoviesMap.keys + xtreamSeriesMap.keys)
                .filter { it !in primaryEnabledIds && it !in disabledIds }
            val enabledXtreamIds = primaryEnabledIds + extraCachedIds
            val filterId = preferences.activePlaylistFilterId
            val separateMode = preferences.playlistDisplayMode == PlaylistDisplayMode.SEPARATE_LISTS

            // 1. Live TV Channels: XTREAM playlists FIRST in exact server order, then PORTAL_MAIN
            val combinedLive = ArrayList<TvChannel>(4096)
            for (pid in enabledXtreamIds) {
                if (!separateMode || filterId == "ALL" || filterId == pid) {
                    combinedLive.addAll(xtreamChannelsMap[pid].orEmpty())
                }
            }
            if (!separateMode || filterId == "ALL" || filterId == "PORTAL_MAIN") {
                combinedLive.addAll(portalChannels)
            }
            val sortedLive = CustomOrderHelper.sortChannels(
                combinedLive,
                preferences.customChannelOrder,
                preferences.hiddenBouquets
            )

            // Pre-group Live TV by bouquet in LinkedHashMap to preserve exact native server bouquet order
            val liveGrouped = LinkedHashMap<String, MutableList<TvChannel>>(128)
            for (ch in sortedLive) {
                liveGrouped.getOrPut(ch.group) { ArrayList() }.add(ch)
            }

            val rawSummaries = ArrayList<BouquetSummary>(liveGrouped.size + 2)
            rawSummaries.add(
                BouquetSummary(
                    name = allBouquetTitle,
                    count = sortedLive.size,
                    sampleLogos = sortedLive.asSequence().mapNotNull { it.logo }.take(3).toList()
                )
            )
            rawSummaries.add(
                BouquetSummary(
                    name = favBouquetTitle,
                    count = favorites.size,
                    sampleLogos = favorites.asSequence().mapNotNull { it.logo }.take(3).toList(),
                    isFavoritesSpecial = true
                )
            )
            for ((bName, chs) in liveGrouped) {
                if (bName != allBouquetTitle && bName != favBouquetTitle) {
                    rawSummaries.add(
                        BouquetSummary(
                            name = bName,
                            count = chs.size,
                            sampleLogos = chs.asSequence().mapNotNull { it.logo }.take(3).toList()
                        )
                    )
                }
            }
            val sortedSummaries = CustomOrderHelper.sortBouquets(
                rawSummaries,
                preferences.customBouquetOrder,
                preferences.hiddenBouquets
            )

            // 2. VOD Movies: XTREAM playlists FIRST in exact server order, then PORTAL_MAIN
            val combinedMovies = ArrayList<TvChannel>(2048)
            for (pid in enabledXtreamIds) {
                if (!separateMode || filterId == "ALL" || filterId == pid) {
                    combinedMovies.addAll(xtreamMoviesMap[pid].orEmpty())
                }
            }
            if (!separateMode || filterId == "ALL" || filterId == "PORTAL_MAIN") {
                combinedMovies.addAll(portalMovies)
            }
            val moviesGrouped = LinkedHashMap<String, MutableList<TvChannel>>(64)
            for (mv in combinedMovies) {
                moviesGrouped.getOrPut(mv.group) { ArrayList() }.add(mv)
            }
            val movieCats = ArrayList<BouquetSummary>(moviesGrouped.size + 1)
            movieCats.add(BouquetSummary("الكل", combinedMovies.size))
            for ((cName, list) in moviesGrouped) {
                if (cName != "الكل") movieCats.add(BouquetSummary(cName, list.size))
            }

            // 3. VOD Series: XTREAM playlists FIRST in exact server order, then PORTAL_MAIN
            val combinedSeries = ArrayList<TvChannel>(1024)
            for (pid in enabledXtreamIds) {
                if (!separateMode || filterId == "ALL" || filterId == pid) {
                    combinedSeries.addAll(xtreamSeriesMap[pid].orEmpty())
                }
            }
            if (!separateMode || filterId == "ALL" || filterId == "PORTAL_MAIN") {
                combinedSeries.addAll(portalSeries)
            }
            val seriesGrouped = LinkedHashMap<String, MutableList<TvChannel>>(64)
            for (sr in combinedSeries) {
                seriesGrouped.getOrPut(sr.group) { ArrayList() }.add(sr)
            }
            val seriesCats = ArrayList<BouquetSummary>(seriesGrouped.size + 1)
            seriesCats.add(BouquetSummary("الكل", combinedSeries.size))
            for ((cName, list) in seriesGrouped) {
                if (cName != "الكل") seriesCats.add(BouquetSummary(cName, list.size))
            }

            CatalogViewSnapshot(
                allChannels = sortedLive,
                channelsByBouquet = liveGrouped,
                rawBouquetSummaries = rawSummaries,
                bouquetSummaries = sortedSummaries,
                allMovies = combinedMovies,
                moviesByCategory = moviesGrouped,
                movieCategories = movieCats,
                allSeries = combinedSeries,
                seriesByCategory = seriesGrouped,
                seriesCategories = seriesCats
            )
        }
        if (initialized) {
            isSortingLocalLibrary = false
        }
    }

    val allChannels = catalogSnapshot.allChannels
    val rawBouquetSummaries = catalogSnapshot.rawBouquetSummaries
    // Item 9: O(1) update of Favorites count badge without rebuilding the 50,000-channel catalog!
    val bouquetSummaries = remember(catalogSnapshot.bouquetSummaries, favorites.size, favBouquetTitle) {
        catalogSnapshot.bouquetSummaries.map { bq ->
            if (bq.isFavoritesSpecial || bq.name == favBouquetTitle) bq.copy(count = favorites.size) else bq
        }
    }
    val allMovies = catalogSnapshot.allMovies
    val allSeries = catalogSnapshot.allSeries

    suspend fun persistCurrentStateSuspend(saveCatalog: Boolean = false) {
        if (!initialized) return
        val pCh = portalChannels
        val pMv = portalMovies
        val pSr = portalSeries
        val xChMap = xtreamChannelsMap
        val xMvMap = xtreamMoviesMap
        val xSrMap = xtreamSeriesMap
        val curDeviceId = deviceId
        val curDeviceName = deviceName
        val curActCode = savedActivationCode
        val curPlaylists = xtreamPlaylists
        val curFavs = favorites
        val curHist = history
        val curSub = subscription
        val curPrefs = preferences

        withContext(Dispatchers.IO) {
            val snapshot = PersistedAppState(
                deviceId = curDeviceId,
                deviceName = curDeviceName,
                activationCode = curActCode,
                xtreamPlaylists = curPlaylists,
                cachedChannels = if (saveCatalog) pCh + xChMap.values.flatten() else emptyList(),
                cachedMovies = if (saveCatalog) pMv + xMvMap.values.flatten() else emptyList(),
                cachedSeries = if (saveCatalog) pSr + xSrMap.values.flatten() else emptyList(),
                favorites = curFavs,
                history = curHist,
                subscription = curSub,
                preferences = curPrefs
            )
            StorageManager.saveState(context, snapshot, saveCatalog = saveCatalog)
        }
    }

    fun persistCurrentState(saveCatalog: Boolean = false) {
        if (!initialized) return
        if (saveCatalog) {
            scope.launch {
                persistCurrentStateSuspend(saveCatalog = true)
            }
        } else {
            saveStateJob?.cancel()
            saveStateJob = scope.launch {
                delay(280L)
                persistCurrentStateSuspend(saveCatalog = false)
            }
        }
    }

    suspend fun syncSingleXtreamPlaylistSuspend(configToSync: XtreamAccountConfig, showToast: Boolean = false) {
        if (!configToSync.isConfigured) return
        if (TiviMateLocalDatabase.isSourceSyncInProgress(configToSync.id)) {
            if (showToast) {
                Toast.makeText(context, "⏳ جارٍ تحديث هذه القائمة بالفعل، يرجى الانتظار...", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val currentSub = subscription
        if (currentSub != null && (currentSub.isExpired || !currentSub.active)) {
            Toast.makeText(
                context,
                "⛔ انتهت صلاحية المشغل ($deviceId). يرجى تجديد الاشتراك أولاً عبر علي سات: $ALI_SAT_PHONE_NUMBER",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        isSyncingXtream = true
        val sName = configToSync.serverName.ifBlank { "XTREAM" }
        syncProgress = SyncProgressState(
            isSyncing = true,
            sourceId = configToSync.id,
            sourceName = sName,
            phaseLabel = "🚀 جارٍ سحب وتحديث قائمة ($sName) بسرعة الصاروخ...",
            progressPercent = 10
        )
        try {
            val xResult = XtreamRepository.syncXtreamAccount(configToSync) { prog ->
                syncProgress = prog
            }

            // 1. Update in-memory UI maps IMMEDIATELY so channels/movies/series appear on screen in 0ms!
            val immediateAccount = xResult.account.copy(
                statusNote = "متصل ✓ (${xResult.liveChannels.size} قناة • ${xResult.movies.size} فيلم • ${xResult.series.size} مسلسل)"
            )
            xtreamPlaylists = xtreamPlaylists.map {
                if (it.id == configToSync.id) immediateAccount else it
            }.let { list ->
                if (list.none { it.id == configToSync.id }) list + immediateAccount else list
            }
            xtreamChannelsMap = xtreamChannelsMap + (xResult.account.id to xResult.liveChannels)
            xtreamMoviesMap = xtreamMoviesMap + (xResult.account.id to xResult.movies)
            xtreamSeriesMap = xtreamSeriesMap + (xResult.account.id to xResult.series)

            // 2. Push to Customer Portal in background ONLY if added/edited locally or expiry changed (reduces API load!)
            if (!configToSync.isRemoteFromPortal || xResult.account.expDate != configToSync.expDate) {
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        ChannelRepository.pushCustomerXtreamToPortal(deviceId, deviceName, xResult.account)
                    }
                }
            }

            // 3. Commit to local SQLite database + JSON backup synchronously so data is NEVER lost on app close!
            val diffStats = sqliteDb.syncSourceCatalogAtomically(
                sourceId = xResult.account.id,
                sourceName = xResult.account.serverName,
                liveChannels = xResult.liveChannels,
                movies = xResult.movies,
                series = xResult.series,
                catalogEtag = xResult.catalogHash
            )
            val diffNote = "متصل ✓ (${xResult.liveChannels.size} قناة • ${xResult.movies.size} فيلم • +${diffStats.addedCount}/~${diffStats.updatedCount})"
            val finalAccount = xResult.account.copy(statusNote = diffNote)
            xtreamPlaylists = xtreamPlaylists.map {
                if (it.id == configToSync.id) finalAccount else it
            }

            syncProgress = syncProgress.copy(
                isSyncing = false,
                progressPercent = 100,
                addedCount = diffStats.addedCount,
                updatedCount = diffStats.updatedCount,
                removedCount = diffStats.removedCount,
                isDifferential = true
            )
            persistCurrentStateSuspend(saveCatalog = true)
            if (xResult.account.expDate.isNotBlank() && ExpiryAlertHelper.isDateAlreadyExpired(xResult.account.expDate)) {
                diagnosticAlert = DiagnosticAlertInfo(
                    type = DiagnosticAlertType.ACCOUNT_EXPIRED,
                    title = "⚠️ انتهت صلاحية حساب القنوات (${xResult.account.serverName})",
                    message = "انتهت فترة اشتراك القائمة (${xResult.account.serverName}) بتاريخ ${xResult.account.expDate}.\nيرجى تجديد اشتراك الحساب عبر التواصل مع مزود الخدمة ($ALI_SAT_PROVIDER_NAME : $ALI_SAT_PHONE_NUMBER) وتقديم رمز الشاشة ($deviceId)، أو تحديث بيانات القائمة من فضاء الزبون.",
                    sourceName = xResult.account.serverName,
                    expDate = xResult.account.expDate
                )
            } else if (showToast) {
                Toast.makeText(
                    context,
                    "✅ تم تحديث قائمة (${xResult.account.serverName}) بنجاح: ${xResult.liveChannels.size} قناة • ${xResult.movies.size} فيلم • ${xResult.series.size} مسلسل",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            val msg = e.message ?: "تعذّر الاتصال بسيرفر XTREAM — تم الاحتفاظ بالمكتبة المحلية"
            TiviMateDiagnostics.logEvent("❌ فشل تحديث [$sName]: $msg (المكتبة القديمة محفوظة)")
            xtreamPlaylists = xtreamPlaylists.map {
                if (it.id == configToSync.id) it.copy(statusNote = "⚠️ محفوظ محلياً ($msg)") else it
            }
            syncProgress = syncProgress.copy(isSyncing = false, lastError = msg)
            val isExpiredAccountErr = msg.contains("منتهي") || msg.contains("غير نشط") ||
                msg.contains("Expired", ignoreCase = true) || msg.contains("Banned", ignoreCase = true) ||
                ExpiryAlertHelper.isDateAlreadyExpired(configToSync.expDate)
            diagnosticAlert = when {
                isExpiredAccountErr -> DiagnosticAlertInfo(
                    type = DiagnosticAlertType.ACCOUNT_EXPIRED,
                    title = "⚠️ انتهت صلاحية حساب القنوات ($sName)",
                    message = "انتهت صلاحية اشتراك الحساب (" + sName + "). يرجى تجديد الاشتراك بالتواصل مع علي سات (" + ALI_SAT_PHONE_NUMBER + ") وتقديم رمز الشاشة (" + deviceId + ")، أو تحديث القائمة من فضاء الزبون.",
                    sourceName = sName,
                    expDate = configToSync.expDate
                )
                !ExpiryAlertHelper.isInternetAvailable(context) -> DiagnosticAlertInfo(
                    type = DiagnosticAlertType.INTERNET_ERROR,
                    title = "📡 تنبيه: مشكلة في اتصال الإنترنت لديك",
                    message = "لا يوجد اتصال مستقر بالإنترنت على جهازك حالياً أثناء محاولة الاتصال بـ ($sName).\nيرجى التحقق من اتصال الواي فاي (Wi-Fi) أو كابل الشبكة والراوتر ثم المحاولة مجدداً. (تم الاحتفاظ بقنواتك المخزنة محلياً).",
                    sourceName = sName
                )
                else -> DiagnosticAlertInfo(
                    type = DiagnosticAlertType.SERVER_OUTAGE,
                    title = "🛠️ تنبيه حالة الخادم: انقطاع مؤقت من المصدر",
                    message = "يتعذّر استجابة خادم القنوات ($sName) حالياً.\n\nيرجى التريث لمدة ساعة، ثم غلق جهاز الراوتر (المودم) وإعادة فتحه مجدداً.\nإذا لم يعد السيرفر للعمل تلقائياً، يرجى التواصل مع مزود الخدمة ($ALI_SAT_PROVIDER_NAME : $ALI_SAT_PHONE_NUMBER) وتقديم رمز الشاشة ($deviceId).",
                    sourceName = sName
                )
            }
        } finally {
            isSyncingXtream = false
        }
    }

    fun syncSingleXtreamPlaylist(configToSync: XtreamAccountConfig, showToast: Boolean = false) {
        scope.launch {
            syncSingleXtreamPlaylistSuspend(configToSync, showToast = showToast)
        }
    }

    fun syncAllEnabledXtreamPlaylists(forceRefresh: Boolean = false) {
        val currentSub = subscription
        if (currentSub != null && (currentSub.isExpired || !currentSub.active)) return
        scope.launch {
            for (cfg in orderedPlaylists.filter { it.enabled && it.isConfigured }) {
                if (forceRefresh || xtreamChannelsMap[cfg.id].isNullOrEmpty()) {
                    syncSingleXtreamPlaylistSuspend(cfg, showToast = false)
                }
            }
        }
    }

    fun stopVpnImmediately() {
        CloudflareWarpVpnService.stopTunnelSafe(context)
        isConnectingVpn = false
        vpnStatus = CloudflareTunnelStatus(
            connected = false,
            statusText = "تم إيقاف الـ VPN لانتهاء صلاحية اشتراك المشغل"
        )
    }

    fun startRealWireGuardServiceAndVerify() {
        val currentSub = subscription
        if (currentSub == null || !currentSub.active || currentSub.isExpired) {
            stopVpnImmediately()
            return
        }
        if (isConnectingVpn) return
        scope.launch {
            isConnectingVpn = true
            try {
                val intent = Intent(context, CloudflareWarpVpnService::class.java).apply {
                    action = CloudflareWarpVpnService.ACTION_CONNECT
                }
                runCatching { context.startService(intent) }.onFailure {
                    // Android 14 background start restriction fallback: activate encrypted DoH tunnel directly
                    CloudflareWarpVpnService.dohShieldFallbackActive = true
                    CloudflareWarpVpnService.isTunnelRunning = true
                }

                repeat(10) {
                    delay(240L)
                    if (CloudflareDohManager.isInternalAppVpnExclusivelyActive(context)) {
                        return@repeat
                    }
                }

                val status = CloudflareDohManager.verifyAndActivateCloudflareTunnel(context)
                vpnStatus = status
            } finally {
                isConnectingVpn = false
            }
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isVpnPermissionDialogShowing = false
        val currentSub = subscription
        if (currentSub == null || !currentSub.active || currentSub.isExpired) {
            stopVpnImmediately()
            return@rememberLauncherForActivityResult
        }
        if (result.resultCode == Activity.RESULT_OK) {
            startRealWireGuardServiceAndVerify()
        } else {
            isConnectingVpn = false
            vpnStatus = CloudflareTunnelStatus(
                connected = false,
                statusText = "يرجى الضغط على زر (تفعيل الـ VPN) والموافقة على الإذن للدخول"
            )
        }
    }

    fun connectVpnAutomatically(manualTrigger: Boolean = false, forcePrompt: Boolean = manualTrigger) {
        val isManual = manualTrigger || forcePrompt
        val currentSub = subscription
        if (currentSub == null || !currentSub.active || currentSub.isExpired) {
            stopVpnImmediately()
            return
        }
        if (isVpnPermissionDialogShowing || isConnectingVpn) return
        val prepareIntent = try { VpnService.prepare(context) } catch (_: Throwable) { null }
        if (prepareIntent != null) {
            // Prevent infinite re-launch loop that freezes the first-time Android system VPN confirmation dialog!
            if (!isManual && vpnPermissionAskedInSession) {
                return
            }
            isVpnPermissionDialogShowing = true
            vpnPermissionAskedInSession = true
            try {
                vpnPermissionLauncher.launch(prepareIntent)
            } catch (_: Throwable) {
                isVpnPermissionDialogShowing = false
                CloudflareWarpVpnService.dohShieldFallbackActive = true
                CloudflareWarpVpnService.isTunnelRunning = true
                startRealWireGuardServiceAndVerify()
            }
        } else {
            startRealWireGuardServiceAndVerify()
        }
    }

    fun syncServerNow(codeOverride: String = savedActivationCode, forceXtreamRefresh: Boolean = false) {
        if (TiviMateLocalDatabase.isSourceSyncInProgress("PORTAL_MAIN")) return
        scope.launch {
            isLoading = true
            syncProgress = SyncProgressState(
                isSyncing = true,
                sourceId = "PORTAL_MAIN",
                sourceName = "الخادم الرئيسي",
                phaseLabel = "جارٍ التحقق التفاضلي ومزامنة المكتبة...",
                progressPercent = 20
            )
            statusMessage = AppStrings.tr(
                lang,
                "جارٍ التحقق من صلاحية المشغل والمزامنة التفاضلية عبر رمز الشاشة...",
                "Vérification et synchronisation différentielle via Code Écran...",
                "Checking validity and differential sync via Screen Code..."
            )
            try {
                // PRIORITY STEP 1: Fast-restore Screen Code subscription & linked XTREAM lists FIRST (in <1s)
                // so restored XTREAM playlists are downloaded and stored BEFORE the main portal list!
                runCatching {
                    val (quickSub, quickRemoteLists) = ChannelRepository.checkSubscriptionAndListsQuick(deviceId, deviceName)
                    subscription = quickSub
                    if (quickSub.active && !quickSub.isExpired) {
                        if (!vpnStatus.connected) {
                            connectVpnAutomatically()
                        }
                        if (quickRemoteLists.isNotEmpty()) {
                            val mergedFast = xtreamPlaylists.toMutableList()
                            for (rCfg in quickRemoteLists) {
                                val existingIdx = mergedFast.indexOfFirst {
                                    it.id == rCfg.id ||
                                        (it.serverUrl.equals(rCfg.serverUrl, ignoreCase = true) &&
                                            it.username.equals(rCfg.username, ignoreCase = true))
                                }
                                if (existingIdx >= 0) {
                                    mergedFast[existingIdx] = rCfg.copy(id = mergedFast[existingIdx].id)
                                } else {
                                    mergedFast.add(rCfg)
                                }
                            }
                            xtreamPlaylists = mergedFast
                        }
                        // Sync restored XTREAM playlists FIRST (each one individually at rocket speed into SQLite)!
                        val listsToSyncFirst = xtreamPlaylists.filter { it.enabled && it.isConfigured }
                        for (cfg in listsToSyncFirst) {
                            if (forceXtreamRefresh || xtreamChannelsMap[cfg.id].isNullOrEmpty()) {
                                syncSingleXtreamPlaylistSuspend(cfg, showToast = false)
                            }
                        }
                    }
                }

                // PRIORITY STEP 2: Now sync the Main Portal list ("PORTAL_MAIN") after XTREAM lists!
                val (_, lastEtag) = sqliteDb.getSourceLastSyncInfo("PORTAL_MAIN")
                val result = ChannelRepository.syncWithServer(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    activationCode = codeOverride,
                    knownCatalogHash = if (portalChannels.isNotEmpty() && !forceXtreamRefresh) lastEtag else ""
                )
                subscription = result.subscription

                if (result.subscription.active && !result.subscription.isExpired) {
                    if (result.remoteXtreamLists.isNotEmpty()) {
                        val mergedLists = xtreamPlaylists.toMutableList()
                        var newlyDiscovered = false
                        for (remoteCfg in result.remoteXtreamLists) {
                            val existingIdx = mergedLists.indexOfFirst {
                                it.id == remoteCfg.id ||
                                    (it.serverUrl.equals(remoteCfg.serverUrl, ignoreCase = true) &&
                                        it.username.equals(remoteCfg.username, ignoreCase = true))
                            }
                            if (existingIdx >= 0) {
                                mergedLists[existingIdx] = remoteCfg.copy(id = mergedLists[existingIdx].id)
                            } else {
                                mergedLists.add(remoteCfg)
                                newlyDiscovered = true
                            }
                        }
                        xtreamPlaylists = mergedLists
                        // If any additional Xtream list came from api.php, sync it BEFORE finalizing Portal Main
                        if (newlyDiscovered) {
                            for (cfg in xtreamPlaylists.filter { it.enabled && it.isConfigured }) {
                                if (xtreamChannelsMap[cfg.id].isNullOrEmpty()) {
                                    syncSingleXtreamPlaylistSuspend(cfg, showToast = false)
                                }
                            }
                        }
                    }

                    if (!result.isDifferentialUnchanged && (result.channels.isNotEmpty() || result.movies.isNotEmpty() || result.series.isNotEmpty())) {
                        portalChannels = result.channels
                        portalMovies = result.movies
                        portalSeries = result.series
                        val diffStats = sqliteDb.syncSourceCatalogAtomically(
                            sourceId = "PORTAL_MAIN",
                            sourceName = "قائمة الخادم الرئيسية",
                            liveChannels = result.channels,
                            movies = result.movies,
                            series = result.series,
                            catalogEtag = result.serverCatalogHash
                        )
                        syncProgress = syncProgress.copy(
                            progressPercent = 95,
                            fetchedLiveCount = result.channels.size,
                            fetchedMovieCount = result.movies.size,
                            fetchedSeriesCount = result.series.size,
                            addedCount = diffStats.addedCount,
                            updatedCount = diffStats.updatedCount,
                            removedCount = diffStats.removedCount,
                            isDifferential = true
                        )
                    } else {
                        TiviMateDiagnostics.logEvent("⚡ فحص تفاضلي سريع: مكتبة الخادم الرئيسي مطابقة للمحلية (لم يتغير شيء)")
                    }

                    val expText = if (result.subscription.expireDate.isNotBlank()) {
                        " • ينتهي: ${result.subscription.expireDate}"
                    } else ""
                    statusMessage = "متصل • الصلاحية المتبقية: ${result.subscription.daysLeft} يوم$expText"

                    if (!vpnStatus.connected) {
                        connectVpnAutomatically()
                    }
                    persistCurrentStateSuspend(saveCatalog = true)
                } else {
                    // Subscription expired or inactive -> IMMEDIATELY stop VPN, block entry & show Ali Sat expiry gate!
                    // Keep local SQLite catalog intact on disk so when the user renews, channels do not need to be re-downloaded!
                    stopVpnImmediately()
                    playingChannel = null
                    val subMsg = result.subscription.message
                    statusMessage = if (subMsg.isNotBlank()) subMsg else "انتهت صلاحية المشغل. تواصل مع علي سات : $ALI_SAT_PHONE_NUMBER"
                    persistCurrentStateSuspend(saveCatalog = false)
                }
            } catch (e: Exception) {
                // Preserve existing local SQLite library when network fails!
                val errTxt = e.message ?: "تعذّر الاتصال بالخادم"
                statusMessage = "$errTxt • يتم عرض المكتبة المخزنة محلياً"
                TiviMateDiagnostics.logEvent("⚠️ انقطاع الشبكة أثناء المزامنة: تم الاحتفاظ بالمكتبة المحلية دون فقدان أي قناة.")
                if (portalChannels.isNotEmpty() || xtreamChannelsMap.isNotEmpty()) {
                    browseOfflineAllowed = true
                }
                if (forceXtreamRefresh) {
                    diagnosticAlert = if (!ExpiryAlertHelper.isInternetAvailable(context)) {
                        DiagnosticAlertInfo(
                            type = DiagnosticAlertType.INTERNET_ERROR,
                            title = "📡 تنبيه: مشكلة في اتصال الإنترنت لديك",
                            message = "تعذّر تحديث القوائم بسبب انقطاع الإنترنت على جهازك.\nيرجى التحقق من اتصال الواي فاي (Wi-Fi) أو كابل الشبكة والراوتر ثم المحاولة مجدداً. (تم الاحتفاظ بجميع القنوات المخزنة محلياً)."
                        )
                    } else {
                        DiagnosticAlertInfo(
                            type = DiagnosticAlertType.SERVER_OUTAGE,
                            title = "🛠️ تنبيه حالة الخادم: انقطاع مؤقت من المصدر",
                            message = "يتعذّر استجابة الخادم حالياً أثناء تحديث المحتوى.\n\nيرجى التريث لمدة ساعة، ثم غلق جهاز الراوتر (المودم) وإعادة فتحه مجدداً.\nإذا لم يعد السيرفر للعمل تلقائياً، يرجى التواصل مع مزود الخدمة ($ALI_SAT_PROVIDER_NAME : $ALI_SAT_PHONE_NUMBER) وتقديم رمز الشاشة ($deviceId)."
                        )
                    }
                }
                if (subscription?.isExpired != true && !vpnStatus.connected) {
                    connectVpnAutomatically()
                }
            } finally {
                isLoading = false
                syncProgress = syncProgress.copy(isSyncing = false)
            }
        }
    }

    /**
     * Updates ONLY the subscription expiry dates for the Player (api.php) and Xtream accounts (< 1 KB)
     * WITHOUT downloading or re-syncing any channels, movies, or series!
     */
    fun refreshValidityDatesOnly(showToast: Boolean = true) {
        if (!SafeClickGuard.allowAction("REFRESH_VALIDITY_ONLY", 500L)) return
        if (isCheckingValidityOnly) return
        scope.launch {
            isCheckingValidityOnly = true
            statusMessage = "⏳ جارٍ تحديث تواريخ الصلاحية فقط (دون سحب القنوات)..."
            try {
                val (quickSub, remoteLists) = ChannelRepository.checkSubscriptionAndListsQuick(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    forceNetwork = true
                )
                subscription = quickSub
                if (quickSub.isExpired && !quickSub.active) {
                    stopVpnImmediately()
                    playingChannel = null
                    statusMessage = quickSub.message.ifBlank { "انتهت صلاحية المشغل. تواصل مع علي سات : $ALI_SAT_PHONE_NUMBER" }
                    if (showToast) {
                        Toast.makeText(context, "⛔ انتهت صلاحية اشتراك المشغل (${deviceId})", Toast.LENGTH_LONG).show()
                    }
                } else {
                    if (!vpnStatus.connected) {
                        connectVpnAutomatically()
                    }
                    val merged = xtreamPlaylists.toMutableList()
                    for (rCfg in remoteLists) {
                        val idx = merged.indexOfFirst {
                            it.id == rCfg.id ||
                                (it.serverUrl.equals(rCfg.serverUrl, ignoreCase = true) &&
                                    it.username.equals(rCfg.username, ignoreCase = true))
                        }
                        if (idx >= 0) {
                            merged[idx] = merged[idx].copy(
                                expDate = rCfg.expDate.ifBlank { merged[idx].expDate }
                            )
                        } else {
                            merged.add(rCfg)
                        }
                    }
                    // Probe live Xtream expiry dates in parallel (< 1 KB each, zero channel download!)
                    val updatedWithProbes = withContext(Dispatchers.IO) {
                        merged.map { pl ->
                            if (pl.enabled && pl.isConfigured) {
                                val (liveExp, _) = XtreamRepository.probeAccountExpiryQuick(pl, forceNetwork = true)
                                if (liveExp.isNotBlank()) pl.copy(expDate = liveExp) else pl
                            } else pl
                        }
                    }
                    xtreamPlaylists = updatedWithProbes
                    val expTxt = if (quickSub.expireDate.isNotBlank()) " • ينتهي: ${quickSub.expireDate}" else ""
                    statusMessage = "✅ تم تحديث الصلاحية • المتبقي: ${quickSub.daysLeft} يوم$expTxt"
                    persistCurrentStateSuspend(saveCatalog = false)
                    if (showToast) {
                        Toast.makeText(
                            context,
                            "✅ تم تحديث تاريخ الصلاحية فقط بنجاح: ${quickSub.daysLeft} يوم متبقٍ$expTxt",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                if (showToast) {
                    Toast.makeText(
                        context,
                        "⚠️ تعذّر فحص الصلاحية حالياً — تم الاحتفاظ بالبيانات المحلية",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                isCheckingValidityOnly = false
            }
        }
    }

    /**
     * Syncs ONLY the Main Portal list ("PORTAL_MAIN") when the customer is in Separate Playlist Mode
     * and has selected "PORTAL_MAIN".
     */
    fun syncPortalMainOnlyNow(showToast: Boolean = true) {
        if (TiviMateLocalDatabase.isSourceSyncInProgress("PORTAL_MAIN")) return
        scope.launch {
            isLoading = true
            syncProgress = SyncProgressState(
                isSyncing = true,
                sourceId = "PORTAL_MAIN",
                sourceName = "القائمة الرئيسية",
                phaseLabel = "🚀 جارٍ تحديث القائمة الرئيسية فقط...",
                progressPercent = 25
            )
            try {
                val result = ChannelRepository.syncWithServer(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    activationCode = savedActivationCode,
                    knownCatalogHash = ""
                )
                subscription = result.subscription
                if (result.subscription.active && !result.subscription.isExpired) {
                    if (result.channels.isNotEmpty() || result.movies.isNotEmpty() || result.series.isNotEmpty()) {
                        portalChannels = result.channels
                        portalMovies = result.movies
                        portalSeries = result.series
                        sqliteDb.syncSourceCatalogAtomically(
                            sourceId = "PORTAL_MAIN",
                            sourceName = "قائمة الخادم الرئيسية",
                            liveChannels = result.channels,
                            movies = result.movies,
                            series = result.series,
                            catalogEtag = result.serverCatalogHash
                        )
                    }
                    persistCurrentStateSuspend(saveCatalog = true)
                    if (showToast) {
                        Toast.makeText(
                            context,
                            "✅ تم تحديث القائمة الرئيسية فقط بنجاح (${result.channels.size} قناة)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                if (showToast) {
                    Toast.makeText(context, "⚠️ تعذّر تحديث القائمة الرئيسية — تم الاحتفاظ بالمكتبة المحلية", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isLoading = false
                syncProgress = syncProgress.copy(isSyncing = false)
            }
        }
    }

    /**
     * Syncs a user-selected subset of Xtream playlists (1 playlist, multiple playlists, or all playlists).
     */
    fun syncSelectedXtreamPlaylists(
        selectedIds: Set<String>,
        includePortalMain: Boolean = false
    ) {
        scope.launch {
            val targets = orderedPlaylists.filter { it.id in selectedIds && it.isConfigured }
            if (targets.size == 1 && !includePortalMain) {
                val onlyPl = targets.first()
                preferences = preferences.copy(
                    playlistDisplayMode = PlaylistDisplayMode.SEPARATE_LISTS,
                    activePlaylistFilterId = onlyPl.id
                )
                syncSingleXtreamPlaylistSuspend(onlyPl, showToast = true)
                return@launch
            }
            for ((idx, cfg) in targets.withIndex()) {
                val isLast = (idx == targets.lastIndex) && !includePortalMain
                syncSingleXtreamPlaylistSuspend(cfg, showToast = isLast)
            }
            if (includePortalMain) {
                syncPortalMainOnlyNow(showToast = true)
            } else if (targets.size > 1) {
                Toast.makeText(
                    context,
                    "✅ تم تحديث القوائم المختارة (${targets.size} قوائم) وحفظها في ذاكرة التطبيق بنجاح!",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Context-Aware Smart Content Refresh ("تحديث المحتوى"):
     * - If in SEPARATE_LISTS mode and a specific Xtream playlist is active -> refreshes ONLY that single Xtream playlist!
     * - If in SEPARATE_LISTS mode and PORTAL_MAIN is active -> refreshes ONLY the Main Portal playlist!
     * - If in MERGE_ALL mode (or "ALL" selected) -> refreshes all playlists!
     */
    fun triggerSmartContentRefresh() {
        if (!SafeClickGuard.allowAction("SMART_REFRESH_BTN", 450L)) return
        val activeFilter = preferences.activePlaylistFilterId
        if (preferences.playlistDisplayMode == PlaylistDisplayMode.SEPARATE_LISTS && activeFilter != "ALL") {
            if (activeFilter == "PORTAL_MAIN") {
                syncPortalMainOnlyNow(showToast = true)
                return
            }
            val targetXtream = orderedPlaylists.find { it.id == activeFilter }
            if (targetXtream != null) {
                syncSingleXtreamPlaylist(targetXtream, showToast = true)
                return
            }
        }
        // Merged mode ("MERGE_ALL" or "ALL"): refresh all playlists
        syncServerNow(forceXtreamRefresh = true)
    }

    fun redeemDirectCode(inputCode: String) {
        if (!SafeClickGuard.allowAction("REDEEM_VOUCHER", 600L)) return
        val trimmed = inputCode.trim().uppercase()
        if (trimmed.length < 4) {
            voucherAlert = VoucherAlertInfo(
                type = VoucherAlertType.INVALID_OR_NOT_FOUND,
                title = "⚠️ يرجى إدخال رمز التفعيل أولاً",
                message = "الرجاء كتابة رمز القسيمة المكون من 8 أحرف وأرقام في الخانة المخصصة (مثال: A1B2-C3D4) ثم الضغط على زر التفعيل.",
                enteredCode = trimmed
            )
            return
        }
        scope.launch {
            isLoading = true
            syncProgress = SyncProgressState(
                isSyncing = true,
                sourceId = "VOUCHER",
                sourceName = "تفعيل الاشتراك",
                phaseLabel = "⏳ جارٍ التحقق من رمز التفعيل ($trimmed) وتنشيط الاشتراك...",
                progressPercent = 50
            )
            statusMessage = "جارٍ التحقق من رمز التنشيط..."
            try {
                val result = ChannelRepository.redeemActivationCode(deviceId, deviceName, trimmed)
                val rawSubMsg = result.subscription.message
                val cleanSubMsg = rawSubMsg.substringBefore("|")
                val addedDays = rawSubMsg.substringAfter("ADDED:", "0").substringBefore("|").toIntOrNull() ?: 0
                val newExp = rawSubMsg.substringAfter("EXP:", "").substringBefore("|").ifBlank { result.subscription.expireDate }
                val cleanFormattedCode = rawSubMsg.substringAfter("CODE:", trimmed).substringBefore("|")

                subscription = result.subscription.copy(message = cleanSubMsg)
                if (result.subscription.active && !result.subscription.isExpired) {
                    savedActivationCode = cleanFormattedCode
                    if (result.channels.isNotEmpty()) portalChannels = result.channels
                    if (result.movies.isNotEmpty()) portalMovies = result.movies
                    if (result.series.isNotEmpty()) portalSeries = result.series
                    showUnifiedValidityModal = false
                    voucherAlert = VoucherAlertInfo(
                        type = VoucherAlertType.SUCCESS,
                        title = "🎉 تم تفعيل وتجديد الاشتراك بنجاح!",
                        message = "$cleanSubMsg\nتم تفعيل شاشتك ($deviceId) بنجاح ويمكنك الآن الاستمتاع بجميع القنوات والأفلام والمسلسلات.",
                        enteredCode = cleanFormattedCode,
                        addedDays = addedDays,
                        newExpireDate = newExp
                    )
                    if (!vpnStatus.connected) {
                        connectVpnAutomatically()
                    }
                    persistCurrentStateSuspend(saveCatalog = result.channels.isNotEmpty())
                } else {
                    stopVpnImmediately()
                    voucherAlert = VoucherAlertInfo(
                        type = VoucherAlertType.INVALID_OR_NOT_FOUND,
                        title = "❌ تعذّر تفعيل رمز القسيمة",
                        message = if (cleanSubMsg.isNotBlank()) cleanSubMsg else "رمز التنشيط غير صالح أو منتهي الصلاحية.",
                        enteredCode = cleanFormattedCode
                    )
                }
            } catch (e: Exception) {
                val rawErr = e.message ?: "CARD_NOT_FOUND|تعذّر التحقق من رمز التنشيط"
                val tag = if (rawErr.contains("|")) rawErr.substringBefore("|") else "CARD_NOT_FOUND"
                val errBody = if (rawErr.contains("|")) rawErr.substringAfter("|") else rawErr
                statusMessage = errBody
                val alertType = when (tag) {
                    "CARD_USED" -> VoucherAlertType.USED_CARD
                    "CARD_EXPIRED" -> VoucherAlertType.EXPIRED_CARD
                    "RATE_LIMITED" -> VoucherAlertType.RATE_LIMITED
                    "NETWORK_ERROR" -> VoucherAlertType.NETWORK_ERROR
                    else -> VoucherAlertType.INVALID_OR_NOT_FOUND
                }
                val alertTitle = when (alertType) {
                    VoucherAlertType.USED_CARD -> "⛔ هذه القسيمة مستخدمة ومحروقة مسبقاً!"
                    VoucherAlertType.EXPIRED_CARD -> "⛔ انتهت صلاحية بطاقة التفعيل"
                    VoucherAlertType.RATE_LIMITED -> "⏳ تنبيه الحماية: محاولات متكررة"
                    VoucherAlertType.NETWORK_ERROR -> "📡 تعذّر الاتصال بخادم التفعيل"
                    else -> "❌ رمز التفعيل غير صحيح أو غير موجود"
                }
                voucherAlert = VoucherAlertInfo(
                    type = alertType,
                    title = alertTitle,
                    message = errBody,
                    enteredCode = trimmed
                )
            } finally {
                isLoading = false
                syncProgress = syncProgress.copy(isSyncing = false, phaseLabel = "")
            }
        }
    }

    LaunchedEffect(Unit) {
        isSortingLocalLibrary = true
        val startLoadMs = android.os.SystemClock.elapsedRealtime()
        val loaded = StorageManager.loadState(context)
        deviceId = loaded.deviceId
        deviceName = loaded.deviceName
        savedActivationCode = loaded.activationCode
        xtreamPlaylists = loaded.xtreamPlaylists
        favorites = loaded.favorites
        history = loaded.history
        subscription = loaded.subscription ?: SubscriptionInfo(active = true, isExpired = false, daysLeft = 30, reason = "ok")
        preferences = loaded.preferences
        vodWatchProgressMap = loaded.vodWatchProgress

        // StorageManager.loadState already loaded SQLite catalog + JSON fallback in one fast pass!
        var hasCachedCatalog = false
        withContext(Dispatchers.Default) {
            val effLive = loaded.cachedChannels
            val effMovies = loaded.cachedMovies
            val effSeries = loaded.cachedSeries

            hasCachedCatalog = effLive.isNotEmpty() || effMovies.isNotEmpty() || effSeries.isNotEmpty()

            if (effLive.isNotEmpty()) {
                val pCh = effLive.filter { it.playlistId == "PORTAL_MAIN" }
                val gX = effLive.filter { it.playlistId != "PORTAL_MAIN" }.groupBy { it.playlistId }
                withContext(Dispatchers.Main) {
                    portalChannels = pCh
                    xtreamChannelsMap = gX
                }
            }
            if (effMovies.isNotEmpty()) {
                val pMv = effMovies.filter { it.playlistId == "PORTAL_MAIN" }
                val gMv = effMovies.filter { it.playlistId != "PORTAL_MAIN" }.groupBy { it.playlistId }
                withContext(Dispatchers.Main) {
                    portalMovies = pMv
                    xtreamMoviesMap = gMv
                }
            }
            if (effSeries.isNotEmpty()) {
                val pSr = effSeries.filter { it.playlistId == "PORTAL_MAIN" }
                val gSr = effSeries.filter { it.playlistId != "PORTAL_MAIN" }.groupBy { it.playlistId }
                withContext(Dispatchers.Main) {
                    portalSeries = pSr
                    xtreamSeriesMap = gSr
                }
            }
        }
        initialized = true
        val elapsedLoad = android.os.SystemClock.elapsedRealtime() - startLoadMs
        if (hasCachedCatalog && elapsedLoad < 700L) {
            delay(700L - elapsedLoad)
        }
        isSortingLocalLibrary = false

        // Check if any saved Xtream account has an expired date to notify customer gracefully
        val expiredSavedPlaylist = loaded.xtreamPlaylists.firstOrNull {
            it.enabled && it.expDate.isNotBlank() && ExpiryAlertHelper.isDateAlreadyExpired(it.expDate)
        }
        if (expiredSavedPlaylist != null) {
            diagnosticAlert = DiagnosticAlertInfo(
                type = DiagnosticAlertType.ACCOUNT_EXPIRED,
                title = "⚠️ انتهت صلاحية حساب القنوات (${expiredSavedPlaylist.serverName})",
                message = "انتهت فترة اشتراك القائمة (${expiredSavedPlaylist.serverName}) بتاريخ ${expiredSavedPlaylist.expDate}.\nيرجى تجديد اشتراك الحساب عبر التواصل مع مزود الخدمة ($ALI_SAT_PROVIDER_NAME : $ALI_SAT_PHONE_NUMBER) وتقديم رمز الشاشة ($deviceId)، أو تحديث بيانات القائمة من فضاء الزبون.",
                sourceName = expiredSavedPlaylist.serverName,
                expDate = expiredSavedPlaylist.expDate
            )
        }

        if (hasCachedCatalog) {
            // SUBSEQUENT LAUNCH: Open INSTANTLY from local SQLite/storage WITHOUT re-downloading channels, movies, or series!
            // Full content refresh happens ONLY when the customer clicks the "تحديث المحتوى" (Refresh) button or changes domain!
            statusMessage = "جاهز للتشغيل الفوري من ذاكرة التطبيق • للتحديث اضغط (تحديث المحتوى)"
            if (subscription?.isExpired != true && !vpnStatus.connected) {
                connectVpnAutomatically()
            }
        } else {
            // FIRST-TIME LAUNCH (Local library is empty):
            // 1. Connect VPN automatically right away with loading spinner (zero clicks needed!)
            if (subscription?.isExpired != true && !vpnStatus.connected) {
                connectVpnAutomatically()
            }
            // 2. Wait until first-time VPN permission dialog finishes so it NEVER freezes the device, then restore playlists!
            scope.launch {
                var waitTicks = 0
                while (isVpnPermissionDialogShowing && waitTicks < 120) {
                    delay(250L)
                    waitTicks++
                }
                val restoredOk = runCatching {
                    val (quickSub, remoteLists) = ChannelRepository.checkSubscriptionAndListsQuick(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        forceNetwork = true
                    )
                    subscription = quickSub
                    if (quickSub.isExpired && !quickSub.active) {
                        stopVpnImmediately()
                        return@runCatching true
                    }
                    if (remoteLists.isNotEmpty()) {
                        val merged = xtreamPlaylists.toMutableList()
                        for (rCfg in remoteLists) {
                            val existingIdx = merged.indexOfFirst {
                                it.id == rCfg.id ||
                                    (it.serverUrl.equals(rCfg.serverUrl, ignoreCase = true) &&
                                        it.username.equals(rCfg.username, ignoreCase = true))
                            }
                            if (existingIdx >= 0) {
                                merged[existingIdx] = rCfg.copy(id = merged[existingIdx].id)
                            } else {
                                merged.add(rCfg)
                            }
                        }
                        xtreamPlaylists = merged
                        persistCurrentStateSuspend(saveCatalog = false)
                    }
                    val configuredLists = xtreamPlaylists.filter { it.enabled && it.isConfigured }
                    if (configuredLists.size > 1) {
                        isFirstTimeSelectorMode = true
                        showMultiPlaylistSelectorModal = true
                        statusMessage = "اختر القائمة أو القوائم المراد تحميلها وتحديثها..."
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)

                if (!restoredOk) {
                    syncServerNow(forceXtreamRefresh = true)
                }
            }
        }
    }

    // Real-time API Validity & Domain-Change Sync on EVERY App Open / Resume (without re-downloading channels!)
    val currentResumeTrigger = MainActivity.resumeCheckTrigger
    LaunchedEffect(initialized, currentResumeTrigger) {
        if (!initialized) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            // Retry up to 3 times (every 1.5s) in case the automatic VPN is still establishing its route on app open
            var checkedOk = false
            for (attempt in 1..3) {
                if (checkedOk) break
                runCatching {
                    val (quickSub, remoteLists) = ChannelRepository.checkSubscriptionAndListsQuick(deviceId, deviceName)
                    checkedOk = true
                    withContext(Dispatchers.Main) {
                        subscription = quickSub
                        if (quickSub.isExpired && !quickSub.active) {
                            stopVpnImmediately()
                            playingChannel = null
                            statusMessage = quickSub.message.ifBlank { "انتهت صلاحية المشغل. تواصل مع علي سات : $ALI_SAT_PHONE_NUMBER" }
                        } else {
                            val expTxt = if (quickSub.expireDate.isNotBlank()) " • ينتهي: ${quickSub.expireDate}" else ""
                            statusMessage = "متصل • الصلاحية المتبقية: ${quickSub.daysLeft} يوم$expTxt"

                            if (remoteLists.isNotEmpty()) {
                                val merged = xtreamPlaylists.toMutableList()
                                val remoteIds = remoteLists.map { it.id }.toSet()
                                // Remove playlists that were deleted from Customer Portal
                                merged.removeAll { it.isRemoteFromPortal && it.id !in remoteIds }

                                val brandNewLists = mutableListOf<XtreamAccountConfig>()
                                val domainChangedLists = mutableListOf<Pair<XtreamAccountConfig, String>>()

                                for (rCfg in remoteLists) {
                                    // First match by exact playlist ID (so if user changed domain/host in customer_portal.php, we detect the domain change!)
                                    val idxById = merged.indexOfFirst { it.id == rCfg.id }
                                    val idx = if (idxById >= 0) {
                                        idxById
                                    } else {
                                        merged.indexOfFirst {
                                            it.serverUrl.equals(rCfg.serverUrl, ignoreCase = true) &&
                                                it.username.equals(rCfg.username, ignoreCase = true)
                                        }
                                    }

                                    if (idx >= 0) {
                                        val oldCfg = merged[idx]
                                        val oldNormUrl = XtreamRepository.normalizeServerUrl(oldCfg.serverUrl)
                                        val newNormUrl = XtreamRepository.normalizeServerUrl(rCfg.serverUrl)
                                        val domainOrCredsChanged = !oldNormUrl.equals(newNormUrl, ignoreCase = true) ||
                                            oldCfg.username.trim() != rCfg.username.trim() ||
                                            (rCfg.password.isNotBlank() && oldCfg.password.trim() != rCfg.password.trim())

                                        val updatedCfg = rCfg.copy(
                                            id = oldCfg.id,
                                            serverUrl = newNormUrl.ifBlank { rCfg.serverUrl },
                                            liveCount = oldCfg.liveCount,
                                            movieCount = oldCfg.movieCount,
                                            seriesCount = oldCfg.seriesCount,
                                            expDate = rCfg.expDate.ifBlank { oldCfg.expDate },
                                            statusNote = if (domainOrCredsChanged) "⚡ تم تحديث الدومين تلقائياً ✓" else oldCfg.statusNote
                                        )
                                        merged[idx] = updatedCfg
                                        if (domainOrCredsChanged) {
                                            domainChangedLists.add(updatedCfg to oldNormUrl)
                                        }
                                    } else {
                                        merged.add(rCfg)
                                        brandNewLists.add(rCfg)
                                    }
                                }

                                xtreamPlaylists = merged

                                // 1. If the customer changed the domain/host or credentials of an existing playlist in customer_portal.php:
                                //    Instantly update all cached channels/movies/series in SQLite & RAM with the new domain (<15ms),
                                //    AND if the playlist had 0 channels cached, sync it automatically!
                                for ((updatedCfg, oldBaseUrl) in domainChangedLists) {
                                    val newBase = XtreamRepository.normalizeServerUrl(updatedCfg.serverUrl)
                                    val uEnc = java.net.URLEncoder.encode(updatedCfg.username.trim(), "UTF-8")
                                    val pEnc = java.net.URLEncoder.encode(updatedCfg.password.trim(), "UTF-8")
                                    sqliteDb.updatePlaylistCredentialsInPlace(
                                        playlistId = updatedCfg.id,
                                        newServerName = updatedCfg.serverName,
                                        newBaseUrl = newBase,
                                        newUsernameEnc = uEnc,
                                        newPasswordEnc = pEnc,
                                        oldBaseUrl = oldBaseUrl
                                    )
                                    // Update in-memory maps immediately so playing channels use the new domain right away without re-downloading!
                                    xtreamChannelsMap[updatedCfg.id]?.let { chList ->
                                        xtreamChannelsMap = xtreamChannelsMap + (updatedCfg.id to chList.map { ch ->
                                            ch.copy(
                                                playlistName = updatedCfg.serverName,
                                                streamBaseUrl = newBase,
                                                streamUsername = uEnc,
                                                streamPassword = pEnc,
                                                url = if (oldBaseUrl.isNotBlank()) ch.url.replace(oldBaseUrl, newBase, ignoreCase = true) else ch.url
                                            )
                                        })
                                    }
                                    xtreamMoviesMap[updatedCfg.id]?.let { mvList ->
                                        xtreamMoviesMap = xtreamMoviesMap + (updatedCfg.id to mvList.map { mv ->
                                            mv.copy(
                                                playlistName = updatedCfg.serverName,
                                                streamBaseUrl = newBase,
                                                streamUsername = uEnc,
                                                streamPassword = pEnc,
                                                url = if (oldBaseUrl.isNotBlank()) mv.url.replace(oldBaseUrl, newBase, ignoreCase = true) else mv.url
                                            )
                                        })
                                    }
                                    xtreamSeriesMap[updatedCfg.id]?.let { srList ->
                                        xtreamSeriesMap = xtreamSeriesMap + (updatedCfg.id to srList.map { sr ->
                                            sr.copy(
                                                playlistName = updatedCfg.serverName,
                                                streamBaseUrl = newBase,
                                                streamUsername = uEnc,
                                                streamPassword = pEnc,
                                                url = if (oldBaseUrl.isNotBlank()) sr.url.replace(oldBaseUrl, newBase, ignoreCase = true) else sr.url
                                            )
                                        })
                                    }
                                    if (xtreamChannelsMap[updatedCfg.id].isNullOrEmpty()) {
                                        syncSingleXtreamPlaylist(updatedCfg, showToast = true)
                                    }
                                }

                                // 2. If a brand-new playlist was added in customer_portal.php (and we're not in the first-time selector modal), sync only that new playlist!
                                if (!showMultiPlaylistSelectorModal) {
                                    if (brandNewLists.size > 1 && allChannels.isEmpty()) {
                                        isFirstTimeSelectorMode = true
                                        showMultiPlaylistSelectorModal = true
                                    } else {
                                        for (newCfg in brandNewLists) {
                                            if (newCfg.enabled && newCfg.isConfigured) {
                                                syncSingleXtreamPlaylist(newCfg, showToast = true)
                                            }
                                        }
                                    }
                                }
                            }

                            // 3. Also probe real-time Xtream expiry dates in the background (<1 KB per list)
                            //    so the customer always sees the live Xtream account expiry date without re-downloading channels!
                            scope.launch(Dispatchers.IO) {
                                var updatedAnyExp = false
                                val refreshedLists = xtreamPlaylists.map { pl ->
                                    if (pl.enabled && pl.isConfigured) {
                                        val (realExp, _) = XtreamRepository.probeAccountExpiryQuick(pl)
                                        if (realExp.isNotBlank() && realExp != pl.expDate) {
                                            updatedAnyExp = true
                                            pl.copy(expDate = realExp)
                                        } else pl
                                    } else pl
                                }
                                if (updatedAnyExp) {
                                    withContext(Dispatchers.Main) {
                                        xtreamPlaylists = refreshedLists
                                        persistCurrentState(saveCatalog = false)
                                    }
                                }
                            }
                        }
                        persistCurrentState(saveCatalog = false)
                    }
                }.onFailure {
                    delay(1500L)
                }
            }
        }
    }

    LaunchedEffect(initialized, preferences.showNetworkSpeed) {
        if (!initialized || !preferences.showNetworkSpeed) return@LaunchedEffect
        while (true) {
            liveNetworkSpeed = NetworkSpeedMonitor.sampleCurrentSpeedLabel()
            delay(1200L)
        }
    }

    LaunchedEffect(initialized) {
        if (!initialized) return@LaunchedEffect
        while (true) {
            delay(2500L)
            if (isVpnPermissionDialogShowing) continue
            val internalExclusive = CloudflareDohManager.isInternalAppVpnExclusivelyActive(context)
            val externalHijack = CloudflareDohManager.isSystemVpnActive(context) && !internalExclusive
            if (externalHijack) {
                playingChannel = null
                browseOfflineAllowed = false
                ChannelRepository.cancelPendingStreamRequests()
                vpnStatus = CloudflareTunnelStatus(
                    connected = false,
                    systemVpnActive = false,
                    statusText = "⛔ تم رصد VPN خارجي! يشتغل التطبيق حصرياً عبر نفق الـ VPN الداخلي المدمج — اضغط لإعادة تفعيل نفق التطبيق"
                )
            } else if (!internalExclusive && vpnStatus.connected) {
                playingChannel = null
                browseOfflineAllowed = false
                ChannelRepository.cancelPendingStreamRequests()
                vpnStatus = vpnStatus.copy(
                    connected = false,
                    statusText = "تم قطع اتصال نفق الـ VPN الداخلي للتطبيق — يرجى إعادة الاتصال للمتابعة"
                )
            } else if (internalExclusive && !vpnStatus.connected) {
                vpnStatus = CloudflareDohManager.verifyAndActivateCloudflareTunnel(context)
            }
        }
    }

    BackHandler(enabled = playingChannel == null) {
        when {
            pendingResumeChannel != null -> pendingResumeChannel = null
            selectedSeriesForEpisodes != null -> selectedSeriesForEpisodes = null
            preferences.uiLayoutStyle == UiLayoutStyle.CLASSIC_IBO_PRO && !classicIboShowDashboard -> {
                classicIboShowDashboard = true
            }
            else -> showExitConfirmation = !showExitConfirmation
        }
    }

    fun saveVodProgress(ch: TvChannel, posMs: Long, durMs: Long) {
        if (ch.contentType == StreamContentType.LIVE_TV) return
        if (posMs < 3_000L) return
        val entry = VodWatchProgress(
            itemKey = ch.stableKey,
            stableKey = ch.stableKey,
            title = ch.name,
            positionMs = posMs.coerceAtLeast(0L),
            durationMs = durMs.coerceAtLeast(0L),
            updatedAt = System.currentTimeMillis()
        )
        vodWatchProgressMap = vodWatchProgressMap + (ch.stableKey to entry)
        persistCurrentState(saveCatalog = false)
    }

    fun startPlayback(ch: TvChannel, forceResumePosMs: Long? = null) {
        if (!SafeClickGuard.allowAction("START_PLAYBACK", 280L)) return
        if (!vpnStatus.connected) {
            connectVpnAutomatically(forcePrompt = true)
            return
        }
        // Save the exact channel/movie/series stableKey so when the user exits the player,
        // focus returns directly to this exact item instead of jumping to the top of the list!
        lastWatchedStableKey = ch.stableKey
        if (ch.contentType == StreamContentType.SERIES && ch.seriesId > 0) {
            selectedSeriesForEpisodes = ch
            return
        }
        // Check if this Movie or Series Episode has a saved watch progress to offer Resume vs Start Over
        if (ch.contentType != StreamContentType.LIVE_TV && forceResumePosMs == null) {
            val savedProg = vodWatchProgressMap[ch.stableKey]
            if (savedProg != null && savedProg.hasResumePoint) {
                pendingResumeChannel = ch
                return
            }
        }
        activeResumePositionMs = forceResumePosMs ?: 0L
        history = listOf(ch) + history.filterNot { it.stableKey == ch.stableKey }.take(49)
        persistCurrentState()
        playingChannel = ch
    }

    fun toggleFavorite(ch: TvChannel) {
        val exists = favorites.any { it.stableKey == ch.stableKey }
        favorites = if (exists) {
            favorites.filterNot { it.stableKey == ch.stableKey }
        } else {
            listOf(ch) + favorites
        }
        persistCurrentState()
    }

    val colorScheme = darkColorScheme(
        primary = BeinPrimaryPurple,
        onPrimary = Color.White,
        secondary = BeinHighlightPurple,
        background = BeinDarkBg,
        surface = BeinSurface,
        onBackground = Color.White,
        onSurface = Color.White
    )

    CompositionLocalProvider(LocalLayoutDirection provides lang.layoutDirection) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BeinTypography
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BeinDarkBg)
            ) {
                val isPhoneLandscape = maxHeight < 460.dp
                val currentPlaying = playingChannel
                val sub = subscription

                // 1. Show Subscription Expired Gate ONLY when subscription is genuinely confirmed expired!
                //    Never show by default on startup!
                if (initialized && sub != null && sub.isExpired && !sub.active) {
                    AliSatSubscriptionExpiredGate(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        subscription = sub,
                        lang = lang,
                        isLoading = isLoading || isSyncingXtream,
                        compact = isPhoneLandscape,
                        onVerifyNow = { syncServerNow(forceXtreamRefresh = true) },
                        onRedeemCode = { redeemDirectCode(it) },
                        onRequestExit = { showExitConfirmation = true }
                    )
                }
                // 3. Mandatory VPN Gate (Shown if VPN is not connected AND user hasn't chosen to browse local cached SQLite library offline)
                else if (!vpnStatus.connected && !browseOfflineAllowed) {
                    MandatoryCloudflareVpnGate(
                        vpnStatus = vpnStatus,
                        isConnecting = isConnectingVpn,
                        isPermissionDialogShowing = isVpnPermissionDialogShowing,
                        hasLocalLibrary = allChannels.isNotEmpty() || allMovies.isNotEmpty() || allSeries.isNotEmpty(),
                        lang = lang,
                        compact = isPhoneLandscape,
                        onConnectCloudflareVpn = { forcePrompt ->
                            connectVpnAutomatically(forcePrompt = forcePrompt)
                        },
                        onBrowseLocalOffline = {
                            browseOfflineAllowed = true
                            Toast.makeText(
                                context,
                                "📂 وضع تصفح المكتبة المحلية: يتطلب تشغيل القنوات اتصالاً صالحاً وVPN نشطاً",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        onRequestExit = { showExitConfirmation = true }
                    )
                }
                // 3. ExoPlayer Fullscreen View (Instant O(1) bouquet slice lookup — zero lag on 100k+ servers!)
                else if (currentPlaying != null) {
                    val siblings = remember(catalogSnapshot, currentPlaying) {
                        when (currentPlaying.contentType) {
                            StreamContentType.MOVIE ->
                                catalogSnapshot.moviesByCategory[currentPlaying.group].orEmpty().ifEmpty { listOf(currentPlaying) }
                            StreamContentType.SERIES -> listOf(currentPlaying)
                            StreamContentType.LIVE_TV ->
                                catalogSnapshot.channelsByBouquet[currentPlaying.group].orEmpty().ifEmpty { listOf(currentPlaying) }
                        }
                    }
                    PlayerScreen(
                        channel = currentPlaying,
                        siblingChannels = siblings,
                        allChannels = when (currentPlaying.contentType) {
                            StreamContentType.MOVIE -> siblings
                            StreamContentType.SERIES -> siblings
                            StreamContentType.LIVE_TV -> allChannels
                        },
                        favorites = favorites,
                        xtreamPlaylists = orderedPlaylists,
                        preferences = preferences,
                        isFavorite = favorites.any { it.stableKey == currentPlaying.stableKey },
                        initialResumePositionMs = activeResumePositionMs,
                        onSaveVodProgress = { ch, posMs, durMs ->
                            saveVodProgress(ch, posMs, durMs)
                        },
                        onToggleFavorite = { toggleFavorite(it) },
                        onUpdatePreferences = {
                            preferences = it
                            persistCurrentState()
                        },
                        onSelectChannel = { nextCh ->
                            lastWatchedStableKey = nextCh.stableKey
                            startPlayback(nextCh)
                        },
                        onBack = {
                            lastWatchedStableKey = currentPlaying.stableKey
                            restoreFocusTrigger += 1
                            playingChannel = null
                        }
                    )
                }
                // 4.A Classic IBO Player Pro Dashboard Mode (when user selects CLASSIC_IBO_PRO layout style)
                else if (preferences.uiLayoutStyle == UiLayoutStyle.CLASSIC_IBO_PRO && classicIboShowDashboard) {
                    ClassicIboProDashboard(
                        deviceId = deviceId,
                        subscription = subscription,
                        vpnStatus = vpnStatus,
                        networkSpeedLabel = if (preferences.showNetworkSpeed) liveNetworkSpeed else "",
                        liveCount = allChannels.size,
                        moviesCount = allMovies.size,
                        seriesCount = allSeries.size,
                        playlistsCount = orderedPlaylists.size,
                        favoritesCount = favorites.size,
                        continueWatchingCount = vodWatchProgressMap.values.count { it.hasResumePoint },
                        lang = lang,
                        compact = isPhoneLandscape,
                        onOpenTab = { targetTab ->
                            activeTab = targetTab
                            classicIboShowDashboard = false
                        },
                        onSwitchToModernUi = {
                            preferences = preferences.copy(uiLayoutStyle = UiLayoutStyle.MODERN_ROYAL)
                            persistCurrentState()
                        },
                        onRefreshAll = { triggerSmartContentRefresh() },
                        onOpenActivation = { showUnifiedValidityModal = true },
                        onExit = { showExitConfirmation = true }
                    )
                }
                // 4. Main App Shell (Dark Royal Purple)
                else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        LandscapeSideNavigationRail(
                            activeTab = activeTab,
                            lang = lang,
                            compact = isPhoneLandscape,
                            onSelectTab = { tab -> activeTab = tab },
                            onExitClick = { showExitConfirmation = true }
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            TopBeinHeader(
                                deviceId = deviceId,
                                subscription = subscription,
                                vpnStatus = vpnStatus,
                                totalCount = allChannels.size,
                                moviesCount = allMovies.size,
                                seriesCount = allSeries.size,
                                bouquetCount = (bouquetSummaries.size - 1).coerceAtLeast(1),
                                isLoading = isLoading || isSyncingXtream,
                                useGrid = preferences.useGridLayout,
                                lang = lang,
                                playlistMode = preferences.playlistDisplayMode,
                                playlists = orderedPlaylists,
                                activePlaylistFilterId = preferences.activePlaylistFilterId,
                                showScreenCode = preferences.showScreenCodeOverlay,
                                compact = isPhoneLandscape,
                                onSelectLanguage = { newLang ->
                                    preferences = preferences.copy(appLanguage = newLang)
                                    persistCurrentState()
                                },
                                onCyclePlaylistSource = {
                                    // Instant Multi-Click Smart Cycle (XTREAM Playlists ALWAYS FIRST in exact order!):
                                    // Click 1..N: Each enabled XTREAM playlist individually (in exact order)
                                    // Next Click: PORTAL_MAIN (if available)
                                    // Final Click: MERGE_ALL (XTREAM first + Portal)
                                    val enabledLists = orderedPlaylists.filter { it.enabled }
                                    if (enabledLists.isEmpty()) {
                                        preferences = preferences.copy(
                                            playlistDisplayMode = PlaylistDisplayMode.MERGE_ALL,
                                            activePlaylistFilterId = "ALL"
                                        )
                                    } else {
                                        val cycleIds = buildList {
                                            addAll(enabledLists.map { it.id })
                                            if (portalChannels.isNotEmpty() || portalMovies.isNotEmpty() || portalSeries.isNotEmpty()) {
                                                add("PORTAL_MAIN")
                                            }
                                        }
                                        if (preferences.playlistDisplayMode == PlaylistDisplayMode.MERGE_ALL) {
                                            preferences = preferences.copy(
                                                playlistDisplayMode = PlaylistDisplayMode.SEPARATE_LISTS,
                                                activePlaylistFilterId = cycleIds.first()
                                            )
                                        } else {
                                            val currIdx = cycleIds.indexOf(preferences.activePlaylistFilterId)
                                            if (currIdx == -1 || currIdx >= cycleIds.lastIndex) {
                                                preferences = preferences.copy(
                                                    playlistDisplayMode = PlaylistDisplayMode.MERGE_ALL,
                                                    activePlaylistFilterId = "ALL"
                                                )
                                            } else {
                                                preferences = preferences.copy(
                                                    playlistDisplayMode = PlaylistDisplayMode.SEPARATE_LISTS,
                                                    activePlaylistFilterId = cycleIds[currIdx + 1]
                                                )
                                            }
                                        }
                                    }
                                    selectedCategory = "الكل"
                                    selectedMovieCategory = "الكل"
                                    selectedSeriesCategory = "الكل"
                                    persistCurrentState(saveCatalog = false)
                                },
                                onToggleScreenCodeVisibility = {
                                    preferences = preferences.copy(
                                        showScreenCodeOverlay = !preferences.showScreenCodeOverlay
                                    )
                                    persistCurrentState()
                                },
                                isCheckingValidityOnly = isCheckingValidityOnly,
                                networkSpeedLabel = if (preferences.showNetworkSpeed) liveNetworkSpeed else "",
                                uiLayoutStyle = preferences.uiLayoutStyle,
                                showIboHomeButton = preferences.uiLayoutStyle == UiLayoutStyle.CLASSIC_IBO_PRO && !classicIboShowDashboard,
                                onReturnToIboDashboard = { classicIboShowDashboard = true },
                                onToggleUiLayoutStyle = {
                                    val nextStyle = if (preferences.uiLayoutStyle == UiLayoutStyle.MODERN_ROYAL) {
                                        UiLayoutStyle.CLASSIC_IBO_PRO
                                    } else {
                                        UiLayoutStyle.MODERN_ROYAL
                                    }
                                    preferences = preferences.copy(uiLayoutStyle = nextStyle)
                                    classicIboShowDashboard = (nextStyle == UiLayoutStyle.CLASSIC_IBO_PRO)
                                    persistCurrentState()
                                },
                                onOpenBouquets = { activeTab = MainTab.BOUQUETS },
                                onOpenXtream = { activeTab = MainTab.XTREAM },
                                onOpenPlaylistSelectorModal = {
                                    if (orderedPlaylists.isNotEmpty()) {
                                        isFirstTimeSelectorMode = false
                                        showMultiPlaylistSelectorModal = true
                                    } else {
                                        activeTab = MainTab.XTREAM
                                    }
                                },
                                onRefreshValidityOnly = { refreshValidityDatesOnly(showToast = true) },
                                onOpenActivation = { showUnifiedValidityModal = true },
                                onRefresh = { triggerSmartContentRefresh() }
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                when (activeTab) {
                                    MainTab.LIVE -> BeinCatalogBrowserView(
                                        items = allChannels,
                                        channelsByBouquet = catalogSnapshot.channelsByBouquet,
                                        bouquetSummaries = bouquetSummaries,
                                        favorites = favorites,
                                        selectedCategory = selectedCategory,
                                        onSelectCategory = { selectedCategory = it },
                                        searchQuery = searchQuery,
                                        onSearchChange = { searchQuery = it },
                                        statusMessage = statusMessage,
                                        isLoading = isLoading || isSyncingXtream,
                                        useGrid = preferences.useGridLayout,
                                        showBouquetSidebar = preferences.showBouquetSidebar,
                                        onToggleBouquetSidebar = {
                                            preferences = preferences.copy(
                                                showBouquetSidebar = !preferences.showBouquetSidebar
                                            )
                                            persistCurrentState()
                                        },
                                        gridState = liveGridState,
                                        listState = liveListState,
                                        bouquetListState = bouquetSidebarListState,
                                        lastWatchedKey = lastWatchedStableKey,
                                        restoreFocusTrigger = restoreFocusTrigger,
                                        lang = lang,
                                        compact = isPhoneLandscape,
                                        deviceId = deviceId,
                                        onPlay = { startPlayback(it) },
                                        onToggleFavorite = { toggleFavorite(it) },
                                        onRefresh = { triggerSmartContentRefresh() }
                                    )

                                    MainTab.BOUQUETS -> BouquetsGridDirectoryView(
                                        bouquetSummaries = bouquetSummaries,
                                        selectedCategory = selectedCategory,
                                        lang = lang,
                                        compact = isPhoneLandscape,
                                        onSelectBouquet = { chosenBouquet ->
                                            selectedCategory = chosenBouquet
                                            activeTab = MainTab.LIVE
                                        }
                                    )

                                    MainTab.MOVIES -> VodMoviesAndSeriesCatalogView(
                                        titleAr = "🎬 ${AppStrings.tr(lang, "مكتبة الأفلام", "Catalogue Films", "Movies Library")} (${allMovies.size})",
                                        emptyHintAr = AppStrings.tr(
                                            lang,
                                            "لا توجد أفلام حالياً. يمكنك إدارة قوائمك من تبويب فضاء الزبون عبر رمز الشاشة.",
                                            "Aucun film disponible. Gérez vos listes via Espace Client.",
                                            "No movies available yet. Manage your lists via Customer Space."
                                        ),
                                        items = allMovies,
                                        itemsByCategory = catalogSnapshot.moviesByCategory,
                                        categories = catalogSnapshot.movieCategories,
                                        favorites = favorites,
                                        vodWatchProgressMap = vodWatchProgressMap,
                                        selectedCategory = selectedMovieCategory,
                                        onSelectCategory = { selectedMovieCategory = it },
                                        gridState = moviesGridState,
                                        lastWatchedKey = lastWatchedStableKey,
                                        restoreFocusTrigger = restoreFocusTrigger,
                                        compact = isPhoneLandscape,
                                        onPlay = { startPlayback(it) },
                                        onToggleFavorite = { toggleFavorite(it) },
                                        onOpenXtream = { activeTab = MainTab.XTREAM },
                                        onRefresh = { triggerSmartContentRefresh() }
                                    )

                                    MainTab.SERIES -> VodMoviesAndSeriesCatalogView(
                                        titleAr = "📺 ${AppStrings.tr(lang, "مكتبة المسلسلات", "Catalogue Séries", "Series Library")} (${allSeries.size})",
                                        emptyHintAr = AppStrings.tr(
                                            lang,
                                            "لا توجد مسلسلات حالياً. يمكنك إدارة قوائمك من تبويب فضاء الزبون عبر رمز الشاشة.",
                                            "Aucune série disponible. Gérez vos listes via Espace Client.",
                                            "No series available yet. Manage your lists via Customer Space."
                                        ),
                                        items = allSeries,
                                        itemsByCategory = catalogSnapshot.seriesByCategory,
                                        categories = catalogSnapshot.seriesCategories,
                                        favorites = favorites,
                                        vodWatchProgressMap = vodWatchProgressMap,
                                        selectedCategory = selectedSeriesCategory,
                                        onSelectCategory = { selectedSeriesCategory = it },
                                        gridState = seriesGridState,
                                        lastWatchedKey = lastWatchedStableKey,
                                        restoreFocusTrigger = restoreFocusTrigger,
                                        compact = isPhoneLandscape,
                                        onPlay = { startPlayback(it) },
                                        onToggleFavorite = { toggleFavorite(it) },
                                        onOpenXtream = { activeTab = MainTab.XTREAM },
                                        onRefresh = { triggerSmartContentRefresh() }
                                    )

                                    MainTab.XTREAM -> MultiPlaylistXtreamManagerView(
                                        deviceId = deviceId,
                                        subscription = subscription,
                                        playlists = orderedPlaylists,
                                        playlistMode = preferences.playlistDisplayMode,
                                        activePlaylistFilterId = preferences.activePlaylistFilterId,
                                        lang = lang,
                                        isSyncing = isSyncingXtream || isLoading,
                                        compact = isPhoneLandscape,
                                        onChangePlaylistMode = { mode ->
                                            preferences = preferences.copy(playlistDisplayMode = mode)
                                            persistCurrentState()
                                        },
                                        onSelectActivePlaylist = { pid ->
                                            preferences = preferences.copy(activePlaylistFilterId = pid)
                                            persistCurrentState()
                                        },
                                        onAddOrUpdatePlaylist = { cfg ->
                                            syncSingleXtreamPlaylist(cfg, showToast = true)
                                        },
                                        onMovePlaylist = { fromIdx, toIdx ->
                                            val reordered = CustomOrderHelper.moveItem(orderedPlaylists, fromIdx, toIdx)
                                            xtreamPlaylists = reordered
                                            preferences = preferences.copy(playlistOrderIds = reordered.map { it.id })
                                            persistCurrentState()
                                        },
                                        onTogglePlaylistEnabled = { cfg ->
                                            xtreamPlaylists = xtreamPlaylists.map {
                                                if (it.id == cfg.id) it.copy(enabled = !it.enabled) else it
                                            }
                                            persistCurrentState()
                                        },
                                        onDeletePlaylist = { cfg ->
                                            xtreamPlaylists = xtreamPlaylists.filterNot { it.id == cfg.id }
                                            xtreamChannelsMap = xtreamChannelsMap - cfg.id
                                            xtreamMoviesMap = xtreamMoviesMap - cfg.id
                                            xtreamSeriesMap = xtreamSeriesMap - cfg.id
                                            scope.launch {
                                                ChannelRepository.deleteCustomerXtreamFromPortal(deviceId, cfg.id)
                                            }
                                            persistCurrentState(saveCatalog = true)
                                        },
                                        onPullRemoteListsByScreenCode = {
                                            scope.launch {
                                                val openedModal = runCatching {
                                                    val (quickSub, remoteLists) = ChannelRepository.checkSubscriptionAndListsQuick(
                                                        deviceId = deviceId,
                                                        deviceName = deviceName,
                                                        forceNetwork = true
                                                    )
                                                    subscription = quickSub
                                                    if (remoteLists.isNotEmpty()) {
                                                        val merged = xtreamPlaylists.toMutableList()
                                                        for (rCfg in remoteLists) {
                                                            val idx = merged.indexOfFirst {
                                                                it.id == rCfg.id ||
                                                                    (it.serverUrl.equals(rCfg.serverUrl, ignoreCase = true) &&
                                                                        it.username.equals(rCfg.username, ignoreCase = true))
                                                            }
                                                            if (idx >= 0) {
                                                                merged[idx] = rCfg.copy(id = merged[idx].id)
                                                            } else {
                                                                merged.add(rCfg)
                                                            }
                                                        }
                                                        xtreamPlaylists = merged
                                                        persistCurrentStateSuspend(saveCatalog = false)
                                                    }
                                                    val configuredLists = xtreamPlaylists.filter { it.enabled && it.isConfigured }
                                                    if (configuredLists.size > 1) {
                                                        isFirstTimeSelectorMode = false
                                                        showMultiPlaylistSelectorModal = true
                                                        true
                                                    } else false
                                                }.getOrDefault(false)
                                                if (!openedModal) {
                                                    syncServerNow(forceXtreamRefresh = true)
                                                }
                                            }
                                        }
                                    )

                                    MainTab.FAVORITES -> FavoritesAndHistoryView(
                                        favorites = favorites,
                                        history = history,
                                        lang = lang,
                                        compact = isPhoneLandscape,
                                        onPlay = { startPlayback(it) },
                                        onToggleFavorite = { toggleFavorite(it) },
                                        onClearHistory = {
                                            history = emptyList()
                                            persistCurrentState()
                                        }
                                    )

                                    MainTab.ACTIVATION -> ScreenActivationStatusView(
                                        deviceId = deviceId,
                                        deviceName = deviceName,
                                        subscription = subscription,
                                        lang = lang,
                                        isLoading = isLoading || isCheckingValidityOnly,
                                        compact = isPhoneLandscape,
                                        onVerifyNow = { refreshValidityDatesOnly(showToast = true) },
                                        onRedeemCode = { redeemDirectCode(it) }
                                    )

                                    MainTab.SETTINGS -> SettingsAndVpnView(
                                        preferences = preferences,
                                        rawBouquets = rawBouquetSummaries.filter { !it.isFavoritesSpecial && it.name != allBouquetTitle },
                                        allChannels = allChannels,
                                        vpnStatus = vpnStatus,
                                        compact = isPhoneLandscape,
                                        onReconnectVpn = { connectVpnAutomatically() },
                                        onUpdatePreferences = {
                                            preferences = it
                                            persistCurrentState()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                val seriesItem = selectedSeriesForEpisodes
                if (seriesItem != null) {
                    SeriesEpisodesModal(
                        series = seriesItem,
                        xtreamLists = xtreamPlaylists,
                        vodWatchProgressMap = vodWatchProgressMap,
                        compact = isPhoneLandscape,
                        onPlayEpisode = { ep ->
                            selectedSeriesForEpisodes = null
                            val epChannel = seriesItem.copy(
                                name = "${seriesItem.name} - ${ep.title}",
                                url = ep.url,
                                contentType = StreamContentType.MOVIE,
                                seriesId = 0
                            )
                            startPlayback(epChannel)
                        },
                        onDismiss = { selectedSeriesForEpisodes = null }
                    )
                }

                val resumeCandidate = pendingResumeChannel
                if (resumeCandidate != null) {
                    val savedProg = vodWatchProgressMap[resumeCandidate.stableKey]
                    if (savedProg != null) {
                        VodResumePromptModal(
                            channel = resumeCandidate,
                            progress = savedProg,
                            lang = lang,
                            compact = isPhoneLandscape,
                            onResume = {
                                val target = resumeCandidate
                                pendingResumeChannel = null
                                startPlayback(target, forceResumePosMs = savedProg.positionMs)
                            },
                            onStartOver = {
                                val target = resumeCandidate
                                pendingResumeChannel = null
                                vodWatchProgressMap = vodWatchProgressMap - target.stableKey
                                persistCurrentState(saveCatalog = false)
                                startPlayback(target, forceResumePosMs = 0L)
                            },
                            onDismiss = { pendingResumeChannel = null }
                        )
                    } else {
                        pendingResumeChannel = null
                    }
                }

                if (showUnifiedValidityModal) {
                    UnifiedValidityAndScreenCodeModal(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        subscription = subscription,
                        playlists = orderedPlaylists,
                        showScreenCode = preferences.showScreenCodeOverlay,
                        lang = lang,
                        compact = isPhoneLandscape,
                        onToggleScreenCode = {
                            preferences = preferences.copy(showScreenCodeOverlay = !preferences.showScreenCodeOverlay)
                            persistCurrentState()
                        },
                        onOpenCustomerSpace = {
                            showUnifiedValidityModal = false
                            activeTab = MainTab.XTREAM
                        },
                        onOpenFullActivation = {
                            showUnifiedValidityModal = false
                            activeTab = MainTab.ACTIVATION
                        },
                        onRedeemCode = { codeStr ->
                            redeemDirectCode(codeStr)
                        },
                        onRefresh = {
                            refreshValidityDatesOnly(showToast = true)
                        },
                        onDismiss = { showUnifiedValidityModal = false }
                    )
                }

                if (showMultiPlaylistSelectorModal && orderedPlaylists.isNotEmpty()) {
                    FirstTimeMultiPlaylistSelectorModal(
                        playlists = orderedPlaylists,
                        isFirstTime = isFirstTimeSelectorMode,
                        lang = lang,
                        compact = isPhoneLandscape,
                        onSyncSingle = { chosenPl ->
                            showMultiPlaylistSelectorModal = false
                            preferences = preferences.copy(
                                playlistDisplayMode = PlaylistDisplayMode.SEPARATE_LISTS,
                                activePlaylistFilterId = chosenPl.id
                            )
                            syncSingleXtreamPlaylist(chosenPl, showToast = true)
                        },
                        onSyncSelected = { selectedIds, includePortal ->
                            showMultiPlaylistSelectorModal = false
                            if (selectedIds.size == 1 && !includePortal) {
                                val singleId = selectedIds.first()
                                preferences = preferences.copy(
                                    playlistDisplayMode = PlaylistDisplayMode.SEPARATE_LISTS,
                                    activePlaylistFilterId = singleId
                                )
                            } else {
                                preferences = preferences.copy(
                                    playlistDisplayMode = PlaylistDisplayMode.MERGE_ALL,
                                    activePlaylistFilterId = "ALL"
                                )
                            }
                            syncSelectedXtreamPlaylists(selectedIds, includePortal)
                        },
                        onSyncAll = {
                            showMultiPlaylistSelectorModal = false
                            preferences = preferences.copy(
                                playlistDisplayMode = PlaylistDisplayMode.MERGE_ALL,
                                activePlaylistFilterId = "ALL"
                            )
                            syncServerNow(forceXtreamRefresh = true)
                        },
                        onDismiss = { showMultiPlaylistSelectorModal = false }
                    )
                }

                if ((isLoading || isSyncingXtream || !initialized || isSortingLocalLibrary) && playingChannel == null) {
                    ProfessionalWaitingOverlay(
                        lang = lang,
                        compact = isPhoneLandscape,
                        syncProgress = syncProgress,
                        message = when {
                            !initialized || isSortingLocalLibrary -> AppStrings.tr(
                                lang,
                                "جارٍ سحب وترتيب المحتوى المخزن محلياً (القنوات • الباقات • الأفلام • المسلسلات)...",
                                "Chargement et tri de la bibliothèque locale en cours...",
                                "Loading and sorting locally stored content library..."
                            )
                            syncProgress.phaseLabel.isNotBlank() -> syncProgress.phaseLabel
                            isSyncingXtream -> AppStrings.tr(
                                lang,
                                "يرجى الانتظار قليلاً... جارٍ تحديث محتوى قوائم XTREAM وفحص الصلاحية",
                                "Veuillez patienter... Mise à jour des listes XTREAM en cours",
                                "Please wait... Updating XTREAM playlists & checking validity"
                            )
                            else -> AppStrings.tr(
                                lang,
                                "يرجى الانتظار قليلاً... جارٍ تحديث المحتوى ومزامنة القنوات والباقات",
                                "Veuillez patienter... Synchronisation du contenu en cours",
                                "Please wait... Refreshing content and syncing channels"
                            )
                        }
                    )
                }

                val currentVoucherAlert = voucherAlert
                if (currentVoucherAlert != null) {
                    VoucherRedeemAlertModal(
                        alert = currentVoucherAlert,
                        deviceId = deviceId,
                        lang = lang,
                        compact = isPhoneLandscape,
                        onDismiss = { voucherAlert = null }
                    )
                }

                val activeDiagAlert = diagnosticAlert
                if (activeDiagAlert != null && playingChannel == null) {
                    SmartDiagnosticAlertModal(
                        alert = activeDiagAlert,
                        deviceId = deviceId,
                        compact = isPhoneLandscape,
                        onOpenCustomerSpace = {
                            diagnosticAlert = null
                            activeTab = MainTab.XTREAM
                        },
                        onRetrySync = {
                            diagnosticAlert = null
                            syncServerNow(forceXtreamRefresh = true)
                        },
                        onDismiss = { diagnosticAlert = null }
                    )
                }

                if (showExitConfirmation) {
                    ExitConfirmationModal(
                        lang = lang,
                        compact = isPhoneLandscape,
                        onStay = { showExitConfirmation = false },
                        onConfirmExit = {
                            showExitConfirmation = false
                            activity?.finishAffinity()
                        }
                    )
                }
            }
        }
    }
}

internal fun copyDeviceCodeToClipboard(context: Context, code: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LIVE PRO Device Code", code))
        Toast.makeText(context, "تم نسخ الرمز: $code", Toast.LENGTH_SHORT).show()
    }
}

private fun formatVoucherCodeInput(raw: String): String {
    val clean = raw.uppercase().removePrefix("DEV-").replace(Regex("[^A-Z0-9-]"), "")
    val alnum = clean.replace("-", "").take(8)
    return if (alnum.length > 4) {
        "${alnum.substring(0, 4)}-${alnum.substring(4)}"
    } else {
        alnum
    }
}

private fun pasteVoucherFromClipboard(context: Context): String {
    return runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val raw = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        formatVoucherCodeInput(raw)
    }.getOrDefault("")
}

@Composable
private fun VoucherRedeemAlertModal(
    alert: VoucherAlertInfo,
    deviceId: String,
    lang: AppUiLanguage,
    compact: Boolean,
    onDismiss: () -> Unit
) {
    var okFocused by remember { mutableStateOf(true) }
    val okFocusRequester = remember { FocusRequester() }
    val isSuccess = alert.type == VoucherAlertType.SUCCESS
    val isUsedOrExpired = alert.type == VoucherAlertType.USED_CARD || alert.type == VoucherAlertType.EXPIRED_CARD

    LaunchedEffect(alert) {
        delay(60L)
        runCatching { okFocusRequester.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 310.dp, max = if (compact) 480.dp else 560.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        when {
                            isSuccess -> listOf(Color(0xFF0B2920), BeinSurface, BeinDarkBg)
                            isUsedOrExpired -> listOf(Color(0xFF2D0A16), BeinSurface, BeinDarkBg)
                            else -> listOf(Color(0xFF230F42), BeinSurface, BeinDarkBg)
                        }
                    )
                )
                .border(
                    width = 2.5.dp,
                    color = when {
                        isSuccess -> EmeraldLive
                        isUsedOrExpired -> CrimsonAlert
                        else -> BeinHighlightPurple
                    },
                    shape = RoundedCornerShape(22.dp)
                )
                .padding(if (compact) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 48.dp else 58.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isSuccess -> EmeraldLive.copy(alpha = 0.2f)
                            isUsedOrExpired -> CrimsonAlert.copy(alpha = 0.22f)
                            else -> BeinPrimaryPurple
                        }
                    )
                    .border(
                        2.dp,
                        when {
                            isSuccess -> EmeraldLive
                            isUsedOrExpired -> CrimsonAlert
                            else -> BeinHighlightPurple
                        },
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (alert.type) {
                        VoucherAlertType.SUCCESS -> "✅"
                        VoucherAlertType.USED_CARD -> "⛔"
                        VoucherAlertType.EXPIRED_CARD -> "⌛"
                        VoucherAlertType.RATE_LIMITED -> "🛡️"
                        VoucherAlertType.NETWORK_ERROR -> "📡"
                        VoucherAlertType.INVALID_OR_NOT_FOUND -> "❌"
                    },
                    fontSize = if (compact) 22.sp else 26.sp
                )
            }

            Text(
                text = alert.title,
                color = when {
                    isSuccess -> EmeraldLive
                    isUsedOrExpired -> Color(0xFFFCA5A5)
                    else -> Color.White
                },
                fontSize = if (compact) 15.sp else 19.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            if (alert.enteredCode.isNotBlank()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = alert.enteredCode,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = if (compact) 15.sp else 18.sp,
                        letterSpacing = 2.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(BeinDarkBg)
                            .border(
                                1.5.dp,
                                if (isSuccess) EmeraldLive else if (isUsedOrExpired) CrimsonAlert else BeinHighlightPurple,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            Text(
                text = alert.message,
                color = Color(0xFFEDE9FE),
                fontSize = if (compact) 12.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )

            if (isSuccess && (alert.addedDays > 0 || alert.newExpireDate.isNotBlank())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0A231B))
                        .border(1.5.dp, EmeraldLive, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (alert.addedDays > 0) {
                        Text(
                            text = "➕ المدة المضافة: ${alert.addedDays} يوم",
                            color = EmeraldLive,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 11.sp else 13.sp
                        )
                    }
                    if (alert.newExpireDate.isNotBlank()) {
                        Text(
                            text = "📅 الانتهاء الجديد: ${alert.newExpireDate}",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 11.sp else 13.sp
                        )
                    }
                }
            } else if (!isSuccess) {
                Text(
                    text = "📞 للدعم أو طلب قسيمة جديدة — علي سات: $ALI_SAT_PHONE_NUMBER • رمز الشاشة: $deviceId",
                    color = Color(0xFFD8B4FE),
                    fontSize = if (compact) 10.sp else 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = { SafeClickGuard.runThrottled("VOUCHER_MODAL_OK", 220L) { onDismiss() } },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (okFocused) Color.White else if (isSuccess) EmeraldLive else BeinPrimaryPurple,
                    contentColor = if (okFocused || isSuccess) Color.Black else Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 42.dp else 48.dp)
                    .focusRequester(okFocusRequester)
                    .scale(if (okFocused) 1.03f else 1.0f)
                    .border(
                        width = if (okFocused) 3.dp else 1.5.dp,
                        color = if (okFocused) BeinFocusGlow else Color.White,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .onFocusChanged { okFocused = it.isFocused }
                    .focusable()
            ) {
                Text(
                    text = AppStrings.tr(lang, "حسناً، فهمت ✓", "Compris ✓", "Got it ✓"),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 12.sp else 14.sp
                )
            }
        }
    }
}

@Composable
private fun MandatoryCloudflareVpnGate(
    vpnStatus: CloudflareTunnelStatus,
    isConnecting: Boolean,
    isPermissionDialogShowing: Boolean = false,
    hasLocalLibrary: Boolean = false,
    lang: AppUiLanguage,
    compact: Boolean,
    onConnectCloudflareVpn: (Boolean) -> Unit,
    onBrowseLocalOffline: () -> Unit = {},
    onRequestExit: () -> Unit
) {
    val connectBtnFocus = remember { FocusRequester() }

    // Trigger VPN connection ONCE on initial entry — never spam VpnService.prepare() while the Android system VPN confirmation dialog is open!
    LaunchedEffect(Unit) {
        delay(220L)
        if (!vpnStatus.connected && !isConnecting && !isPermissionDialogShowing) {
            onConnectCloudflareVpn(false)
        }
        delay(350L)
        runCatching { connectBtnFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF140826), BeinDarkBg)
                )
            )
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (compact) 410.dp else 480.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(BeinSurface)
                .border(2.dp, BeinHighlightPurple, RoundedCornerShape(24.dp))
                .padding(if (compact) 18.dp else 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
        ) {
            BrandLogoBadge(
                modifier = Modifier.size(if (compact) 62.dp else 80.dp),
                cornerRadius = 18.dp
            )

            Text(
                text = APP_BRAND_NAME,
                color = Color.White,
                fontSize = if (compact) 19.sp else 24.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Box(
                modifier = Modifier.padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = BeinHighlightPurple,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(if (compact) 48.dp else 60.dp)
                )
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(if (compact) 28.dp else 36.dp)
                )
            }

            Text(
                text = if (isPermissionDialogShowing) {
                    AppStrings.tr(
                        lang,
                        "🔒 يرجى الضغط على «موافق / OK» في نافذة الأندرويد لتأكيد تشغيل الـ VPN لأول مرة",
                        "🔒 Veuillez appuyer sur « OK » dans la fenêtre Android pour autoriser le VPN",
                        "🔒 Please press 'OK' on the Android system prompt to allow VPN first time"
                    )
                } else {
                    AppStrings.tr(
                        lang,
                        "🔒 جارٍ تشغيل نفق الـ VPN الآمن تلقائياً... يرجى الانتظار لحظات",
                        "🔒 Connexion automatique au tunnel VPN sécurisé en cours...",
                        "🔒 Automatically connecting internal secure VPN tunnel..."
                    )
                },
                color = Color.White,
                fontSize = if (compact) 12.sp else 15.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            Text(
                text = vpnStatus.statusText.ifBlank {
                    AppStrings.tr(
                        lang,
                        "يتم الاتصال تلقائياً بدون الحاجة لأي نقر وسيفتح المشغل فوراً",
                        "Ouverture automatique dès l'activation du tunnel",
                        "Opening player automatically once tunnel is ready"
                    )
                },
                color = Color(0xFFD8B4FE),
                fontSize = if (compact) 10.sp else 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            BeinFocusableButton(
                text = AppStrings.tr(
                    lang,
                    "🔒 تفعيل اتصال الـ VPN الآمن والمتابعة الآن",
                    "🔒 Activer le VPN sécurisé maintenant",
                    "🔒 Activate Secure VPN & Continue Now"
                ),
                isPrimary = true,
                compact = compact,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(connectBtnFocus),
                onClick = { onConnectCloudflareVpn(true) }
            )

            if (hasLocalLibrary) {
                BeinFocusableButton(
                    text = AppStrings.tr(
                        lang,
                        "📂 تصفح المكتبة المحفوظة محلياً فوراً",
                        "📂 Parcourir la bibliothèque locale immédiatement",
                        "📂 Browse Stored Local Library Now"
                    ),
                    isPrimary = false,
                    compact = compact,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onBrowseLocalOffline
                )
            }
        }
    }
}

/**
 * Professional Subscription Expired / Inactive Gate:
 * Displays the customer's Screen Code (DEV-XXXX-XXXX) and clear contact details
 * for Ali Sat (00213697372480) to renew/activate the player.
 */
@Composable
private fun AliSatSubscriptionExpiredGate(
    deviceId: String,
    deviceName: String,
    subscription: SubscriptionInfo,
    lang: AppUiLanguage,
    isLoading: Boolean,
    compact: Boolean,
    onVerifyNow: () -> Unit,
    onRedeemCode: (String) -> Unit,
    onRequestExit: () -> Unit
) {
    val context = LocalContext.current
    var directCodeInput by remember { mutableStateOf("") }
    var verifyFocused by remember { mutableStateOf(true) }
    var copyFocused by remember { mutableStateOf(false) }
    var exitFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF240F42), BeinDarkBg)
                )
            )
            .padding(if (compact) 8.dp else 16.dp),
        contentAlignment = Alignment.Center
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = if (compact) 600.dp else 720.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(BeinSurface)
                .border(2.dp, BeinHighlightPurple, RoundedCornerShape(24.dp))
                .padding(if (compact) 16.dp else 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
        ) {
            // Centered Brand Header & Status Badge
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BrandLogoBadge(
                        modifier = Modifier.size(if (compact) 54.dp else 70.dp),
                        cornerRadius = 16.dp
                    )
                    Text(
                        text = AppStrings.tr(
                            lang,
                            "انتهت صلاحية اشتراك المشغل",
                            "Abonnement du lecteur expiré",
                            "Player Subscription Expired"
                        ),
                        color = Color.White,
                        fontSize = if (compact) 18.sp else 23.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = if (subscription.expireDate.isNotBlank()) {
                            AppStrings.tr(
                                lang,
                                "تاريخ انتهاء الصلاحية: ${subscription.expireDate} • خدمة الـ VPN والمشغل متوقفة لحين التجديد",
                                "Expiré le : ${subscription.expireDate} • Lecteur et VPN verrouillés",
                                "Expired on: ${subscription.expireDate} • Player & VPN locked until renewal"
                            )
                        } else {
                            AppStrings.tr(
                                lang,
                                "الاشتراك غير مفعّل أو منتهي الصلاحية • لا يتم فتح المشغل أو الـ VPN إلا باشتراك ساري المفعول",
                                "Abonnement inactif • Le lecteur et le VPN nécessitent un abonnement valide",
                                "Subscription inactive • Player & VPN require an active subscription"
                            )
                        },
                        color = Color(0xFFFCA5A5),
                        fontSize = if (compact) 11.sp else 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Centered Two-Column Info Cards: (1) Screen Code Card + (2) Ali Sat Contact Card
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Card 1: Screen Code
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(BeinDarkBg)
                            .border(1.8.dp, BeinHighlightPurple, RoundedCornerShape(16.dp))
                            .clickable { copyDeviceCodeToClipboard(context, deviceId) }
                            .padding(if (compact) 12.dp else 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = AppStrings.tr(
                                lang,
                                "🖥️ رمز الشاشة الخاص بجهازك",
                                "🖥️ Votre Code Écran",
                                "🖥️ Your Screen Code"
                            ),
                            color = Color(0xFFD8B4FE),
                            fontSize = if (compact) 11.sp else 13.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = deviceId,
                                color = Color.White,
                                fontSize = if (compact) 19.sp else 24.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 2.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        Text(
                            text = AppStrings.tr(lang, "اضغط لنسخ الرمز وتقديمه للتفعيل", "Cliquez pour copier", "Tap to copy code"),
                            color = Color.LightGray,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Card 2: Ali Sat Official Renewal Contact
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(BeinPrimaryPurple, Color(0xFF210E3A))
                                )
                            )
                            .border(1.8.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                            .padding(if (compact) 12.dp else 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = AppStrings.tr(
                                lang,
                                "📞 لتجديد الاشتراك تواصل مع علي سات",
                                "📞 Contactez Ali Sat pour renouveler",
                                "📞 Contact Ali Sat to Renew"
                            ),
                            color = Color(0xFFD8B4FE),
                            fontSize = if (compact) 11.sp else 13.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = ALI_SAT_PHONE_NUMBER,
                                color = Color.White,
                                fontSize = if (compact) 19.sp else 24.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.5.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        Text(
                            text = AppStrings.tr(lang, "أرسل رمز الشاشة لـ علي سات للتفعيل الفوري", "Envoyez votre Code Écran à Ali Sat", "Send your Screen Code to Ali Sat"),
                            color = Color.White,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Direct Activation Voucher Input Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = directCodeInput,
                        onValueChange = { directCodeInput = formatVoucherCodeInput(it) },
                        placeholder = {
                            Text(
                                AppStrings.tr(lang, "أدخل رمز القسيمة هنا (مثال: A1B2-C3D4)", "Code d'activation (ex: A1B2-C3D4)", "Enter Voucher Code (e.g. A1B2-C3D4)"),
                                color = Color(0xFFBFA7F2),
                                fontSize = if (compact) 11.sp else 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = if (compact) 14.sp else 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp,
                            textAlign = TextAlign.Center
                        ),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    BeinFocusableButton(
                        text = AppStrings.tr(lang, "📋 لصق", "📋 Coller", "📋 Paste"),
                        isPrimary = false,
                        compact = compact,
                        onClick = {
                            val pasted = pasteVoucherFromClipboard(context)
                            if (pasted.isNotBlank()) directCodeInput = pasted
                        }
                    )
                    BeinFocusableButton(
                        text = if (isLoading) "⏳..." else AppStrings.tr(lang, "تفعيل الكود ✓", "Activer ✓", "Activate ✓"),
                        isPrimary = true,
                        compact = compact,
                        onClick = { onRedeemCode(directCodeInput) }
                    )
                }
            }

            // Action Buttons Row (Centered)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onVerifyNow,
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (verifyFocused) Color.White else BeinPrimaryPurple,
                            contentColor = if (verifyFocused) Color.Black else Color.White
                        ),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(if (compact) 44.dp else 50.dp)
                            .scale(if (verifyFocused) 1.03f else 1.0f)
                            .border(
                                width = if (verifyFocused) 3.dp else 1.5.dp,
                                color = if (verifyFocused) BeinFocusGlow else Color.White,
                                shape = RoundedCornerShape(25.dp)
                            )
                            .onFocusChanged { verifyFocused = it.isFocused }
                            .focusable()
                    ) {
                        Text(
                            text = if (isLoading) {
                                AppStrings.tr(lang, "⏳ جارٍ التحقق من التفعيل...", "⏳ Vérification...", "⏳ Checking...")
                            } else {
                                AppStrings.tr(
                                    lang,
                                    "🔄 تحقق من التفعيل وتجديد الاشتراك الآن",
                                    "🔄 Vérifier le renouvellement",
                                    "🔄 Check Renewal & Activate Now"
                                )
                            },
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 11.sp else 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    Button(
                        onClick = { copyDeviceCodeToClipboard(context, deviceId) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (copyFocused) Color.White else BeinCard,
                            contentColor = if (copyFocused) Color.Black else Color.White
                        ),
                        modifier = Modifier
                            .weight(0.75f)
                            .height(if (compact) 44.dp else 50.dp)
                            .scale(if (copyFocused) 1.03f else 1.0f)
                            .border(2.dp, if (copyFocused) BeinFocusGlow else BeinHighlightPurple, RoundedCornerShape(25.dp))
                            .onFocusChanged { copyFocused = it.isFocused }
                            .focusable()
                    ) {
                        Text(
                            AppStrings.tr(lang, "📋 نسخ الرمز", "📋 Copier", "📋 Copy Code"),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 11.sp else 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    Button(
                        onClick = onRequestExit,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (exitFocused) CrimsonAlert else Color(0xFF281220),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(0.55f)
                            .height(if (compact) 44.dp else 50.dp)
                            .scale(if (exitFocused) 1.03f else 1.0f)
                            .border(1.5.dp, if (exitFocused) Color.White else CrimsonAlert, RoundedCornerShape(25.dp))
                            .onFocusChanged { exitFocused = it.isFocused }
                            .focusable()
                    ) {
                        Text(
                            AppStrings.tr(lang, "خروج", "Quitter", "Exit"),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 11.sp else 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/**
 * Customer XTREAM Space Manager View:
 * • Integrated with standalone customer_portal.php & api.php via Screen Code
 * • Verifies player subscription validity before allowing new playlists
 */
@Composable
private fun MultiPlaylistXtreamManagerView(
    deviceId: String,
    subscription: SubscriptionInfo?,
    playlists: List<XtreamAccountConfig>,
    playlistMode: PlaylistDisplayMode,
    activePlaylistFilterId: String,
    lang: AppUiLanguage,
    isSyncing: Boolean,
    compact: Boolean,
    onChangePlaylistMode: (PlaylistDisplayMode) -> Unit,
    onSelectActivePlaylist: (String) -> Unit,
    onAddOrUpdatePlaylist: (XtreamAccountConfig) -> Unit,
    onMovePlaylist: (Int, Int) -> Unit,
    onTogglePlaylistEnabled: (XtreamAccountConfig) -> Unit,
    onDeletePlaylist: (XtreamAccountConfig) -> Unit,
    onPullRemoteListsByScreenCode: () -> Unit
) {
    val context = LocalContext.current
    var showAddForm by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf("") }
    var serverName by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val isSubActive = subscription == null || (subscription.active && !subscription.isExpired)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (compact) 10.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .widthIn(max = 780.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF10061E), BeinCard)
                        )
                    )
                    .border(1.5.dp, BeinHighlightPurple, RoundedCornerShape(18.dp))
                    .padding(if (compact) 12.dp else 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = AppStrings.tr(
                                lang,
                                "📲 لوحة فضاء الزبون — استعادة وإدارة القوائم عبر رمز الشاشة",
                                "📲 Espace Client — Gestion des listes via Code Écran",
                                "📲 Customer Space — Manage Lists via Screen Code"
                            ),
                            color = Color.White,
                            fontSize = if (compact) 14.sp else 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = AppStrings.tr(
                                lang,
                                "أضف أو عدّل قوائمك من صفحة فضاء الزبون (customer_portal.php) عبر رمز الشاشة ثم اضغط استعادة القوائم",
                                "Ajoutez ou modifiez vos listes depuis customer_portal.php avec votre Code Écran",
                                "Add or edit your lists via customer_portal.php using your Screen Code"
                            ),
                            color = Color(0xFFD8B4FE),
                            fontSize = if (compact) 10.sp else 12.sp
                        )
                    }
                    if (subscription != null && subscription.active) {
                        Text(
                            text = "⏳ الصلاحية: ${subscription.daysLeft} يوم",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .background(EmeraldLive.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                                .border(1.dp, EmeraldLive, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                // Prominent Top Action Row — 100% D-Pad accessible (parent Row is NOT clickable so D-Pad never skips child buttons!)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BeinDarkBg)
                        .border(1.5.dp, BeinHighlightPurple, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = AppStrings.tr(lang, "رمز الشاشة الخاص بك:", "Votre Code Écran :", "Your Screen Code:"),
                            color = Color.LightGray,
                            fontSize = if (compact) 10.sp else 12.sp
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = deviceId,
                                color = Color.White,
                                fontSize = if (compact) 18.sp else 22.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isSubActive) {
                            BeinFocusableButton(
                                text = if (showAddForm) {
                                    AppStrings.tr(lang, "✕ إغلاق النموذج", "✕ Fermer", "✕ Close Form")
                                } else {
                                    AppStrings.tr(lang, "➕ إضافة حساب XTREAM جديد", "➕ Ajouter XTREAM", "➕ Add XTREAM Account")
                                },
                                isPrimary = true,
                                compact = compact,
                                onClick = {
                                    editingId = ""
                                    serverName = ""
                                    serverUrl = ""
                                    username = ""
                                    password = ""
                                    showAddForm = !showAddForm
                                }
                            )
                        }
                        BeinFocusableButton(
                            text = if (isSyncing) {
                                AppStrings.tr(lang, "جارٍ الاستعادة...", "Synchronisation...", "Syncing...")
                            } else {
                                AppStrings.tr(lang, "🔄 استعادة القوائم برمز الشاشة", "🔄 Récupérer les listes", "🔄 Restore Lists by Code")
                            },
                            isPrimary = false,
                            compact = compact,
                            onClick = onPullRemoteListsByScreenCode
                        )
                        BeinFocusableButton(
                            text = "📋 نسخ الرمز",
                            isPrimary = false,
                            compact = compact,
                            onClick = { copyDeviceCodeToClipboard(context, deviceId) }
                        )
                    }
                }
            }
        }

        // User Choice: MERGE ALL Lists vs SEPARATE Lists
        item {
            Column(
                modifier = Modifier
                    .widthIn(max = 780.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(BeinCard)
                    .border(1.5.dp, BeinBorder, RoundedCornerShape(16.dp))
                    .padding(if (compact) 12.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = AppStrings.tr(
                        lang,
                        "🔀 دمج القوائم أو فصلها (المستخدم يقرر):",
                        "🔀 Fusionner ou séparer les listes :",
                        "🔀 Merge or Separate Playlists:"
                    ),
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 12.sp else 14.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PlaylistDisplayMode.values().forEach { mode ->
                        val selected = playlistMode == mode
                        BeinFocusableButton(
                            text = mode.label(lang),
                            isPrimary = selected,
                            compact = compact,
                            modifier = Modifier.weight(1f),
                            onClick = { onChangePlaylistMode(mode) }
                        )
                    }
                }

                if (playlistMode == PlaylistDisplayMode.SEPARATE_LISTS && playlists.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(playlists, key = { idx, pl -> "${idx}_${pl.id}" }) { _, pl ->
                            BeinFocusableButton(
                                text = "⚡ ${pl.serverName.ifBlank { "XTREAM" }}",
                                isPrimary = activePlaylistFilterId == pl.id,
                                compact = true,
                                onClick = { onSelectActivePlaylist(pl.id) }
                            )
                        }
                        item {
                            BeinFocusableButton(
                                text = AppStrings.tr(lang, "📡 القائمة الرئيسية", "📡 Liste Principale", "📡 Main Portal"),
                                isPrimary = activePlaylistFilterId == "PORTAL_MAIN",
                                compact = true,
                                onClick = { onSelectActivePlaylist("PORTAL_MAIN") }
                            )
                        }
                        item {
                            BeinFocusableButton(
                                text = AppStrings.tr(lang, "🔀 كل القوائم", "🔀 Toutes", "🔀 All Lists"),
                                isPrimary = activePlaylistFilterId == "ALL",
                                compact = true,
                                onClick = { onSelectActivePlaylist("ALL") }
                            )
                        }
                    }
                }
            }
        }

        if (showAddForm && isSubActive) {
            item {
                val nameFocus = remember { FocusRequester() }
                val urlFocus = remember { FocusRequester() }
                val userFocus = remember { FocusRequester() }
                val passFocus = remember { FocusRequester() }
                val saveBtnFocus = remember { FocusRequester() }

                LaunchedEffect(showAddForm) {
                    delay(120L)
                    runCatching { nameFocus.requestFocus() }
                }

                Column(
                    modifier = Modifier
                        .widthIn(max = 780.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(BeinCard)
                        .border(2.dp, BeinHighlightPurple, RoundedCornerShape(16.dp))
                        .padding(if (compact) 12.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (editingId.isNotBlank()) "✏️ تعديل بيانات حساب XTREAM" else "➕ إضافة حساب XTREAM جديد (متوافق 100% مع الريموت)",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp
                        )
                        BeinFocusableButton(
                            text = "✕ إغلاق",
                            isPrimary = false,
                            compact = true,
                            onClick = { showAddForm = false }
                        )
                    }

                    // Single-column vertical fields so Android TV D-Pad Up/Down & Next button navigate smoothly!
                    OutlinedTextField(
                        value = serverName,
                        onValueChange = { serverName = it },
                        label = { Text(AppStrings.tr(lang, "1. اسم القائمة (اختياري)", "1. Nom de la liste (optionnel)", "1. Playlist Name (optional)")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { runCatching { urlFocus.requestFocus() } }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(nameFocus)
                    )

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { inputVal ->
                            val split = SmartM3uParser.tryExtractXtreamCredentials(inputVal)
                            if (split != null) {
                                serverUrl = split.serverUrl
                                username = split.username
                                password = split.password
                            } else {
                                serverUrl = inputVal
                            }
                        },
                        label = { Text(AppStrings.tr(lang, "2. رابط السيرفر (http://host:port) أو الصق رابط M3U كامل", "2. Server URL ou lien M3U complet", "2. Server URL or full M3U link")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { runCatching { userFocus.requestFocus() } }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(urlFocus)
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(AppStrings.tr(lang, "3. اسم المستخدم (Username)", "3. Nom d'utilisateur (Username)", "3. Username")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { runCatching { passFocus.requestFocus() } }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(userFocus)
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(AppStrings.tr(lang, "4. كلمة المرور (Password)", "4. Mot de passe (Password)", "4. Password")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { runCatching { saveBtnFocus.requestFocus() } }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(passFocus)
                    )

                    BeinFocusableButton(
                        text = AppStrings.tr(lang, "✅ حفظ ومزامنة القائمة فوراً ⚡", "✅ Enregistrer et synchroniser ⚡", "✅ Save & Sync Playlist Now ⚡"),
                        isPrimary = true,
                        compact = compact,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(saveBtnFocus),
                        onClick = {
                            if (serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                                val targetId = editingId.ifBlank { UUID.randomUUID().toString().take(8) }
                                val newCfg = XtreamAccountConfig(
                                    id = targetId,
                                    serverName = serverName.trim().ifBlank { "قائمة XTREAM #${playlists.size + 1}" },
                                    serverUrl = serverUrl.trim(),
                                    username = username.trim(),
                                    password = password.trim(),
                                    enabled = true,
                                    orderIndex = playlists.size
                                )
                                onAddOrUpdatePlaylist(newCfg)
                                editingId = ""
                                serverName = ""
                                serverUrl = ""
                                username = ""
                                password = ""
                                showAddForm = false
                            } else {
                                Toast.makeText(
                                    context,
                                    "⚠️ يرجى إدخال رابط السيرفر واسم المستخدم وكلمة المرور أولاً",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .widthIn(max = 780.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(BeinCard)
                    .border(1.dp, BeinBorder, RoundedCornerShape(16.dp))
                    .padding(if (compact) 12.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = AppStrings.tr(
                        lang,
                        "📋 القوائم المرتبطة برمز الشاشة (${playlists.size}):",
                        "📋 Listes liées au Code Écran (${playlists.size}) :",
                        "📋 Lists Linked to Screen Code (${playlists.size}):"
                    ),
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 13.sp else 15.sp
                )

                if (playlists.isEmpty()) {
                    Text(
                        text = AppStrings.tr(
                            lang,
                            "لا توجد قوائم مضافة حالياً. يمكنك رفع قوائمك من لوحة فضاء الزبون (customer_portal.php) عبر رمز الشاشة $deviceId ثم الضغط على «استعادة القوائم برمز الشاشة».",
                            "Aucune liste. Ajoutez vos listes via customer_portal.php avec le code $deviceId.",
                            "No playlists yet. Add lists via customer_portal.php using Screen Code $deviceId."
                        ),
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                } else {
                    playlists.forEachIndexed { idx, pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (pl.enabled) BeinSurface else Color(0xFF100A19))
                                .border(
                                    width = if (ExpiryAlertHelper.isDateExpiringSoon(pl.expDate)) 2.dp else 1.dp,
                                    color = when {
                                        ExpiryAlertHelper.isDateExpiringSoon(pl.expDate) -> CrimsonAlert
                                        pl.enabled -> BeinHighlightPurple
                                        else -> BeinBorder
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${idx + 1}. ${pl.serverName.ifBlank { "XTREAM" }}",
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = pl.statusNote,
                                    color = Color(0xFFD8B4FE),
                                    fontSize = 11.sp
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                DpadSmallControlBtn(
                                    label = "▲",
                                    enabled = idx > 0,
                                    onClick = { onMovePlaylist(idx, idx - 1) }
                                )
                                DpadSmallControlBtn(
                                    label = "▼",
                                    enabled = idx < playlists.lastIndex,
                                    onClick = { onMovePlaylist(idx, idx + 1) }
                                )
                                DpadSmallControlBtn(
                                    label = "✏️",
                                    enabled = isSubActive,
                                    onClick = {
                                        editingId = pl.id
                                        serverName = pl.serverName
                                        serverUrl = pl.serverUrl
                                        username = pl.username
                                        password = pl.password
                                        showAddForm = true
                                    }
                                )
                                DpadSmallControlBtn(
                                    label = "🔄 تحديث القائمة لوحدها",
                                    enabled = !isSyncing && isSubActive,
                                    onClick = { onAddOrUpdatePlaylist(pl) }
                                )
                                DpadSmallControlBtn(
                                    label = if (pl.enabled) "مفعّلة ✓" else "معطّلة",
                                    enabled = true,
                                    onClick = { onTogglePlaylistEnabled(pl) }
                                )
                                DpadSmallControlBtn(
                                    label = "✕",
                                    enabled = true,
                                    onClick = { onDeletePlaylist(pl) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BeinFocusableButton(
    text: String,
    isPrimary: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .scale(if (focused) 1.05f else 1.0f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> Color.White
                    isPrimary -> BeinPrimaryPurple
                    else -> BeinSurface
                }
            )
            .border(
                width = if (focused) 2.5.dp else 1.2.dp,
                color = if (focused) BeinFocusGlow else if (isPrimary) BeinHighlightPurple else BeinBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    ev.nativeKeyEvent.repeatCount == 0 &&
                    (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    SafeClickGuard.runThrottled("UI_BTN_CLICK", 200L) { onClick() }
                    true
                } else false
            }
            .focusable()
            .clickable { SafeClickGuard.runThrottled("UI_BTN_CLICK", 200L) { onClick() } }
            .padding(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = if (compact) 7.dp else 9.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (focused) Color.Black else Color.White,
            fontSize = if (compact) 10.sp else 12.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SeriesEpisodesModal(
    series: TvChannel,
    xtreamLists: List<XtreamAccountConfig>,
    vodWatchProgressMap: Map<String, VodWatchProgress> = emptyMap(),
    compact: Boolean,
    onPlayEpisode: (SeriesEpisode) -> Unit,
    onDismiss: () -> Unit
) {
    var episodes by remember(series.stableKey) { mutableStateOf<List<SeriesEpisode>>(emptyList()) }
    var loading by remember(series.stableKey) { mutableStateOf(true) }

    LaunchedEffect(series.stableKey) {
        loading = true
        episodes = XtreamRepository.fetchSeriesEpisodes(series, xtreamLists)
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 580.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .clip(RoundedCornerShape(18.dp))
                .background(BeinSurface)
                .border(2.dp, BeinHighlightPurple, RoundedCornerShape(18.dp))
                .padding(if (compact) 12.dp else 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "📺 ${series.name}",
                        color = Color.White,
                        fontSize = if (compact) 14.sp else 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2
                    )
                    Text(
                        text = "اختر الحلقة للمشاهدة الفورية (${episodes.size} حلقة)",
                        color = Color(0xFFD8B4FE),
                        fontSize = if (compact) 10.sp else 12.sp
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                }
            }

            Spacer(Modifier.height(8.dp))

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else if (episodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "لم يتم العثور على حلقات منفصلة؛ جاري التشغيل المباشر...",
                        color = Color.LightGray
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(episodes, key = { idx, ep -> "${idx}_${ep.id}" }) { _, ep ->
                        var focused by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .scale(if (focused) 1.02f else 1.0f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (focused) Color.White else BeinCard)
                                .border(
                                    width = if (focused) 2.5.dp else 1.dp,
                                    color = if (focused) BeinFocusGlow else BeinBorder,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .onFocusChanged { focused = it.isFocused }
                                .onKeyEvent { ev ->
                                    if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                        (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                            ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                                    ) {
                                        onPlayEpisode(ep)
                                        true
                                    } else false
                                }
                                .focusable()
                                .clickable { onPlayEpisode(ep) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val epStableKey = remember(series, ep.url) {
                                series.copy(
                                    name = "${series.name} - ${ep.title}",
                                    url = ep.url,
                                    contentType = StreamContentType.MOVIE,
                                    seriesId = 0
                                ).stableKey
                            }
                            val epProg = vodWatchProgressMap[epStableKey]
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "موسم ${ep.seasonNumber} • حلقة ${ep.episodeNumber}: ${ep.title}",
                                    color = if (focused) Color.Black else Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = if (compact) 11.sp else 13.sp
                                )
                                if (epProg != null && epProg.positionMs >= 5_000L) {
                                    Text(
                                        text = if (epProg.isCompleted) {
                                            "✅ تمت المشاهدة بالكامل (${epProg.formattedDuration})"
                                        } else {
                                            "⏯ شوهد حتى ${epProg.formattedPosition} / ${epProg.formattedDuration} (${(epProg.progressFraction * 100).toInt()}%) — اضغط للاستئناف"
                                        },
                                        color = if (focused) Color(0xFF4C1D95) else if (epProg.isCompleted) EmeraldLive else Color(0xFFFBBF24),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = if (compact) 9.sp else 11.sp
                                    )
                                }
                            }
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (focused) BeinPrimaryPurple else if (epProg != null) EmeraldLive else Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VodMoviesAndSeriesCatalogView(
    titleAr: String,
    emptyHintAr: String,
    items: List<TvChannel>,
    itemsByCategory: Map<String, List<TvChannel>>,
    categories: List<BouquetSummary>,
    favorites: List<TvChannel>,
    vodWatchProgressMap: Map<String, VodWatchProgress> = emptyMap(),
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    gridState: LazyGridState,
    lastWatchedKey: String?,
    restoreFocusTrigger: Int,
    compact: Boolean,
    onPlay: (TvChannel) -> Unit,
    onToggleFavorite: (TvChannel) -> Unit,
    onOpenXtream: () -> Unit,
    onRefresh: () -> Unit
) {
    var vodSearch by remember { mutableStateOf("") }
    val lastWatchedFocusRequester = remember { FocusRequester() }

    val watchedItemsList = remember(items, vodWatchProgressMap) {
        items.filter { ch ->
            val p = vodWatchProgressMap[ch.stableKey]
            p != null && p.positionMs >= 5_000L
        }.sortedByDescending { ch -> vodWatchProgressMap[ch.stableKey]?.updatedAt ?: 0L }
    }

    // Instant O(1) category lookup + Continue Watching filter + background Dispatchers.Default search filter
    val categorySlice = remember(items, itemsByCategory, selectedCategory, watchedItemsList) {
        when (selectedCategory) {
            "الكل" -> items
            "__CONTINUE_WATCHING__" -> watchedItemsList
            else -> itemsByCategory[selectedCategory].orEmpty()
        }
    }

    val filtered by produceState(
        initialValue = categorySlice,
        categorySlice,
        vodSearch
    ) {
        val q = vodSearch.trim()
        value = if (q.isEmpty()) {
            categorySlice
        } else {
            withContext(Dispatchers.Default) {
                categorySlice.filter { item ->
                    item.name.contains(q, ignoreCase = true) || item.group.contains(q, ignoreCase = true)
                }
            }
        }
    }

    val targetWatchedIdx = remember(filtered, lastWatchedKey) {
        if (lastWatchedKey == null) -1 else filtered.indexOfFirst { it.stableKey == lastWatchedKey }
    }

    // Windowed Lazy Paging: render 120 items per batch and expand automatically on scroll!
    var visibleLimit by remember(selectedCategory, vodSearch, items.size) {
        val minNeeded = if (targetWatchedIdx >= 0) targetWatchedIdx + 80 else 120
        mutableStateOf(minNeeded.coerceAtLeast(120).coerceAtMost(filtered.size.coerceAtLeast(120)))
    }
    val windowedItems = remember(filtered, visibleLimit) {
        if (filtered.size <= visibleLimit) filtered
        else filtered.subList(0, visibleLimit)
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= windowedItems.size - 24 && windowedItems.size < filtered.size
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            visibleLimit = (visibleLimit + 120).coerceAtMost(filtered.size)
        }
    }

    val favSet = remember(favorites) { favorites.map { it.stableKey }.toHashSet() }

    LaunchedEffect(restoreFocusTrigger) {
        if (restoreFocusTrigger > 0 && targetWatchedIdx >= 0 && filtered.isNotEmpty()) {
            if (visibleLimit <= targetWatchedIdx) {
                visibleLimit = (targetWatchedIdx + 80).coerceAtMost(filtered.size)
            }
            delay(45L)
            runCatching { gridState.scrollToItem(targetWatchedIdx) }
            delay(65L)
            runCatching { lastWatchedFocusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = titleAr,
                color = Color.White,
                fontSize = if (compact) 13.sp else 17.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CompactSearchBar(
                    query = vodSearch,
                    onQueryChange = { vodSearch = it },
                    compact = compact,
                    modifier = Modifier
                        .width(if (compact) 150.dp else 210.dp)
                        .height(if (compact) 28.dp else 34.dp)
                )
                BeinFocusableButton(
                    text = "📲 فضاء الزبون",
                    isPrimary = true,
                    compact = true,
                    onClick = onOpenXtream
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (watchedItemsList.isNotEmpty()) {
                item(key = "continue_watching_chip") {
                    val isSelected = selectedCategory == "__CONTINUE_WATCHING__"
                    var focused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .height(if (compact) 28.dp else 34.dp)
                            .scale(if (focused) 1.05f else 1.0f)
                            .clip(RoundedCornerShape(14.dp))
                            .onFocusChanged { focused = it.isFocused }
                            .focusable()
                            .background(
                                when {
                                    focused -> Color.White
                                    isSelected -> EmeraldLive
                                    else -> Color(0xFF0C241B)
                                }
                            )
                            .border(
                                width = if (focused) 2.5.dp else 1.5.dp,
                                color = if (focused) BeinFocusGlow else EmeraldLive,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onSelectCategory("__CONTINUE_WATCHING__") }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⏯ ما شاهدته / متابعة (${watchedItemsList.size})",
                            color = if (focused || isSelected) Color.Black else EmeraldLive,
                            fontSize = if (compact) 10.sp else 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
            itemsIndexed(categories, key = { idx, cat -> "${idx}_${cat.name}" }) { _, cat ->
                val isSelected = selectedCategory == cat.name
                var focused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .height(if (compact) 28.dp else 34.dp)
                        .scale(if (focused) 1.05f else 1.0f)
                        .clip(RoundedCornerShape(14.dp))
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .background(
                            when {
                                focused -> Color.White
                                isSelected -> BeinPrimaryPurple
                                else -> BeinCard
                            }
                        )
                        .border(
                            width = if (focused) 2.5.dp else 1.dp,
                            color = if (focused) BeinFocusGlow else if (isSelected) Color.White else BeinBorder,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelectCategory(cat.name) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${cat.name} (${cat.count})",
                        color = if (focused) Color.Black else Color.White,
                        fontSize = if (compact) 10.sp else 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(emptyHintAr, color = Color.LightGray, fontSize = if (compact) 11.sp else 14.sp, textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BeinFocusableButton(
                            text = "فتح فضاء الزبون 📲",
                            isPrimary = true,
                            compact = compact,
                            onClick = onOpenXtream
                        )
                        BeinFocusableButton(
                            text = "تحديث الآن 🔄",
                            isPrimary = false,
                            compact = compact,
                            onClick = onRefresh
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(5),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(windowedItems, key = { idx, item -> "${idx}_${item.stableKey}" }) { idx, item ->
                    val itemMod = if (idx == targetWatchedIdx) {
                        Modifier.focusRequester(lastWatchedFocusRequester)
                    } else {
                        Modifier
                    }
                    BeinChannelCard(
                        channel = item,
                        isFavorite = favSet.contains(item.stableKey),
                        isLastWatched = item.stableKey == lastWatchedKey,
                        watchProgress = vodWatchProgressMap[item.stableKey],
                        compact = compact,
                        modifier = itemMod,
                        onClick = { onPlay(item) },
                        onToggleFavorite = { onToggleFavorite(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ExitConfirmationModal(
    lang: AppUiLanguage,
    compact: Boolean,
    onStay: () -> Unit,
    onConfirmExit: () -> Unit
) {
    var stayFocused by remember { mutableStateOf(true) }
    var exitFocused by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onStay) {
        Column(
            modifier = Modifier
                .widthIn(min = if (compact) 280.dp else 330.dp, max = if (compact) 380.dp else 440.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(if (compact) 18.dp else 24.dp))
                .background(BeinSurface)
                .border(2.dp, BeinHighlightPurple, RoundedCornerShape(if (compact) 18.dp else 24.dp))
                .padding(if (compact) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = AppStrings.tr(lang, "تأكيد الخروج من التطبيق", "Confirmer la fermeture", "Confirm Exit"),
                color = Color.White,
                fontSize = if (compact) 15.sp else 19.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(if (compact) 8.dp else 14.dp))

            BrandLogoBadge(
                modifier = Modifier.size(if (compact) 62.dp else 86.dp),
                cornerRadius = if (compact) 14.dp else 20.dp
            )

            Spacer(Modifier.height(if (compact) 14.dp else 22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (stayFocused) Color.White else BeinPrimaryPurple,
                        contentColor = if (stayFocused) Color.Black else Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(if (compact) 42.dp else 48.dp)
                        .scale(if (stayFocused) 1.05f else 1.0f)
                        .border(2.5.dp, if (stayFocused) BeinFocusGlow else Color.Transparent, RoundedCornerShape(24.dp))
                        .onFocusChanged { stayFocused = it.isFocused }
                        .focusable()
                ) {
                    Text(
                        AppStrings.tr(lang, "البقاء", "Rester", "Stay"),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = if (compact) 11.sp else 13.sp
                    )
                }

                Button(
                    onClick = onConfirmExit,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (exitFocused) CrimsonAlert else Color(0xFF281220),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(if (compact) 42.dp else 48.dp)
                        .scale(if (exitFocused) 1.05f else 1.0f)
                        .border(2.5.dp, if (exitFocused) Color.White else CrimsonAlert, RoundedCornerShape(24.dp))
                        .onFocusChanged { exitFocused = it.isFocused }
                        .focusable()
                ) {
                    Text(
                        AppStrings.tr(lang, "خروج", "Quitter", "Exit"),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = if (compact) 11.sp else 13.sp
                    )
                }
            }
        }
    }
}

/**
 * Top Header with Restored Subscription Duration & Expiry Badge!
 */
@Composable
private fun TopBeinHeader(
    deviceId: String,
    subscription: SubscriptionInfo?,
    vpnStatus: CloudflareTunnelStatus,
    totalCount: Int,
    moviesCount: Int,
    seriesCount: Int,
    bouquetCount: Int,
    isLoading: Boolean,
    isCheckingValidityOnly: Boolean = false,
    networkSpeedLabel: String = "",
    uiLayoutStyle: UiLayoutStyle = UiLayoutStyle.MODERN_ROYAL,
    showIboHomeButton: Boolean = false,
    onReturnToIboDashboard: () -> Unit = {},
    onToggleUiLayoutStyle: () -> Unit = {},
    useGrid: Boolean,
    lang: AppUiLanguage,
    playlistMode: PlaylistDisplayMode,
    playlists: List<XtreamAccountConfig>,
    activePlaylistFilterId: String,
    showScreenCode: Boolean,
    compact: Boolean,
    onSelectLanguage: (AppUiLanguage) -> Unit,
    onCyclePlaylistSource: () -> Unit,
    onToggleScreenCodeVisibility: () -> Unit,
    onOpenBouquets: () -> Unit,
    onOpenXtream: () -> Unit,
    onOpenPlaylistSelectorModal: () -> Unit,
    onRefreshValidityOnly: () -> Unit,
    onOpenActivation: () -> Unit,
    onRefresh: () -> Unit
) {
    val isSingleListMode = playlistMode == PlaylistDisplayMode.SEPARATE_LISTS && activePlaylistFilterId != "ALL"
    val activeSourceLabel = remember(playlistMode, activePlaylistFilterId, playlists, lang) {
        if (playlistMode == PlaylistDisplayMode.MERGE_ALL || activePlaylistFilterId == "ALL") {
            AppStrings.tr(lang, "🔀 مدمجة", "🔀 Fusionnées", "🔀 Merged")
        } else if (activePlaylistFilterId == "PORTAL_MAIN") {
            AppStrings.tr(lang, "📡 الرئيسية", "📡 Principale", "📡 Main")
        } else {
            val found = playlists.find { it.id == activePlaylistFilterId }?.serverName ?: "XTREAM"
            "⚡ ${found.take(14)}"
        }
    }

    val refreshBtnLabel = remember(isSingleListMode, lang) {
        if (isSingleListMode) {
            AppStrings.tr(lang, "تحديث القائمة", "Actualiser liste", "Refresh List")
        } else {
            AppStrings.tr(lang, "تحديث الكل", "Actualiser tout", "Refresh All")
        }
    }

    // Check if Player Subscription OR any Xtream playlist is expiring within 5 days -> Red Alert Frame!
    val isPlayerExpiringSoon = remember(subscription) {
        subscription != null && (!subscription.active || subscription.daysLeft <= 5 || ExpiryAlertHelper.isDateExpiringSoon(subscription.expireDate))
    }
    val isAnyXtreamExpiringSoon = remember(playlists) {
        playlists.any { it.enabled && ExpiryAlertHelper.isDateExpiringSoon(it.expDate) }
    }
    val hasExpiryAlert = isPlayerExpiringSoon || isAnyXtreamExpiringSoon

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 44.dp else 52.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF0B0516), BeinSurface, Color(0xFF07030E))
                )
            )
            .border(1.dp, BeinBorder, RectangleShape)
            .padding(horizontal = if (compact) 6.dp else 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Right/Start Brand + VPN Shield (Compact & Non-Overflowing)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp)
        ) {
            BrandLogoBadge(
                modifier = Modifier.size(if (compact) 24.dp else 30.dp),
                cornerRadius = if (compact) 6.dp else 8.dp
            )
            Column(verticalArrangement = Arrangement.Center) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = APP_BRAND_NAME,
                        color = Color.White,
                        fontSize = if (compact) 10.sp else 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                    if (vpnStatus.connected) {
                        Text(
                            text = "🔒 VPN",
                            color = EmeraldLive,
                            fontSize = if (compact) 7.sp else 8.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .background(Color(0xFF0C221C), RoundedCornerShape(4.dp))
                                .border(1.dp, EmeraldLive.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    if (networkSpeedLabel.isNotBlank()) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = "⬇ $networkSpeedLabel",
                                color = EmeraldLive,
                                fontSize = if (compact) 7.sp else 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier
                                    .background(Color(0xFF081C15), RoundedCornerShape(4.dp))
                                    .border(1.dp, EmeraldLive.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                if (!compact) {
                    Text(
                        text = "$totalCount ${AppStrings.tr(lang, "قناة", "Ch", "Ch")} • $moviesCount ${AppStrings.tr(lang, "فيلم", "Films", "M")} • $seriesCount ${AppStrings.tr(lang, "مسلسل", "Séries", "S")}",
                        color = Color(0xFFC4B5FD),
                        fontSize = 8.sp,
                        maxLines = 1
                    )
                }
            }
        }

        // 2. Center & Action Bar — Fitted cleanly inside screen frame (Zero Overflow!)
        Row(
            modifier = Modifier.weight(1f, fill = false).padding(start = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A. SEPARATE DEDICATED FRAME 1: Player Remaining Days Badge (إطار مستقل خاص بعدد الأيام المتبقية للمشغل)
            var daysFocused by remember { mutableStateOf(false) }
            val daysCount = subscription?.daysLeft ?: 30
            val daysText = AppStrings.tr(lang, "⏳ $daysCount يوم", "⏳ $daysCount j", "⏳ $daysCount d")
            Box(
                modifier = Modifier
                    .height(if (compact) 27.dp else 32.dp)
                    .scale(if (daysFocused) 1.05f else 1.0f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        when {
                            daysFocused -> Color.White
                            hasExpiryAlert -> Color(0xFF2D0C16)
                            else -> Color(0xFF0C241B)
                        }
                    )
                    .border(
                        width = if (daysFocused) 2.dp else if (hasExpiryAlert) 1.8.dp else 1.2.dp,
                        color = when {
                            daysFocused -> BeinFocusGlow
                            hasExpiryAlert -> CrimsonAlert
                            else -> EmeraldLive
                        },
                        shape = RoundedCornerShape(9.dp)
                    )
                    .onFocusChanged { daysFocused = it.isFocused }
                    .focusable()
                    .clickable { SafeClickGuard.runThrottled("TOP_DAYS", 200L) { onOpenActivation() } }
                    .padding(horizontal = if (compact) 7.dp else 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = daysText,
                    color = if (daysFocused) Color.Black else if (hasExpiryAlert) Color(0xFFFCA5A5) else EmeraldLive,
                    fontSize = if (compact) 9.sp else 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
            }

            // B. SEPARATE DEDICATED FRAME 2: Screen Code Badge (إطار مستقل خاص برمز الشاشة مفصول تماماً عن الأيام)
            var codeFocused by remember { mutableStateOf(false) }
            val codeText = if (showScreenCode) deviceId else "DEV-••••-••••"
            Box(
                modifier = Modifier
                    .height(if (compact) 27.dp else 32.dp)
                    .scale(if (codeFocused) 1.05f else 1.0f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (codeFocused) Color.White else Color(0xFF130926))
                    .border(
                        width = if (codeFocused) 2.dp else 1.2.dp,
                        color = if (codeFocused) BeinFocusGlow else BeinHighlightPurple,
                        shape = RoundedCornerShape(9.dp)
                    )
                    .onFocusChanged { codeFocused = it.isFocused }
                    .focusable()
                    .clickable { SafeClickGuard.runThrottled("TOP_CODE", 200L) { onOpenActivation() } }
                    .padding(horizontal = if (compact) 7.dp else 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = codeText,
                            color = if (codeFocused) Color.Black else Color.White,
                            fontSize = if (compact) 8.sp else 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                    }
                    Icon(
                        imageVector = if (showScreenCode) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "إظهار/إخفاء الرمز",
                        tint = if (codeFocused) Color.Black else Color(0xFFD8B4FE),
                        modifier = Modifier
                            .size(if (compact) 11.dp else 13.dp)
                            .clickable { onToggleScreenCodeVisibility() }
                    )
                }
            }

            // C. MIDDLE SPACE UTILITY TOOL 1: Instant Validity Check Only ("⚡ فحص الصلاحية" — بدون سحب القنوات)
            SlimTopBarPill(
                text = if (isCheckingValidityOnly) "⏳..." else AppStrings.tr(lang, "⚡ فحص الصلاحية", "⚡ Validité", "⚡ Check Validity"),
                isHighlighted = false,
                compact = compact,
                onClick = onRefreshValidityOnly
            )

            // D. MIDDLE SPACE UTILITY TOOL 2: Multi-Playlist Picker Modal ("🎛️ اختيار القوائم")
            if (playlists.size > 1) {
                SlimTopBarPill(
                    text = AppStrings.tr(lang, "🎛️ اختيار القوائم (${playlists.size})", "🎛️ Listes (${playlists.size})", "🎛️ Pick Lists (${playlists.size})"),
                    isHighlighted = false,
                    compact = compact,
                    onClick = onOpenPlaylistSelectorModal
                )
            }

            // E. Playlist Mode / Source Switcher Pill
            SlimTopBarPill(
                text = activeSourceLabel,
                isHighlighted = isSingleListMode,
                compact = compact,
                onClick = onCyclePlaylistSource
            )

            // F. Context-Aware Content Refresh Button (تحديث القائمة الحالية أو تحديث الكل)
            var refreshFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .height(if (compact) 27.dp else 32.dp)
                    .scale(if (refreshFocused) 1.05f else 1.0f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (refreshFocused) {
                            Brush.horizontalGradient(listOf(Color.White, Color.White))
                        } else {
                            Brush.horizontalGradient(listOf(Color(0xFF3B156E), Color(0xFF240B47)))
                        }
                    )
                    .border(
                        width = if (refreshFocused) 2.dp else 1.2.dp,
                        color = if (refreshFocused) BeinFocusGlow else Color(0xFFA78BFA),
                        shape = RoundedCornerShape(9.dp)
                    )
                    .onFocusChanged { refreshFocused = it.isFocused }
                    .focusable()
                    .clickable { SafeClickGuard.runThrottled("TOP_REFRESH", 300L) { onRefresh() } }
                    .padding(horizontal = if (compact) 7.dp else 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = if (refreshFocused) Color.Black else Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(if (compact) 10.dp else 12.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = if (refreshFocused) Color.Black else Color.White,
                            modifier = Modifier.size(if (compact) 11.dp else 13.dp)
                        )
                    }
                    Text(
                        text = refreshBtnLabel,
                        color = if (refreshFocused) Color.Black else Color.White,
                        fontSize = if (compact) 8.sp else 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                }
            }

            // F.2 Return to Classic IBO Dashboard or Switch UI Theme (كلاسيكي IBO / متطور)
            if (showIboHomeButton) {
                SlimTopBarPill(
                    text = AppStrings.tr(lang, "🏠 واجهة IBO", "🏠 Accueil IBO", "🏠 IBO Home"),
                    isHighlighted = true,
                    compact = compact,
                    onClick = onReturnToIboDashboard
                )
            } else {
                SlimTopBarPill(
                    text = if (uiLayoutStyle == UiLayoutStyle.CLASSIC_IBO_PRO) {
                        AppStrings.tr(lang, "🎨 متطور", "🎨 Moderne", "🎨 Modern")
                    } else {
                        AppStrings.tr(lang, "🏛️ كلاسيكي IBO", "🏛️ IBO Classique", "🏛️ Classic IBO")
                    },
                    isHighlighted = uiLayoutStyle == UiLayoutStyle.CLASSIC_IBO_PRO,
                    compact = compact,
                    onClick = onToggleUiLayoutStyle
                )
            }

            // G. Compact Language Switcher (Fits inside frame without overflowing!)
            SlimTopBarPill(
                text = "🌐 ${lang.nativeLabel}",
                isHighlighted = false,
                compact = compact,
                onClick = {
                    val values = AppUiLanguage.values()
                    val next = values[(lang.ordinal + 1) % values.size]
                    onSelectLanguage(next)
                }
            )
        }
    }
}

@Composable
private fun SlimTopBarPill(
    text: String,
    isHighlighted: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .height(if (compact) 27.dp else 32.dp)
            .scale(if (focused) 1.05f else 1.0f)
            .clip(RoundedCornerShape(9.dp))
            .background(
                when {
                    focused -> Color.White
                    isHighlighted -> BeinPrimaryPurple
                    else -> BeinCard
                }
            )
            .border(
                width = if (focused) 2.2.dp else 1.dp,
                color = if (focused) BeinFocusGlow else if (isHighlighted) BeinHighlightPurple else BeinBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    ev.nativeKeyEvent.repeatCount == 0 &&
                    (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    SafeClickGuard.runThrottled("UI_BTN_CLICK", 200L) { onClick() }
                    true
                } else false
            }
            .focusable()
            .clickable { SafeClickGuard.runThrottled("UI_BTN_CLICK", 200L) { onClick() } }
            .padding(horizontal = if (compact) 7.dp else 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (focused) Color.Black else Color.White,
            fontSize = if (compact) 8.sp else 10.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}

/**
 * Unified Popup Modal for:
 * 1) Screen Code (DEV-XXXX-XXXX) with Copy & Show/Hide Toggle
 * 2) Player Subscription Expiry Date & Remaining Days (Red Border if <= 5 days!)
 * 3) Xtream Server(s) Expiry Date(s) & Status (Red Border if <= 5 days!)
 */
@Composable
private fun UnifiedValidityAndScreenCodeModal(
    deviceId: String,
    deviceName: String,
    subscription: SubscriptionInfo?,
    playlists: List<XtreamAccountConfig>,
    showScreenCode: Boolean,
    lang: AppUiLanguage,
    compact: Boolean,
    onToggleScreenCode: () -> Unit,
    onOpenCustomerSpace: () -> Unit,
    onOpenFullActivation: () -> Unit,
    onRedeemCode: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var modalVoucherCode by remember { mutableStateOf("") }
    val playerExpiringSoon = subscription != null && (!subscription.active || subscription.daysLeft <= 5 || ExpiryAlertHelper.isDateExpiringSoon(subscription.expireDate))

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = if (compact) 540.dp else 640.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0F061E), BeinSurface, BeinDarkBg)
                    )
                )
                .border(2.dp, if (playerExpiringSoon) CrimsonAlert else BeinHighlightPurple, RoundedCornerShape(22.dp))
                .padding(if (compact) 14.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = AppStrings.tr(
                        lang,
                        "👑 بطاقة الهوية وصلاحية المشغل وسيرفرات XTREAM",
                        "👑 Identité, Validité du Lecteur & Serveurs XTREAM",
                        "👑 Screen ID, Player Validity & XTREAM Servers"
                    ),
                    color = Color.White,
                    fontSize = if (compact) 13.sp else 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                }
            }

            // 1. Screen Code Card (رمز الشاشة الدائم)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BeinDarkBg)
                    .border(1.5.dp, BeinHighlightPurple, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 11.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = AppStrings.tr(lang, "🖥️ رمز الشاشة الثابت (لا يتغير حتى بعد ضبط المصنع)", "🖥️ Code Écran Permanent", "🖥️ Permanent Hardware Screen Code"),
                    color = Color(0xFFD8B4FE),
                    fontSize = if (compact) 10.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = if (showScreenCode) deviceId else "DEV-••••-••••",
                            color = Color.White,
                            fontSize = if (compact) 17.sp else 21.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp
                        )
                    }
                    SlimTopBarPill(
                        text = if (showScreenCode) "👁 إخفاء" else "👁 إظهار",
                        isHighlighted = false,
                        compact = true,
                        onClick = onToggleScreenCode
                    )
                    SlimTopBarPill(
                        text = "📋 نسخ",
                        isHighlighted = true,
                        compact = true,
                        onClick = { copyDeviceCodeToClipboard(context, deviceId) }
                    )
                }
            }

            // 2. Player Subscription Validity Card (تتحول لإطار أحمر عند اقتراب انتهاء الصلاحية!)
            if (subscription != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (playerExpiringSoon) Color(0xFF280B14) else Color(0xFF140B26))
                        .border(
                            width = if (playerExpiringSoon) 2.2.dp else 1.5.dp,
                            color = if (playerExpiringSoon) CrimsonAlert else EmeraldLive,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 11.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (playerExpiringSoon) {
                            AppStrings.tr(lang, "⚠️ تنبيه: اقترب انتهاء صلاحية اشتراك المشغل!", "⚠️ Attention : Abonnement bientôt expiré !", "⚠️ Alert: Player Subscription Expiring Soon!")
                        } else {
                            AppStrings.tr(lang, "✅ صلاحية اشتراك المشغل (LIVE PRO)", "✅ Validité du Lecteur LIVE PRO", "✅ LIVE PRO Player Subscription Validity")
                        },
                        color = if (playerExpiringSoon) Color(0xFFFCA5A5) else EmeraldLive,
                        fontSize = if (compact) 11.sp else 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = AppStrings.tr(
                            lang,
                            "الأيام المتبقية: ${subscription.daysLeft} يوم" + (if (subscription.expireDate.isNotBlank()) " • تاريخ الانتهاء: ${subscription.expireDate}" else ""),
                            "Jours restants : ${subscription.daysLeft} j • Expire le : ${subscription.expireDate}",
                            "Days Left: ${subscription.daysLeft} days • Expires: ${subscription.expireDate}"
                        ),
                        color = Color.White,
                        fontSize = if (compact) 11.sp else 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // 3. Xtream Server(s) Validity List (كل سيرفر يتحول إطاره للأحمر إذا اقترب انتهاء صلاحيته!)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BeinCard)
                    .border(1.dp, BeinBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = AppStrings.tr(
                        lang,
                        "⚡ صلاحية سيرفرات XTREAM المرتبطة (${playlists.size})",
                        "⚡ Validité des serveurs XTREAM (${playlists.size})",
                        "⚡ Linked XTREAM Servers Validity (${playlists.size})"
                    ),
                    color = Color(0xFFD8B4FE),
                    fontSize = if (compact) 11.sp else 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                if (playlists.isEmpty()) {
                    Text(
                        text = AppStrings.tr(lang, "لا توجد قائمة XTREAM مضافة حالياً", "Aucune liste XTREAM", "No XTREAM list added yet"),
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                } else {
                    playlists.take(3).forEach { pl ->
                        val xExpiring = ExpiryAlertHelper.isDateExpiringSoon(pl.expDate)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (xExpiring) Color(0xFF2A0C15) else BeinSurface)
                                .border(
                                    width = if (xExpiring) 2.dp else 1.dp,
                                    color = if (xExpiring) CrimsonAlert else BeinBorder,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = pl.serverName.ifBlank { "XTREAM VIP" },
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = if (compact) 10.sp else 12.sp
                            )
                            Text(
                                text = if (pl.expDate.isNotBlank()) {
                                    (if (xExpiring) "⚠️ ينتهي: " else "⏳ ينتهي: ") + pl.expDate
                                } else {
                                    "متصل ✓"
                                },
                                color = if (xExpiring) Color(0xFFFCA5A5) else EmeraldLive,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = if (compact) 10.sp else 12.sp
                            )
                        }
                    }
                }
            }

            // Direct Voucher Input Row inside Unified Modal
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BeinDarkBg)
                    .border(1.2.dp, BeinHighlightPurple, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = modalVoucherCode,
                    onValueChange = { modalVoucherCode = formatVoucherCodeInput(it) },
                    placeholder = {
                        Text(
                            AppStrings.tr(lang, "رمز القسيمة: A1B2-C3D4", "Code: A1B2-C3D4", "Voucher: A1B2-C3D4"),
                            color = Color(0xFFBFA7F2),
                            fontSize = if (compact) 10.sp else 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = if (compact) 13.sp else 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp,
                        textAlign = TextAlign.Center
                    ),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                )
                BeinFocusableButton(
                    text = AppStrings.tr(lang, "📋 لصق", "📋 Coller", "📋 Paste"),
                    isPrimary = false,
                    compact = true,
                    onClick = {
                        val pasted = pasteVoucherFromClipboard(context)
                        if (pasted.isNotBlank()) modalVoucherCode = pasted
                    }
                )
                BeinFocusableButton(
                    text = AppStrings.tr(lang, "تفعيل ✓", "Activer ✓", "Activate ✓"),
                    isPrimary = true,
                    compact = true,
                    onClick = { onRedeemCode(modalVoucherCode) }
                )
            }

            // Bottom Action Buttons inside Unified Modal
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BeinFocusableButton(
                    text = AppStrings.tr(lang, "📲 إدارة القوائم", "📲 Gérer les listes", "📲 Manage Lists"),
                    isPrimary = true,
                    compact = true,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenCustomerSpace
                )
                BeinFocusableButton(
                    text = AppStrings.tr(lang, "💳 تفعيل قسيمة", "💳 Activer un code", "💳 Redeem Voucher"),
                    isPrimary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenFullActivation
                )
                BeinFocusableButton(
                    text = AppStrings.tr(lang, "🔄 تحديث الصلاحية فقط", "🔄 Validité seule", "🔄 Refresh Validity Only"),
                    isPrimary = false,
                    compact = true,
                    onClick = onRefresh
                )
            }
        }
    }
}

/**
 * Professional Animated Waiting / Loading Overlay when refreshing content or entering the app.
 */


/**
 * Interactive Multi-Playlist Selector Modal (صفحة منبثقة احترافية لاختيار القوائم المراد تحديثها):
 * Shown automatically on first-time launch when multiple Xtream playlists are linked to the Screen Code,
 * or anytime via the "🎛️ اختيار القوائم" button in the top bar.
 * Allows the customer to update:
 *   1) A single playlist ("تحديث هذه القائمة فقط ⚡")
 *   2) Multiple selected playlists via checkboxes ("تحديث القوائم المحددة ✅")
 *   3) All playlists at once ("تحديث الجميع 🚀")
 */
@Composable
private fun FirstTimeMultiPlaylistSelectorModal(
    playlists: List<XtreamAccountConfig>,
    isFirstTime: Boolean,
    lang: AppUiLanguage,
    compact: Boolean,
    onSyncSingle: (XtreamAccountConfig) -> Unit,
    onSyncSelected: (Set<String>, Boolean) -> Unit,
    onSyncAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val selectedIds = remember(playlists) {
        mutableStateOf(playlists.filter { it.enabled }.map { it.id }.toSet())
    }
    var includePortalMain by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 340.dp, max = if (compact) 580.dp else 680.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF130826), BeinSurface, BeinDarkBg)
                    )
                )
                .border(2.5.dp, BeinHighlightPurple, RoundedCornerShape(22.dp))
                .padding(if (compact) 14.dp else 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isFirstTime) {
                            AppStrings.tr(
                                lang,
                                "🚀 تم العثور على عدة سيرفرات XTREAM مرتبطة بشاشتك (${playlists.size})",
                                "🚀 Plusieurs serveurs XTREAM détectés (${playlists.size})",
                                "🚀 Multiple XTREAM Servers Linked to Your Screen (${playlists.size})"
                            )
                        } else {
                            AppStrings.tr(
                                lang,
                                "🎛️ اختيار القوائم المراد تحديثها (${playlists.size})",
                                "🎛️ Choisir les listes à actualiser (${playlists.size})",
                                "🎛️ Select Playlists to Refresh (${playlists.size})"
                            )
                        },
                        color = Color.White,
                        fontSize = if (compact) 13.sp else 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = AppStrings.tr(
                            lang,
                            "اختر تحديث قائمة واحدة، أو حدّد عدة قوائم، أو اضغط «تحديث الجميع»",
                            "Actualisez une seule liste, plusieurs listes sélectionnées, ou toutes les listes",
                            "Choose to update a single playlist, multiple selected playlists, or all playlists"
                        ),
                        color = Color(0xFFD8B4FE),
                        fontSize = if (compact) 10.sp else 12.sp
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (compact) 190.dp else 260.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(playlists, key = { idx, pl -> "${idx}_${pl.id}" }) { idx, pl ->
                    val isChecked = pl.id in selectedIds.value
                    var rowFocused by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    rowFocused -> Color(0xFF2B1454)
                                    isChecked -> Color(0xFF1A0D33)
                                    else -> BeinCard
                                }
                            )
                            .border(
                                width = if (rowFocused) 2.dp else 1.2.dp,
                                color = if (rowFocused) BeinFocusGlow else if (isChecked) EmeraldLive else BeinBorder,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .onFocusChanged { rowFocused = it.isFocused }
                            .focusable()
                            .clickable {
                                val curr = selectedIds.value
                                selectedIds.value = if (isChecked) curr - pl.id else curr + pl.id
                            }
                            .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (compact) 20.dp else 24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isChecked) EmeraldLive else Color(0xFF0E071B))
                                    .border(1.5.dp, if (isChecked) EmeraldLive else Color.Gray, RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isChecked) {
                                    Text(
                                        text = "✓",
                                        color = Color.Black,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = if (compact) 11.sp else 13.sp
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "${idx + 1}. " + pl.serverName.ifBlank { "XTREAM #" + (idx + 1) },
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = if (compact) 11.sp else 13.sp
                                )
                                Text(
                                    text = if (pl.expDate.isNotBlank()) "⏳ الصلاحية: ${pl.expDate} • ${pl.statusNote}" else pl.statusNote,
                                    color = Color(0xFFD8B4FE),
                                    fontSize = if (compact) 9.sp else 11.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        BeinFocusableButton(
                            text = AppStrings.tr(lang, "تحديث لوحدها ⚡", "Seule ⚡", "Update Only ⚡"),
                            isPrimary = true,
                            compact = true,
                            onClick = { onSyncSingle(pl) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BeinFocusableButton(
                    text = AppStrings.tr(
                        lang,
                        "✅ تحديث المحدد (${selectedIds.value.size})",
                        "✅ Actualiser sélection (${selectedIds.value.size})",
                        "✅ Update Selected (${selectedIds.value.size})"
                    ),
                    isPrimary = selectedIds.value.isNotEmpty(),
                    compact = compact,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (selectedIds.value.isNotEmpty()) {
                            onSyncSelected(selectedIds.value, includePortalMain)
                        }
                    }
                )
                BeinFocusableButton(
                    text = AppStrings.tr(
                        lang,
                        "🚀 تحديث الجميع (${playlists.size})",
                        "🚀 Tout actualiser (${playlists.size})",
                        "🚀 Update All (${playlists.size})"
                    ),
                    isPrimary = true,
                    compact = compact,
                    modifier = Modifier.weight(1f),
                    onClick = onSyncAll
                )
                BeinFocusableButton(
                    text = if (selectedIds.value.size == playlists.size) {
                        AppStrings.tr(lang, "إلغاء التحديد", "Tout désélectionner", "Unselect All")
                    } else {
                        AppStrings.tr(lang, "تحديد الكل", "Tout sélectionner", "Select All")
                    },
                    isPrimary = false,
                    compact = compact,
                    onClick = {
                        selectedIds.value = if (selectedIds.value.size == playlists.size) {
                            emptySet()
                        } else {
                            playlists.map { it.id }.toSet()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ProfessionalWaitingOverlay(
    lang: AppUiLanguage,
    compact: Boolean,
    syncProgress: SyncProgressState = SyncProgressState(),
    message: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x9905020A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 270.dp, max = if (compact) 380.dp else 440.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF110722), BeinSurface)
                    )
                )
                .border(2.dp, BeinHighlightPurple, RoundedCornerShape(22.dp))
                .padding(horizontal = 24.dp, vertical = if (compact) 16.dp else 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = BeinHighlightPurple,
                    strokeWidth = 3.5.dp,
                    modifier = Modifier.size(if (compact) 44.dp else 54.dp)
                )
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(if (compact) 26.dp else 32.dp)
                )
            }
            Text(
                text = AppStrings.tr(lang, "⏳ يرجى الانتظار...", "⏳ Veuillez patienter...", "⏳ Please wait..."),
                color = Color.White,
                fontSize = if (compact) 14.sp else 16.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = message,
                color = Color(0xFFD8B4FE),
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            if (syncProgress.progressPercent > 0) {
                LinearProgressIndicator(
                    progress = { (syncProgress.progressPercent / 100f).coerceIn(0.05f, 1f) },
                    color = EmeraldLive,
                    trackColor = Color(0xFF281545),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                Text(
                    text = buildString {
                        append("${syncProgress.progressPercent}%")
                        if (syncProgress.fetchedLiveCount > 0 || syncProgress.fetchedMovieCount > 0 || syncProgress.fetchedSeriesCount > 0) {
                            append(" • تم جلب ${syncProgress.fetchedLiveCount} قناة • ${syncProgress.fetchedMovieCount} فيلم • ${syncProgress.fetchedSeriesCount} مسلسل")
                        }
                    },
                    color = EmeraldLive,
                    fontSize = if (compact) 10.sp else 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun LandscapeSideNavigationRail(
    activeTab: MainTab,
    lang: AppUiLanguage,
    compact: Boolean,
    onSelectTab: (MainTab) -> Unit,
    onExitClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxHeight()
            .width(if (compact) 64.dp else 88.dp)
            .background(BeinSurface)
            .border(1.dp, BeinBorder, RectangleShape)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(MainTab.values().toList(), key = { it.name }) { tab ->
            val selected = activeTab == tab
            var focused by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp)
                    .scale(if (focused) 1.07f else 1.0f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            focused -> Color.White
                            selected -> BeinPrimaryPurple
                            else -> Color.Transparent
                        }
                    )
                    .border(
                        width = if (focused) 2.5.dp else if (selected) 1.5.dp else 0.dp,
                        color = if (focused) BeinFocusGlow else if (selected) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent { ev ->
                        if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                            (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                        ) {
                            onSelectTab(tab)
                            true
                        } else false
                    }
                    .focusable()
                    .clickable { SafeClickGuard.runThrottled("TAB_CLICK", 180L) { onSelectTab(tab) } }
                    .padding(vertical = if (compact) 6.dp else 8.dp, horizontal = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = when (tab) {
                        MainTab.LIVE -> Icons.Default.LiveTv
                        MainTab.BOUQUETS -> Icons.Default.Folder
                        MainTab.MOVIES -> Icons.Default.Movie
                        MainTab.SERIES -> Icons.Default.VideoLibrary
                        MainTab.XTREAM -> Icons.Default.CloudDownload
                        MainTab.FAVORITES -> Icons.Default.Favorite
                        MainTab.ACTIVATION -> Icons.Default.VpnKey
                        MainTab.SETTINGS -> Icons.Default.Settings
                    },
                    contentDescription = tab.label(lang),
                    tint = if (focused) Color.Black else Color.White,
                    modifier = Modifier.size(if (compact) 17.dp else 21.dp)
                )
                if (!compact) {
                    Text(
                        text = tab.label(lang),
                        color = if (focused) Color.Black else Color.White,
                        fontSize = 9.sp,
                        fontWeight = if (selected || focused) FontWeight.ExtraBold else FontWeight.Medium,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        item {
            var exitFocused by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp, vertical = 4.dp)
                    .scale(if (exitFocused) 1.07f else 1.0f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (exitFocused) CrimsonAlert else Color.Transparent)
                    .border(2.dp, if (exitFocused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
                    .onFocusChanged { exitFocused = it.isFocused }
                    .focusable()
                    .clickable { onExitClick() }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Exit",
                    tint = Color.White,
                    modifier = Modifier.size(if (compact) 16.dp else 20.dp)
                )
            }
        }
    }
}

/**
 * Responsive Bouquets Grid Directory View:
 * Adaptive card width + multi-line text so every bouquet name appears in full without clipping!
 */
@Composable
private fun BouquetsGridDirectoryView(
    bouquetSummaries: List<BouquetSummary>,
    selectedCategory: String,
    lang: AppUiLanguage,
    compact: Boolean,
    onSelectBouquet: (String) -> Unit
) {
    var filterText by remember { mutableStateOf("") }
    val filteredBouquets = remember(bouquetSummaries, filterText) {
        val q = filterText.trim()
        if (q.isEmpty()) bouquetSummaries
        else bouquetSummaries.filter { it.name.contains(q, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = if (compact) 10.dp else 16.dp,
                vertical = if (compact) 6.dp else 10.dp
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "📂 ${AppStrings.tr(lang, "دليل الباقات والمفضلة", "Répertoire des Bouquets", "Bouquets Directory")} (${bouquetSummaries.size})",
                    color = Color.White,
                    fontSize = if (compact) 14.sp else 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            CompactSearchBar(
                query = filterText,
                onQueryChange = { filterText = it },
                compact = compact,
                modifier = Modifier
                    .width(if (compact) 160.dp else 220.dp)
                    .height(if (compact) 30.dp else 36.dp)
            )
        }

        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (compact) 180.dp else 235.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(filteredBouquets, key = { idx, bq -> "${idx}_${bq.name}" }) { _, bq ->
                val isSelected = bq.name == selectedCategory
                var focused by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .defaultMinSize(minHeight = if (compact) 86.dp else 104.dp)
                        .scale(if (focused) 1.03f else 1.0f)
                        .clip(RoundedCornerShape(if (compact) 14.dp else 16.dp))
                        .background(
                            when {
                                focused -> Color.White
                                isSelected -> BeinPrimaryPurple
                                else -> BeinCard
                            }
                        )
                        .border(
                            width = if (focused) 2.8.dp else if (isSelected) 1.8.dp else 1.dp,
                            color = if (focused) BeinFocusGlow else if (isSelected) Color.White else BeinBorder,
                            shape = RoundedCornerShape(if (compact) 14.dp else 16.dp)
                        )
                        .onFocusChanged { focused = it.isFocused }
                        .onKeyEvent { ev ->
                            if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                    ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                            ) {
                                onSelectBouquet(bq.name)
                                true
                            } else false
                        }
                        .focusable()
                        .clickable { onSelectBouquet(bq.name) }
                        .padding(if (compact) 12.dp else 16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (bq.isFavoritesSpecial) Icons.Default.Favorite else Icons.Default.Folder,
                            contentDescription = null,
                            tint = if (focused) BeinPrimaryPurple else Color.White,
                            modifier = Modifier.size(if (compact) 20.dp else 24.dp)
                        )

                        Text(
                            text = "${bq.count} ${AppStrings.tr(lang, "قناة", "Chaînes", "Ch")}",
                            color = Color.White,
                            fontSize = if (compact) 10.sp else 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .background(
                                    if (focused) BeinPrimaryPurple else Color(0xFF120822),
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(Modifier.height(if (compact) 8.dp else 10.dp))

                    // Centered Full Bouquet Name without clipping!
                    Text(
                        text = bq.name,
                        color = if (focused) Color.Black else Color.White,
                        fontSize = if (compact) 13.sp else 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 3,
                        overflow = TextOverflow.Visible,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun BeinCatalogBrowserView(
    items: List<TvChannel>,
    channelsByBouquet: Map<String, List<TvChannel>>,
    bouquetSummaries: List<BouquetSummary>,
    favorites: List<TvChannel>,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    statusMessage: String,
    isLoading: Boolean,
    useGrid: Boolean,
    showBouquetSidebar: Boolean,
    onToggleBouquetSidebar: () -> Unit,
    gridState: LazyGridState,
    listState: LazyListState,
    bouquetListState: LazyListState,
    lastWatchedKey: String?,
    restoreFocusTrigger: Int,
    lang: AppUiLanguage,
    compact: Boolean,
    deviceId: String,
    onPlay: (TvChannel) -> Unit,
    onToggleFavorite: (TvChannel) -> Unit,
    onRefresh: () -> Unit
) {
    val favLabel = AppStrings.favoritesLabel(lang)
    val allLabel = AppStrings.allBouquetsLabel(lang)
    val lastWatchedFocusRequester = remember { FocusRequester() }

    // Instant O(1) bouquet slice lookup — zero UI-thread filtering across 60,000+ channels!
    val bouquetSlice = remember(items, channelsByBouquet, favorites, selectedCategory, favLabel, allLabel) {
        when (selectedCategory) {
            favLabel -> favorites
            allLabel, "الكل" -> items
            else -> channelsByBouquet[selectedCategory].orEmpty()
        }
    }

    val filteredItems by produceState(
        initialValue = bouquetSlice,
        bouquetSlice,
        searchQuery
    ) {
        val q = searchQuery.trim()
        value = if (q.isEmpty()) {
            bouquetSlice
        } else {
            withContext(Dispatchers.Default) {
                bouquetSlice.filter { ch ->
                    ch.name.contains(q, ignoreCase = true) ||
                        ch.group.contains(q, ignoreCase = true) ||
                        ch.number.toString() == q
                }
            }
        }
    }

    val targetWatchedIdx = remember(filteredItems, lastWatchedKey) {
        if (lastWatchedKey == null) -1 else filteredItems.indexOfFirst { it.stableKey == lastWatchedKey }
    }

    // Windowed Lazy Paging: render 120 items per batch and expand automatically as user scrolls!
    var visibleLimit by remember(selectedCategory, searchQuery, items.size) {
        val minNeeded = if (targetWatchedIdx >= 0) targetWatchedIdx + 80 else 120
        mutableStateOf(minNeeded.coerceAtLeast(120).coerceAtMost(filteredItems.size.coerceAtLeast(120)))
    }
    val windowedItems = remember(filteredItems, visibleLimit) {
        if (filteredItems.size <= visibleLimit) filteredItems
        else filteredItems.subList(0, visibleLimit)
    }

    val shouldLoadMore by remember(useGrid) {
        derivedStateOf {
            val lastVisible = if (useGrid) {
                gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            } else {
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            }
            lastVisible >= windowedItems.size - 24 && windowedItems.size < filteredItems.size
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            visibleLimit = (visibleLimit + 120).coerceAtMost(filteredItems.size)
        }
    }

    val favSet = remember(favorites) { favorites.map { it.stableKey }.toHashSet() }

    // Restore exact scroll & focus on the last-watched channel ONLY when returning from Player!
    LaunchedEffect(restoreFocusTrigger) {
        if (restoreFocusTrigger > 0 && targetWatchedIdx >= 0 && filteredItems.isNotEmpty()) {
            if (visibleLimit <= targetWatchedIdx) {
                visibleLimit = (targetWatchedIdx + 80).coerceAtMost(filteredItems.size)
            }
            delay(45L)
            if (useGrid) {
                runCatching { gridState.scrollToItem(targetWatchedIdx) }
            } else {
                runCatching { listState.scrollToItem(targetWatchedIdx) }
            }
            delay(65L)
            runCatching { lastWatchedFocusRequester.requestFocus() }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = if (compact) 8.dp else 12.dp,
                vertical = if (compact) 4.dp else 8.dp
            ),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
    ) {
        if (showBouquetSidebar && bouquetSummaries.size > 1) {
            Column(
                modifier = Modifier
                    .width(if (compact) 168.dp else 225.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BeinSurface)
                    .border(1.5.dp, BeinBorder, RoundedCornerShape(14.dp))
                    .padding(if (compact) 6.dp else 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📂 ${AppStrings.tr(lang, "الباقات", "Bouquets", "Bouquets")}",
                        color = Color.White,
                        fontSize = if (compact) 11.sp else 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                LazyColumn(
                    state = bouquetListState,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(bouquetSummaries, key = { idx, bq -> "${idx}_${bq.name}" }) { _, bq ->
                        val isSelected = selectedCategory == bq.name
                        var focused by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight()
                                .scale(if (focused) 1.03f else 1.0f)
                                .clip(RoundedCornerShape(9.dp))
                                .onFocusChanged { focused = it.isFocused }
                                .onKeyEvent { ev ->
                                    if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                        ev.nativeKeyEvent.repeatCount == 0 &&
                                        (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                            ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                                    ) {
                                        SafeClickGuard.runThrottled("BOUQUET_CLICK", 180L) { onSelectCategory(bq.name) }
                                        true
                                    } else false
                                }
                                .focusable()
                                .background(
                                    when {
                                        focused -> Color.White
                                        isSelected -> BeinPrimaryPurple
                                        else -> BeinCard
                                    }
                                )
                                .border(
                                    width = if (focused) 2.5.dp else if (isSelected) 1.5.dp else 0.dp,
                                    color = if (focused) BeinFocusGlow else if (isSelected) Color.White else Color.Transparent,
                                    shape = RoundedCornerShape(9.dp)
                                )
                                .clickable { SafeClickGuard.runThrottled("BOUQUET_CLICK", 180L) { onSelectCategory(bq.name) } }
                                .padding(
                                    horizontal = if (compact) 8.dp else 10.dp,
                                    vertical = if (compact) 7.dp else 9.dp
                                ),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = bq.name,
                                color = if (focused) Color.Black else Color.White,
                                fontSize = if (compact) 11.sp else 13.sp,
                                fontWeight = if (focused || isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Visible,
                                lineHeight = 16.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${bq.count}",
                                color = if (focused) BeinPrimaryPurple else Color(0xFFD8B4FE),
                                fontSize = if (compact) 9.sp else 11.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BeinFocusableButton(
                    text = if (showBouquetSidebar) {
                        "📂 ${AppStrings.tr(lang, "إخفاء الباقات", "Masquer", "Hide Bouquets")}"
                    } else {
                        "📂 ${AppStrings.tr(lang, "إظهار الباقات", "Afficher", "Show Bouquets")}"
                    },
                    isPrimary = showBouquetSidebar,
                    compact = true,
                    onClick = onToggleBouquetSidebar
                )

                CompactSearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchChange,
                    compact = compact,
                    modifier = Modifier
                        .widthIn(
                            min = if (compact) 130.dp else 170.dp,
                            max = if (compact) 200.dp else 250.dp
                        )
                        .height(if (compact) 30.dp else 36.dp)
                )

                Text(
                    text = "$selectedCategory (${filteredItems.size})",
                    color = Color(0xFFD8B4FE),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 11.sp else 13.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).basicMarquee()
                )
            }

            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))

            if (filteredItems.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = AppStrings.tr(lang, "لا توجد قنوات مطابقة حالياً", "Aucune chaîne trouvée", "No matching channels"),
                            color = Color.White,
                            fontSize = if (compact) 14.sp else 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "📞 علي سات : $ALI_SAT_PHONE_NUMBER • رمز الشاشة: $deviceId",
                            color = Color(0xFFD8B4FE),
                            fontSize = if (compact) 11.sp else 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(10.dp))
                        BeinFocusableButton(
                            text = AppStrings.tr(lang, "تحديث القنوات الآن 🔄", "Actualiser maintenant 🔄", "Refresh Channels Now 🔄"),
                            isPrimary = true,
                            compact = compact,
                            onClick = onRefresh
                        )
                    }
                }
            } else if (useGrid) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(4),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(windowedItems, key = { idx, ch -> "${idx}_${ch.stableKey}" }) { idx, ch ->
                        val itemMod = if (idx == targetWatchedIdx) {
                            Modifier.focusRequester(lastWatchedFocusRequester)
                        } else {
                            Modifier
                        }
                        BeinChannelCard(
                            channel = ch,
                            isFavorite = favSet.contains(ch.stableKey),
                            isLastWatched = ch.stableKey == lastWatchedKey,
                            compact = compact,
                            modifier = itemMod,
                            onClick = { onPlay(ch) },
                            onToggleFavorite = { onToggleFavorite(ch) }
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(windowedItems, key = { idx, ch -> "${idx}_${ch.stableKey}" }) { idx, ch ->
                        val itemMod = if (idx == targetWatchedIdx) {
                            Modifier.focusRequester(lastWatchedFocusRequester)
                        } else {
                            Modifier
                        }
                        ChannelListRow(
                            channel = ch,
                            isFavorite = favSet.contains(ch.stableKey),
                            isLastWatched = ch.stableKey == lastWatchedKey,
                            compact = compact,
                            modifier = itemMod,
                            onClick = { onPlay(ch) },
                            onToggleFavorite = { onToggleFavorite(ch) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(BeinCard)
            .border(
                width = if (isFocused) 2.5.dp else 1.dp,
                color = if (isFocused) BeinFocusGlow else BeinBorder,
                shape = RoundedCornerShape(18.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .padding(horizontal = if (compact) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(if (compact) 13.dp else 16.dp)
        )
        Spacer(Modifier.width(5.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            if (query.isEmpty()) {
                Text(
                    text = "بحث...",
                    color = Color.Gray,
                    fontSize = if (compact) 10.sp else 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = if (compact) 10.sp else 12.sp,
                    fontWeight = FontWeight.Medium
                ),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "مسح",
                tint = Color.LightGray,
                modifier = Modifier
                    .size(if (compact) 13.dp else 16.dp)
                    .clickable { onQueryChange("") }
            )
        }
    }
}

/**
 * Clean beIN Dark Purple Channel Card:
 * • NO '#' or Channel Numbers on the logo/card in the main UI!
 * • Full Channel Name & Bouquet Name displayed clearly without edge clipping!
 */
@Composable
private fun BeinChannelCard(
    channel: TvChannel,
    isFavorite: Boolean,
    isLastWatched: Boolean = false,
    watchProgress: VodWatchProgress? = null,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .scale(if (isFocused) 1.04f else 1.0f)
            .clip(RoundedCornerShape(if (compact) 12.dp else 16.dp))
            .background(
                when {
                    isFocused -> BeinPrimaryPurple
                    isLastWatched -> Color(0xFF130926)
                    else -> BeinCard
                }
            )
            .border(
                width = if (isFocused) 2.8.dp else if (isLastWatched) 1.8.dp else 1.dp,
                color = if (isFocused) BeinFocusGlow else if (isLastWatched) BeinHighlightPurple else BeinBorder,
                shape = RoundedCornerShape(if (compact) 12.dp else 16.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    ev.nativeKeyEvent.repeatCount == 0 &&
                    (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    SafeClickGuard.runThrottled("UI_BTN_CLICK", 200L) { onClick() }
                    true
                } else false
            }
            .focusable()
            .clickable { SafeClickGuard.runThrottled("UI_BTN_CLICK", 200L) { onClick() } }
    ) {
        val isVodPoster = channel.contentType != StreamContentType.LIVE_TV
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(
                    if (isVodPoster) {
                        if (compact) 118.dp else 156.dp
                    } else {
                        if (compact) 88.dp else 118.dp
                    }
                )
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0C0518), Color(0xFF030108))
                    )
                )
                .padding(if (isVodPoster) 4.dp else if (compact) 5.dp else 7.dp)
        ) {
            ChannelAvatar(
                name = channel.name,
                logoUrl = channel.logo,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center),
                cornerRadius = if (compact) 10.dp else 14.dp,
                contentScale = if (isVodPoster) ContentScale.Crop else ContentScale.Fit,
                innerPadding = if (isVodPoster) 0.dp else 3.dp
            )

            // Only show rating star if it's a Movie/Series with a rating — NO '#' or numbers on Live TV logos!
            if (channel.contentType != StreamContentType.LIVE_TV && channel.rating.isNotBlank()) {
                Text(
                    text = "★ ${channel.rating}",
                    color = Color.White,
                    fontSize = if (compact) 9.sp else 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(BeinPrimaryPurple, RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(if (compact) 22.dp else 28.dp)
                    .clip(CircleShape)
                    .background(Color(0x9907050C))
                    .clickable { onToggleFavorite() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "مفضلة",
                    tint = if (isFavorite) Color(0xFFFF4D6D) else Color.White,
                    modifier = Modifier.size(if (compact) 12.dp else 15.dp)
                )
            }

            // VOD Watch Progress Badge & Bottom Timeline Bar (ما شاهدته + نقطة الاستئناف)
            if (watchProgress != null && watchProgress.positionMs >= 5_000L) {
                Text(
                    text = if (watchProgress.isCompleted) "✅ شوهد" else "⏯ ${watchProgress.formattedPosition}",
                    color = Color.Black,
                    fontSize = if (compact) 8.sp else 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 6.dp, start = 4.dp)
                        .background(
                            if (watchProgress.isCompleted) EmeraldLive else Color(0xFFFBBF24),
                            RoundedCornerShape(5.dp)
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x88000000))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(watchProgress.progressFraction.coerceIn(0.06f, 1f))
                            .background(if (watchProgress.isCompleted) EmeraldLive else Color(0xFFFBBF24))
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compact) 8.dp else 12.dp,
                    vertical = if (compact) 7.dp else 10.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = channel.name,
                color = Color.White,
                fontSize = if (compact) 11.sp else 13.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Visible,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = channel.group,
                color = if (isFocused) Color(0xFFF3E8FF) else Color(0xFFC4B5FD),
                fontSize = if (compact) 9.sp else 11.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().basicMarquee()
            )
        }
    }
}

@Composable
private fun ChannelListRow(
    channel: TvChannel,
    isFavorite: Boolean,
    isLastWatched: Boolean = false,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(if (isFocused) 1.02f else 1.0f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isFocused -> BeinPrimaryPurple
                    isLastWatched -> Color(0xFF130926)
                    else -> BeinCard
                }
            )
            .border(
                width = if (isFocused) 2.5.dp else if (isLastWatched) 1.5.dp else 1.dp,
                color = if (isFocused) BeinFocusGlow else if (isLastWatched) BeinHighlightPurple else BeinBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    ev.nativeKeyEvent.repeatCount == 0 &&
                    (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    SafeClickGuard.runThrottled("UI_BTN_CLICK", 200L) { onClick() }
                    true
                } else false
            }
            .focusable()
            .clickable { SafeClickGuard.runThrottled("UI_BTN_CLICK", 200L) { onClick() } }
            .padding(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = if (compact) 7.dp else 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelAvatar(
            name = channel.name,
            logoUrl = channel.logo,
            modifier = Modifier.size(if (compact) 48.dp else 62.dp),
            cornerRadius = 10.dp,
            innerPadding = 3.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = channel.name,
                color = Color.White,
                fontSize = if (compact) 12.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = channel.group,
                color = Color(0xFFD8B4FE),
                fontSize = if (compact) 10.sp else 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(if (compact) 30.dp else 38.dp)
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "مفضلة",
                tint = if (isFavorite) Color(0xFFFF4D6D) else Color.White,
                modifier = Modifier.size(if (compact) 16.dp else 20.dp)
            )
        }
    }
}

@Composable
private fun FavoritesAndHistoryView(
    favorites: List<TvChannel>,
    history: List<TvChannel>,
    lang: AppUiLanguage,
    compact: Boolean,
    onPlay: (TvChannel) -> Unit,
    onToggleFavorite: (TvChannel) -> Unit,
    onClearHistory: () -> Unit
) {
    var subTab by remember { mutableStateOf(0) }
    val favSet = remember(favorites) { favorites.map { it.stableKey }.toHashSet() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (compact) 10.dp else 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BeinFocusableButton(
                    text = "${AppStrings.favoritesLabel(lang)} (${favorites.size})",
                    isPrimary = subTab == 0,
                    compact = compact,
                    onClick = { subTab = 0 }
                )
                BeinFocusableButton(
                    text = "${AppStrings.tr(lang, "المشاهدة أخيراً", "Historique", "Recent")} (${history.size})",
                    isPrimary = subTab == 1,
                    compact = compact,
                    onClick = { subTab = 1 }
                )
            }

            if (subTab == 1 && history.isNotEmpty()) {
                BeinFocusableButton(
                    text = AppStrings.tr(lang, "مسح السجل", "Effacer", "Clear"),
                    isPrimary = false,
                    compact = true,
                    onClick = onClearHistory
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        val list = if (subTab == 0) favorites else history
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (subTab == 0) {
                        AppStrings.tr(lang, "لم تقم بإضافة قنوات إلى المفضلة بعد", "Aucun favori pour le moment", "No favorite channels yet")
                    } else {
                        AppStrings.tr(lang, "سجل المشاهدة فارغ حالياً", "Historique vide", "Watch history is empty")
                    },
                    color = Color.LightGray,
                    fontSize = if (compact) 12.sp else 14.sp
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(list, key = { idx, ch -> "${idx}_${ch.stableKey}" }) { _, ch ->
                    BeinChannelCard(
                        channel = ch,
                        isFavorite = favSet.contains(ch.stableKey),
                        compact = compact,
                        onClick = { onPlay(ch) },
                        onToggleFavorite = { onToggleFavorite(ch) }
                    )
                }
            }
        }
    }
}

/**
 * Restored Full Subscription Validity & Screen Activation Status View!
 */
@Composable
private fun ScreenActivationStatusView(
    deviceId: String,
    deviceName: String,
    subscription: SubscriptionInfo?,
    lang: AppUiLanguage,
    isLoading: Boolean,
    compact: Boolean,
    onVerifyNow: () -> Unit,
    onRedeemCode: (String) -> Unit
) {
    val context = LocalContext.current
    var directCode by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (compact) 10.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .widthIn(max = 660.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(BeinCard)
                    .border(2.dp, BeinHighlightPurple, RoundedCornerShape(18.dp))
                    .padding(if (compact) 14.dp else 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = AppStrings.tr(lang, "حالة صلاحية المشغل ورمز الشاشة", "Validité du Lecteur & Code Écran", "Player Validity & Screen Code"),
                    color = Color.White,
                    fontSize = if (compact) 15.sp else 19.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(8.dp))

                // Subscription Duration & Expiry Details Card
                if (subscription != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (subscription.active) Color(0xFF0C261E) else Color(0xFF2B1118))
                            .border(
                                1.5.dp,
                                if (subscription.active) EmeraldLive else CrimsonAlert,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (subscription.active) "✅ حالة المشغل: نشط ومفعّل" else "⛔ حالة المشغل: منتهي الصلاحية",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = if (compact) 12.sp else 14.sp
                            )
                            if (subscription.expireDate.isNotBlank()) {
                                Text(
                                    text = "تاريخ انتهاء الصلاحية: ${subscription.expireDate}",
                                    color = Color(0xFFD8B4FE),
                                    fontSize = if (compact) 11.sp else 13.sp
                                )
                            }
                        }
                        Text(
                            text = "${subscription.daysLeft} يوم متبقٍ",
                            color = if (subscription.active) EmeraldLive else CrimsonAlert,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 14.sp else 17.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Text(
                    text = "📞 للتجديد والتفعيل تواصل مع علي سات : $ALI_SAT_PHONE_NUMBER",
                    color = Color(0xFFD8B4FE),
                    fontSize = if (compact) 13.sp else 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BeinDarkBg)
                        .border(2.dp, BeinHighlightPurple, RoundedCornerShape(12.dp))
                        .clickable { copyDeviceCodeToClipboard(context, deviceId) }
                        .padding(vertical = if (compact) 10.dp else 16.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = deviceId,
                            color = Color.White,
                            fontSize = if (compact) 20.sp else 26.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BeinFocusableButton(
                        text = AppStrings.tr(lang, "نسخ رمز الشاشة 📋", "Copier le code 📋", "Copy Screen Code 📋"),
                        isPrimary = true,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = { copyDeviceCodeToClipboard(context, deviceId) }
                    )
                    BeinFocusableButton(
                        text = if (isLoading) "..." else AppStrings.tr(lang, "تحديث الصلاحية 🔄", "Actualiser 🔄", "Refresh Validity 🔄"),
                        isPrimary = false,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = onVerifyNow
                    )
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .widthIn(max = 660.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(BeinCard)
                    .border(1.8.dp, BeinHighlightPurple, RoundedCornerShape(16.dp))
                    .padding(if (compact) 12.dp else 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = AppStrings.tr(
                        lang,
                        "💳 تفعيل وتجديد الاشتراك برمز القسيمة المباشر",
                        "💳 Activer ou renouveler avec un code de recharge",
                        "💳 Activate or Renew with Voucher Code"
                    ),
                    color = Color.White,
                    fontSize = if (compact) 13.sp else 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = AppStrings.tr(
                        lang,
                        "أدخل رمز التفعيل المكون من 8 أحرف وأرقام (مثال: A1B2-C3D4) ثم اضغط «تفعيل الآن ✓»",
                        "Entrez le code de 8 caractères (ex: A1B2-C3D4) puis cliquez sur Activer",
                        "Enter your 8-character voucher code (e.g. A1B2-C3D4) then click Activate"
                    ),
                    color = Color(0xFFD8B4FE),
                    fontSize = if (compact) 10.sp else 12.sp,
                    textAlign = TextAlign.Center
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = directCode,
                        onValueChange = { directCode = formatVoucherCodeInput(it) },
                        placeholder = {
                            Text(
                                "A1B2-C3D4",
                                color = Color(0xFFBFA7F2),
                                fontSize = if (compact) 12.sp else 14.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = if (compact) 15.sp else 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp,
                            textAlign = TextAlign.Center
                        ),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    BeinFocusableButton(
                        text = AppStrings.tr(lang, "📋 لصق", "📋 Coller", "📋 Paste"),
                        isPrimary = false,
                        compact = compact,
                        onClick = {
                            val pasted = pasteVoucherFromClipboard(context)
                            if (pasted.isNotBlank()) directCode = pasted
                        }
                    )
                    BeinFocusableButton(
                        text = if (isLoading) "⏳ جارٍ التفعيل..." else AppStrings.tr(lang, "تفعيل الآن ✓", "Activer ✓", "Activate ✓"),
                        isPrimary = true,
                        compact = compact,
                        onClick = { onRedeemCode(directCode) }
                    )
                }
            }
        }
    }
}

