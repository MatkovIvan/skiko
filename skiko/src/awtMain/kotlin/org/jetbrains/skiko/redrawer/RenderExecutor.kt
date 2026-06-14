package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.withContext
import org.jetbrains.skiko.FrameDispatcher
import org.jetbrains.skiko.MainUIDispatcher

/**
 * The off-EDT frame driver behind `AwtDisplayFrameTicker`, the AWT `DisplayFrameTicker` implementation.
 *
 * It wraps a single [FrameDispatcher] and adds an off-EDT hop, giving the ticker two things:
 *  - [scheduleFrame] coalesces requests exactly like [FrameDispatcher] (multiple calls before a frame
 *    begins collapse to one [onFrame]; calls during a frame schedule exactly one follow-up frame).
 *  - [onFrame] runs on the EDT ([MainUIDispatcher]); the body paces and fans out to listeners there and
 *    uses [offThread] to run the blocking vsync wait off the EDT.
 *
 * Pulling this out of the ticker keeps the scheduling/threading mechanism separate from the ticker's
 * listener/pacing policy, and lets that mechanism be unit-tested deterministically (inject a frame body,
 * drive [scheduleFrame], assert thread + coalescing) without a display or native vsync.
 *
 * Used by `AwtDisplayFrameTicker` and by the per-instance on-screen [Redrawer]s (Metal, Software,
 * Direct3D, ANGLE, direct-software); each keeps its own bespoke pacing inside its [onFrame] body (Metal
 * per-window CVDisplayLink + occlusion backoff, D3D vsync fused into the native swap, software
 * `FrameLimiter`). The Linux/Windows GL redrawers are the deliberate exception: they keep a single
 * **static** [FrameDispatcher] that batches all on-screen windows into one frame and coalesces a single
 * vsync across monitors — a cross-instance concern a per-instance executor would regress (5 windows would
 * wait for vsync 5 times). [RenderExecutor] decides *when work runs* and *on which thread*, never *how it
 * is paced*.
 *
 * @param onFrame the per-frame body, invoked on the EDT for each scheduled frame.
 */
internal class RenderExecutor(
    private val onFrame: suspend RenderExecutor.() -> Unit
) : AutoCloseable {
    private val frameDispatcher = FrameDispatcher(MainUIDispatcher) {
        this@RenderExecutor.onFrame()
    }

    /**
     * Schedule the next frame. Coalescing semantics match [FrameDispatcher.scheduleFrame].
     */
    fun scheduleFrame() {
        frameDispatcher.scheduleFrame()
    }

    /**
     * Run [block] off the EDT on the shared blocking pool ([dispatcherToBlockOn]), suspend until it
     * completes, and return its result. Used for the GPU rasterize/present + any blocking vsync wait,
     * so the EDT stays free. After it returns, the frame continuation resumes back on the EDT.
     *
     * Covers the simple off-thread hop; per-API pacing with bespoke blocking strategies (e.g. an
     * occlusion `withTimeoutOrNull` loop) stays in the caller's [onFrame] body.
     */
    suspend fun <T> offThread(block: suspend () -> T): T =
        withContext(dispatcherToBlockOn) {
            block()
        }

    /**
     * Cancel the frame loop. No further [onFrame] callbacks happen after this returns.
     */
    override fun close() {
        frameDispatcher.cancel()
    }
}
