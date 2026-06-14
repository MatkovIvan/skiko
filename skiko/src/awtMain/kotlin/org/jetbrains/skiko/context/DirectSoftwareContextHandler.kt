package org.jetbrains.skiko.context

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.impl.getPtr
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.redrawer.AbstractDirectSoftwareRedrawer
import java.lang.ref.Reference

internal class DirectSoftwareContextHandler(
    private val softwareRedrawer: AbstractDirectSoftwareRedrawer,
    gpuResourceCacheLimit: Long,
    pixelGeometry: PixelGeometry,
    drawContent: Canvas.() -> Unit
) : ContextFreeContextHandler(GraphicsApi.SOFTWARE_FAST, pixelGeometry, gpuResourceCacheLimit, drawContent) {

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
        val w = width
        val h = height
        if (isSizeChanged(w, h) || surface == null) {
            disposeCanvas()
            if (w > 0 && h > 0) {
                softwareRedrawer.resize(w, h)
                surface = softwareRedrawer.acquireSurface()
                canvas = surface!!.canvas
            } else {
                surface = null
                canvas = null
            }
        }
    }

    override fun present() {
        val surface = surface
        if (surface != null) {
            try {
                softwareRedrawer.finishFrame(getPtr(surface))
            } finally {
                Reference.reachabilityFence(surface)
            }
        }
    }
}