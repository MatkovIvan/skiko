@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skia.*
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.RenderContext
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.hostArch
import org.jetbrains.skiko.hostOs
import org.jetbrains.skiko.SkiaPanel
import org.jetbrains.skiko.context.SoftwareDrawTarget
import java.awt.Color
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.*

/**
 * The software (CPU raster) [RenderContext] for AWT on-screen rendering. [acquireSurface] hands back a raster
 * [Surface] drawing straight into a reused [Bitmap]; [present] reads those pixels back and blits them onto the
 * layer's [java.awt.Graphics]. No GPU context ([directContext] is `null`), no natives. Standalone — it reads
 * its draw target, render API and pixel geometry from [layer], so the factory can create it directly.
 *
 * This is the former `SoftwareContextHandler`, renamed to its role and decoupled from the `ContextHandler`
 * legacy base.
 */
internal class SoftwareRenderContext(
    private val layer: SkiaPanel,
) : RenderContext {
    private val pixelGeometry: PixelGeometry get() = layer.pixelGeometry
    private val target = object : SoftwareDrawTarget {
        override val graphics get() = layer.requireBackedLayer.getGraphics()
        override val width get() = layer.width
        override val height get() = layer.height
        override val isFullscreen get() = layer.fullscreen
        override val isTransparent get() = layer.transparency
    }

    private val colorModel = ComponentColorModel(
        ColorSpace.getInstance(ColorSpace.CS_sRGB),
        true,
        false,
        Transparency.TRANSLUCENT,
        DataBuffer.TYPE_BYTE
    )
    private val storage = Bitmap()
    private var surface: Surface? = null
    private var currentWidth = 0
    private var currentHeight = 0

    override val graphicsApi: GraphicsApi get() = layer.renderApi
    override val directContext: DirectContext? get() = null

    override fun acquireSurface(width: Int, height: Int): Surface {
        if (width <= 0 || height <= 0) throw RenderException("Software surface needs a positive size (${width}x$height)")
        surface?.let { if (width == currentWidth && height == currentHeight) return it }

        disposeSurface()
        currentWidth = width
        currentHeight = height
        if (storage.width != width || storage.height != height) {
            storage.allocPixelsFlags(ImageInfo.makeS32(width, height, ColorAlphaType.PREMUL), false)
        }
        // A raster Surface drawing directly into the bitmap's pixels; present() reads them back and blits.
        val pixels = storage.peekPixels()?.addr ?: throw RenderException("Can't get software pixels address")
        return Surface.makeRasterDirect(
            storage.imageInfo,
            pixels,
            storage.rowBytes,
            SurfaceProps(pixelGeometry = pixelGeometry)
        ).also { surface = it }
    }

    override fun present() {
        val w = currentWidth
        val h = currentHeight
        if (w <= 0 || h <= 0) return

        surface?.flushAndSubmit()
        val bytes = storage.readPixels(storage.imageInfo, (w * 4), 0, 0) ?: return
        val buffer = DataBufferByte(bytes, bytes.size)
        val raster = Raster.createInterleavedRaster(
            buffer,
            w,
            h,
            w * 4, 4,
            intArrayOf(2, 1, 0, 3), // BGRA order
            null
        )
        val image = BufferedImage(colorModel, raster, false, null)
        val graphics = target.graphics
        if (!target.isFullscreen && target.isTransparent && hostOs == OS.MacOS) {
            graphics?.setColor(Color(0, 0, 0, 0))
            graphics?.clearRect(0, 0, w, h)
        }
        graphics?.drawImage(image, 0, 0, target.width, target.height, null)
    }

    fun rendererInfo(): String =
        "GraphicsApi: ${layer.renderApi}\n" +
            "OS: ${hostOs.id} ${hostArch.id}\n"

    override fun close() {
        disposeSurface()
        storage.close()
    }

    private fun disposeSurface() {
        surface?.close()
        surface = null
    }
}
