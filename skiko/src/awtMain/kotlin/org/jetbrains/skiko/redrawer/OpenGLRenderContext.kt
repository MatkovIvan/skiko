@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.OpenGLApi
import org.jetbrains.skiko.RenderContext
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.hostArch
import org.jetbrains.skiko.hostOs
import org.jetbrains.skiko.makeGLContext
import org.jetbrains.skiko.makeGLRenderTarget

/**
 * The shared OpenGL GPU binder: it binds a Skia GL [DirectContext]/[Surface] to the **currently bound** GL
 * framebuffer. The GL device/make-current and buffer swap are owned by the composing per-OS on-screen
 * context (`LinuxOpenGLRenderContext`/`WindowsOpenGLRenderContext`), which calls [acquireSurface]/[present]
 * with its GL context current.
 *
 * Former `OpenGLContextHandler`, decoupled from the `ContextHandler` legacy base. One instance per context.
 */
internal class OpenGLRenderContext(
    private val gpuResourceCacheLimit: Long,
    private val pixelGeometry: PixelGeometry,
) : RenderContext {
    private var context: DirectContext? = null
    private var renderTarget: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var currentWidth = 0
    private var currentHeight = 0

    override val graphicsApi: GraphicsApi get() = GraphicsApi.OPENGL
    override val directContext: DirectContext? get() = context

    override fun acquireSurface(width: Int, height: Int): Surface {
        val context = context ?: makeGLContext().also {
            context = it
            if (gpuResourceCacheLimit >= 0) it.resourceCacheLimit = gpuResourceCacheLimit
        }
        if (surface == null || width != currentWidth || height != currentHeight) {
            disposeSurface()
            currentWidth = width
            currentHeight = height
            val gl = OpenGLApi.instance
            val fbId = gl.glGetIntegerv(gl.GL_DRAW_FRAMEBUFFER_BINDING)
            renderTarget = makeGLRenderTarget(width, height, 0, 8, fbId, FramebufferFormat.GR_GL_RGBA8)
            surface = Surface.makeFromBackendRenderTarget(
                context,
                renderTarget!!,
                SurfaceOrigin.BOTTOM_LEFT,
                SurfaceColorFormat.RGBA_8888,
                ColorSpace.sRGB,
                SurfaceProps(pixelGeometry = pixelGeometry)
            ) ?: throw RenderException("Cannot create OpenGL surface")
        }
        return surface!!
    }

    override fun present() {
        // GL flush only; the buffer swap is the driver's (swapBuffers).
        context?.flush()
    }

    override fun close() {
        disposeSurface()
        context?.close()
        context = null
    }

    fun rendererInfo(): String {
        val gl = OpenGLApi.instance
        return "GraphicsApi: ${GraphicsApi.OPENGL}\n" +
            "OS: ${hostOs.id} ${hostArch.id}\n" +
            "Vendor: ${gl.glGetString(gl.GL_VENDOR)}\n" +
            "Model: ${gl.glGetString(gl.GL_RENDERER)}\n" +
            "Total VRAM: ${gl.glGetIntegerv(gl.GL_TOTAL_MEMORY) / 1024} MB\n"
    }

    private fun disposeSurface() {
        surface?.close()
        surface = null
        renderTarget?.close()
        renderTarget = null
    }
}
