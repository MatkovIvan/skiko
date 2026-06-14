package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.*
import org.jetbrains.skiko.*
import org.jetbrains.skiko.layerFrameLimiter
import org.jetbrains.skiko.context.rasterizeFrame

internal class SoftwareRedrawer(
    private val layer: SkiaPanel,
    analytics: SkiaLayerAnalytics,
    properties: SkiaLayerProperties
) : AWTRedrawer(layer, analytics, GraphicsApi.SOFTWARE_FAST) {
    init {
        onDeviceChosen("Software")
    }

    // SoftwareRenderContext is standalone — it reads its draw target + render API (SOFTWARE_COMPAT or
    // SOFTWARE_FAST) + pixel geometry straight from the layer.
    private val ctx = SoftwareRenderContext(layer)
    override val renderInfo: String get() = ctx.rendererInfo()

    @OptIn(ExperimentalSkikoApi::class)
    override val renderContext: RenderContext get() = ctx

    private val frameJob = if (properties.isVsyncEnabled && properties.isVsyncFramelimitFallbackEnabled) Job() else null
    private val frameLimiter = frameJob?.let {
        layerFrameLimiter(CoroutineScope(it), layer.requireBackedLayer)
    }

    private val frameExecutor = RenderExecutor {
        frameLimiter?.awaitNextFrame()

        if (layer.isShowing) {
            update()
            inDrawScope { performDraw() }
        }
    }

    init {
        onContextInit()
    }

    override fun dispose() {
        frameJob?.cancel()
        frameExecutor.close()
        ctx.close()
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
                performDraw()
            }
        }
    }

    private fun LayerDrawScope.performDraw() {
        if (scaledLayerWidth > 0 && scaledLayerHeight > 0) {
            val surface = ctx.acquireSurface(scaledLayerWidth, scaledLayerHeight)
            surface.canvas.rasterizeFrame { layer.draw(this) }
            ctx.present()
        }
    }

    override fun isTransparentBackgroundSupported(): Boolean {
        // TODO: why Software rendering has another transparency logic from the beginning
        return hostOs == OS.MacOS
    }
}