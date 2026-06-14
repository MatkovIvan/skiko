@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaPanel
import org.jetbrains.skiko.lockLinuxDrawingSurface

/**
 * Linux [DirectSoftwareRenderContext]: creates an X11 window-backed raster device from the layer's drawing
 * surface (display/window). The device + every later native call must run under `lockLinuxDrawingSurface`;
 * the driver/`SkiaPanel` frame loop provides that scope around [acquireSurface]/[present]/[close].
 *
 * Former `LinuxSoftwareRedrawer.createDevice`.
 */
internal class LinuxDirectSoftwareRenderContext(layer: SkiaPanel) : DirectSoftwareRenderContext() {
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

    private external fun createDevice(display: Long, window: Long, width: Int, height: Int): Long
}
