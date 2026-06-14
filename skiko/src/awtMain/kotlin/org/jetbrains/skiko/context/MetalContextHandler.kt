package org.jetbrains.skiko.context

import org.jetbrains.skia.*
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.Logger
import org.jetbrains.skiko.MetalAdapter
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.redrawer.MetalDevice

/**
 * Provides a way to draw on a Skia canvas backed by a Metal [device] using GPU acceleration.
 *
 * For each [ContextHandler.draw] request it initializes a Skia Canvas with a Metal context and
 * rasterizes [drawContent] into it.
 *
 * @see "src/awtMain/objectiveC/macos/MetalContextHandler.mm" -- native implementation
 */
internal class MetalContextHandler(
    private val device: MetalDevice,
    private val adapter: MetalAdapter,
    gpuResourceCacheLimit: Long,
    pixelGeometry: PixelGeometry,
    drawContent: Canvas.() -> Unit
) : ContextBasedContextHandler(GraphicsApi.METAL, pixelGeometry, gpuResourceCacheLimit, "Metal", drawContent) {
    internal val metalDeviceObjcPtr: Long get() = getMetalDevicePointer(device.ptr)
    internal val metalCommandQueueObjcPtr: Long get() = getMetalCommandQueuePointer(device.ptr)

    override fun initCanvas(width: Int, height: Int) {
        disposeCanvas()

        if (width > 0 && height > 0) {
            renderTarget = makeRenderTarget(width, height)

            surface = Surface.makeFromBackendRenderTarget(
                context!!,
                renderTarget!!,
                SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.BGRA_8888,
                ColorSpace.sRGB,
                SurfaceProps(pixelGeometry = pixelGeometry)
            ) ?: throw RenderException("Cannot create surface")

            canvas = surface!!.canvas
        } else {
            renderTarget = null
            surface = null
            canvas = null
        }
    }

    override fun present() {
        context?.flush()
        surface?.flushAndSubmit()
        finishFrame()
        Logger.debug { "MetalContextHandler finished drawing frame" }
    }

    override fun rendererInfo(): String {
        return super.rendererInfo() +
                "Video card: ${adapter.name}\n" +
                "Total VRAM: ${adapter.memorySize / 1024 / 1024} MB\n"
    }

    private fun makeRenderTarget(width: Int, height: Int) = BackendRenderTarget(
        makeMetalRenderTarget(device.ptr, width, height)
    )

    override fun makeContext() = DirectContext(
        makeMetalContext(device.ptr)
    )

    private fun finishFrame() = finishFrame(device.ptr)

    private external fun makeMetalContext(device: Long): Long
    private external fun makeMetalRenderTarget(device: Long, width: Int, height: Int): Long
    private external fun finishFrame(device: Long)
    private external fun getMetalDevicePointer(device: Long): Long
    private external fun getMetalCommandQueuePointer(device: Long): Long
}
