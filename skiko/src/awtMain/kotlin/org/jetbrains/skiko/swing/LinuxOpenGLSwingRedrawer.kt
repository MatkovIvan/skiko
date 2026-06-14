package org.jetbrains.skiko.swing

import org.jetbrains.skia.*
import org.jetbrains.skiko.*

/**
 * Offscreen OpenGL surface (Linux) for the Swing-composited path. Provides the per-API offscreen surface
 * inside the GL render brackets; the shared draw/blit orchestration lives in [SwingRedrawerBase].
 */
internal class LinuxOpenGLSwingRedrawer(
    swingLayerProperties: SwingLayerProperties,
    renderDelegate: SkikoRenderDelegate,
    analytics: SkiaLayerAnalytics
) : SwingRedrawerBase(swingLayerProperties, renderDelegate, analytics, GraphicsApi.OPENGL) {
    init {
        onDeviceChosen("OpenGL OffScreen") // TODO: properly choose device
    }

    override val painter: SwingPainter = SoftwareSwingPainter(swingLayerProperties)

    private val offScreenContextPtr: Long = makeOffScreenContext().also {
        if (it == 0L) {
            throw RenderException("Cannot create OpenGL context")
        }
    }

    private var offScreenBufferPtr: Long = 0L

    init {
        onContextInit(null)
    }

    override fun dispose() {
        disposeOffScreenBuffer(offScreenBufferPtr)
        disposeOffScreenContext(offScreenContextPtr)
        painter.dispose()
        super.dispose()
    }

    override fun renderOffscreen(width: Int, height: Int, draw: (surface: Surface, texturePtr: Long) -> Unit) {
        offScreenBufferPtr = makeOffScreenBuffer(offScreenContextPtr, offScreenBufferPtr, width, height)
        if (offScreenBufferPtr == 0L) {
            throw RenderException("Cannot create offScreen OpenGL buffer")
        }
        startRendering(offScreenContextPtr, offScreenBufferPtr)
        try {
            autoCloseScope {
                // TODO: reuse texture
                val glTexturePtr = createAndBindTexture(width, height)
                if (glTexturePtr == 0L) {
                    throw RenderException("Cannot create offScreen OpenGL texture")
                }
                val fbId = getFboId(glTexturePtr)
                val renderTarget = makeGLRenderTarget(
                    width,
                    height,
                    0,
                    8,
                    fbId,
                    FramebufferFormat.GR_GL_RGBA8
                ).autoClose()

                // TODO: may be it is possible to reuse [makeGLContext]
                val directContext = makeGLContext().configureContext().autoClose()
                val surface = Surface.makeFromBackendRenderTarget(
                    directContext,
                    renderTarget,
                    SurfaceOrigin.TOP_LEFT,
                    SurfaceColorFormat.BGRA_8888,
                    ColorSpace.sRGB,
                    SurfaceProps(pixelGeometry = PixelGeometry.UNKNOWN)
                )?.autoClose() ?: throw RenderException("Cannot create surface")

                draw(surface, 0)
                unbindAndDisposeTexture(glTexturePtr)
            }
        } finally {
            finishRendering(offScreenContextPtr)
        }
    }

    override fun flushSurface(surface: Surface) {
        surface.flushAndSubmit(syncCpu = true)
    }

    /**
     * Creates new OpenGL context and opens X11 connection.
     * This context can be used together with buffer ([makeOffScreenBuffer]) for offscreen rendering.
     *
     * Should be manually disposed using [disposeOffScreenContext] when no longer needed.
     */
    private external fun makeOffScreenContext(): Long
    private external fun disposeOffScreenContext(contextPtr: Long): Long

    /**
     * Provides offscreen pixels GPU buffer.
     * If size of [oldBufferPtr] same as [width] and [height], it will be reused
     * or created new one otherwise ([oldBufferPtr] will be disposed in this case automatically).
     *
     * Should be manually disposed using [disposeOffScreenBuffer] when no longer needed.
     *
     * @see [makeOffScreenContext]
     */
    private external fun makeOffScreenBuffer(contextPtr: Long, oldBufferPtr: Long, width: Int, height: Int): Long
    private external fun disposeOffScreenBuffer(bufferPtr: Long)

    /**
     * Sets current OpenGL context to [contextPtr] and [bufferPtr],
     * so OpenGL will render into offscreen texture not on screen.
     *
     * Make sure to call [finishRendering] to reset context and wait for all Open GL commands to apply.
     */
    private external fun startRendering(contextPtr: Long, bufferPtr: Long)
    private external fun finishRendering(contextPtr: Long)

    private external fun createAndBindTexture(width: Int, height: Int): Long
    private external fun getFboId(texturePtr: Long): Int
    private external fun unbindAndDisposeTexture(texturePtr: Long)
}
