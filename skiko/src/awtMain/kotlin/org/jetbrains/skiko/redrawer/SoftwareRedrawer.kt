package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.*
import org.jetbrains.skiko.*
import org.jetbrains.skiko.layerFrameLimiter
import org.jetbrains.skiko.context.SoftwareContextHandler
import org.jetbrains.skiko.context.SoftwareDrawTarget

internal class SoftwareRedrawer(
    private val layer: SkiaPanel,
    analytics: SkiaLayerAnalytics,
    properties: SkiaLayerProperties
) : AWTRedrawer(layer, analytics, GraphicsApi.SOFTWARE_FAST) {
    init {
        onDeviceChosen("Software")
    }

    private val drawTarget = object : SoftwareDrawTarget {
        override val graphics get() = layer.requireBackedLayer.getGraphics()
        override val width get() = layer.width
        override val height get() = layer.height
        override val isFullscreen get() = layer.fullscreen
        override val isTransparent get() = layer.transparency
    }

    // SoftwareRedrawer serves both SOFTWARE_COMPAT and SOFTWARE_FAST (see Actuals.awt.kt), so the
    // reported API must follow the selected one (layer.renderApi) rather than a hardcoded constant.
    private val contextHandler = SoftwareContextHandler(layer.renderApi, drawTarget, properties.gpuResourceCacheLimit, layer.pixelGeometry, layer::draw)
    override val renderInfo: String get() = contextHandler.rendererInfo()

    @OptIn(ExperimentalSkikoApi::class)
    override val renderContext: RenderContext get() = contextHandler

    private val frameJob = if (properties.isVsyncEnabled && properties.isVsyncFramelimitFallbackEnabled) Job() else null
    private val frameLimiter = frameJob?.let {
        layerFrameLimiter(CoroutineScope(it), layer.requireBackedLayer)
    }

    private val frameExecutor = RenderExecutor {
        frameLimiter?.awaitNextFrame()

        if (layer.isShowing) {
            update()
            inDrawScope { contextHandler.draw() }
        }
    }

    init {
        onContextInit()
    }

    override fun dispose() {
        frameJob?.cancel()
        frameExecutor.close()
        contextHandler.dispose()
        super.dispose()
    }

    override fun needRender(throttledToVsync: Boolean) {
        frameExecutor.scheduleFrame()
    }

    override fun renderImmediately() {
        checkDisposed()
        update()
        inDrawScope {
            if (!isDisposed) { // Redrawer may be disposed in user code, during `update`
                contextHandler.draw()
            }
        }
    }

    override fun isTransparentBackgroundSupported(): Boolean {
        // TODO: why Software rendering has another transparency logic from the beginning
        return hostOs == OS.MacOS
    }
}