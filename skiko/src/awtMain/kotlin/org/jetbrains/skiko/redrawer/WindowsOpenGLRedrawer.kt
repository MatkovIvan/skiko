package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.*
import org.jetbrains.skiko.*
import org.jetbrains.skiko.context.rasterizeFrame

/**
 * The AWT on-screen Windows OpenGL driver: a frame loop over a [WindowsOpenGLRenderContext] (which owns the
 * WGL device + context, make-current and swap). Content is provided by [SkiaPanel.draw].
 *
 * Transitional: the multi-window batching loop lives here (a single static [FrameDispatcher] that coalesces
 * one dwmFlush vsync wait across all on-screen GL windows); deleted once `SkiaPanel` drives the context via
 * the render-context factory + `RenderExecutor` (per-window pacing).
 */
internal class WindowsOpenGLRedrawer(
    private val layer: SkiaPanel,
    analytics: SkiaLayerAnalytics,
    private val properties: SkiaLayerProperties
) : AWTRedrawer(layer, analytics, GraphicsApi.OPENGL) {
    init {
        loadOpenGLLibrary()
    }

    @OptIn(ExperimentalSkikoApi::class)
    private val ctx = WindowsOpenGLRenderContext(layer, properties)

    @OptIn(ExperimentalSkikoApi::class)
    override val renderInfo: String get() = ctx.rendererInfo()

    @OptIn(ExperimentalSkikoApi::class)
    override val renderContext: RenderContext get() = ctx

    init {
        onDeviceChosen(ctx.adapterName)
        onContextInit()
    }

    override fun dispose() {
        check(!isDisposed) { "WindowsOpenGLRedrawer is disposed" }
        ctx.close()
        super.dispose()
    }

    override fun needRender(throttledToVsync: Boolean) {
        check(!isDisposed) { "WindowsOpenGLRedrawer is disposed" }
        toRedraw.add(this)
        frameDispatcher.scheduleFrame()
    }

    @OptIn(ExperimentalSkikoApi::class)
    override fun renderImmediately() {
        check(!isDisposed) { "WindowsOpenGLRedrawer is disposed" }
        update()
        inDrawScope {
            if (!isDisposed) { // Redrawer may be disposed in user code, during `update`
                ctx.makeCurrent()
                performDraw()
                ctx.swap()
                OpenGLApi.instance.glFinish()
                if (SkikoProperties.windowsWaitForVsyncOnRedrawImmediately) {
                    ctx.dwmFlush()
                }
            }
        }
    }

    private fun draw() {
        inDrawScope { performDraw() }
    }

    @OptIn(ExperimentalSkikoApi::class)
    private fun LayerDrawScope.performDraw() {
        if (scaledLayerWidth > 0 && scaledLayerHeight > 0) {
            val surface = ctx.acquireSurface(scaledLayerWidth, scaledLayerHeight)
            surface.canvas.rasterizeFrame { layer.draw(this) }
            ctx.present()
        }
    }

    companion object {
        private val toRedraw = mutableSetOf<WindowsOpenGLRedrawer>()
        private val toRedrawCopy = mutableSetOf<WindowsOpenGLRedrawer>()
        private val toRedrawVisible = toRedrawCopy
            .asSequence()
            .filterNot(WindowsOpenGLRedrawer::isDisposed)
            .filter { it.layer.isShowing }

        // Deliberately a single static FrameDispatcher (not a per-instance RenderExecutor): it batches all
        // on-screen GL windows into one frame and coalesces one dwmFlush vsync wait across them. A
        // per-instance executor would regress this — each window would wait for vsync separately.
        @OptIn(ExperimentalSkikoApi::class)
        private val frameDispatcher = FrameDispatcher(MainUIDispatcher) {
            toRedrawCopy.addAll(toRedraw)
            toRedraw.clear()

            val nanoTime = System.nanoTime()
            for (redrawer in toRedrawVisible) {
                try {
                    redrawer.update(nanoTime)
                } catch (e: CancellationException) {
                    // continue
                }
            }

            for (redrawer in toRedrawVisible) {
                redrawer.ctx.makeCurrent()
                redrawer.draw()
            }

            for (redrawer in toRedrawVisible) {
                redrawer.ctx.swap()
            }

            for (redrawer in toRedrawVisible) {
                redrawer.ctx.makeCurrent()
                OpenGLApi.instance.glFinish()
            }

            val isVsyncEnabled = toRedrawVisible.all { it.properties.isVsyncEnabled }
            if (isVsyncEnabled) {
                withContext(dispatcherToBlockOn) {
                    toRedrawVisible.firstOrNull()?.ctx?.dwmFlush() // wait for vsync
                }
            }

            // Without clearing we will have a memory leak
            toRedrawCopy.clear()
        }
    }
}
