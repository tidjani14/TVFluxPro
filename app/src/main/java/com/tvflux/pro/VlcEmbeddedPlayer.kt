package com.tvflux.pro

import android.content.Context
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.util.ArrayList

/**
 * Reflection-safe Embedded LibVLC Video Engine (org.videolan.libvlc.LibVLC / MediaPlayer / Media / IVLCVout).
 * Allows the customer to choose the genuine VLC internal player engine programmatically inside LIVE PRO
 * with 100% compile-time safety and automatic fallback to ExoPlayer if native LibVLC cannot initialize on an exotic ABI.
 */
object VlcEngineBridge {

    fun isLibVlcRuntimeAvailable(): Boolean {
        return runCatching {
            Class.forName("org.videolan.libvlc.LibVLC")
            Class.forName("org.videolan.libvlc.MediaPlayer")
            Class.forName("org.videolan.libvlc.Media")
            true
        }.getOrDefault(false)
    }
}

class EmbeddedVlcController(private val context: Context) {
    private var libVlcInstance: Any? = null
    private var mediaPlayerInstance: Any? = null
    private var currentSurfaceView: SurfaceView? = null
    private var isReleased = false

    var onBufferingChanged: ((Boolean) -> Unit)? = null
    var onPlayingChanged: ((Boolean) -> Unit)? = null
    var onErrorOccurred: ((String) -> Unit)? = null
    var onCodecDetected: ((String) -> Unit)? = null
    var onVideoSizeDetected: ((Int, Int) -> Unit)? = null

    fun initialize(preferSoftwareDecoder: Boolean, isLowRam: Boolean, isVod: Boolean): Boolean {
        return runCatching {
            val libVlcClass = Class.forName("org.videolan.libvlc.LibVLC")
            val mediaPlayerClass = Class.forName("org.videolan.libvlc.MediaPlayer")
            val iLibVlcClass = Class.forName("org.videolan.libvlc.interfaces.ILibVLC")

            val options = ArrayList<String>().apply {
                add("--network-caching=${if (isVod) 450 else if (isLowRam) 1200 else 1800}")
                add("--file-caching=${if (isVod) 300 else 1000}")
                add("--live-caching=${if (isLowRam) 1200 else 1800}")
                add("--clock-jitter=0")
                add("--clock-synchro=0")
                add("--drop-late-frames")
                add("--skip-frames")
                add("--http-reconnect")
                if (preferSoftwareDecoder) {
                    add("--codec=avcodec,all")
                }
            }

            val ctor = libVlcClass.getConstructor(Context::class.java, List::class.java)
            val libVlc = ctor.newInstance(context.applicationContext, options)
            val mpCtor = mediaPlayerClass.getConstructor(iLibVlcClass)
            val mp = mpCtor.newInstance(libVlc)

            libVlcInstance = libVlc
            mediaPlayerInstance = mp
            true
        }.getOrElse {
            false
        }
    }

