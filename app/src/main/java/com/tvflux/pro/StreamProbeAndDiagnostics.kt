package com.tvflux.pro

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Guards against channel mixing and stale async stream responses when zapping rapidly.
 */
object PlaybackSessionGuard {
    private val sessionCounter = AtomicLong(0L)

    @Volatile
    var currentActiveChannelKey: String = ""
        private set

    fun beginSession(channelStableKey: String): Long {
        currentActiveChannelKey = channelStableKey
        ChannelRepository.cancelPendingStreamRequests()
        return sessionCounter.incrementAndGet()
    }

    fun isSessionCurrent(sessionId: Long, channelStableKey: String): Boolean {
        return sessionCounter.get() == sessionId && currentActiveChannelKey == channelStableKey
    }
}

/**
 * Inspects the Android device's hardware & software MediaCodec capabilities
 * for H.264, H.265/HEVC, VP8, VP9, AV1, MPEG-2, and audio codecs (AAC, AC3, E-AC3, DTS, Opus, MP3).
 */
object CodecCapabilityInspector {

    data class DeviceCodecReport(
        val videoCodecs: List<String>,
        val audioCodecs: List<String>,
        val hasHardwareHevc: Boolean,
        val hasHardwareAv1: Boolean,
        val hasHardwareVp9: Boolean
    )

    val report: DeviceCodecReport by lazy { inspectDeviceCodecs() }

    private fun inspectDeviceCodecs(): DeviceCodecReport {
        val videoSet = LinkedHashSet<String>()
        val audioSet = LinkedHashSet<String>()
        var hwHevc = false
        var hwAv1 = false
        var hwVp9 = false

        runCatching {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            for (info in codecList) {
                if (info.isEncoder) continue
                val codecName = info.name.lowercase(Locale.ROOT)
                val isSoftware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isSoftwareOnly
                } else {
                    codecName.startsWith("omx.google.") ||
                        codecName.startsWith("c2.android.") ||
                        codecName.contains("sw") ||
                        codecName.contains("ffmpeg")
                }
                val modeTag = if (isSoftware) "SW" else "HW"

                for (type in info.supportedTypes) {
                    val low = type.lowercase(Locale.ROOT)
                    when {
                        low == "video/avc" -> videoSet.add("H.264/AVC ($modeTag)")
                        low == "video/hevc" -> {
                            videoSet.add("H.265/HEVC ($modeTag)")
                            if (!isSoftware) hwHevc = true
                        }
                        low.contains("vp8") -> videoSet.add("VP8 ($modeTag)")
                        low.contains("vp9") -> {
                            videoSet.add("VP9 ($modeTag)")
                            if (!isSoftware) hwVp9 = true
                        }
                        low.contains("av01") || low.contains("av1") -> {
                            videoSet.add("AV1 ($modeTag)")
                            if (!isSoftware) hwAv1 = true
                        }
                        low.contains("mpeg2") -> videoSet.add("MPEG-2 ($modeTag)")
                        low.contains("mp4v-es") -> videoSet.add("MPEG-4 ($modeTag)")
                        low == "audio/mp4a-latm" -> audioSet.add("AAC")
                        low == "audio/ac3" -> audioSet.add("AC-3 (Dolby)")
                        low == "audio/eac3" -> audioSet.add("E-AC3 (Dolby+)")
                        low.contains("dts") -> audioSet.add("DTS")
                        low == "audio/opus" -> audioSet.add("Opus")
                        low == "audio/mpeg" -> audioSet.add("MP3")
                        low == "audio/vorbis" -> audioSet.add("Vorbis")
                        low == "audio/flac" -> audioSet.add("FLAC")
                    }
                }
            }
        }

        if (videoSet.isEmpty()) {
            videoSet.addAll(listOf("H.264/AVC (HW)", "H.265/HEVC (Auto)", "VP9 (Auto)"))
        }
        if (audioSet.isEmpty()) {
            audioSet.addAll(listOf("AAC", "MP3", "Opus"))
        }

