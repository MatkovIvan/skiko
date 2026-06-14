package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.*
import org.jetbrains.skiko.*
import org.jetbrains.skiko.context.rasterizeFrame

/**
 * The AWT on-screen Linux OpenGL driver: a frame loop over a [LinuxOpenGLRenderContext] (which owns the GLX
 * context, make-current and swap). Content is provided by [SkiaPanel.draw]. Every GLX call runs inside
 * `lockLinuxDrawingSurface`.
 *
 * Transitional: the multi-window batching loop lives here (a single static [FrameDispatcher] that coalesces
 * one vsync across all on-screen GL windows); deleted once `SkiaPanel` drives the context via the
 * render-context factory + `RenderExecutor` (per-window pacing).
 */
internal class LinuxOpenGLRedrawer(
    private val layer: SkiaPanel,
    analytics: SkiaLayerAnalytics,
    private val properties: SkiaLayerProperties
) : AWTRedrawer(layer, analytics, GraphicsApi.OPENGL) {
    init {
        loadOpenGLLibrary()
    }

    @OptIn(ExperimentalSkikoApi::class)
    private val ctx = LinuxOpenGLRenderContext(layer, properties)

    @OptIn(ExperimentalSkikoApi::class)
    override val renderInfo: String get() = ctx.rendererInfo()

    @OptIn(ExperimentalSkikoApi::class)
    override val renderContext: RenderContext get() = ctx

    init {
        onDeviceChosen(ctx.adapterName)
        onContextInit()
    }

    private val frameJob = Job()
    @Volatile
    private var frameLimit = 0.0
    private val frameLimiter = layerFrameLimiter(
        CoroutineScope(frameJob),
        layer.requireBackedLayer,
        onNewFrameLimit = { frameLimit = it }
    )

    private suspend fun limitFramesIfNeeded() {
        // Some Linuxes don't turn vsync on, so we apply additional frame limit (which should be no longer than enabled vsync)
        if (properties.isVsyncEnabled) {
            try {
                frameLimiter.awaitNextFrame()
            } catch (e: CancellationException) {
                // ignore
            }
        }
    }

    @OptIn(ExperimentalSkikoApi::class)
    override fun dispose() {
        checkDisposed()
        frameJob.cancel()
        layer.requireBackedLayer.lockLinuxDrawingSurface {
            // makeCurrent is mandatory to destroy context, otherwise, OpenGL will destroy wrong context (from another window).
            // see the official example: https://www.khronos.org/opengl/wiki/Tutorial:_OpenGL_3.0_Context_Creation_(GLX)
            ctx.makeCurrent(it.display, it.window)
            ctx.close()
            ctx.destroy(it.display)
        }
        super.dispose()
    }

    override fun needRender(throttledToVsync: Boolean) {
        checkDisposed()
        toRedraw.add(this)
        frameDispatcher.scheduleFrame()
    }

    @OptIn(ExperimentalSkikoApi::class)
    override fun renderImmediately() = layer.requireBackedLayer.lockLinuxDrawingSurface {
        checkDisposed()
        update()
        inDrawScope {
            ctx.makeCurrent(it.display, it.window)
            performDraw()
            val turnOfVsync = properties.isVsyncEnabled && !SkikoProperties.linuxWaitForVsyncOnRedrawImmediately
            if (turnOfVsync) {
                ctx.swapInterval(it.display, it.window, 0)
            }
            ctx.swap(it.display, it.window)
            OpenGLApi.instance.glFlush()
            if (turnOfVsync) {
                ctx.swapInterval(it.display, it.window, if (properties.isVsyncEnabled) 1 else 0)
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
        private val toRedraw = mutableSetOf<LinuxOpenGLRedrawer>()
        private val toRedrawCopy = mutableSetOf<LinuxOpenGLRedrawer>()
        private val toRedrawVisible = toRedrawCopy
            .asSequence()
            .filterNot(LinuxOpenGLRedrawer::isDisposed)
            .filter { it.layer.isShowing }

        // Deliberately a single static FrameDispatcher (not a per-instance RenderExecutor): it batches all
        // on-screen GL windows into one frame and coalesces a single vsync across monitors. A per-instance
        // executor would regress this — 5 windows would each wait for vsync separately.
        @OptIn(ExperimentalSkikoApi::class)
        private val frameDispatcher = FrameDispatcher(MainUIDispatcher) {
            toRedrawCopy.addAll(toRedraw)
            toRedraw.clear()

            // we should wait for the window with the maximum frame limit to avoid bottleneck when there is a window on a slower monitor
            toRedrawVisible.maxByOrNull { it.frameLimit }?.limitFramesIfNeeded()

            val nanoTime = System.nanoTime()
            for (redrawer in toRedrawVisible) {
                try {
                    redrawer.update(nanoTime)
                } catch (e: CancellationException) {
                    // continue
                }
            }

            val drawingSurfaces = toRedrawVisible.associateWith { lockLinuxDrawingSurface(it.layer.requireBackedLayer) }
            try {
                for (redrawer in toRedrawVisible) {
                    val ds = drawingSurfaces[redrawer]!!
                    redrawer.ctx.makeCurrent(ds.display, ds.window)
                    redrawer.draw()
                }

                // TODO(demin): How can we properly synchronize multiple windows with multiple displays?
                //  I checked, and without vsync there is no tearing. Is it only my case (Ubuntu, Nvidia, X11),
                //  or Ubuntu write all the screen content into an intermediate buffer? If so, then we probably only
                //  need a frame limiter.

                // Synchronize with vsync only for the fastest monitor, for the single window.
                // Otherwise, 5 windows will wait for vsync 5 times.
                val vsyncRedrawer = toRedrawVisible
                    .filter { it.properties.isVsyncEnabled }
                    .maxByOrNull { it.frameLimit }

                for (redrawer in toRedrawVisible.filter { it != vsyncRedrawer }) {
                    val ds = drawingSurfaces[redrawer]!!
                    redrawer.ctx.makeCurrent(ds.display, ds.window)
                    redrawer.ctx.swapInterval(ds.display, ds.window, 0)
                    redrawer.ctx.swap(ds.display, ds.window)
                    OpenGLApi.instance.glFlush()
                }

                if (vsyncRedrawer != null) {
                    val ds = drawingSurfaces[vsyncRedrawer]!!
                    vsyncRedrawer.ctx.makeCurrent(ds.display, ds.window)
                    vsyncRedrawer.ctx.swapInterval(ds.display, ds.window, 1)
                    vsyncRedrawer.ctx.swap(ds.display, ds.window)
                    OpenGLApi.instance.glFlush()
                }
            } finally {
                drawingSurfaces.values.forEach(::unlockLinuxDrawingSurface)
            }

            // Without clearing we will have a memory leak
            toRedrawCopy.clear()
        }
    }
}
