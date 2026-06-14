@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.withContext
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
import org.jetbrains.skiko.useDrawingSurfacePlatformInfo

/**
 * The Windows (WGL) OpenGL on-screen [AwtRenderContext]. It **owns** the WGL device (HDC) + context (HGLRC)
 * created from the window and delegates the Skia GL surface binding to a composed [OpenGLRenderContext].
 * Standalone — the factory can create it directly. [renderFrame] makes the context current, draws, swaps and
 * finishes; vsync is paced via [dwmFlush] in [paceAfterFrame] (per-window — the former static multi-window
 * dispatcher is gone).
 *
 * Former `WindowsOpenGLRedrawer` + `OpenGLContextHandler`.
 */
internal class WindowsOpenGLRenderContext(
    private val layer: SkiaPanel,
    properties: SkiaLayerProperties,
) : AwtRenderContext {
    private val inner = OpenGLRenderContext(properties.gpuResourceCacheLimit, layer.pixelGeometry)
    private val isVsyncEnabled = properties.isVsyncEnabled

    private val device: Long = layer.requireBackedLayer.useDrawingSurfacePlatformInfo {
        getDevice(it).also { devicePtr -> check(devicePtr != 0L) { "Can't get device" } }
    }

    private var context = createContext(device, layer.contentHandle, layer.transparency).also {
        if (it == 0L) throw RenderException("Cannot create Windows GL context")
        makeCurrent(device, it)
        adapterName.also { name ->
            if (name != null && !isVideoCardSupported(GraphicsApi.OPENGL, hostOs, name)) {
                throw RenderException("Cannot create Windows GL context")
            }
        }
    }

    init {
        makeCurrent(device, context)
        // For vsync we use dwmFlush instead of swapInterval: with DWM the swap-interval pacing isn't stable.
        setSwapInterval(0)
    }

    val adapterName: String? get() = OpenGLApi.instance.glGetString(OpenGLApi.instance.GL_RENDERER)

    override val graphicsApi: GraphicsApi get() = GraphicsApi.OPENGL
    override val directContext: DirectContext? get() = inner.directContext
    override val deviceName: String? get() = adapterName
    override val renderInfo: String get() = inner.rendererInfo()
    override fun isTransparentBackgroundSupported(): Boolean = defaultIsTransparentBackgroundSupported(layer.fullscreen)

    override fun acquireSurface(width: Int, height: Int): Surface = inner.acquireSurface(width, height)
    override fun present() = inner.present()

    override suspend fun renderFrame(width: Int, height: Int, immediate: Boolean, render: (Canvas) -> Unit) {
        makeCurrent(device, context)
        if (width > 0 && height > 0) {
            val surface = inner.acquireSurface(width, height)
            surface.canvas.rasterizeFrame { render(this) }
            inner.present()
        }
        swapBuffers(device)
        OpenGLApi.instance.glFinish()
        if (immediate && SkikoProperties.windowsWaitForVsyncOnRedrawImmediately) {
            dwmFlush()
        }
    }

    override suspend fun paceAfterFrame() {
        if (isVsyncEnabled) {
            withContext(dispatcherToBlockOn) {
                dwmFlush() // wait for vsync
            }
        }
    }

    override fun close() {
        makeCurrent(device, context)
        inner.close()
        if (context != 0L) {
            deleteContext(context)
            context = 0L
        }
    }

    private external fun makeCurrent(device: Long, context: Long)
    private external fun getDevice(platformInfo: Long): Long
    private external fun createContext(device: Long, contentHandle: Long, transparency: Boolean): Long
    private external fun deleteContext(context: Long)
    private external fun setSwapInterval(interval: Int)
    private external fun swapBuffers(device: Long)

    // TODO according to https://bugs.chromium.org/p/chromium/issues/detail?id=467617 dwmFlush has lag 3 ms after vsync.
    //  Maybe we should use D3DKMTWaitForVerticalBlankEvent? See also https://www.vsynctester.com/chromeisbroken.html
    private external fun dwmFlush()
}