        return DeviceCodecReport(
            videoCodecs = videoSet.toList(),
            audioCodecs = audioSet.toList(),
            hasHardwareHevc = hwHevc,
            hasHardwareAv1 = hwAv1,
            hasHardwareVp9 = hwVp9
        )
    }

    fun inferVideoCodecFromText(name: String, url: String): String {
        val combined = "$name $url".lowercase(Locale.ROOT)
        return when {
            combined.contains("av1") || combined.contains("av01") -> "AV1"
            combined.contains("hevc") || combined.contains("h265") || combined.contains("h.265") || combined.contains("x265") || combined.contains("4k") || combined.contains("uhd") -> "H.265/HEVC"
            combined.contains("vp9") -> "VP9"
            combined.contains("vp8") -> "VP8"
            else -> "H.264/AVC"
        }
    }

    fun inferAudioCodecFromText(name: String, url: String): String {
        val combined = "$name $url".lowercase(Locale.ROOT)
        return when {
            combined.contains("eac3") || combined.contains("atmos") || combined.contains("dd+") -> "E-AC3"
            combined.contains("ac3") || combined.contains("dolby") -> "AC-3"
            combined.contains("dts") -> "DTS"
            combined.contains("opus") -> "Opus"
            combined.contains("flac") -> "FLAC"
            else -> "AAC"
        }
    }

    fun shouldUseSoftwareDecoderFallback(videoCodecHint: String): Boolean {
        return when (videoCodecHint) {
            "AV1" -> !report.hasHardwareAv1
            "H.265/HEVC" -> !report.hasHardwareHevc
            "VP9" -> !report.hasHardwareVp9
            else -> false
        }
    }
}

/**
 * TiviMate-Style Stream URL Prober, Token Renewer & Magic-Byte Format Detector.
 * - Detects stream container & MIME type from URL, HTTP Content-Type header, AND first 512 magic bytes
 *   (#EXTM3U, <MPD, 0x47 MPEG-TS sync byte, ftyp MP4/MOV, EBML MKV/WebM, RIFF AVI, FLV).
 * - Follows 301/302 redirect tokens and captures fresh Set-Cookie headers before playback.
 * - Never downloads the full video file (closes the stream immediately after reading 512 header bytes).
 */
object StreamFormatDetectorAndRenewer {

