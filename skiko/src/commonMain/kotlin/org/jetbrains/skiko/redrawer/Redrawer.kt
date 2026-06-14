@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.RenderContext
import org.jetbrains.skiko.hostOs
import kotlin.time.TimeSource

private val initialTime = TimeSource.Monotonic.markNow()

internal interface Redrawer {
    fun dispose()
    fun needRender(throttledToVsync: Boolean)
    fun renderImmediately()
    fun syncBounds() = Unit
    fun update(nanoTime: Long = initialTime.elapsedNow().inWholeNanoseconds)
    fun setVisible(isVisible: Boolean) = Unit
    val renderInfo: String
    fun isTransparentBackgroundSupported(): Boolean

    /**
     * The live [RenderContext] this redrawer rasterizes through (its per-API on-screen context handler), or
     * `null` for redrawers that do not expose one. Surfaced publicly via `SkiaPanel.renderContext` for GPU
     * interop.
     */
    val renderContext: RenderContext? get() = null
}

internal fun defaultIsTransparentBackgroundSupported(isFullscreen: Boolean): Boolean {
    if (hostOs == OS.MacOS) {
        // macOS transparency is always supported
        return true
    }

    // for non-macOS in fullscreen transparency is not supported
    return !isFullscreen
}