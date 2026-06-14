package org.jetbrains.skiko

import org.jetbrains.skia.*
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skiko.wasm.createWebGLContext
import org.w3c.dom.HTMLCanvasElement

/**
 * Create a [RenderContext] that rasterizes onto a **caller-owned** [HTMLCanvasElement] (web, WebGL).
 *
 * The non-AWT entry point of the render-context API for the browser: skiko owns no view — the consumer
 * provides its `<canvas>`, skiko binds a WebGL context to it. Each frame:
 * [acquireSurface][RenderContext.acquireSurface], draw onto it, then [present][RenderContext.present]
 * (which flushes; WebGL shows the default framebuffer implicitly). The surface is reused while the size is
 * unchanged. Scheduling (e.g. `requestAnimationFrame`) stays the consumer's concern.
 */
@ExperimentalSkikoApi
fun createRenderContext(canvas: HTMLCanvasElement): RenderContext =
    WebGLRenderContext(createWebGLContext(canvas))

@OptIn(ExperimentalSkikoApi::class)
internal class WebGLRenderContext(internal val contextPointer: NativePointer) : RenderContext {
    private val context: DirectContext
    private var surface: Surface? = null
    private var renderTarget: BackendRenderTarget? = null
    private var currentWidth = -1
    private var currentHeight = -1

    override val graphicsApi: GraphicsApi get() = GraphicsApi.WEBGL
    override val directContext: DirectContext get() = context

    init {
        GL.makeContextCurrent(contextPointer)
        context = DirectContext.makeGL()
    }

    override fun acquireSurface(width: Int, height: Int): Surface {
        GL.makeContextCurrent(contextPointer)
        if (surface == null || width != currentWidth || height != currentHeight) {
            disposeSurface()
            currentWidth = width
            currentHeight = height
            renderTarget = BackendRenderTarget.makeGL(width, height, 1, 8, 0, 0x8058 /* GR_GL_RGBA8 */)
            surface = Surface.makeFromBackendRenderTarget(
                context,
                renderTarget!!,
                SurfaceOrigin.BOTTOM_LEFT,
                SurfaceColorFormat.RGBA_8888,
                ColorSpace.sRGB,
                SurfaceProps()
            ) ?: throw RenderException("Cannot create surface")
        }
        return surface!!
    }

    override fun present() {
        surface?.flushAndSubmit()
        context.flush()
        // WebGL shows the default framebuffer implicitly.
    }

    override fun close() {
        disposeSurface()
        context.close()
    }

    private fun disposeSurface() {
        surface?.close()
        surface = null
        renderTarget?.close()
        renderTarget = null
    }
}
