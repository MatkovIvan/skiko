package org.jetbrains.skiko

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.jetbrains.skiko.redrawer.MetalVSyncer
import org.jetbrains.skiko.redrawer.RenderExecutor
import java.awt.Component
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Creates an AWT-backed [DisplayFrameTicker] for [component]'s display. CREATE only — the caller owns
 * sharing and lifecycle.
 *
 * **You must [close][DisplayFrameTicker.close] it**: an un-closed ticker keeps its frame loop (and a
 * reference to [component]) alive for the process lifetime.
 *
 * Pacing: a [FrameLimiter] at the component's display refresh rate (the portable fallback). The [pacer]
 * seam is where a real vsync source (CVDisplayLink/MetalVSyncer) and its predicted present time can be
 * wired in — which is why the pacer *returns* the target time (the vsync source is what knows it).
 */
@ExperimentalSkikoApi
fun DisplayFrameTicker(component: Component): DisplayFrameTicker {
    val refreshRate: () -> Double = {
        // graphicsConfiguration is null before the component is displayable; fall back until realized.
        (component.graphicsConfiguration?.device?.displayMode?.refreshRate?.toDouble() ?: 0.0)
            .coerceAtLeast(MinMainstreamMonitorRefreshRate)
    }
    val pacingScope = CoroutineScope(Job())
    val frameLimiter = FrameLimiter(
        coroutineScope = pacingScope,
        frameMillis = { (1000.0 / refreshRate()).toLong() },
    )
    return AwtDisplayFrameTicker(
        pacer = {
            // The pacing wait runs off the EDT (FrameLimiter switches dispatchers internally).
            frameLimiter.awaitNextFrame()
            // Estimate: one refresh ahead of now. A real vsync source returns the CVDisplayLink outputTime here.
            System.nanoTime() + (1_000_000_000.0 / refreshRate()).toLong()
        },
        onClose = { pacingScope.cancel() },
    )
}

/**
 * A Metal/macOS vsync-aligned [DisplayFrameTicker] whose [FrameListener.onFrame] target time is the
 * **real** CVDisplayLink predicted present time, not the refresh-rate estimate. Paced by the display
 * link of [windowHandle]'s screen.
 *
 * Internal for now: it requires a native window handle, which on AWT is owned by the heavyweight render
 * component. It is wired into a public windowed ticker once that component drives it.
 *
 * @param windowHandle the NSWindow pointer whose screen's vsync paces this ticker.
 */
internal fun metalDisplayFrameTicker(windowHandle: Long): DisplayFrameTicker {
    val vsyncer = MetalVSyncer(windowHandle)
    return AwtDisplayFrameTicker(
        // waitForVSync returns the CVDisplayLink outputTime (System.nanoTime clock), or 0 if unknown.
        pacer = { offThread { vsyncer.waitForVSync() } },
        onClose = { vsyncer.dispose() },
    )
}

/**
 * The AWT [DisplayFrameTicker] implementation, built on [RenderExecutor] so it shares the one
 * coalescing/EDT/off-thread frame-driving primitive.
 *
 * The [pacer] both paces the frame and *returns* its predicted present time, so the vsync source — the
 * thing that actually knows the present time — is the single place that produces it. Injecting it
 * lets the scheduling/delivery/coalescing/idle-resume contract be unit-tested deterministically without
 * a display or native vsync.
 *
 * @param pacer run per frame on the frame coroutine; paces (off the EDT) and returns the target present
 * time in [System.nanoTime] units. The returned value is delivered to listeners on the EDT.
 * @param onClose extra teardown beyond stopping the frame loop (e.g. cancel the pacing scope).
 */
internal class AwtDisplayFrameTicker(
    private val pacer: suspend RenderExecutor.() -> Long,
    private val onClose: () -> Unit = {},
) : DisplayFrameTicker {
    private val listeners = CopyOnWriteArrayList<FrameListener>()

    private val executor = RenderExecutor {
        val target = pacer()
        // CopyOnWriteArrayList iteration is snapshot-safe against concurrent (un)subscribe.
        for (listener in listeners) {
            listener.onFrame(target)
        }
    }

    override fun subscribe(listener: FrameListener): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    override fun scheduleFrame() {
        executor.scheduleFrame()
    }

    override fun close() {
        executor.close()
        listeners.clear()
        onClose()
    }
}
