@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.OpenGLApi
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.SkiaPanel
import org.jetbrains.skiko.SkikoProperties
import org.jetbrains.skiko.context.rasterizeFrame
import org.jetbrains.skiko.hostOs
import org.jetbrains.skiko.isVideoCardSupported
import org.jetbrains.skiko.layerFrameLimiter
import org.jetbrains.skiko.lockLinuxDrawingSurface

/**
 * The Linux (GLX) OpenGL on-screen [AwtRenderContext]. It **owns** the GLX context created from the window's
 * display and delegates the Skia GL surface binding to a composed [OpenGLRenderContext]. Standalone — the
 * factory can create it directly. Every GLX call (create / make-current / swap / swap-interval / destroy)
 * runs inside `lockLinuxDrawingSurface { }`; [renderFrame] makes the context current, draws, swaps and
 * flushes within one lock scope (per-window pacing — the former static multi-window dispatcher is gone).
 *
 * Former `LinuxOpenGLRedrawer` + `OpenGLContextHandler`.
 */
internal class LinuxOpenGLRenderContext(
    private val layer: SkiaPanel,
    properties: SkiaLayerProperties,
) : AwtRenderContext {
    private val inner = OpenGLRenderContext(properties.gpuResourceCacheLimit, layer.pixelGeometry)
    private var context = 0L
    private val isVsyncEnabled = properties.isVsyncEnabled
    private val swapIntervalValue = if (properties.isVsyncEnabled) 1 else 0

    private val frameJob = Job()
    @Volatile
    private var frameLimit = 0.0
    private val frameLimiter = layerFrameLimiter(
        CoroutineScope(frameJob),
        layer.requireBackedLayer,
        onNewFrameLimit = { frameLimit = it }
    )

    /** The GL renderer string, captured (with the context current) during construction. */
    var adapterName: String? = null
        private set

    init {
        layer.requireBackedLayer.lockLinuxDrawingSurface {
            context = createContext(it.display, layer.transparency)
            if (context == 0L) throw RenderException("Cannot create Linux GL context")
            makeCurrent(it.display, it.window, context)
            adapterName = OpenGLApi.instance.glGetString(OpenGLApi.instance.GL_RENDERER)
            adapterName?.let { name ->
                if (!isVideoCardSupported(GraphicsApi.OPENGL, hostOs, name)) {
                    throw RenderException("Cannot create Linux GL context")
                }
            }
            setSwapInterval(it.display, it.window, swapIntervalValue)
        }
    }

    override val graphicsApi: GraphicsApi get() = GraphicsApi.OPENGL
    override val directContext: DirectContext? get() = inner.directContext
    override val deviceName: String? get() = adapterName
    override val renderInfo: String get() = inner.rendererInfo()
    override fun isTransparentBackgroundSupported(): Boolean = defaultIsTransparentBackgroundSupported(layer.fullscreen)

    override fun acquireSurface(width: Int, height: Int): Surface = inner.acquireSurface(width, height)
    override fun present() = inner.present()

    override suspend fun paceBeforeFrame() {
        // Some Linuxes don't turn vsync on, so apply an additional frame limit (no longer than enabled vsync).
        if (isVsyncEnabled) {
            try {
                frameLimiter.awaitNextFrame()
            } catch (e: CancellationException) {
                // ignore
            }
        }
    }

    override suspend fun renderFrame(width: Int, height: Int, immediate: Boolean, render: (Canvas) -> Unit) =
        layer.requireBackedLayer.lockLinuxDrawingSurface {
            makeCurrent(it.display, it.window, context)
            if (width > 0 && height > 0) {
                val surface = inner.acquireSurface(width, height)
                surface.canvas.rasterizeFrame { render(this) }
                inner.present()
            }
            val turnOffVsync = immediate && isVsyncEnabled && !SkikoProperties.linuxWaitForVsyncOnRedrawImmediately
            if (turnOffVsync) setSwapInterval(it.display, it.window, 0)
            swapBuffers(it.display, it.window)
            OpenGLApi.instance.glFlush()
            if (turnOffVsync) setSwapInterval(it.display, it.window, swapIntervalValue)
        }

    override fun close() {
        frameJob.cancel()
        layer.requireBackedLayer.lockLinuxDrawingSurface {
            // makeCurrent is mandatory to destroy the context, otherwise GL destroys the wrong one.
            makeCurrent(it.display, it.window, context)
            inner.close()
            if (context != 0L) {
                destroyContext(it.display, context)
                context = 0L
            }
        }
    }

    private external fun makeCurrent(display: Long, window: Long, context: Long)
    private external fun createContext(display: Long, transparency: Boolean): Long
    private external fun destroyContext(display: Long, context: Long)
    private external fun setSwapInterval(display: Long, window: Long, interval: Int)
    private external fun swapBuffers(display: Long, window: Long)
}
