@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.runBlocking
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.SkiaLayerAnalytics.DeviceAnalytics
import org.jetbrains.skiko.SkiaPanel
import org.jetbrains.skiko.Version
import org.jetbrains.skiko.hostOs
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single, generic on-screen driver for [SkiaPanel]'s DirectSurface engine: an off-EDT coalescing frame
 * loop ([RenderExecutor]) over an [AwtRenderContext], plus the rendering analytics. It replaces the deleted
 * per-API `*Redrawer` hierarchy + `AWTRedrawer` — there is no longer a second per-API type; the backend
 * specifics live in the [AwtRenderContext].
 *
 * Per frame: `paceBeforeFrame → (update) → renderFrame → paceAfterFrame`. The optional separate update
 * executor preserves Metal's non-vsync-throttled input-latency optimisation ([AwtRenderContext.separatesUpdateAndDraw]).
 */
internal class OnScreenRenderer(
    private val panel: SkiaPanel,
    val ctx: AwtRenderContext,
    analytics: SkiaLayerAnalytics,
) {
    private val rendererAnalytics = analytics.renderer(Version.skiko, hostOs, ctx.graphicsApi)
    private val deviceAnalytics: DeviceAnalytics?
    private var isFirstFrameRendered = false

    var isDisposed = false
        private set

    init {
        rendererAnalytics.init()
        rendererAnalytics.deviceChosen()
        deviceAnalytics = analytics.device(Version.skiko, hostOs, ctx.graphicsApi, ctx.deviceName).also {
            it.init()
            it.contextInit()
        }
    }

    val renderInfo: String get() = ctx.renderInfo
    fun isTransparentBackgroundSupported(): Boolean = ctx.isTransparentBackgroundSupported()

    private val updateRequested = AtomicBoolean(false)
    private fun updateIfRequested() {
        if (updateRequested.getAndSet(false)) panel.update(System.nanoTime())
    }

    private val updateExecutor = if (ctx.separatesUpdateAndDraw) RenderExecutor {
        if (panel.isShowing) updateIfRequested()
    } else null

    private val frameExecutor = RenderExecutor {
        ctx.paceBeforeFrame()
        if (panel.isShowing) {
            updateIfRequested()
            drawFrame(immediate = false)
        }
        ctx.paceAfterFrame()
    }

    fun needRender(throttledToVsync: Boolean) {
        check(!isDisposed) { "OnScreenRenderer is disposed" }
        updateRequested.set(true)
        if (updateExecutor != null && !throttledToVsync) {
            updateExecutor.scheduleFrame()
        }
        frameExecutor.scheduleFrame()
    }

    fun renderImmediately() {
        check(!isDisposed) { "OnScreenRenderer is disposed" }
        panel.update(System.nanoTime())
        if (!isDisposed) { // panel may be disposed in user code during `update`
            runBlocking { drawFrame(immediate = true) }
        }
    }

    private suspend fun drawFrame(immediate: Boolean) {
        if (isDisposed) return
        val isFirstFrame = !isFirstFrameRendered
        if (isFirstFrame) deviceAnalytics?.beforeFirstFrameRender()
        deviceAnalytics?.beforeFrameRender()
        try {
            panel.inDrawScope {
                ctx.renderFrame(scaledLayerWidth, scaledLayerHeight, immediate) { canvas -> panel.draw(canvas) }
            }
        } finally {
            isFirstFrameRendered = true
            if (isFirstFrame && !isDisposed) deviceAnalytics?.afterFirstFrameRender()
            deviceAnalytics?.afterFrameRender()
        }
    }

    fun syncBounds() = ctx.syncBounds()
    fun setVisible(isVisible: Boolean) = ctx.setVisible(isVisible)

    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        updateExecutor?.close()
        frameExecutor.close()
        ctx.close()
    }
}
