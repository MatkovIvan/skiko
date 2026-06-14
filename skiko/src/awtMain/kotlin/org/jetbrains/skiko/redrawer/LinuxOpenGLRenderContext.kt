@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.OpenGLApi
import org.jetbrains.skiko.RenderContext
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaPanel
import org.jetbrains.skiko.hostOs
import org.jetbrains.skiko.lockLinuxDrawingSurface
import org.jetbrains.skiko.isVideoCardSupported

/**
 * The Linux (GLX) OpenGL [RenderContext] for AWT on-screen rendering. It **owns** the GLX context created
 * from the window's display, and delegates the Skia GL surface binding to a composed [OpenGLRenderContext].
 * Standalone — the factory can create it directly (the GLX context is made in the constructor).
 *
 * Every GLX call (create / make-current / swap / swap-interval / destroy) must run inside
 * `lockLinuxDrawingSurface { }`; the driver/`SkiaPanel` frame loop holds that lock and passes the
 * display/window in. Former `LinuxOpenGLRedrawer`'s GLX device half + `OpenGLContextHandler`.
 */
internal class LinuxOpenGLRenderContext(
    layer: SkiaPanel,
    properties: org.jetbrains.skiko.SkiaLayerProperties,
) : RenderContext {
    private val inner = OpenGLRenderContext(properties.gpuResourceCacheLimit, layer.pixelGeometry)
    private var context = 0L
    private val swapInterval = if (properties.isVsyncEnabled) 1 else 0

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
            setSwapInterval(it.display, it.window, swapInterval)
        }
    }

    override val graphicsApi: GraphicsApi get() = GraphicsApi.OPENGL
    override val directContext: DirectContext? get() = inner.directContext
    override fun acquireSurface(width: Int, height: Int): Surface = inner.acquireSurface(width, height)
    override fun present() = inner.present()

    /** Make the GLX context current on the given drawing surface; the caller holds the surface lock. */
    fun makeCurrent(display: Long, window: Long) = makeCurrent(display, window, context)
    fun swap(display: Long, window: Long) = swapBuffers(display, window)
    fun swapInterval(display: Long, window: Long, interval: Int) = setSwapInterval(display, window, interval)

    override fun close() = inner.close()

    /** Destroy the GLX context; the caller holds the surface lock and has made the context current. */
    fun destroy(display: Long) {
        if (context != 0L) {
            destroyContext(display, context)
            context = 0L
        }
    }

    fun rendererInfo(): String = inner.rendererInfo()

    private external fun makeCurrent(display: Long, window: Long, context: Long)
    private external fun createContext(display: Long, transparency: Boolean): Long
    private external fun destroyContext(display: Long, context: Long)
    private external fun setSwapInterval(display: Long, window: Long, interval: Int)
    private external fun swapBuffers(display: Long, window: Long)
}
