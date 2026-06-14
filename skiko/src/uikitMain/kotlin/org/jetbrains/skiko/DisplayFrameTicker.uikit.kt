package org.jetbrains.skiko

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSSelectorFromString
import platform.QuartzCore.CADisplayLink
import platform.darwin.NSObject

/**
 * Creates a [DisplayFrameTicker] backed by a [CADisplayLink] on the main run loop (iOS). CREATE only — the
 * caller owns sharing and lifecycle; [close][DisplayFrameTicker.close] it when done. Callbacks run on the
 * main thread; [FrameListener.onFrame] receives the display link's predicted present time.
 */
@OptIn(ExperimentalForeignApi::class)
@ExperimentalSkikoApi
fun DisplayFrameTicker(): DisplayFrameTicker = UIKitDisplayFrameTicker()

@OptIn(ExperimentalSkikoApi::class, ExperimentalForeignApi::class)
private class UIKitDisplayFrameTicker : DisplayFrameTicker {
    private val listeners = mutableListOf<FrameListener>()
    private var closed = false

    private val displayLink: CADisplayLink = CADisplayLink.displayLinkWithTarget(
        target = DisplayLinkProxy { onTick() },
        selector = NSSelectorFromString(DisplayLinkProxy::handleTick.name)
    ).apply {
        setPaused(true)
        addToRunLoop(NSRunLoop.mainRunLoop, NSDefaultRunLoopMode)
    }

    override fun subscribe(listener: FrameListener): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    override fun scheduleFrame() {
        if (closed) return
        displayLink.setPaused(false)
    }

    private fun onTick() {
        if (closed) return
        // One-shot: pause until the consumer schedules the next frame (coalescing).
        displayLink.setPaused(true)
        val targetTimeNanos = (displayLink.targetTimestamp * 1_000_000_000.0).toLong()
        for (listener in listeners.toList()) listener.onFrame(targetTimeNanos)
    }

    override fun close() {
        closed = true
        displayLink.invalidate()
        listeners.clear()
    }
}

private class DisplayLinkProxy(private val callback: () -> Unit) : NSObject() {
    @ObjCAction
    fun handleTick() = callback()
}
