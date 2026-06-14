package org.jetbrains.skiko

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSTimer
import platform.QuartzCore.CACurrentMediaTime

/**
 * Creates a [DisplayFrameTicker] for macOS, paced on the main run loop at the display refresh rate.
 *
 * macOS (deployment target 11.0) has no `CADisplayLink`; this uses an [NSTimer] at ~60 Hz on the main run
 * loop — adequate for driving a [RenderContext] animation loop. CREATE only — the caller owns sharing and
 * lifecycle; [close][DisplayFrameTicker.close] it when done. Callbacks run on the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
@ExperimentalSkikoApi
fun DisplayFrameTicker(): DisplayFrameTicker = MacosDisplayFrameTicker()

@OptIn(ExperimentalSkikoApi::class, ExperimentalForeignApi::class)
private class MacosDisplayFrameTicker : DisplayFrameTicker {
    private val listeners = mutableListOf<FrameListener>()
    private val frameIntervalSeconds = 1.0 / 60.0
    private var scheduled = false
    private var closed = false

    override fun subscribe(listener: FrameListener): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    override fun scheduleFrame() {
        if (closed || scheduled) return
        scheduled = true
        NSTimer.scheduledTimerWithTimeInterval(frameIntervalSeconds, repeats = false) {
            scheduled = false
            if (closed) return@scheduledTimerWithTimeInterval
            val targetTimeNanos = ((CACurrentMediaTime() + frameIntervalSeconds) * 1_000_000_000.0).toLong()
            for (listener in listeners.toList()) listener.onFrame(targetTimeNanos)
        }
    }

    override fun close() {
        closed = true
        listeners.clear()
    }
}
