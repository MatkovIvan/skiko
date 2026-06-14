package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.*
import org.jetbrains.skiko.context.rasterizeFrame

/**
 * The AWT on-screen ANGLE driver: a thin frame loop over an [AngleRenderContext] (which owns the device,
 * GL context, surface and swap). Content is provided by [SkiaPanel.draw].
 *
 * Transitional: the loop lives here; deleted once `SkiaPanel` drives the context via the render-context
 * factory + `RenderExecutor`.
 */
internal class AngleRedrawer(
    private val layer: SkiaPanel,
    analytics: SkiaLayerAnalytics,
    private val properties: SkiaLayerProperties
) : AWTRedrawer(layer, analytics, GraphicsApi.ANGLE) {
    init {
        try {
            loadAngleLibrary()
        } catch (e: Exception) {
            throw RenderException("Failed to load ANGLE library", cause = e)
        }
    }

    private val ctx = AngleRenderContext(layer, properties)
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
            update(System.nanoTime())
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

    private fun draw() {
        inDrawScope {
            drawAndSwap(withVsync = properties.isVsyncEnabled)
        }
    }

    private fun LayerDrawScope.drawAndSwap(withVsync: Boolean) = synchronized(drawLock) {
        if (!isDisposed && scaledLayerWidth > 0 && scaledLayerHeight > 0) {
            val surface = ctx.acquireSurface(scaledLayerWidth, scaledLayerHeight)
            surface.canvas.rasterizeFrame { layer.draw(this) }
            ctx.present()
            ctx.swap(withVsync)
        }
    }
}
