@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.OpenGLApi
import org.jetbrains.skiko.RenderContext
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.SkiaPanel
import org.jetbrains.skiko.hostOs
import org.jetbrains.skiko.isVideoCardSupported
import org.jetbrains.skiko.useDrawingSurfacePlatformInfo

/**
 * The Windows (WGL) OpenGL [RenderContext] for AWT on-screen rendering. It **owns** the WGL device (HDC) +
 * context (HGLRC) created from the window, and delegates the Skia GL surface binding to a composed
 * [OpenGLRenderContext]. Standalone — the factory can create it directly.
 *
 * The buffer swap is paced by the driver/`SkiaPanel` frame loop (vsync via [dwmFlush], not swap-interval).
 * Former `WindowsOpenGLRedrawer`'s WGL device half + `OpenGLContextHandler`.
 */
internal class WindowsOpenGLRenderContext(
    layer: SkiaPanel,
    properties: SkiaLayerProperties,
) : RenderContext {
    private val inner = OpenGLRenderContext(properties.gpuResourceCacheLimit, layer.pixelGeometry)

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
        makeCurrent()
        // For vsync we use dwmFlush instead of swapInterval: with DWM the swap-interval pacing isn't stable.
        setSwapInterval(0)
    }

    val adapterName: String? get() = OpenGLApi.instance.glGetString(OpenGLApi.instance.GL_RENDERER)

    override val graphicsApi: GraphicsApi get() = GraphicsApi.OPENGL
    override val directContext: DirectContext? get() = inner.directContext
    override fun acquireSurface(width: Int, height: Int): Surface = inner.acquireSurface(width, height)
    override fun present() = inner.present()

    fun makeCurrent() = makeCurrent(device, context)
    fun swap() = swapBuffers(device)

    override fun close() {
        inner.close()
        if (context != 0L) {
            deleteContext(context)
            context = 0L
        }
    }

    fun rendererInfo(): String = inner.rendererInfo()

    private external fun makeCurrent(device: Long, context: Long)
    private external fun getDevice(platformInfo: Long): Long
    private external fun createContext(device: Long, contentHandle: Long, transparency: Boolean): Long
    private external fun deleteContext(context: Long)
    external fun setSwapInterval(interval: Int)
    private external fun swapBuffers(device: Long)

    // TODO according to https://bugs.chromium.org/p/chromium/issues/detail?id=467617 dwmFlush has lag 3 ms after vsync.
    //  Maybe we should use D3DKMTWaitForVerticalBlankEvent? See also https://www.vsynctester.com/chromeisbroken.html
    // TODO should we support Windows 7? DWM can be disabled on Windows 7.
    external fun dwmFlush()
}
