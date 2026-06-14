package org.jetbrains.skiko

import kotlinx.browser.window

/**
 * Creates a [DisplayFrameTicker] backed by the browser's `requestAnimationFrame`. CREATE only — the caller
 * owns sharing and lifecycle; [close][DisplayFrameTicker.close] it when done. Callbacks run on the browser's
 * animation-frame callback (the main thread).
 */
@ExperimentalSkikoApi
fun DisplayFrameTicker(): DisplayFrameTicker = WebDisplayFrameTicker()

@OptIn(ExperimentalSkikoApi::class)
private class WebDisplayFrameTicker : DisplayFrameTicker {
    private val listeners = mutableListOf<FrameListener>()
    private var scheduled = false
    private var closed = false

    override fun subscribe(listener: FrameListener): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    override fun scheduleFrame() {
        if (closed || scheduled) return
        scheduled = true
        window.requestAnimationFrame { timestampMillis ->
            scheduled = false
            if (closed) return@requestAnimationFrame
            val targetTimeNanos = (timestampMillis * 1_000_000.0).toLong()
            // Snapshot so a listener may (un)subscribe during dispatch.
            for (listener in listeners.toList()) listener.onFrame(targetTimeNanos)
        }
    }

    override fun close() {
        closed = true
        listeners.clear()
    }
}
