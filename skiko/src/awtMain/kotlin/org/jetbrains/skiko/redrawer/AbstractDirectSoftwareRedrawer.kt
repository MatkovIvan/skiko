package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.*
import org.jetbrains.skiko.*
import org.jetbrains.skiko.layerFrameLimiter
import org.jetbrains.skiko.context.rasterizeFrame

/**
 * The AWT on-screen direct-software driver: a thin off-EDT frame loop over a [DirectSoftwareRenderContext]
 * (which owns the device, raster surface and blit). The platform subclass
 * ([LinuxSoftwareRedrawer]/[WindowsSoftwareRedrawer]) supplies the per-OS [ctx].
 *
 * Transitional: the loop lives here; deleted once `SkiaPanel` drives the context via the render-context
 * factory + `RenderExecutor`.
 */
internal abstract class AbstractDirectSoftwareRedrawer(
    private val layer: SkiaPanel,
    analytics: SkiaLayerAnalytics,
    private val properties: SkiaLayerProperties
) : AWTRedrawer(layer, analytics, GraphicsApi.SOFTWARE_FAST) {
    protected abstract val ctx: DirectSoftwareRenderContext

    @OptIn(ExperimentalSkikoApi::class)
    override val renderInfo: String get() = ctx.rendererInfo()

    @OptIn(ExperimentalSkikoApi::class)
    override val renderContext: RenderContext get() = ctx

    private val frameJob = Job()
    private val frameLimiter = layerFrameLimiter(CoroutineScope(frameJob), layer.requireBackedLayer)
    private val frameExecutor = RenderExecutor {
        if (properties.isVsyncEnabled && properties.isVsyncFramelimitFallbackEnabled) {
            frameLimiter.awaitNextFrame()
        }

        if (layer.isShowing) {
            update()
            draw()
        }
    }

    override fun needRender(throttledToVsync: Boolean) {
        frameExecutor.scheduleFrame()
    }

    protected open fun draw() = inDrawScope { performDraw() }

    @OptIn(ExperimentalSkikoApi::class)
    private fun LayerDrawScope.performDraw() {
        if (scaledLayerWidth > 0 && scaledLayerHeight > 0) {
            val surface = ctx.acquireSurface(scaledLayerWidth, scaledLayerHeight)
            surface.canvas.rasterizeFrame { layer.draw(this) }
            ctx.present()
        }
    }

    override fun renderImmediately() {
        update()
        if (!isDisposed) { // Redrawer may be disposed in user code, during `update`
            draw()
        }
    }

    @OptIn(ExperimentalSkikoApi::class)
    override fun dispose() {
        frameJob.cancel()
        frameExecutor.close()
        ctx.close()
        super.dispose()
    }
}
