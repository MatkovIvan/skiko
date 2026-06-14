package org.jetbrains.skiko.swing

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.Library
import org.jetbrains.skiko.MetalAdapter
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.autoCloseScope
import org.jetbrains.skiko.autoreleasepool
import org.jetbrains.skiko.chooseMetalAdapter
import org.jetbrains.skiko.dispose
import org.jetbrains.skiko.swing.SharedTexturesAdapter.Companion.createSharedTexturesAdapter

/**
 * Offscreen Metal surface for the Swing-composited path. Provides the per-API offscreen surface; the
 * shared clear/draw/flush/blit orchestration lives in [SwingRedrawerBase].
 *
 * For on-screen rendering see [org.jetbrains.skiko.redrawer.MetalRedrawer].
 */
internal class MetalSwingRedrawer(
    swingLayerProperties: SwingLayerProperties,
    renderDelegate: SkikoRenderDelegate,
    analytics: SkiaLayerAnalytics
) : SwingRedrawerBase(swingLayerProperties, renderDelegate, analytics, GraphicsApi.METAL) {
    companion object {
        init {
            Library.load()
        }

        private fun createSwingPainter(swingLayerProperties: SwingLayerProperties): SwingPainter = try {
            AcceleratedSwingPainter(
                sharedTextures = createSharedTexturesAdapter()
            ) { SoftwareSwingPainter(swingLayerProperties) }
        } catch (_: RenderException) {
            SoftwareSwingPainter(swingLayerProperties)
        }
    }

    private val adapter: MetalAdapter = chooseMetalAdapter(swingLayerProperties.adapterPriority).also {
        onDeviceChosen(it.name)
    }
    private val context: DirectContext = makeMetalContext()

    private var texturePtr: Long = 0

    init {
        onContextInit(context)
    }

    override val painter: SwingPainter = createSwingPainter(swingLayerProperties)

    override fun dispose() {
        disposeMetalTexture(texturePtr)
        context.close()
        adapter.dispose()
        painter.dispose()
        super.dispose()
    }

    override fun renderOffscreen(width: Int, height: Int, draw: (surface: Surface, texturePtr: Long) -> Unit) {
        require(width <= adapter.maxTextureSize && height <= adapter.maxTextureSize) {
            "Texture dimensions must be less than maximum allowed size: ${adapter.maxTextureSize}, got $width x $height"
        }

        autoreleasepool {
            autoCloseScope {
                texturePtr = makeMetalTexture(adapter.ptr, texturePtr, width, height)
                val renderTarget = makeRenderTarget().autoClose()
                val surface = Surface.makeFromBackendRenderTarget(
                    context,
                    renderTarget,
                    SurfaceOrigin.TOP_LEFT,
                    SurfaceColorFormat.BGRA_8888,
                    ColorSpace.sRGB,
                    SurfaceProps(pixelGeometry = PixelGeometry.UNKNOWN)
                )?.autoClose() ?: throw RenderException("Cannot create surface")

                draw(surface, texturePtr)
            }
        }
    }

    override fun flushSurface(surface: Surface) {
        surface.flushAndSubmit(syncCpu = true)
    }

    override fun rendererInfo(): String {
        return super.rendererInfo() +
            "Video card: ${adapter.name}\n" +
            "Total VRAM: ${adapter.memorySize / 1024 / 1024} MB\n"
    }

    private fun makeRenderTarget() = BackendRenderTarget(
        makeMetalRenderTargetOffScreen(texturePtr)
    )

    private fun makeMetalContext(): DirectContext = DirectContext(
        makeMetalContext(adapter.ptr)
    )

    private external fun makeMetalContext(adapter: Long): Long

    private external fun makeMetalRenderTargetOffScreen(texture: Long): Long

    /**
     * Provides Metal texture taking given [oldTexture] into account since it
     * can be reused if width and height are not changed, or the new one will
     * be created.
     */
    private external fun makeMetalTexture(adapter: Long, oldTexture: Long, width: Int, height: Int): Long
    private external fun disposeMetalTexture(texture: Long): Long
}