    private val probeHttpClient by lazy {
        ChannelRepository.streamHttpClient.newBuilder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .callTimeout(3800, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun detectFastFromUrlAndMetadata(channel: TvChannel, targetUrl: String, userAgent: String): DetectedStreamSpec {
        val low = targetUrl.lowercase(Locale.ROOT).substringBefore("?")
        val fullLow = targetUrl.lowercase(Locale.ROOT)
        val vCodec = channel.videoCodecHint.ifBlank { CodecCapabilityInspector.inferVideoCodecFromText(channel.name, targetUrl) }
        val aCodec = channel.audioCodecHint.ifBlank { CodecCapabilityInspector.inferAudioCodecFromText(channel.name, targetUrl) }
        val swFallback = CodecCapabilityInspector.shouldUseSoftwareDecoderFallback(vCodec)

        val (container, mime) = when {
            low.endsWith(".mpd") || fullLow.contains("format=mpd") || fullLow.contains("type=mpd") ->
                "MPEG-DASH (MPD)" to MimeTypes.APPLICATION_MPD
            low.endsWith(".m3u8") || fullLow.contains("output=m3u8") || fullLow.contains("type=m3u8") || fullLow.contains("output=hls") ->
                "HLS (M3U8)" to MimeTypes.APPLICATION_M3U8
            low.endsWith(".ts") || fullLow.contains("output=ts") || fullLow.contains("type=ts") ->
                "MPEG-TS" to MimeTypes.VIDEO_MP2T
            low.endsWith(".mkv") ->
                "MKV (Matroska)" to MimeTypes.VIDEO_MATROSKA
            low.endsWith(".webm") ->
                "WebM" to MimeTypes.VIDEO_WEBM
            low.endsWith(".mp4") || low.endsWith(".m4v") || low.endsWith(".mov") ->
                "MP4 / MOV" to MimeTypes.VIDEO_MP4
            low.endsWith(".avi") ->
                "AVI" to MimeTypes.VIDEO_AVI
            low.endsWith(".flv") ->
                "FLV" to MimeTypes.VIDEO_FLV
            low.contains(".ism") ->
                "SmoothStreaming" to MimeTypes.APPLICATION_SS
            low.startsWith("rtsp://") ->
                "RTSP" to MimeTypes.APPLICATION_RTSP
            channel.contentType == StreamContentType.LIVE_TV ->
                "MPEG-TS / HLS Auto" to ""
            else ->
                "VOD Container Auto" to ""
        }

        return DetectedStreamSpec(
            resolvedUrl = targetUrl,
            containerFormat = container,
            mimeType = mime,
            videoCodecHint = vCodec,
            audioCodecHint = aCodec,
            preferSoftwareDecoder = swFallback
        )
    }

    /**
     * Probes the stream endpoint (reading at most 512 bytes) to renew redirect tokens/cookies
     * and inspect Content-Type + Magic Bytes when the URL has no explicit extension or fails initial open.
     */
    suspend fun probeStreamEndpoint(
        channel: TvChannel,
        targetUrl: String,
        userAgent: String
    ): DetectedStreamSpec = withContext(Dispatchers.IO) {
        val baseSpec = detectFastFromUrlAndMetadata(channel, targetUrl, userAgent)
        if (targetUrl.startsWith("rtsp://", ignoreCase = true) ||
            targetUrl.startsWith("rtmp://", ignoreCase = true) ||
            targetUrl.startsWith("udp://", ignoreCase = true)
        ) {
            return@withContext baseSpec
        }

        val startMs = SystemClock.elapsedRealtime()
        try {
            val reqBuilder = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .header("Range", "bytes=0-511")
                .header("Connection", "keep-alive")

            if (!channel.referer.isNullOrBlank()) reqBuilder.header("Referer", channel.referer)
            if (!channel.origin.isNullOrBlank()) reqBuilder.header("Origin", channel.origin)
            if (!channel.cookie.isNullOrBlank()) reqBuilder.header("Cookie", channel.cookie)

            probeHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val contentTypeHeader = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                val setCookie = resp.headers("Set-Cookie").firstOrNull()?.substringBefore(";")?.trim()

                val magicBuf = ByteArray(512)
                var readLen = 0
                resp.body?.byteStream()?.use { ins ->
                    readLen = ins.read(magicBuf, 0, magicBuf.size).coerceAtLeast(0)
                }

                val magicAscii = if (readLen > 0) {
                    String(magicBuf, 0, readLen.coerceAtMost(128), Charsets.ISO_8859_1).trimStart()
                } else ""

                val (detectedContainer, detectedMime) = when {
                    contentTypeHeader.contains("dash+xml") || magicAscii.startsWith("<MPD", ignoreCase = true) || magicAscii.contains("<MPD", ignoreCase = true) ->
                        "MPEG-DASH (MPD)" to MimeTypes.APPLICATION_MPD
                    contentTypeHeader.contains("mpegurl") || contentTypeHeader.contains("x-mpegurl") || magicAscii.startsWith("#EXTM3U", ignoreCase = true) ->
                        "HLS (M3U8)" to MimeTypes.APPLICATION_M3U8
                    contentTypeHeader.contains("mp2t") || (readLen >= 189 && magicBuf[0] == 0x47.toByte() && magicBuf[188] == 0x47.toByte()) || (readLen >= 1 && magicBuf[0] == 0x47.toByte()) ->
                        "MPEG-TS" to MimeTypes.VIDEO_MP2T
                    contentTypeHeader.contains("matroska") || (readLen >= 4 && magicBuf[0] == 0x1A.toByte() && magicBuf[1] == 0x45.toByte() && magicBuf[2] == 0xDF.toByte() && magicBuf[3] == 0xA3.toByte()) -> {
                        if (contentTypeHeader.contains("webm") || magicAscii.lowercase(Locale.ROOT).contains("webm")) {
                            "WebM" to MimeTypes.VIDEO_WEBM
                        } else {
                            "MKV (Matroska)" to MimeTypes.VIDEO_MATROSKA
                        }
                    }
                    contentTypeHeader.contains("video/mp4") || contentTypeHeader.contains("quicktime") || (readLen >= 8 && String(magicBuf, 4, 4, Charsets.ISO_8859_1) == "ftyp") ->
                        "MP4 / MOV" to MimeTypes.VIDEO_MP4
                    contentTypeHeader.contains("msvideo") || contentTypeHeader.contains("x-msvideo") || (magicAscii.startsWith("RIFF") && magicAscii.contains("AVI")) ->
                        "AVI" to MimeTypes.VIDEO_AVI
                    contentTypeHeader.contains("x-flv") || magicAscii.startsWith("FLV") ->
                        "FLV" to MimeTypes.VIDEO_FLV
                    else -> baseSpec.containerFormat to baseSpec.mimeType
                }

                val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                TiviMateDiagnostics.logEvent(
                    "🔍 فحص الصيغة [${channel.name.take(22)}]: $detectedContainer • ${baseSpec.videoCodecHint}/${baseSpec.audioCodecHint} (${elapsed}ms)"
                )

                baseSpec.copy(
                    resolvedUrl = finalUrl.ifBlank { targetUrl },
                    containerFormat = detectedContainer,
                    mimeType = detectedMime,
                    freshCookie = setCookie ?: channel.cookie,
                    probeLatencyMs = elapsed,
                    diagnosticNote = "$detectedContainer • ${baseSpec.videoCodecHint}"
                )
            }
        } catch (_: Exception) {
            baseSpec
        }
    }
}

/**
 * Real-time Performance, Memory, SQLite Storage & Sync Diagnostics Logger.
 */
object TiviMateDiagnostics {
    private val logs = CopyOnWriteArrayList<String>()
    private const val MAX_LOGS = 40

