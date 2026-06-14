package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.*

internal class LinuxSoftwareRedrawer(
    private val layer: SkiaPanel,
    analytics: SkiaLayerAnalytics,
    properties: SkiaLayerProperties
) : AbstractDirectSoftwareRedrawer(layer, analytics, properties) {

    @OptIn(ExperimentalSkikoApi::class)
    override val ctx = LinuxDirectSoftwareRenderContext(layer)

    init {
        onDeviceChosen("Software")
        onContextInit()
    }

    // On Linux every native device call must run inside lockLinuxDrawingSurface; wrap the whole frame/lifecycle
    // op so the context's resize/acquire/present/dispose calls are all covered.
    override fun dispose() = layer.requireBackedLayer.lockLinuxDrawingSurface {
        super.dispose()
    }

    override fun draw() = layer.requireBackedLayer.lockLinuxDrawingSurface {
        super.draw()
    }

    override fun renderImmediately() = layer.requireBackedLayer.lockLinuxDrawingSurface {
        super.renderImmediately()
    }
}
