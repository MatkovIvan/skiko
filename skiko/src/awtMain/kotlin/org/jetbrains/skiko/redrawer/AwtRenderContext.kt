@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.RenderContext
import org.jetbrains.skiko.hostOs

/**
 * A [RenderContext] that also drives AWT on-screen presentation. It folds in what used to be the per-API
 * `*Redrawer`: the actual frame (device make-current / drawing-surface lock / present / swap / autorelease
 * pool / off-EDT hand-off), the pacing, visibility and on-screen bounds. There is **no second per-API
 * hierarchy** — [org.jetbrains.skiko.SkiaPanel] owns one generic scheduling loop ([OnScreenRenderer]) and
 * calls these hooks; the backend-specific behaviour lives here.
 *
 * Pacing is expressed as two hooks so each backend keeps its **exact** pacing position: software/GL frame
 * limiters run [paceBeforeFrame]; Metal's vsync wait runs [paceAfterFrame]; ANGLE/Direct3D swap with vsync
 * inside [renderFrame] and pace in neither.
 */
internal interface AwtRenderContext : RenderContext {
    /** Adapter/device name for analytics; `null` if unknown. */
    val deviceName: String?

    /** Human-readable backend/device summary (the old `Redrawer.renderInfo`). */
    val renderInfo: String

    fun isTransparentBackgroundSupported(): Boolean

    /**
     * Whether non-vsync-throttled [OnScreenRenderer.needRender] should run [org.jetbrains.skiko.SkiaPanel.update]
     * on a separate executor (input-latency optimisation — Metal only).
     */
    val separatesUpdateAndDraw: Boolean get() = false

    /** Frame limiter, run before the frame is rendered (software / Linux GL). Default: nothing. */
    suspend fun paceBeforeFrame() {}

    /** Vsync wait / occlusion throttle, run after the frame (Metal). Default: nothing. */
    suspend fun paceAfterFrame() {}

    /**
     * Render one frame: acquire a [width]x[height] surface, run [render] on its canvas, then present and swap.
     * Owns the backend's threading (off-EDT hand-off), drawing-surface lock, autorelease pool and size guard.
     * [immediate] selects the synchronous redraw path (its vsync handling differs per backend). Suspends
     * because some backends move the GPU work off the EDT.
     */
    suspend fun renderFrame(width: Int, height: Int, immediate: Boolean, render: (Canvas) -> Unit)

    /** AWT peer became visible/hidden (Metal pauses its drawable). Default: nothing. */
    fun setVisible(isVisible: Boolean) {}

    /** AWT bounds changed; reposition the on-screen surface if the backend needs it (Metal). Default: nothing. */
    fun syncBounds() {}
}

/** Default transparent-background support: always on macOS; off in fullscreen elsewhere. */
internal fun defaultIsTransparentBackgroundSupported(isFullscreen: Boolean): Boolean {
    if (hostOs == OS.MacOS) return true
    return !isFullscreen
}
