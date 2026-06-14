package org.jetbrains.skiko.swing

import org.jetbrains.skia.*
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.autoCloseScope

/**
 * Offscreen software (CPU raster) surface for the Swing-composited path. Provides the per-API offscreen
 * surface; the shared draw/blit orchestration lives in [SwingRedrawerBase].
 */
internal class SoftwareSwingRedrawer(
    swingLayerProperties: SwingLayerProperties,
    renderDelegate: SkikoRenderDelegate,
    analytics: SkiaLayerAnalytics
) : SwingRedrawerBase(
    swingLayerProperties,
    renderDelegate,
    analytics,
    GraphicsApi.SOFTWARE_FAST
) {
    init {
        onDeviceChosen("Software")
    }

    override val painter: SwingPainter = SoftwareSwingPainter(swingLayerProperties)

    private val storage = Bitmap()

    init {
        onContextInit(null)
    }

    override fun dispose() {
        super.dispose()
        storage.close()
        painter.dispose()
    }

    override fun renderOffscreen(width: Int, height: Int, draw: (surface: Surface, texturePtr: Long) -> Unit) =
        autoCloseScope {
            if (storage.width != width || storage.height != height) {
                storage.allocPixelsFlags(ImageInfo.makeS32(width, height, ColorAlphaType.PREMUL), false)
            }

            val pixelsPointer = storage.peekPixels()?.addr!!
            val surface = Surface.makeRasterDirect(
                imageInfo = storage.imageInfo,
                pixelsPtr = pixelsPointer,
                rowBytes = storage.rowBytes
            ).autoClose()

            draw(surface, 0)
        }

    // Raster surface: nothing to submit to a GPU.
    override fun flushSurface(surface: Surface) = Unit
}