    @Volatile
    var lastQueryTimeMs: Long = 2L
        private set

    @Volatile
    var lastSyncDurationMs: Long = 0L
        private set

    @Volatile
    var lastSyncSummary: String = "المكتبة المحلية جاهزة"
        private set

    fun logEvent(message: String) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logs.add(0, "[$timeStr] $message")
        while (logs.size > MAX_LOGS) {
            logs.removeAt(logs.lastIndex)
        }
    }

    fun recordQueryLatency(ms: Long) {
        lastQueryTimeMs = ms
    }

    fun recordSyncStats(sourceName: String, stats: DifferentialSyncStats) {
        lastSyncDurationMs = stats.durationMs
        lastSyncSummary = "$sourceName: +${stats.addedCount} جديد • ~${stats.updatedCount} محدّث • -${stats.removedCount} محذوف • =${stats.unchangedCount} ثابت (${stats.durationMs}ms)"
        logEvent("✅ مزامنة تفاضلية [$lastSyncSummary]")
    }

    suspend fun buildSnapshot(context: Context): DatabasePerformanceMetrics {
        val db = TiviMateLocalDatabase.getInstance(context)
        val (liveCnt, movieCnt, seriesCnt) = db.getCountsSummary()
        val rt = Runtime.getRuntime()
        val usedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
        val maxMb = (rt.maxMemory() / (1024 * 1024)).toInt()
        val codecReport = CodecCapabilityInspector.report

        return DatabasePerformanceMetrics(
            totalChannelsInDb = liveCnt,
            totalMoviesInDb = movieCnt,
            totalSeriesInDb = seriesCnt,
            totalBouquetsInDb = 0,
            dbFileSizeMb = db.getDatabaseSizeMb(),
            thumbCacheSizeMb = FluxBitmapCache.getCacheSizeMb(context),
            ramUsedMb = usedMb,
            ramMaxMb = maxMb,
            lastQueryTimeMs = lastQueryTimeMs,
            lastSyncDurationMs = lastSyncDurationMs,
            lastSyncSummary = lastSyncSummary,
            supportedVideoCodecs = codecReport.videoCodecs,
            supportedAudioCodecs = codecReport.audioCodecs,
            recentDiagnosticLogs = logs.toList()
        )
    }
}
