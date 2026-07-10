package org.jetbrains.skiko.redrawer

import org.jetbrains.skia.impl.interopScope
import org.jetbrains.skia.impl.InteropPointer
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.RenderException

internal class WindowsSoftwareRedrawer(
    host: AwtSurfaceHost,
    properties: SkiaLayerProperties
) : AbstractDirectSoftwareRedrawer(host, properties) {

    init {
        device = interopScope {
            createDevice(host.contentHandle, toInterop(SurfaceProps(pixelGeometry = host.pixelGeometry).packToIntArray()), host.transparency).also {
                if (it == 0L) {
                    throw RenderException("Failed to create Software device")
                }
            }
        }
    }

    private external fun createDevice(contentHandle: Long, surfacePropsIntArray: InteropPointer, transparency: Boolean): Long
}
