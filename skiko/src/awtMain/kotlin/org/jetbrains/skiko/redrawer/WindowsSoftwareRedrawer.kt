package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.SkiaPanel
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.SkiaLayerAnalytics

internal class WindowsSoftwareRedrawer(
    layer: SkiaPanel,
    analytics: SkiaLayerAnalytics,
    properties: SkiaLayerProperties
) : AbstractDirectSoftwareRedrawer(layer, analytics, properties) {

    @OptIn(ExperimentalSkikoApi::class)
    override val ctx = WindowsDirectSoftwareRenderContext(layer)

    init {
        onDeviceChosen("Software")
        onContextInit()
    }
}
