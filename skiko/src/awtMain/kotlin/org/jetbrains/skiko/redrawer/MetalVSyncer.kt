package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.skiko.Library
import org.jetbrains.skiko.RendezvousBroadcastChannel


/**
 * A utility allowing several coroutines to wait for the next vsync.
 */
internal class MetalVSyncer(windowPtr: Long) {

    // The underlying throttler that blocks a thread
    private val displayLinkThrottler = DisplayLinkThrottler(windowPtr)

    // Broadcasts the predicted present time (nanoseconds, System.nanoTime clock) of the vsync, or 0
    // when it is unknown (no display link).
    private val channel = RendezvousBroadcastChannel<Long>()

    // A channel to trigger the thread that waits on vsync to doing so
    private val triggerResumeOnVSync = Channel<Unit>(Channel.CONFLATED)

    private val job = CoroutineScope(dispatcherToBlockOn).launch {
        while (isActive) {
            triggerResumeOnVSync.receive()  // Suspend until needed
            val outputTimeNanos = displayLinkThrottler.waitVSync()  // This blocks (not suspends!) the thread
            if (isActive) {
                channel.sendAll(outputTimeNanos)
            }
        }
    }.also {
        it.invokeOnCompletion {
            displayLinkThrottler.dispose()
        }
    }

    /**
     * Suspends until the next vsync and returns its predicted present time (nanoseconds, on the
     * [System.nanoTime] clock), or 0 if that time is unknown.
     */
    suspend fun waitForVSync(): Long {
        triggerResumeOnVSync.trySend(Unit)
        return channel.receive()
    }

    fun dispose() {
        job.cancel()
    }
}

private class DisplayLinkThrottler(windowPtr: Long) {
    private val implPtr = create(windowPtr)

    fun dispose() = dispose(implPtr)

    /*
     * Creates a DisplayLink if needed with refresh rate matching NSScreen of NSWindow passed in [windowPtr].
     * If DisplayLink is already active, blocks until next vsync for physical screen of NSWindow passed in [windowPtr].
     */
    private external fun create(windowPtr: Long): Long

    fun waitVSync(): Long = waitVSync(implPtr)

    private external fun dispose(implPtr: Long)

    private external fun waitVSync(implPtr: Long): Long

    companion object {
        init {
            Library.load()
        }
    }
}
