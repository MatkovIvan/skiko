package org.jetbrains.skiko

/**
 * A vsync-aligned frame source shared across platforms.
 *
 * It is the companion of [RenderContext]: `RenderContext` is *how* you draw a frame, `DisplayFrameTicker`
 * is *when*. A consumer that owns its view (via `createRenderContext`) subscribes a [FrameListener], calls
 * [scheduleFrame], and on each callback does `acquireSurface → draw → present` — without writing a
 * platform-specific frame loop (`requestAnimationFrame`, `Choreographer`, `CADisplayLink`, `CVDisplayLink`).
 *
 * Skiko owns *how* to tick (vsync pacing, coalescing, delivery on the platform's UI thread, idle
 * pause/resume, and the predicted present time). It does **not** decide how many tickers exist or who shares
 * them: a consumer creates one (typically per display), shares it, and [close]s it.
 *
 * Each platform provides a factory: `DisplayFrameTicker(component)` on AWT, `DisplayFrameTicker()` on web /
 * Android / macOS / iOS. Listeners are invoked on the platform's UI thread (the AWT EDT, the browser's
 * animation-frame callback, the Android main thread, the iOS/macOS main run loop).
 */
@ExperimentalSkikoApi
interface DisplayFrameTicker : AutoCloseable {
    /**
     * Register [listener] to receive frame callbacks. The returned [AutoCloseable] unsubscribes it.
     * Subscribing does not by itself start frames — call [scheduleFrame].
     */
    fun subscribe(listener: FrameListener): AutoCloseable

    /**
     * Request the next frame. Coalescing: multiple calls before a frame begins collapse to one frame;
     * calls during a frame schedule exactly one follow-up. Resumes the ticker if it was idle.
     */
    fun scheduleFrame()
}

/**
 * A frame callback for [DisplayFrameTicker], invoked on the platform's UI thread.
 *
 * @param targetTimeNanos the predicted present time of this frame, in the platform's monotonic clock
 * (`System.nanoTime` units on JVM/Android). Monotonic and suitable as an animation clock.
 */
@ExperimentalSkikoApi
fun interface FrameListener {
    fun onFrame(targetTimeNanos: Long)
}