    fun attachSurface(surfaceView: SurfaceView, width: Int, height: Int) {
        currentSurfaceView = surfaceView
        val mp = mediaPlayerInstance ?: return
        runCatching {
            val vout = mp.javaClass.getMethod("getVLCVout").invoke(mp) ?: return
            val detachMethod = vout.javaClass.getMethod("detachViews")
            runCatching { detachMethod.invoke(vout) }

            val setVideoSurfaceMethod = vout.javaClass.getMethod(
                "setVideoSurface",
                android.view.Surface::class.java,
                SurfaceHolder::class.java
            )
            setVideoSurfaceMethod.invoke(vout, surfaceView.holder.surface, surfaceView.holder)

            val attachMethod = vout.javaClass.getMethod("attachViews")
            attachMethod.invoke(vout)

            if (width > 0 && height > 0) {
                val setWindowSize = vout.javaClass.getMethod("setWindowSize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                setWindowSize.invoke(vout, width, height)
            }
        }
    }

    fun updateWindowSize(width: Int, height: Int) {
        val mp = mediaPlayerInstance ?: return
        if (width <= 0 || height <= 0) return
        runCatching {
            val vout = mp.javaClass.getMethod("getVLCVout").invoke(mp) ?: return
            val setWindowSize = vout.javaClass.getMethod("setWindowSize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            setWindowSize.invoke(vout, width, height)
        }
    }

    fun playStream(
        url: String,
        userAgent: String,
        referer: String?,
        cookie: String?,
        isVod: Boolean,
        preferSoftware: Boolean,
        startPositionMs: Long = 0L
    ) {
        val libVlc = libVlcInstance ?: return
        val mp = mediaPlayerInstance ?: return
        runCatching {
            onBufferingChanged?.invoke(true)
            val stopMethod = mp.javaClass.getMethod("stop")
            runCatching { stopMethod.invoke(mp) }

            val iLibVlcClass = Class.forName("org.videolan.libvlc.interfaces.ILibVLC")
            val mediaClass = Class.forName("org.videolan.libvlc.Media")
            val iMediaClass = Class.forName("org.videolan.libvlc.interfaces.IMedia")

            val mediaCtor = mediaClass.getConstructor(iLibVlcClass, Uri::class.java)
            val media = mediaCtor.newInstance(libVlc, Uri.parse(url.trim()))

            val setHwDecoder = mediaClass.getMethod(
                "setHWDecoderEnabled",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            setHwDecoder.invoke(media, !preferSoftware, false)

            val addOption = mediaClass.getMethod("addOption", String::class.java)
            addOption.invoke(media, ":http-user-agent=$userAgent")
            addOption.invoke(media, ":network-caching=${if (isVod) 400 else 1600}")
            addOption.invoke(media, ":http-reconnect")
            if (!referer.isNullOrBlank()) {
                addOption.invoke(media, ":http-referrer=$referer")
            }
            if (!cookie.isNullOrBlank()) {
                addOption.invoke(media, ":http-cookies=$cookie")
            }

            val setMedia = mp.javaClass.getMethod("setMedia", iMediaClass)
            setMedia.invoke(mp, media)

            val releaseMedia = mediaClass.getMethod("release")
            releaseMedia.invoke(media)

            val playMethod = mp.javaClass.getMethod("play")
            playMethod.invoke(mp)

            if (isVod && startPositionMs > 5_000L) {
                setTimeMs(startPositionMs)
            }
            onPlayingChanged?.invoke(true)
        }.onFailure { e ->
            onErrorOccurred?.invoke(e.message ?: "VLC playback error")
        }
    }

    fun isPlaying(): Boolean {
        val mp = mediaPlayerInstance ?: return false
        return runCatching {
            mp.javaClass.getMethod("isPlaying").invoke(mp) as? Boolean ?: false
        }.getOrDefault(false)
    }

    fun play() {
        val mp = mediaPlayerInstance ?: return
        runCatching {
            mp.javaClass.getMethod("play").invoke(mp)
            onPlayingChanged?.invoke(true)
        }
    }

    fun pause() {
        val mp = mediaPlayerInstance ?: return
        runCatching {
            mp.javaClass.getMethod("pause").invoke(mp)
            onPlayingChanged?.invoke(false)
        }
    }

    fun getTimeMs(): Long {
        val mp = mediaPlayerInstance ?: return 0L
        return runCatching {
            (mp.javaClass.getMethod("getTime").invoke(mp) as? Long)?.coerceAtLeast(0L) ?: 0L
        }.getOrDefault(0L)
    }

    fun getLengthMs(): Long {
        val mp = mediaPlayerInstance ?: return 0L
        return runCatching {
            (mp.javaClass.getMethod("getLength").invoke(mp) as? Long)?.coerceAtLeast(0L) ?: 0L
        }.getOrDefault(0L)
    }

    fun setTimeMs(posMs: Long) {
        val mp = mediaPlayerInstance ?: return
        runCatching {
            mp.javaClass.getMethod("setTime", Long::class.javaPrimitiveType).invoke(mp, posMs.coerceAtLeast(0L))
        }
    }

    fun setRate(rate: Float) {
        val mp = mediaPlayerInstance ?: return
        runCatching {
            mp.javaClass.getMethod("setRate", Float::class.javaPrimitiveType).invoke(mp, rate)
        }
    }

    fun setScaleMode(resizeMode: VideoResizeMode) {
        val mp = mediaPlayerInstance ?: return
        runCatching {
            val setAspectRatio = mp.javaClass.getMethod("setAspectRatio", String::class.java)
            val setScale = mp.javaClass.getMethod("setScale", Float::class.javaPrimitiveType)
            when (resizeMode) {
                VideoResizeMode.FILL -> {
                    setScale.invoke(mp, 0f)
                    setAspectRatio.invoke(mp, "16:9")
                }
                VideoResizeMode.FIT -> {
                    setScale.invoke(mp, 0f)
                    setAspectRatio.invoke(mp, null as String?)
                }
                VideoResizeMode.ZOOM -> {
                    setScale.invoke(mp, 1.18f)
                    setAspectRatio.invoke(mp, null as String?)
                }
                else -> {
                    setScale.invoke(mp, 0f)
                    setAspectRatio.invoke(mp, null as String?)
                }
            }
        }
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        val mp = mediaPlayerInstance
        val libVlc = libVlcInstance
        mediaPlayerInstance = null
        libVlcInstance = null
        currentSurfaceView = null
        Thread {
            runCatching {
                if (mp != null) {
                    val vout = mp.javaClass.getMethod("getVLCVout").invoke(mp)
                    vout?.javaClass?.getMethod("detachViews")?.invoke(vout)
                    mp.javaClass.getMethod("stop").invoke(mp)
                    mp.javaClass.getMethod("release").invoke(mp)
                }
                libVlc?.javaClass?.getMethod("release")?.invoke(libVlc)
            }
        }.start()
    }
}

@Composable
fun VlcEmbeddedVideoSurface(
    controller: EmbeddedVlcController,
    resizeMode: VideoResizeMode,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(controller, resizeMode) {
        controller.setScaleMode(resizeMode)
    }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                keepScreenOn = true
                isFocusable = false
                isFocusableInTouchMode = false
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        controller.attachSurface(this@apply, width, height)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                        controller.updateWindowSize(w, h)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                    }
                })
            }
        },
        update = { view ->
            if (view.width > 0 && view.height > 0) {
                controller.updateWindowSize(view.width, view.height)
            }
        },
        modifier = modifier
    )
}
