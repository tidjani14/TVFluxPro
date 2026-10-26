@file:Suppress("DEPRECATION")

package com.tvflux.pro

import android.app.Activity
import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream

data class TrackOption(
    val title: String,
    val subtitle: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val isSelected: Boolean
)

/**
 * VLC-Parity User-Agent Rotation:
 * Starts with VLC/3.0.20 LibVLC/3.0.20 so servers that work in VLC accept the stream on attempt #1.
 */
private val STREAM_USER_AGENTS = listOf(
    "VLC/3.0.20 LibVLC/3.0.20",
    "Lavf/60.16.100",
    "TiviMate/4.7.0 (Android 14; TV)",
    "IPTVSmartersPlayer/6.0 (LIVE-PRO-UNIVERSAL/12.0)",
    "Mozilla/5.0 (Linux; Android 14; SHIELD Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

/**
 * Custom Ultra-Fast MPD (DASH) Manifest Parser:
 * Ensures dynamic live .mpd manifests refresh every ~2.4 seconds even when servers roll segments
 * every 3 seconds or set an overly long/missing minimumUpdatePeriod attribute!
 */
@OptIn(UnstableApi::class)
private class FastRefreshDashManifestParser : DashManifestParser() {
    override fun parse(uri: Uri, inputStream: InputStream): DashManifest {
        val rawBytes = inputStream.readBytes()
        val xmlText = String(rawBytes, Charsets.UTF_8)
        val patchedXml = if (xmlText.contains("type=\"dynamic\"", ignoreCase = true)) {
            if (xmlText.contains("minimumUpdatePeriod=", ignoreCase = true)) {
                xmlText.replace(
                    Regex("minimumUpdatePeriod\\s*=\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE),
                    "minimumUpdatePeriod=\"PT2.4S\""
                )
            } else {
                xmlText.replaceFirst(
                    Regex("<MPD\\b", RegexOption.IGNORE_CASE),
                    "<MPD minimumUpdatePeriod=\"PT2.4S\""
                )
            }
        } else {
            xmlText
        }
        return super.parse(uri, ByteArrayInputStream(patchedXml.toByteArray(Charsets.UTF_8)))
    }
}

@OptIn(UnstableApi::class)
private fun buildClearKeyDrmManager(drmKeyString: String): DefaultDrmSessionManager? {
    val clean = drmKeyString.trim()
    if (clean.isEmpty()) return null
    return runCatching {
        val jwkJson = if (clean.startsWith("{")) {
            clean
        } else if (clean.contains(":")) {
            val kidHex = clean.substringBefore(":").trim()
            val keyHex = clean.substringAfter(":").trim()
            val kidB64 = hexToBase64Url(kidHex)
            val keyB64 = hexToBase64Url(keyHex)
            "{\"keys\":[{\"kty\":\"oct\",\"k\":\"$keyB64\",\"kid\":\"$kidB64\"}],\"type\":\"temporary\"}"
        } else {
            return null
        }
        val callback = LocalMediaDrmCallback(jwkJson.toByteArray(Charsets.UTF_8))
        DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(false)
            .build(callback)
    }.getOrNull()
}

private fun hexToBase64Url(hex: String): String {
    val clean = hex.replace("-", "").trim()
    val len = clean.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len - 1) {
        data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
        i += 2
    }
    return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    channel: TvChannel,
    siblingChannels: List<TvChannel>,
    allChannels: List<TvChannel>,
    favorites: List<TvChannel>,
    xtreamPlaylists: List<XtreamAccountConfig> = emptyList(),
    preferences: PlayerPreferences,
    isFavorite: Boolean,
    onToggleFavorite: (TvChannel) -> Unit,
    onUpdatePreferences: (PlayerPreferences) -> Unit,
    onSelectChannel: (TvChannel) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lang = preferences.appLanguage

    var showControls by remember { mutableStateOf(false) }
    var showQuickDrawer by remember { mutableStateOf(false) }
    var drawerSelectedBouquet by remember { mutableStateOf(channel.group) }
    var focusFirstChannelTrigger by remember { mutableStateOf(0) }

    var showTracksDialog by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(preferences.resizeMode) }
    var playbackSpeed by remember { mutableStateOf(PlaybackSpeedOption.NORMAL) }

    var isBuffering by remember { mutableStateOf(true) }
    var showBufferingBadge by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var errorMessage by remember(channel.stableKey) { mutableStateOf<String?>(null) }
    var lastErrorCodeName by remember(channel.stableKey) { mutableStateOf("") }
    var fastFallbackTriggered by remember(channel.stableKey) { mutableStateOf(false) }
    var retryCount by remember(channel.stableKey) { mutableStateOf(0) }
    var videoResolutionLabel by remember(channel.stableKey) { mutableStateOf("") }
    var currentTracksSnapshot by remember { mutableStateOf(Tracks.EMPTY) }

    val matchingAccount = remember(channel.playlistId, xtreamPlaylists) {
        xtreamPlaylists.find { it.id == channel.playlistId }
    }
    val formatCandidateUrls = remember(channel.stableKey, channel.url, matchingAccount) {
        channel.buildFormatCandidateUrls(matchingAccount)
    }
    var detectedSpecLabel by remember(channel.stableKey) {
        mutableStateOf(
            listOfNotNull(
                channel.videoCodecHint.takeIf { it.isNotBlank() },
                channel.audioCodecHint.takeIf { it.isNotBlank() }
            ).joinToString(" • ")
        )
    }
    var candidateIndex by remember(channel.stableKey) { mutableStateOf(0) }
    var formatAttemptMode by remember(channel.stableKey) { mutableStateOf(0) }
    var numberInputBuffer by remember { mutableStateOf("") }

    val rootPlayerFocusRequester = remember { FocusRequester() }
    val activeBouquetFocusRequester = remember { FocusRequester() }
    val firstChannelInBouquetFocusRequester = remember { FocusRequester() }
    val activeChannelFocusRequester = remember { FocusRequester() }

    LaunchedEffect(showQuickDrawer, showTracksDialog, showControls) {
        if (!showQuickDrawer && !showTracksDialog && !showControls) {
            delay(60L)
            runCatching { rootPlayerFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(isBuffering, errorMessage) {
        if (isBuffering && errorMessage == null) {
            delay(850L)
            if (isBuffering) showBufferingBadge = true
        } else {
            showBufferingBadge = false
        }
    }

    val favLabel = AppStrings.favoritesLabel(lang)
    val allLabel = AppStrings.allBouquetsLabel(lang)

    // Compute drawer bouquets OFF the Main UI Thread so opening a channel never blocks or lags!
    val allBouquets by produceState<List<Pair<String, List<TvChannel>>>>(
        initialValue = listOf(channel.group to siblingChannels),
        allChannels,
        favorites,
        preferences.customBouquetOrder,
        preferences.hiddenBouquets,
        lang
    ) {
        value = withContext(Dispatchers.Default) {
            val source = allChannels.ifEmpty { siblingChannels }
            val visibleChannels = if (preferences.hiddenBouquets.isEmpty()) {
                source
            } else {
                source.filter { it.group !in preferences.hiddenBouquets }
            }
            val grouped = visibleChannels.groupBy { it.group }

            val rawSummaries = ArrayList<BouquetSummary>(grouped.size + 2)
            rawSummaries.add(BouquetSummary(name = allLabel, count = visibleChannels.size))
            rawSummaries.add(BouquetSummary(name = favLabel, count = favorites.size, isFavoritesSpecial = true))
            grouped.forEach { (gName, list) ->
                rawSummaries.add(BouquetSummary(name = gName, count = list.size))
            }

            val sortedSummaries = CustomOrderHelper.sortBouquets(
                rawSummaries,
                preferences.customBouquetOrder,
                preferences.hiddenBouquets
            )

            sortedSummaries.map { summary ->
                val chList = when {
                    summary.isFavoritesSpecial -> favorites
                    summary.name == allLabel -> visibleChannels
                    else -> grouped[summary.name] ?: emptyList()
                }
                summary.name to CustomOrderHelper.sortChannels(
                    chList,
                    preferences.customChannelOrder,
                    preferences.hiddenBouquets
                )
            }
        }
    }

    val drawerChannels = remember(allBouquets, drawerSelectedBouquet, siblingChannels) {
        allBouquets.find { it.first == drawerSelectedBouquet }?.second
            ?: siblingChannels.ifEmpty {
                allBouquets.firstOrNull { !it.first.startsWith("★") && it.second.isNotEmpty() }?.second.orEmpty()
            }
    }

    val effectiveUrl = remember(formatCandidateUrls, candidateIndex) {
        formatCandidateUrls.getOrElse(candidateIndex.coerceIn(0, (formatCandidateUrls.size - 1).coerceAtLeast(0))) {
            channel.url
        }
    }

    DisposableEffect(Unit) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val httpFactory = remember {
        OkHttpDataSource.Factory(ChannelRepository.streamHttpClient)
            .setUserAgent(STREAM_USER_AGENTS.first())
    }

    val extractorsFactory = remember {
        DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setTsExtractorMode(TsExtractor.MODE_MULTI_PMT)
            .setTsExtractorTimestampSearchBytes(188 * 7200)
    }

    val defaultMediaSourceFactory = remember {
        DefaultMediaSourceFactory(context, extractorsFactory)
            .setDataSourceFactory(httpFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
    }

    val fastDashMediaSourceFactory = remember {
        DashMediaSource.Factory(httpFactory)
            .setManifestParser(FastRefreshDashManifestParser())
            .setFallbackTargetLiveOffsetMs(2_800L)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
    }

    val fastHlsMediaSourceFactory = remember {
        HlsMediaSource.Factory(httpFactory)
            .setAllowChunklessPreparation(true)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
    }

    val player = remember {
        val bp = preferences.bufferProfile
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = am?.isLowRamDevice == true || (am?.memoryClass ?: 192) <= 160
        val safeTargetBytes = if (isLowRam) {
            bp.targetBufferBytes.coerceAtMost(16 * 1024 * 1024)
        } else {
            bp.targetBufferBytes
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bp.minBufferMs,
                bp.maxBufferMs,
                bp.playbackBufferMs,
                bp.rebufferMs
            )
            .setTargetBufferBytes(safeTargetBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(bp.backBufferMs, true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(
                if (preferences.preferSoftwareDecoder || isLowRam) {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                } else {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                }
            )
            setEnableDecoderFallback(true)
        }

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoBitrate(if (isLowRam) 8_500_000 else Int.MAX_VALUE)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .setAllowAudioMixedSampleRateAdaptiveness(true)
            )
        }

        ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(defaultMediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build()
    }

    LaunchedEffect(player, playbackSpeed) {
        player.playbackParameters = PlaybackParameters(playbackSpeed.speed)
    }

    LaunchedEffect(player, channel.stableKey, effectiveUrl, formatAttemptMode, retryCount) {
        val sessionId = PlaybackSessionGuard.beginSession(channel.stableKey)
        isBuffering = true
        errorMessage = null
        showControls = false
        player.stop()
        // Immediately close any previous channel socket so max_connections = 1 Xtream servers never block the new stream!
        ChannelRepository.cancelPendingStreamRequests()

        val rotatedUa = channel.userAgent?.takeIf { it.isNotBlank() }
            ?: STREAM_USER_AGENTS[retryCount % STREAM_USER_AGENTS.size]

        // Zero pre-playback HTTP probe: never burn single-use Xtream tokens or max_connections=1 slots before ExoPlayer connects!
        val fastSpec = StreamFormatDetectorAndRenewer.detectFastFromUrlAndMetadata(channel, effectiveUrl, rotatedUa)

        if (!PlaybackSessionGuard.isSessionCurrent(sessionId, channel.stableKey)) {
            return@LaunchedEffect
        }

        detectedSpecLabel = "${fastSpec.containerFormat} • ${fastSpec.videoCodecHint}"
        val targetPlayUrl = effectiveUrl.trim()
        val low = targetPlayUrl.lowercase().substringBefore("?")
        val isMpdStream = fastSpec.mimeType == MimeTypes.APPLICATION_MPD || low.contains(".mpd") || targetPlayUrl.lowercase().contains("format=mpd") || targetPlayUrl.lowercase().contains("type=mpd")
        val isHlsStream = formatAttemptMode == 1 || (
            formatAttemptMode == 0 && (
                fastSpec.mimeType == MimeTypes.APPLICATION_M3U8 ||
                    low.endsWith(".m3u8") ||
                    targetPlayUrl.lowercase().contains("output=m3u8") ||
                    targetPlayUrl.lowercase().contains("type=m3u8")
                )
            )

        // Clean VLC-compatible HTTP headers (NO Icy-MetaData which breaks strict Nginx/Xtream video servers!)
        val customHeaders = mutableMapOf(
            "Connection" to "keep-alive",
            "Accept" to "*/*"
        )
        if (isMpdStream || isHlsStream) {
            customHeaders["Cache-Control"] = "no-cache, no-store, max-age=0, must-revalidate"
            customHeaders["Pragma"] = "no-cache"
            customHeaders["Expires"] = "0"
        }
        if (!channel.referer.isNullOrBlank()) customHeaders["Referer"] = channel.referer
        if (!channel.origin.isNullOrBlank()) customHeaders["Origin"] = channel.origin
        if (!channel.cookie.isNullOrBlank()) customHeaders["Cookie"] = channel.cookie
        httpFactory.setDefaultRequestProperties(customHeaders)
        httpFactory.setUserAgent(rotatedUa)

        val itemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(targetPlayUrl))

        // Attach Widevine DRM license URL if provided
        if (channel.drmKey.startsWith("http://", ignoreCase = true) || channel.drmKey.startsWith("https://", ignoreCase = true)) {
            itemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(channel.drmKey.trim())
                    .setMultiSession(false)
                    .build()
            )
        }

        val isVodFile = channel.contentType != StreamContentType.LIVE_TV ||
            low.endsWith(".mp4") || low.endsWith(".mkv") || low.endsWith(".avi") ||
            low.endsWith(".mov") || low.endsWith(".webm") || low.endsWith(".flv")

        if (!isVodFile) {
            val targetOffset = when {
                isMpdStream -> 3_200L
                retryCount > 0 -> 9_000L
                else -> 5_500L
            }
            val liveConfig = MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(targetOffset)
                .setMinOffsetMs(if (isMpdStream) 1_800L else 2_500L)
                .setMaxOffsetMs(45_000L)
                .setMinPlaybackSpeed(0.96f)
                .setMaxPlaybackSpeed(1.04f)
                .build()
            itemBuilder.setLiveConfiguration(liveConfig)
        }

        when (formatAttemptMode) {
            1 -> if (!isVodFile) itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            2 -> {
                // Mode 2: Force progressive auto-extractor (TsExtractor / Matroska / MP4) without HLS manifest parser
            }
            else -> {
                when {
                    isMpdStream -> itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
                    isHlsStream && !isVodFile -> itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                    low.contains(".ism") -> itemBuilder.setMimeType(MimeTypes.APPLICATION_SS)
                    low.startsWith("rtsp://") -> itemBuilder.setMimeType(MimeTypes.APPLICATION_RTSP)
                }
            }
        }

        val mediaItem = itemBuilder.build()
        val clearKeyManager = if (channel.drmKey.isNotBlank() && !channel.drmKey.startsWith("http", ignoreCase = true)) {
            buildClearKeyDrmManager(channel.drmKey)
        } else null

        if (isMpdStream) {
            val dashFactory = if (clearKeyManager != null) {
                DashMediaSource.Factory(httpFactory)
                    .setManifestParser(FastRefreshDashManifestParser())
                    .setFallbackTargetLiveOffsetMs(2_800L)
                    .setDrmSessionManagerProvider { clearKeyManager }
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
            } else {
                fastDashMediaSourceFactory
            }
            player.setMediaSource(dashFactory.createMediaSource(mediaItem))
        } else if (isHlsStream && formatAttemptMode != 2 && !isVodFile && clearKeyManager == null) {
            player.setMediaSource(fastHlsMediaSourceFactory.createMediaSource(mediaItem))
        } else if (clearKeyManager != null) {
            val drmDefaultFactory = DefaultMediaSourceFactory(context, extractorsFactory)
                .setDataSourceFactory(httpFactory)
                .setDrmSessionManagerProvider { clearKeyManager }
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
            player.setMediaSource(drmDefaultFactory.createMediaSource(mediaItem))
        } else {
            player.setMediaSource(defaultMediaSourceFactory.createMediaSource(mediaItem))
        }

        player.playWhenReady = true
        player.prepare()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    errorMessage = null
                    lastErrorCodeName = ""
                    fastFallbackTriggered = false
                    retryCount = 0
                    currentTracksSnapshot = player.currentTracks
                } else if (playbackState == Player.STATE_ENDED && preferences.autoReconnect &&
                    channel.contentType == StreamContentType.LIVE_TV
                ) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.play()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                currentTracksSnapshot = tracks
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val tag = when {
                        videoSize.height >= 2160 -> "4K UHD"
                        videoSize.height >= 1080 -> "1080p FHD"
                        videoSize.height >= 720 -> "720p HD"
                        else -> "${videoSize.height}p SD"
                    }
                    videoResolutionLabel = "${videoSize.width}×${videoSize.height} • $tag"
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.play()
                } else if (
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                ) {
                    lastErrorCodeName = error.errorCodeName
                    TiviMateDiagnostics.logEvent("⚠️ فشل فك الترميز العتادي لـ [${channel.name}] (${error.errorCodeName}) — تفعيل المعالجة البرمجية")
                    if (!preferences.preferSoftwareDecoder) {
                        onUpdatePreferences(preferences.copy(preferSoftwareDecoder = true))
                    }
                    errorMessage = AppStrings.tr(
                        lang,
                        "ترميز الفيديو غير مدعوم عتادياً — جارٍ التحويل لفك الترميز البرمجي...",
                        "Codec non supporté matériellement — Passage au décodeur logiciel...",
                        "Hardware codec unsupported — Switching to software decoder..."
                    )
                } else {
                    lastErrorCodeName = error.errorCodeName
                    fastFallbackTriggered = (
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                        )
                    TiviMateDiagnostics.logEvent("🔄 تبديل محرك البث (VLC Fallback) لـ [${channel.name}]: ${error.errorCodeName}")
                    errorMessage = AppStrings.tr(
                        lang,
                        "جارٍ فتح القناة بمحرك VLC البديل (${candidateIndex + 1}/${formatCandidateUrls.size})...",
                        "Ouverture via moteur VLC alternatif (${candidateIndex + 1}/${formatCandidateUrls.size})...",
                        "Opening via alternate VLC stream mode (${candidateIndex + 1}/${formatCandidateUrls.size})..."
                    )
                }
            }
        }
        player.addListener(listener)
        onDispose {
            ChannelRepository.cancelPendingStreamRequests()
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(channel.stableKey, errorMessage, retryCount) {
        if (errorMessage != null && preferences.autoReconnect) {
            if (retryCount >= preferences.maxReconnectRetries) {
                errorMessage = AppStrings.tr(
                    lang,
                    "تعذّر فتح القناة بعد ${preferences.maxReconnectRetries} محاولات ($lastErrorCodeName)",
                    "Échec de lecture après ${preferences.maxReconnectRetries} tentatives ($lastErrorCodeName)",
                    "Playback failed after ${preferences.maxReconnectRetries} attempts ($lastErrorCodeName)"
                )
                return@LaunchedEffect
            }
            val backoffMs = if (fastFallbackTriggered) 80L else (350L * (retryCount + 1)).coerceAtMost(2_200L)
            delay(backoffMs)
            retryCount += 1

            if (channel.contentType != StreamContentType.LIVE_TV && formatCandidateUrls.size > 1) {
                formatAttemptMode = 0
                candidateIndex = (candidateIndex + 1) % formatCandidateUrls.size
            } else if (
                channel.contentType == StreamContentType.LIVE_TV &&
                fastFallbackTriggered &&
                formatAttemptMode == 0 &&
                effectiveUrl.contains(".m3u8", ignoreCase = true)
            ) {
                // Server returned raw MPEG-TS bytes on a .m3u8 URL: immediately open as raw TS (mode 2)!
                formatAttemptMode = 2
            } else if (formatCandidateUrls.size > 1) {
                formatAttemptMode = 0
                candidateIndex = (candidateIndex + 1) % formatCandidateUrls.size
            } else if (channel.contentType == StreamContentType.LIVE_TV && formatAttemptMode < 2) {
                formatAttemptMode += 1
            } else {
                formatAttemptMode = 0
                errorMessage = null
                if (channel.contentType == StreamContentType.LIVE_TV) {
                    player.seekToDefaultPosition()
                }
                player.prepare()
                player.play()
            }
        }
    }

    LaunchedEffect(numberInputBuffer) {
        if (numberInputBuffer.isNotEmpty()) {
            delay(950L)
            val targetNum = numberInputBuffer.toIntOrNull()
            numberInputBuffer = ""
            if (targetNum != null) {
                val match = siblingChannels.find { it.number == targetNum }
                    ?: allChannels.find { it.number == targetNum }
                if (match != null) {
                    showControls = false
                    onSelectChannel(match)
                }
            }
        }
    }

    LaunchedEffect(showControls, isPlaying, showQuickDrawer, showTracksDialog) {
        if (showControls && isPlaying && !showQuickDrawer && !showTracksDialog && errorMessage == null) {
            delay(3800L)
            showControls = false
        }
    }

    BackHandler {
        when {
            showTracksDialog -> showTracksDialog = false
            showQuickDrawer -> showQuickDrawer = false
            showControls -> showControls = false
            else -> onBack()
        }
    }

    fun switchRelativeChannel(offset: Int) {
        val list = siblingChannels.ifEmpty { allChannels }
        if (list.size <= 1) return
        val idx = list.indexOfFirst { it.stableKey == channel.stableKey }
        if (idx == -1) return
        val nextIdx = (idx + offset + list.size) % list.size
        showControls = false
        onSelectChannel(list[nextIdx])
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootPlayerFocusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (showQuickDrawer || showTracksDialog) {
                    return@onKeyEvent false
                }
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    val code = event.nativeKeyEvent.keyCode
                    if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                        val digit = (code - KeyEvent.KEYCODE_0).toString()
                        if (numberInputBuffer.length < 4) {
                            numberInputBuffer += digit
                        }
                        return@onKeyEvent true
                    }
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> {
                            if (!showControls) {
                                drawerSelectedBouquet = channel.group
                                showQuickDrawer = true
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_MENU,
                        KeyEvent.KEYCODE_INFO -> {
                            showControls = !showControls
                            true
                        }
                        KeyEvent.KEYCODE_CHANNEL_UP -> {
                            switchRelativeChannel(-1)
                            true
                        }
                        KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                            switchRelativeChannel(1)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (!showControls) {
                                switchRelativeChannel(-1)
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (!showControls) {
                                switchRelativeChannel(1)
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (!showControls) {
                                if (channel.contentType != StreamContentType.LIVE_TV) {
                                    player.seekTo((player.currentPosition - 15_000L).coerceAtLeast(0L))
                                } else {
                                    drawerSelectedBouquet = channel.group
                                    showQuickDrawer = true
                                }
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!showControls) {
                                if (channel.contentType != StreamContentType.LIVE_TV) {
                                    player.seekTo(player.currentPosition + 15_000L)
                                } else {
                                    drawerSelectedBouquet = channel.group
                                    showQuickDrawer = true
                                }
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (player.isPlaying) player.pause() else player.play()
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (showQuickDrawer) showQuickDrawer = false
                else showControls = !showControls
            }
    ) {
        val isPhoneLandscape = maxHeight < 460.dp

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    this.useController = false
                    this.resizeMode = resizeMode.exoMode
                    this.keepScreenOn = true
                    this.isFocusable = false
                    this.isFocusableInTouchMode = false
                }
            },
            update = { view ->
                view.player = player
                view.resizeMode = resizeMode.exoMode
            },
            modifier = Modifier.fillMaxSize()
        )

        if (numberInputBuffer.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .background(Color(0xEB080411), RoundedCornerShape(12.dp))
                    .border(2.dp, BeinHighlightPurple, RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(
                    text = numberInputBuffer,
                    color = Color.White,
                    fontSize = if (isPhoneLandscape) 20.sp else 26.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        if (showBufferingBadge || errorMessage != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xE8080411), RoundedCornerShape(24.dp))
                    .border(1.5.dp, BeinHighlightPurple, RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(if (isPhoneLandscape) 18.dp else 22.dp)
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = Color.White,
                        fontSize = if (isPhoneLandscape) 11.sp else 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xE6030106),
                                Color(0x22030106),
                                Color(0x22030106),
                                Color(0xF0030106)
                            )
                        )
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(
                            horizontal = if (isPhoneLandscape) 12.dp else 20.dp,
                            vertical = if (isPhoneLandscape) 6.dp else 14.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        FocusableCircleButton(
                            onClick = onBack,
                            icon = Icons.Default.ArrowBack,
                            contentDescription = "رجوع",
                            sizeDp = if (isPhoneLandscape) 36.dp else 44.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        ChannelAvatar(
                            name = channel.name,
                            logoUrl = channel.logo,
                            modifier = Modifier.size(if (isPhoneLandscape) 34.dp else 44.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = channel.name,
                                color = Color.White,
                                fontSize = if (isPhoneLandscape) 13.sp else 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee()
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = channel.group,
                                    color = Color(0xFFD8B4FE),
                                    fontSize = if (isPhoneLandscape) 11.sp else 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (videoResolutionLabel.isNotBlank() || detectedSpecLabel.isNotBlank()) {
                                    Text(
                                        text = listOfNotNull(
                                            videoResolutionLabel.takeIf { it.isNotBlank() },
                                            detectedSpecLabel.takeIf { it.isNotBlank() }
                                        ).joinToString(" • "),
                                        color = EmeraldLive,
                                        fontSize = if (isPhoneLandscape) 10.sp else 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FocusableCircleButton(
                            onClick = { onToggleFavorite(channel) },
                            icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            tint = if (isFavorite) Color(0xFFFF4D6D) else Color.White,
                            contentDescription = "المفضلة",
                            sizeDp = if (isPhoneLandscape) 36.dp else 44.dp
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                            FocusableCircleButton(
                                onClick = {
                                    runCatching {
                                        val params = PictureInPictureParams.Builder()
                                            .setAspectRatio(Rational(16, 9))
                                            .build()
                                        activity.enterPictureInPictureMode(params)
                                    }
                                },
                                icon = Icons.Default.ExitToApp,
                                contentDescription = "PiP",
                                sizeDp = if (isPhoneLandscape) 36.dp else 44.dp
                            )
                        }

                        FocusableCircleButton(
                            onClick = {
                                showControls = false
                                drawerSelectedBouquet = channel.group
                                showQuickDrawer = true
                            },
                            icon = Icons.Default.List,
                            contentDescription = "الباقات والقنوات",
                            sizeDp = if (isPhoneLandscape) 36.dp else 44.dp
                        )
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(if (isPhoneLandscape) 14.dp else 22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (channel.contentType != StreamContentType.LIVE_TV) {
                        FocusableCircleButton(
                            onClick = { player.seekTo((player.currentPosition - 15_000L).coerceAtLeast(0L)) },
                            icon = Icons.Default.FastRewind,
                            tint = Color.White,
                            contentDescription = "-15s",
                            sizeDp = if (isPhoneLandscape) 38.dp else 48.dp
                        )
                    } else if (siblingChannels.size > 1) {
                        FocusableCircleButton(
                            onClick = { switchRelativeChannel(-1) },
                            icon = Icons.Default.KeyboardArrowRight,
                            tint = Color.White,
                            contentDescription = "السابقة",
                            sizeDp = if (isPhoneLandscape) 38.dp else 48.dp
                        )
                    }

                    var centerPlayFocused by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                        },
                        modifier = Modifier
                            .size(if (isPhoneLandscape) 48.dp else 60.dp)
                            .scale(if (centerPlayFocused) 1.12f else 1.0f)
                            .onFocusChanged { centerPlayFocused = it.isFocused }
                            .focusable()
                            .background(if (centerPlayFocused) Color.White else BeinPrimaryPurple, CircleShape)
                            .border(3.dp, if (centerPlayFocused) BeinFocusGlow else Color.White, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "تشغيل/إيقاف",
                            tint = if (centerPlayFocused) Color.Black else Color.White,
                            modifier = Modifier.size(if (isPhoneLandscape) 24.dp else 32.dp)
                        )
                    }

                    if (channel.contentType != StreamContentType.LIVE_TV) {
                        FocusableCircleButton(
                            onClick = { player.seekTo(player.currentPosition + 15_000L) },
                            icon = Icons.Default.FastForward,
                            tint = Color.White,
                            contentDescription = "+15s",
                            sizeDp = if (isPhoneLandscape) 38.dp else 48.dp
                        )
                    } else if (siblingChannels.size > 1) {
                        FocusableCircleButton(
                            onClick = { switchRelativeChannel(1) },
                            icon = Icons.Default.KeyboardArrowLeft,
                            tint = Color.White,
                            contentDescription = "التالية",
                            sizeDp = if (isPhoneLandscape) 38.dp else 48.dp
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(
                            horizontal = if (isPhoneLandscape) 10.dp else 18.dp,
                            vertical = if (isPhoneLandscape) 6.dp else 12.dp
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FocusableActionPill(
                            text = "📂 ${AppStrings.tr(lang, "القنوات والباقات", "Chaînes & Bouquets", "Channels & Bouquets")}",
                            isPrimary = true,
                            compact = isPhoneLandscape,
                            onClick = {
                                showControls = false
                                drawerSelectedBouquet = channel.group
                                showQuickDrawer = true
                            }
                        )
                        FocusableActionPill(
                            text = "⚙️ ${AppStrings.tr(lang, "الجودة والمعلق والسرعة", "Qualité, Audio & Vitesse", "Quality, Audio & Speed")}",
                            isPrimary = false,
                            compact = isPhoneLandscape,
                            onClick = { showTracksDialog = true }
                        )
                        FocusableActionPill(
                            text = "🔄 الصيغة (${candidateIndex + 1}/${formatCandidateUrls.size})",
                            isPrimary = false,
                            compact = isPhoneLandscape,
                            onClick = {
                                formatAttemptMode = 0
                                retryCount = 0
                                candidateIndex = (candidateIndex + 1) % formatCandidateUrls.size.coerceAtLeast(1)
                            }
                        )
                        FocusableActionPill(
                            text = resizeMode.titleAr,
                            isPrimary = false,
                            compact = isPhoneLandscape,
                            onClick = {
                                val modes = VideoResizeMode.values()
                                resizeMode = modes[(resizeMode.ordinal + 1) % modes.size]
                            }
                        )
                    }
                }
            }
        }

        if (showQuickDrawer) {
            val bouquetListState = rememberLazyListState()
            val channelListState = rememberLazyListState()

            val activeBouquetIdx = remember(allBouquets, drawerSelectedBouquet) {
                allBouquets.indexOfFirst { it.first == drawerSelectedBouquet }.coerceAtLeast(0)
            }
            val activeChannelIdx = remember(drawerChannels, channel.stableKey) {
                drawerChannels.indexOfFirst { it.stableKey == channel.stableKey }.coerceAtLeast(0)
            }

            // Windowed Lazy Paging inside Quick Drawer (prevents any UI lag on huge bouquets)
            var drawerVisibleLimit by remember(drawerSelectedBouquet, drawerChannels.size) {
                mutableStateOf((activeChannelIdx + 140).coerceAtLeast(160).coerceAtMost(drawerChannels.size.coerceAtLeast(1)))
            }
            val windowedDrawerChannels = remember(drawerChannels, drawerVisibleLimit) {
                if (drawerChannels.size <= drawerVisibleLimit) drawerChannels
                else drawerChannels.subList(0, drawerVisibleLimit)
            }
            val favKeySet = remember(favorites) { favorites.map { it.stableKey }.toHashSet() }

            val shouldLoadMoreDrawer by remember {
                derivedStateOf {
                    val lastVisible = channelListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisible >= windowedDrawerChannels.size - 24 && windowedDrawerChannels.size < drawerChannels.size
                }
            }
            LaunchedEffect(shouldLoadMoreDrawer) {
                if (shouldLoadMoreDrawer) {
                    drawerVisibleLimit = (drawerVisibleLimit + 160).coerceAtMost(drawerChannels.size)
                }
            }

            LaunchedEffect(showQuickDrawer, channel.stableKey) {
                if (showQuickDrawer) {
                    drawerSelectedBouquet = channel.group
                    delay(45L)
                    runCatching { bouquetListState.scrollToItem(activeBouquetIdx) }
                    runCatching { channelListState.scrollToItem((activeChannelIdx - 1).coerceAtLeast(0)) }
                    delay(65L)
                    runCatching { activeChannelFocusRequester.requestFocus() }
                        .onFailure { runCatching { firstChannelInBouquetFocusRequester.requestFocus() } }
                        .onFailure { runCatching { activeBouquetFocusRequester.requestFocus() } }
                }
            }

            LaunchedEffect(focusFirstChannelTrigger) {
                if (focusFirstChannelTrigger > 0) {
                    runCatching { channelListState.scrollToItem(0) }
                    delay(60L)
                    runCatching { firstChannelInBouquetFocusRequester.requestFocus() }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth(if (isPhoneLandscape) 0.86f else 0.68f)
                    .widthIn(min = 380.dp, max = 660.dp)
                    .fillMaxHeight(if (isPhoneLandscape) 0.90f else 0.86f)
                    .align(Alignment.CenterStart)
                    .padding(start = if (isPhoneLandscape) 8.dp else 16.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xF705020A))
                    .border(2.dp, BeinHighlightPurple, RoundedCornerShape(18.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
                    .padding(if (isPhoneLandscape) 8.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0A0514))
                        .padding(8.dp)
                ) {
                    Text(
                        text = "📂 ${AppStrings.tr(lang, "الباقات", "Bouquets", "Bouquets")} (${allBouquets.size})",
                        color = Color.White,
                        fontSize = if (isPhoneLandscape) 11.sp else 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                    LazyColumn(
                        state = bouquetListState,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(allBouquets, key = { idx, pair -> "${idx}_${pair.first}" }) { idx, (bName, bList) ->
                            val selected = drawerSelectedBouquet == bName
                            var bFocused by remember { mutableStateOf(false) }
                            val bModifier = if (idx == activeBouquetIdx) {
                                Modifier.focusRequester(activeBouquetFocusRequester)
                            } else {
                                Modifier
                            }
                            Row(
                                modifier = bModifier
                                    .fillMaxWidth()
                                    .wrapContentHeight()
                                    .scale(if (bFocused) 1.03f else 1.0f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        when {
                                            bFocused -> Color.White
                                            selected -> BeinPrimaryPurple
                                            else -> Color(0xFF100820)
                                        }
                                    )
                                    .border(
                                        width = if (bFocused) 2.5.dp else if (selected) 1.5.dp else 0.dp,
                                        color = if (bFocused) BeinFocusGlow else if (selected) Color.White else Color.Transparent,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .onFocusChanged { bFocused = it.isFocused }
                                    .onKeyEvent { ev ->
                                        if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                            when (ev.nativeKeyEvent.keyCode) {
                                                KeyEvent.KEYCODE_DPAD_CENTER,
                                                KeyEvent.KEYCODE_ENTER,
                                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                                    drawerSelectedBouquet = bName
                                                    focusFirstChannelTrigger += 1
                                                    true
                                                }
                                                else -> false
                                            }
                                        } else false
                                    }
                                    .focusable()
                                    .clickable {
                                        drawerSelectedBouquet = bName
                                        focusFirstChannelTrigger += 1
                                    }
                                    .padding(horizontal = 10.dp, vertical = if (isPhoneLandscape) 7.dp else 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = bName,
                                    color = if (bFocused) Color.Black else Color.White,
                                    fontSize = if (isPhoneLandscape) 11.sp else 13.sp,
                                    fontWeight = if (bFocused || selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Visible,
                                    lineHeight = 16.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "${bList.size}",
                                    color = if (bFocused) BeinPrimaryPurple else Color(0xFFD8B4FE),
                                    fontSize = if (isPhoneLandscape) 10.sp else 11.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$drawerSelectedBouquet (${drawerChannels.size})",
                            color = Color.White,
                            fontSize = if (isPhoneLandscape) 12.sp else 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 2,
                            lineHeight = 17.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { showQuickDrawer = false },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "إغلاق",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))

                    if (windowedDrawerChannels.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = AppStrings.tr(
                                    lang,
                                    "لا توجد قنوات في هذه الباقة حالياً",
                                    "Aucune chaîne dans ce bouquet",
                                    "No channels in this bouquet"
                                ),
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            state = channelListState,
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(windowedDrawerChannels, key = { idx, it -> "${idx}_${it.stableKey}" }) { idx, item ->
                                val active = item.stableKey == channel.stableKey
                                val isFavItem = favKeySet.contains(item.stableKey)
                                var rowFocused by remember { mutableStateOf(false) }
                                val chModifier = when {
                                    idx == 0 && idx == activeChannelIdx ->
                                        Modifier
                                            .focusRequester(firstChannelInBouquetFocusRequester)
                                            .focusRequester(activeChannelFocusRequester)
                                    idx == 0 ->
                                        Modifier.focusRequester(firstChannelInBouquetFocusRequester)
                                    idx == activeChannelIdx ->
                                        Modifier.focusRequester(activeChannelFocusRequester)
                                    else -> Modifier
                                }
                                Row(
                                    modifier = chModifier
                                        .fillMaxWidth()
                                        .wrapContentHeight()
                                        .scale(if (rowFocused) 1.02f else 1.0f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            when {
                                                rowFocused -> Color.White
                                                active -> BeinPrimaryPurple
                                                else -> Color(0xFF0E071C)
                                            }
                                        )
                                        .border(
                                            width = if (rowFocused) 2.5.dp else if (active) 1.5.dp else 0.dp,
                                            color = if (rowFocused) BeinFocusGlow else if (active) Color.White else Color.Transparent,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .onFocusChanged { rowFocused = it.isFocused }
                                        .onKeyEvent { ev ->
                                            if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                                when (ev.nativeKeyEvent.keyCode) {
                                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                                    KeyEvent.KEYCODE_ENTER -> {
                                                        showControls = false
                                                        showQuickDrawer = false
                                                        onSelectChannel(item)
                                                        true
                                                    }
                                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                        runCatching { activeBouquetFocusRequester.requestFocus() }
                                                        true
                                                    }
                                                    else -> false
                                                }
                                            } else false
                                        }
                                        .focusable()
                                        .clickable {
                                            showControls = false
                                            showQuickDrawer = false
                                            onSelectChannel(item)
                                        }
                                        .padding(horizontal = 10.dp, vertical = if (isPhoneLandscape) 6.dp else 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ChannelAvatar(
                                        name = item.name,
                                        logoUrl = item.logo,
                                        modifier = Modifier.size(if (isPhoneLandscape) 24.dp else 30.dp),
                                        cornerRadius = 7.dp,
                                        innerPadding = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = item.name,
                                        color = if (rowFocused) Color.Black else Color.White,
                                        fontSize = if (isPhoneLandscape) 11.sp else 13.sp,
                                        fontWeight = if (active || rowFocused) FontWeight.ExtraBold else FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Visible,
                                        lineHeight = 16.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isFavItem) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "★",
                                            color = if (rowFocused) BeinPrimaryPurple else Color(0xFFFF4D6D),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showTracksDialog) {
            InteractiveTrackAndSpeedModal(
                player = player,
                tracks = currentTracksSnapshot,
                currentSpeed = playbackSpeed,
                onSelectSpeed = { playbackSpeed = it },
                compact = isPhoneLandscape,
                onDismiss = { showTracksDialog = false }
            )
        }
    }
}

@Composable
private fun FocusableCircleButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
    sizeDp: androidx.compose.ui.unit.Dp = 44.dp
) {
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(sizeDp)
            .scale(if (focused) 1.14f else 1.0f)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(if (focused) Color.White else Color(0xCC0E071C), CircleShape)
            .border(
                width = if (focused) 2.8.dp else 1.5.dp,
                color = if (focused) BeinFocusGlow else BeinHighlightPurple.copy(alpha = 0.7f),
                shape = CircleShape
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (focused) Color.Black else tint,
            modifier = Modifier.size(sizeDp * 0.52f)
        )
    }
}

@Composable
private fun FocusableActionPill(
    text: String,
    isPrimary: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .scale(if (focused) 1.08f else 1.0f)
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick()
                    true
                } else false
            }
            .focusable()
            .background(
                when {
                    focused -> Color.White
                    isPrimary -> BeinPrimaryPurple
                    else -> Color(0xD90E071C)
                }
            )
            .border(
                width = if (focused) 2.8.dp else 1.2.dp,
                color = if (focused) BeinFocusGlow else BeinHighlightPurple.copy(alpha = 0.75f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = if (compact) 6.dp else 8.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (focused) Color.Black else Color.White,
            fontSize = if (compact) 10.sp else 12.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun InteractiveTrackAndSpeedModal(
    player: ExoPlayer,
    tracks: Tracks,
    currentSpeed: PlaybackSpeedOption,
    onSelectSpeed: (PlaybackSpeedOption) -> Unit,
    compact: Boolean,
    onDismiss: () -> Unit
) {
    var selectedSection by remember { mutableStateOf(0) }
    val firstItemFocus = remember { FocusRequester() }

    val videoOptions = remember(tracks) {
        val list = mutableListOf<TrackOption>()
        tracks.groups.forEachIndexed { gIndex, group ->
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (tIndex in 0 until group.length) {
                    val fmt = group.getTrackFormat(tIndex)
                    if (fmt.height > 0) {
                        val badge = when {
                            fmt.height >= 2160 -> "4K UHD"
                            fmt.height >= 1080 -> "Full HD 1080p"
                            fmt.height >= 720 -> "HD 720p"
                            else -> "SD ${fmt.height}p"
                        }
                        val br = if (fmt.bitrate > 0) "${fmt.bitrate / 1000} kbps" else "Adaptive"
                        val fps = if (fmt.frameRate > 0) " • ${fmt.frameRate.toInt()}fps" else ""
                        list.add(
                            TrackOption(
                                title = "$badge (${fmt.width}×${fmt.height})",
                                subtitle = "معدل البث: $br$fps",
                                groupIndex = gIndex,
                                trackIndex = tIndex,
                                isSelected = group.isTrackSelected(tIndex)
                            )
                        )
                    }
                }
            }
        }
        list.sortedByDescending { it.title }
    }

    val audioOptions = remember(tracks) {
        val list = mutableListOf<TrackOption>()
        var commentatorNum = 1
        tracks.groups.forEachIndexed { gIndex, group ->
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (tIndex in 0 until group.length) {
                    val fmt = group.getTrackFormat(tIndex)
                    val rawLabel = fmt.label?.takeIf { it.isNotBlank() }
                    val rawLang = fmt.language?.takeIf { it.isNotBlank() && it != "und" }
                    val title = when {
                        rawLabel != null && rawLang != null -> "معلق / صوت $commentatorNum: $rawLabel ($rawLang)"
                        rawLabel != null -> "معلق / صوت $commentatorNum: $rawLabel"
                        rawLang != null -> "معلق / مسار صوتي $commentatorNum ($rawLang)"
                        else -> "المعلق الصوتي / المسار رقم $commentatorNum"
                    }
                    val channelsDesc = if (fmt.channelCount >= 6) "صوت محيطي 5.1" else "ستيريو واضح"
                    val br = if (fmt.bitrate > 0) " • ${fmt.bitrate / 1000} kbps" else ""
                    list.add(
                        TrackOption(
                            title = title,
                            subtitle = "$channelsDesc$br",
                            groupIndex = gIndex,
                            trackIndex = tIndex,
                            isSelected = group.isTrackSelected(tIndex)
                        )
                    )
                    commentatorNum++
                }
            }
        }
        list
    }

    val hasVideoOverride = remember(player.trackSelectionParameters) {
        player.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }
    }

    LaunchedEffect(Unit) {
        delay(80L)
        runCatching { firstItemFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB3000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 320.dp, max = if (compact) 540.dp else 640.dp)
                    .fillMaxWidth(0.90f)
                    .fillMaxHeight(if (compact) 0.88f else 0.80f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF080411))
                    .border(2.dp, BeinHighlightPurple, RoundedCornerShape(18.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
                    .padding(if (compact) 12.dp else 18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "إعدادات الجودة والمعلق الصوتي وسرعة العرض ⚙️",
                            color = Color.White,
                            fontSize = if (compact) 13.sp else 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "تنقل بأسهم الريموت (▲▼◄►) واضغط (OK) للاختيار الفوري",
                            color = Color.LightGray,
                            fontSize = if (compact) 9.sp else 11.sp
                        )
                    }
                    FocusableCircleButton(
                        onClick = onDismiss,
                        icon = Icons.Default.Close,
                        contentDescription = "إغلاق",
                        sizeDp = if (compact) 32.dp else 38.dp
                    )
                }

                Spacer(Modifier.height(if (compact) 6.dp else 10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    InteractiveModalTabButton(
                        title = "📺 الجودة (${videoOptions.size + 1})",
                        selected = selectedSection == 0,
                        compact = compact,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(firstItemFocus),
                        onClick = { selectedSection = 0 }
                    )
                    InteractiveModalTabButton(
                        title = "🎙 المعلق (${audioOptions.size.coerceAtLeast(1)})",
                        selected = selectedSection == 1,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedSection = 1 }
                    )
                    InteractiveModalTabButton(
                        title = "⚡ السرعة",
                        selected = selectedSection == 2,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedSection = 2 }
                    )
                }

                Spacer(Modifier.height(if (compact) 6.dp else 10.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when (selectedSection) {
                        0 -> {
                            item {
                                InteractiveTrackCard(
                                    title = "تلقائي متكيّف (Auto Adaptive HD) — موصى به",
                                    subtitle = "يختار أعلى جودة مستقرة حسب سرعة الاتصال",
                                    isSelected = !hasVideoOverride,
                                    compact = compact,
                                    onClick = {
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                            .build()
                                        onDismiss()
                                    }
                                )
                            }

                            itemsIndexed(videoOptions) { _, opt ->
                                InteractiveTrackCard(
                                    title = opt.title,
                                    subtitle = opt.subtitle,
                                    isSelected = hasVideoOverride && opt.isSelected,
                                    compact = compact,
                                    onClick = {
                                        val group = tracks.groups[opt.groupIndex].mediaTrackGroup
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .setOverrideForType(
                                                TrackSelectionOverride(group, opt.trackIndex)
                                            )
                                            .build()
                                        onDismiss()
                                    }
                                )
                            }
                        }
                        1 -> {
                            if (audioOptions.isEmpty()) {
                                item {
                                    InteractiveTrackCard(
                                        title = "المسار الصوتي الافتراضي للمصدر",
                                        subtitle = "هذه القناة تبث بمسار تعليق صوتي موحّد",
                                        isSelected = true,
                                        compact = compact,
                                        onClick = onDismiss
                                    )
                                }
                            } else {
                                itemsIndexed(audioOptions) { _, opt ->
                                    InteractiveTrackCard(
                                        title = opt.title,
                                        subtitle = opt.subtitle,
                                        isSelected = opt.isSelected,
                                        compact = compact,
                                        onClick = {
                                            val group = tracks.groups[opt.groupIndex].mediaTrackGroup
                                            player.trackSelectionParameters = player.trackSelectionParameters
                                                .buildUpon()
                                                .setOverrideForType(
                                                    TrackSelectionOverride(group, opt.trackIndex)
                                                )
                                                .build()
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                        }
                        else -> {
                            itemsIndexed(PlaybackSpeedOption.values().toList()) { _, spd ->
                                InteractiveTrackCard(
                                    title = "سرعة التشغيل: ${spd.labelAr}",
                                    subtitle = if (spd == PlaybackSpeedOption.NORMAL) {
                                        "السرعة المثالية لاستقرار القنوات المباشرة"
                                    } else {
                                        "تغيير سرعة عرض الفيديو والصوت"
                                    },
                                    isSelected = currentSpeed == spd,
                                    compact = compact,
                                    onClick = {
                                        onSelectSpeed(spd)
                                        onDismiss()
                                    }
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
private fun InteractiveModalTabButton(
    title: String,
    selected: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(if (compact) 34.dp else 42.dp)
            .scale(if (focused) 1.05f else 1.0f)
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick()
                    true
                } else false
            }
            .focusable()
            .background(
                when {
                    focused -> Color.White
                    selected -> BeinPrimaryPurple
                    else -> Color(0xFF0E071C)
                }
            )
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) BeinFocusGlow else if (selected) Color.White else Color(0xFF1F1037),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (focused) Color.Black else Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (compact) 9.sp else 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InteractiveTrackCard(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (focused) 1.02f else 1.0f)
            .clip(RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick()
                    true
                } else false
            }
            .focusable()
            .background(
                when {
                    focused -> Color.White
                    isSelected -> BeinPrimaryPurple.copy(alpha = 0.55f)
                    else -> Color(0xFF0E071C)
                }
            )
            .border(
                width = if (focused) 2.8.dp else if (isSelected) 1.5.dp else 1.dp,
                color = when {
                    focused -> BeinFocusGlow
                    isSelected -> Color.White
                    else -> Color(0xFF1F1037)
                },
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = if (compact) 8.dp else 11.dp
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (focused) Color.Black else Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (compact) 11.sp else 13.sp
            )
            Text(
                text = subtitle,
                color = if (focused) Color.DarkGray else Color.LightGray,
                fontSize = if (compact) 9.sp else 11.sp
            )
        }

        Box(
            modifier = Modifier
                .size(if (compact) 20.dp else 24.dp)
                .clip(CircleShape)
                .background(if (isSelected) EmeraldLive else Color(0xFF180C2E))
                .border(1.dp, if (focused) Color.Black else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "محدد",
                    tint = Color.Black,
                    modifier = Modifier.size(if (compact) 13.dp else 15.dp)
                )
            }
        }
    }
}
