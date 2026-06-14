@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.SkiaPanel
import org.jetbrains.skiko.lockLinuxDrawingSurface

/**
 * Linux [DirectSoftwareRenderContext]: creates an X11 window-backed raster device from the layer's drawing
 * surface (display/window), and wraps every frame and the dispose in `lockLinuxDrawingSurface` (the JAWT
 * drawing-surface lock all native calls require).
 *
 * Former `LinuxSoftwareRedrawer`.
 */
internal class LinuxDirectSoftwareRenderContext(
    layer: SkiaPanel,
    properties: SkiaLayerProperties,
) : DirectSoftwareRenderContext(layer, properties) {
    init {
        val scale = layer.contentScale
        val width = (layer.width * scale).toInt().coerceAtLeast(0)
        val height = (layer.height * scale).toInt().coerceAtLeast(0)
        layer.requireBackedLayer.lockLinuxDrawingSurface {
            device = createDevice(it.display, it.window, width, height).also { devicePtr ->
                if (devicePtr == 0L) throw RenderException("Failed to create Software device")
            }
        }
    }

    override suspend fun renderFrame(width: Int, height: Int, immediate: Boolean, render: (Canvas) -> Unit) =
        layer.requireBackedLayer.lockLinuxDrawingSurface {
            super.renderFrame(width, height, immediate, render)
        }

    override fun close() = layer.requireBackedLayer.lockLinuxDrawingSurface {
        super.close()
    }

    private external fun createDevice(display: Long, window: Long, width: Int, height: Int): Long
}
