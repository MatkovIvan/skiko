package org.jetbrains.skiko.context

import org.jetbrains.skia.*
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.hostOs
import java.awt.Color
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.*

internal class SoftwareContextHandler(
    renderApi: GraphicsApi,
    private val target: SoftwareDrawTarget,
    gpuResourceCacheLimit: Long,
    pixelGeometry: PixelGeometry,
    drawContent: Canvas.() -> Unit
) : ContextFreeContextHandler(renderApi, pixelGeometry, gpuResourceCacheLimit, drawContent) {
    val colorModel = ComponentColorModel(
        ColorSpace.getInstance(ColorSpace.CS_sRGB),
        true,
        false,
        Transparency.TRANSLUCENT,
        DataBuffer.TYPE_BYTE
    )
    val storage = Bitmap()
    var image: BufferedImage? = null
    var imageData: ByteArray? = null
    var raster: WritableRaster? = null

    private var currentWidth = 0
    private var currentHeight = 0

    override fun initCanvas(width: Int, height: Int) {
        // Reuse the raster surface (and its backing bitmap) while the size is unchanged.
        if (surface != null && width == currentWidth && height == currentHeight) return

        disposeCanvas()
        currentWidth = width
        currentHeight = height
        if (width <= 0 || height <= 0) {
            surface = null
            canvas = null
            return
        }

        if (storage.width != width || storage.height != height) {
            storage.allocPixelsFlags(ImageInfo.makeS32(width, height, ColorAlphaType.PREMUL), false)
        }

        // A raster Surface drawing directly into the bitmap's pixels — so software is a real RenderContext
        // surface too, not a bare Canvas. present() reads those pixels back and blits them.
        val pixels = storage.peekPixels()?.addr ?: throw RenderException("Can't get software pixels address")
        surface = Surface.makeRasterDirect(
            storage.imageInfo,
            pixels,
            storage.rowBytes,
            SurfaceProps(pixelGeometry = pixelGeometry)
        )
        canvas = surface!!.canvas
    }

    override fun present() {
        val w = currentWidth
        val h = currentHeight

        surface?.flushAndSubmit()
        val bytes = storage.readPixels(storage.imageInfo, (w * 4), 0, 0)
        if (bytes != null) {
            val buffer = DataBufferByte(bytes, bytes.size)
            raster = Raster.createInterleavedRaster(
                buffer,
                w,
                h,
                w * 4, 4,
                intArrayOf(2, 1, 0, 3), // BGRA order
                null
            )
            image = BufferedImage(colorModel, raster!!, false, null)
            val graphics = target.graphics
            if (!target.isFullscreen && target.isTransparent && hostOs == OS.MacOS) {
                graphics?.setColor(Color(0, 0, 0, 0))
                graphics?.clearRect(0, 0, w, h)
            }
            graphics?.drawImage(image!!, 0, 0, target.width, target.height, null)
        }
    }
}
