@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skia.impl.InteropPointer
import org.jetbrains.skia.impl.interopScope
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.SkiaPanel

/**
 * Windows [DirectSoftwareRenderContext]: creates a GDI window-backed raster device from the layer's content
 * handle. Unlike Linux it needs no per-call drawing-surface lock.
 *
 * Former `WindowsSoftwareRedrawer.createDevice`.
 */
internal class WindowsDirectSoftwareRenderContext(
    layer: SkiaPanel,
    properties: SkiaLayerProperties,
) : DirectSoftwareRenderContext(layer, properties) {
    init {
        device = interopScope {
            createDevice(
                layer.contentHandle,
                toInterop(SurfaceProps(pixelGeometry = layer.pixelGeometry).packToIntArray()),
                layer.transparency
            ).also { devicePtr ->
                if (devicePtr == 0L) throw RenderException("Failed to create Software device")
            }
        }
    }

    private external fun createDevice(contentHandle: Long, surfacePropsIntArray: InteropPointer, transparency: Boolean): Long
}
