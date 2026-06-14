package org.jetbrains.skiko.redrawer

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderExecutorTest {

    private fun awaitEdtIdle() {
        // Flush the EDT so any already-queued onFrame/yield continuations have run.
        SwingUtilities.invokeAndWait { }
    }

    @Test(timeout = 10_000)
    fun `onFrame runs on the EDT after scheduleFrame`() {
        val latch = CountDownLatch(1)
        val ranOnEdt = AtomicReference(false)
        val executor = RenderExecutor {
            ranOnEdt.set(SwingUtilities.isEventDispatchThread())
            latch.countDown()
        }
        try {
            executor.scheduleFrame()
            assertTrue(latch.await(5, TimeUnit.SECONDS), "onFrame was not invoked")
            assertTrue(ranOnEdt.get(), "onFrame must run on the EDT")
        } finally {
            executor.close()
        }
    }

    @Test(timeout = 10_000)
    fun `offThread runs the block off the EDT`() {
        val latch = CountDownLatch(1)
        val onFrameOnEdt = AtomicReference(false)
        val offThreadOnEdt = AtomicReference(true)
        val executor = RenderExecutor {
            onFrameOnEdt.set(SwingUtilities.isEventDispatchThread())
            offThread {
                offThreadOnEdt.set(SwingUtilities.isEventDispatchThread())
            }
            latch.countDown()
        }
        try {
            executor.scheduleFrame()
            assertTrue(latch.await(5, TimeUnit.SECONDS), "onFrame/offThread did not complete")
            assertTrue(onFrameOnEdt.get(), "onFrame must run on the EDT")
            assertFalse(offThreadOnEdt.get(), "offThread block must NOT run on the EDT")
        } finally {
            executor.close()
        }
    }

    @Test(timeout = 10_000)
    fun `multiple scheduleFrame calls before a frame coalesce into one onFrame`() {
        // Block the very first frame on the EDT-dispatched coroutine so all the rapid scheduleFrame()
        // calls below land before that frame ends -> per FrameDispatcher semantics they collapse to a
        // single follow-up frame.
        val firstFrameStarted = CountDownLatch(1)
        val releaseFirstFrame = CountDownLatch(1)
        // Counts down on every frame; awaiting it for 2 deterministically waits for the coalesced
        // follow-up frame (no fixed-sleep lower bound).
        val twoFramesRan = CountDownLatch(2)
        val frameCount = AtomicInteger(0)
        val executor = RenderExecutor {
            val n = frameCount.incrementAndGet()
            twoFramesRan.countDown()
            if (n == 1) {
                firstFrameStarted.countDown()
                // Suspend the first frame (off the EDT) until the test has queued its bursts.
                offThread { releaseFirstFrame.await() }
            }
        }
        try {
            executor.scheduleFrame()
            assertTrue(firstFrameStarted.await(5, TimeUnit.SECONDS), "first frame never started")
            // Burst many requests while the first frame is in-flight.
            repeat(50) { executor.scheduleFrame() }
            releaseFirstFrame.countDown()
            // Deterministically wait for the second (coalesced) frame to actually run.
            assertTrue(twoFramesRan.await(5, TimeUnit.SECONDS), "coalesced follow-up frame never ran")
            // Settle, then assert NO third frame appeared (the over-count guard: never 51).
            Thread.sleep(200)
            awaitEdtIdle()
            assertEquals(2, frameCount.get(), "bursts must coalesce into a single follow-up frame")
        } finally {
            executor.close()
        }
    }

    @Test(timeout = 10_000)
    fun `close stops further frames`() {
        val frameCount = AtomicInteger(0)
        val firstFrame = CountDownLatch(1)
        val executor = RenderExecutor {
            frameCount.incrementAndGet()
            firstFrame.countDown()
        }
        executor.scheduleFrame()
        assertTrue(firstFrame.await(5, TimeUnit.SECONDS), "first frame never ran")
        awaitEdtIdle()
        executor.close()
        val countAfterClose = frameCount.get()
        // Scheduling after close must not produce more frames.
        executor.scheduleFrame()
        Thread.sleep(200)
        awaitEdtIdle()
        assertEquals(countAfterClose, frameCount.get(), "no frames should run after close()")
    }
}
