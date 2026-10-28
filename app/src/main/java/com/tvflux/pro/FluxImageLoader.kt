package com.tvflux.pro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val SharedAvatarBgBrush = Brush.linearGradient(
    listOf(Color(0xFF100720), Color(0xFF0A0A0E))
)

/**
 * Turbo-Light Bounded Two-Tier Thumbnail Cache (v17.0):
 * 1. RAM LruCache in RGB_565 (2 bytes/pixel, 50% RAM savings).
 * 2. Fast ConcurrentHashMap URL->Key lookup (eliminates repeated SHA-256 on scroll).
 * 3. Scroll-settle debounce (65ms) in ChannelAvatar so rapid D-Pad scrolling never triggers disk/network I/O for off-screen rows.
 */
object FluxBitmapCache {
    private val maxRamKb: Int = ((Runtime.getRuntime().maxMemory() / 1024L) / 8L)
        .toInt()
        .coerceIn(4 * 1024, 20 * 1024)

    private val memoryCache = object : LruCache<String, ImageBitmap>(maxRamKb) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return ((value.width * value.height * 2) / 1024).coerceAtLeast(1)
        }
    }

    private val keyCache = ConcurrentHashMap<String, String>(2048)
    private val downloadSemaphore = Semaphore(8)
    private val writeCounter = AtomicInteger(0)

    @Volatile
    var maxDiskCacheBytes: Long = ThumbnailCacheLimitOption.MB_64.maxBytes

    fun get(url: String): ImageBitmap? = synchronized(memoryCache) { memoryCache.get(url) }

    fun put(url: String, bmp: ImageBitmap) = synchronized(memoryCache) {
        memoryCache.put(url, bmp)
    }

    fun getCacheSizeMb(context: Context): String {
        val thumbDir = File(context.cacheDir, "flux_thumbs")
        val bytes = if (thumbDir.exists()) {
            thumbDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else 0L
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    fun enforceDiskQuota(context: Context, limitBytes: Long = maxDiskCacheBytes) {
        runCatching {
            val thumbDir = File(context.cacheDir, "flux_thumbs")
            if (!thumbDir.exists()) return
            val files = thumbDir.listFiles()?.filter { it.isFile } ?: return
            var totalBytes = files.sumOf { it.length() }
            if (totalBytes <= limitBytes) return

            val targetBytes = (limitBytes * 85L) / 100L
            val sortedOldest = files.sortedBy { it.lastModified() }
            for (f in sortedOldest) {
                val len = f.length()
                if (f.delete()) {
                    totalBytes -= len
                    if (totalBytes <= targetBytes) break
                }
            }
        }
    }

    fun clear(context: Context) {
        synchronized(memoryCache) { memoryCache.evictAll() }
        keyCache.clear()
        runCatching {
            File(context.cacheDir, "flux_thumbs").deleteRecursively()
        }
    }

    suspend fun load(context: Context, url: String, maxDimensionPx: Int = 180): ImageBitmap? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        get(url)?.let { return it }
        val effectiveMaxPx = if (DevicePerformanceDetector.isLowRamOrWeakDevice(context)) {
            maxDimensionPx.coerceAtMost(128)
        } else {
            maxDimensionPx.coerceAtMost(180)
        }

        return withContext(Dispatchers.IO) {
            downloadSemaphore.withPermit {
                get(url)?.let { return@withPermit it }
                try {
                    val cacheDir = File(context.cacheDir, "flux_thumbs").apply { mkdirs() }
                    val diskFile = File(cacheDir, diskKeyForUrl(url))

                    if (diskFile.exists() && diskFile.length() > 64L) {
                        val cachedBytes = diskFile.readBytes()
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val bmp = BitmapFactory.decodeByteArray(cachedBytes, 0, cachedBytes.size, opts)
                        if (bmp != null) {
                            val imgBmp = bmp.asImageBitmap()
                            put(url, imgBmp)
                            return@withPermit imgBmp
                        }
                    }

                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Android; LIVE-PRO/17.0)")
                        .header("Accept", "image/*,*/*;q=0.8")
                        .build()

                    val rawBytes: ByteArray = ChannelRepository.httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withPermit null
                        val b = resp.body?.bytes() ?: return@withPermit null
                        if (b.size < 64 || b.size > (3 * 1024 * 1024)) return@withPermit null
                        b
                    }

                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withPermit null

                    var sampleSize = 1
                    while (bounds.outWidth / sampleSize > effectiveMaxPx ||
                        bounds.outHeight / sampleSize > effectiveMaxPx
                    ) {
                        sampleSize *= 2
                    }

                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                        ?: return@withPermit null

                    runCatching {
                        val bos = ByteArrayOutputStream(12288)
                        @Suppress("DEPRECATION")
                        bmp.compress(Bitmap.CompressFormat.WEBP, 80, bos)
                        val thumbBytes = bos.toByteArray()
                        if (thumbBytes.size > 64) {
                            diskFile.writeBytes(thumbBytes)
                        }
                        if (writeCounter.incrementAndGet() % 40 == 0) {
                            enforceDiskQuota(context, maxDiskCacheBytes)
                        }
                    }

                    val imgBmp = bmp.asImageBitmap()
                    put(url, imgBmp)
                    imgBmp
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private fun diskKeyForUrl(input: String): String {
        keyCache[input]?.let { return it }
        val computed = runCatching {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            buildString(32) {
                for (i in 0 until 16) {
                    val b = digest[i].toInt() and 0xFF
                    append("0123456789abcdef"[b ushr 4])
                    append("0123456789abcdef"[b and 0x0F])
                }
            }
        }.getOrElse { input.hashCode().toUInt().toString(16) }
        if (keyCache.size > 8192) keyCache.clear()
        keyCache[input] = computed
        return computed
    }
}

@Composable
fun BrandLogoBadge(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf(FluxBitmapCache.get(APP_LOGO_URL)) }

    LaunchedEffect(Unit) {
        if (bitmap == null) {
            bitmap = FluxBitmapCache.load(context, APP_LOGO_URL, maxDimensionPx = 220)
        }
    }

    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF120A1E))
            .border(1.5.dp, BeinHighlightPurple.copy(alpha = 0.85f), shape),
        contentAlignment = Alignment.Center
    ) {
        val currentBmp = bitmap
        if (currentBmp != null) {
            Image(
                bitmap = currentBmp,
                contentDescription = APP_BRAND_NAME,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = APP_BRAND_NAME,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun ChannelAvatar(
    name: String,
    logoUrl: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    contentScale: ContentScale = ContentScale.Fit,
    innerPadding: Dp = 3.dp
) {
    val context = LocalContext.current
    var bitmap by remember(logoUrl) {
        mutableStateOf(logoUrl?.let { FluxBitmapCache.get(it) })
    }

    LaunchedEffect(logoUrl) {
        if (bitmap == null && !logoUrl.isNullOrBlank()) {
            // 65ms scroll-settle debounce: cancels off-screen logo loads automatically during fast D-Pad scrolling
            delay(65L)
            bitmap = FluxBitmapCache.load(context, logoUrl, maxDimensionPx = 160)
        }
    }

    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .clip(shape)
            .background(SharedAvatarBgBrush),
        contentAlignment = Alignment.Center
    ) {
        val currentBmp = bitmap
        if (currentBmp != null) {
            Image(
                bitmap = currentBmp,
                contentDescription = name,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
            val initials = remember(name) {
                val trimmed = name.trim()
                if (trimmed.length >= 2) trimmed.take(2).uppercase() else if (trimmed.isNotEmpty()) trimmed.uppercase() else "TV"
            }
            Text(
                text = initials,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}
