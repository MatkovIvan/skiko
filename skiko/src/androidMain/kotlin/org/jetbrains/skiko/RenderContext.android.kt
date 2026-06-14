package org.jetbrains.skiko

import android.opengl.GLES30
import org.jetbrains.skia.*
import java.nio.IntBuffer

/**
 * Create a [RenderContext] that rasterizes onto the **currently bound** GLES framebuffer (Android).
 *
 * The non-AWT entry point of the render-context API for Android: skiko owns no view — the consumer drives
 * a `GLSurfaceView` (or its own EGL surface) and calls into this from the GL thread with a current context
 * (e.g. inside `Renderer.onDrawFrame`). Each frame: [acquireSurface][RenderContext.acquireSurface], draw
 * onto it, then [present][RenderContext.present] (which flushes; the `GLSurfaceView` swaps buffers). The
 * surface is reused while the size is unchanged. Must be driven from the GL thread.
 */
@ExperimentalSkikoApi
fun createRenderContext(): RenderContext = AndroidGLRenderContext()

@OptIn(ExperimentalSkikoApi::class)
internal class AndroidGLRenderContext : RenderContext {
    private var context: DirectContext? = null
    private var renderTarget: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var currentWidth = -1
    private var currentHeight = -1

    override val graphicsApi: GraphicsApi get() = GraphicsApi.OPENGL
    override val directContext: DirectContext? get() = context

    override fun acquireSurface(width: Int, height: Int): Surface {
        val directContext = context ?: makeGLContext().also { context = it }
        if (surface == null || width != currentWidth || height != currentHeight) {
            disposeSurface()
            currentWidth = width
            currentHeight = height
            val boundFramebuffer = IntBuffer.allocate(1)
            GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, boundFramebuffer)
            renderTarget = makeGLRenderTarget(
                width,
                height,
                0,
                8,
                boundFramebuffer[0],
                FramebufferFormat.GR_GL_RGBA8
            )
            surface = Surface.makeFromBackendRenderTarget(
                directContext,
                renderTarget!!,
                SurfaceOrigin.BOTTOM_LEFT,
                SurfaceColorFormat.RGBA_8888,
                ColorSpace.sRGB
            ) ?: throw RenderException("Cannot create surface")
        }
        return surface!!
    }

    override fun present() {
        surface?.flushAndSubmit()
        context?.flush()
        // GLSurfaceView swaps buffers after the frame.
    }

    override fun close() {
        disposeSurface()
        context?.close()
        context = null
    }

    private fun disposeSurface() {
        surface?.close()
        surface = null
        renderTarget?.close()
        renderTarget = null
    }
}
