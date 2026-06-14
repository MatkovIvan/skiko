package org.jetbrains.skiko.context

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skia.impl.getPtr
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.redrawer.Direct3DRedrawer
import java.lang.ref.Reference

internal class Direct3DContextHandler(
    private val directXRedrawer: Direct3DRedrawer,
    gpuResourceCacheLimit: Long,
    pixelGeometry: PixelGeometry,
    drawContent: Canvas.() -> Unit
) : ContextBasedContextHandler(GraphicsApi.DIRECT3D, pixelGeometry, gpuResourceCacheLimit, "Direct3D", drawContent) {
    internal val direct3DAdapterPtr: Long get() = directXRedrawer.adapterPointer()
    internal val direct3DDevicePtr: Long get() = directXRedrawer.devicePointer()
    internal val direct3DQueuePtr: Long get() = directXRedrawer.queuePointer()

    private val bufferCount = 2
    private var surfaces: Array<Surface?> = arrayOfNulls(bufferCount)
    private fun isSurfacesNull() = surfaces.all { it == null }

    override fun makeContext(): DirectContext = directXRedrawer.makeContext()

    private var currentWidth = 0
    private var currentHeight = 0
    private fun isSizeChanged(width: Int, height: Int): Boolean {
        if (width != currentWidth || height != currentHeight) {
            currentWidth = width
            currentHeight = height
            return true
        }
        return false
    }

    override fun initCanvas(width: Int, height: Int) {
        val context = context ?: return

        // Direct3D can't work with zero size.
        // Don't rewrite code to skipping, as we need the whole pipeline in zero case too
        // (drawing -> flushing -> swapping -> waiting for vsync)
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)

        if (isSizeChanged(w, h) || isSurfacesNull()) {
            disposeCanvas()
            context.flush()

            val justInitialized = directXRedrawer.changeSize(w, h)
            try {
                val surfaceProps = SurfaceProps(pixelGeometry = pixelGeometry)
                for (bufferIndex in 0 until bufferCount) {
                    surfaces[bufferIndex] = directXRedrawer.makeSurface(
                        context = getPtr(context),
                        width = w,
                        height = h,
                        surfaceProps = surfaceProps,
                        index = bufferIndex
                    )
                }
            } finally {
                Reference.reachabilityFence(context)
            }

            if (justInitialized) {
                directXRedrawer.initFence()
            }
        }
        surface = surfaces[directXRedrawer.getBufferIndex()]
        canvas = surface!!.canvas
    }

    override fun present() {
        val context = context ?: return
        val surface = surface ?: return
        try {
            flush(getPtr(context), getPtr(surface))
        } finally {
            Reference.reachabilityFence(context)
            Reference.reachabilityFence(surface)
        }
    }

    override fun disposeCanvas() {
        for (bufferIndex in 0 until bufferCount) {
            surfaces[bufferIndex]?.close()
        }
    }

    override fun rendererInfo(): String {
        return super.rendererInfo() +
            "Video card: ${directXRedrawer.adapterName}\n" +
            "Total VRAM: ${directXRedrawer.adapterMemorySize / 1024 / 1024} MB\n"
    }

    private external fun flush(context: Long, surface: Long)
}
