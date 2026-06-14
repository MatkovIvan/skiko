package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skiko.*
import org.jetbrains.skiko.context.rasterizeFrame
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities.*

/**
 * The AWT on-screen Metal driver: a thin frame loop + vsync pacing over a [MetalRenderContext], which owns
 * the device, drawable, present and occlusion. Content is provided by [SkiaPanel.draw].
 *
 * Transitional: the loop/pacing still live here; once `SkiaPanel` drives the context via the render-context
 * factory + `RenderExecutor`, this class is deleted.
 *
 * @see MetalRenderContext
 */
internal class MetalRedrawer(
    private val layer: SkiaPanel,
    analytics: SkiaLayerAnalytics,
    properties: SkiaLayerProperties
) : AWTRedrawer(layer, analytics, GraphicsApi.METAL) {
    private val ctx = MetalRenderContext(layer, properties)

    companion object {
        init {
            Library.load()
        }
    }

    private val drawLock = Any()
    private val vSyncer = if (properties.isVsyncEnabled) MetalVSyncer(layer.windowHandle) else null

    init {
        onDeviceChosen(ctx.adapter.name)
        onContextInit()
    }

    override val renderInfo: String get() = ctx.rendererInfo()

    @OptIn(ExperimentalSkikoApi::class)
    override val renderContext: RenderContext get() = ctx

    private val frameDispatcher = FrameScheduler()

    override fun dispose() = synchronized(drawLock) {
        frameDispatcher.cancel()
        ctx.close()
        vSyncer?.dispose()
        super.dispose()
    }

    override fun needRender(throttledToVsync: Boolean) {
        checkDisposed()
        frameDispatcher.scheduleFrame(needUpdate = true, throttledToVsync = throttledToVsync)
    }

    override fun renderImmediately() {
        checkDisposed()
        update()
        inDrawScope {
            if (!isDisposed) { // Redrawer may be disposed in user code, during `update`
                performDraw()
                // Trying to draw immediately in Metal will result in lost (undrawn)
                // frames if there are more than two between consecutive vsync events.
                if (SkikoProperties.macOSWaitForPreviousFrameVsyncOnRedrawImmediately) {
                    runBlocking {
                        vSyncer?.waitForVSync()
                    }
                }
            }
        }
    }

    private suspend fun draw() {
        inDrawScope {
            // Move drawing to another thread to free the main thread (keeps FPS stable —
            // see [SkiaLayerPerformanceTest]).
            withContext(dispatcherToBlockOn) {
                performDraw()
            }
        }
        if (isDisposed) throw CancellationException()

        // When the window is occluded, don't redraw fast (battery).
        if (ctx.isWindowOccluded) {
            withTimeoutOrNull(300) {
                @Suppress("ControlFlowWithEmptyBody")
                while (ctx.occlusionChannel.receive()) { }
            }
        }
    }

    private fun LayerDrawScope.performDraw() = synchronized(drawLock) {
        if (!isDisposed && scaledLayerWidth > 0 && scaledLayerHeight > 0) {
            autoreleasepool {
                val surface = ctx.acquireSurface(scaledLayerWidth, scaledLayerHeight)
                surface.canvas.rasterizeFrame { layer.draw(this) }
                ctx.present()
            }
        }
    }

    override fun syncBounds() = synchronized(drawLock) {
        check(isEventDispatchThread()) { "Method should be called from AWT event dispatch thread" }
        val rootPane = getRootPane(layer)
        val globalPosition = convertPoint(layer.requireBackedLayer, 0, 0, rootPane)
        val x = globalPosition.x
        val y = rootPane.height - globalPosition.y - layer.height
        val width = layer.requireBackedLayer.width.coerceAtLeast(0)
        val height = layer.requireBackedLayer.height.coerceAtLeast(0)
        Logger.debug { "MetalRedrawer#resize $this {x: $x y: $y width: $width height: $height} rootPane: ${rootPane.size}" }
        ctx.resize(x, y, width, height, layer.contentScale)
    }

    override fun setVisible(isVisible: Boolean) {
        Logger.debug { "MetalRedrawer#setVisible($isVisible)" }
        ctx.setVisible(isVisible)
    }

    private inner class FrameScheduler {
        private var updateRequested = AtomicBoolean(false)

        private fun updateIfRequested() {
            if (updateRequested.getAndSet(false)) {
                update()
            }
        }

        private val updateExecutor = RenderExecutor {
            if (layer.isShowing) {
                updateIfRequested()
            }
        }

        private val frameExecutor = RenderExecutor {
            if (layer.isShowing) {
                updateIfRequested()
                draw()
            }
            vSyncer?.waitForVSync()
        }

        fun scheduleFrame(needUpdate: Boolean, throttledToVsync: Boolean) {
            if (needUpdate) {
                updateRequested.set(true)

                if (!throttledToVsync) {
                    updateExecutor.scheduleFrame()
                }
            }
            frameExecutor.scheduleFrame()
        }

        fun cancel() {
            updateExecutor.close()
            frameExecutor.close()
        }
    }
}
