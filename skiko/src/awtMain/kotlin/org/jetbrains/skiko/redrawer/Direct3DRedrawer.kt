package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.withContext
import org.jetbrains.skiko.*
import org.jetbrains.skiko.context.rasterizeFrame

/**
 * The AWT on-screen Direct3D 12 driver: a thin off-EDT frame loop over a [Direct3DRenderContext] (which owns
 * the device, swap chain, surfaces and present). Content is provided by [SkiaPanel.draw].
 *
 * Transitional: the loop lives here; deleted once `SkiaPanel` drives the context via the render-context
 * factory + `RenderExecutor`.
 */
internal class Direct3DRedrawer(
    private val layer: SkiaPanel,
    analytics: SkiaLayerAnalytics,
    private val properties: SkiaLayerProperties
) : AWTRedrawer(layer, analytics, GraphicsApi.DIRECT3D) {

    private val ctx = Direct3DRenderContext(layer, properties)
    private val drawLock = Any()

    init {
        onDeviceChosen(ctx.adapterName)
        onContextInit()
    }

    override val renderInfo: String get() = ctx.rendererInfo()

    @OptIn(ExperimentalSkikoApi::class)
    override val renderContext: RenderContext get() = ctx

    private val frameExecutor = RenderExecutor {
        if (layer.isShowing) {
            update()
            draw()
        }
    }

    override fun dispose() = synchronized(drawLock) {
        frameExecutor.close()
        ctx.close()
        super.dispose()
    }

    override fun needRender(throttledToVsync: Boolean) {
        checkDisposed()
        frameExecutor.scheduleFrame()
    }

    override fun renderImmediately() {
        checkDisposed()
        update()
        inDrawScope {
            if (!isDisposed) { // Redrawer may be disposed in user code, during `update`
                drawAndSwap(withVsync = SkikoProperties.windowsWaitForVsyncOnRedrawImmediately)
            }
        }
    }

    private suspend fun draw() {
        inDrawScope {
            withContext(dispatcherToBlockOn) {
                drawAndSwap(withVsync = properties.isVsyncEnabled)
            }
        }
    }

    private fun LayerDrawScope.drawAndSwap(withVsync: Boolean) = synchronized(drawLock) {
        if (!isDisposed) {
            // No size guard: D3D coerces to 1×1 in acquireSurface and runs the whole pipeline.
            val surface = ctx.acquireSurface(scaledLayerWidth, scaledLayerHeight)
            surface.canvas.rasterizeFrame { layer.draw(this) }
            ctx.present()
            ctx.swap(withVsync)
        }
    }
}
