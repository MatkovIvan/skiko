@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko

import org.jetbrains.skiko.redrawer.RenderExecutor
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayFrameTickerTest {

    private fun awaitEdtIdle() = SwingUtilities.invokeAndWait { }

    private fun ticker(pacer: suspend RenderExecutor.() -> Long) = AwtDisplayFrameTicker(pacer = pacer)

    @Test(timeout = 10_000)
    fun `onFrame is delivered on the EDT with the pacer's target time`() {
        val latch = CountDownLatch(1)
        val onEdt = AtomicReference(false)
        val got = AtomicLong(-1)
        val t = ticker { 123_456_789L }
        t.subscribe { targetTimeNanos ->
            onEdt.set(SwingUtilities.isEventDispatchThread())
            got.set(targetTimeNanos)
            latch.countDown()
        }
        try {
            t.scheduleFrame()
            assertTrue(latch.await(5, TimeUnit.SECONDS), "onFrame not delivered")
            assertTrue(onEdt.get(), "onFrame must run on the EDT")
            assertEquals(123_456_789L, got.get(), "delivered target must be exactly what the pacer returned")
        } finally {
            t.close()
        }
    }

    @Test(timeout = 10_000)
    fun `each frame delivers the target the pacer produced, in order`() {
        val seq = AtomicLong(0)
        val targets = Collections.synchronizedList(mutableListOf<Long>())
        val ran = CountDownLatch(3)
        val t = ticker { seq.addAndGet(1000) }
        t.subscribe { targetTimeNanos ->
            targets.add(targetTimeNanos)
            ran.countDown()
        }
        try {
            repeat(3) {
                t.scheduleFrame()
                awaitEdtIdle()
            }
            assertTrue(ran.await(5, TimeUnit.SECONDS), "not all frames ran")
            assertEquals(listOf(1000L, 2000L, 3000L), targets.toList().take(3), "monotonic, pacer-produced targets")
        } finally {
            t.close()
        }
    }

    @Test(timeout = 10_000)
    fun `multiple listeners all receive the frame and unsubscribe stops one`() {
        val a = AtomicInteger(0)
        val b = AtomicInteger(0)
        val t = ticker { 0L }
        val subA = t.subscribe { a.incrementAndGet() }
        t.subscribe { b.incrementAndGet() }
        try {
            val firstRound = CountDownLatch(1)
            t.subscribe { firstRound.countDown() }
            t.scheduleFrame()
            assertTrue(firstRound.await(5, TimeUnit.SECONDS))
            awaitEdtIdle()
            assertEquals(1, a.get(), "listener A should have fired once")
            assertEquals(1, b.get(), "listener B should have fired once")

            subA.close() // unsubscribe A
            val secondRound = CountDownLatch(1)
            t.subscribe { secondRound.countDown() }
            t.scheduleFrame()
            assertTrue(secondRound.await(5, TimeUnit.SECONDS))
            awaitEdtIdle()
            assertEquals(1, a.get(), "unsubscribed listener A must not fire again")
            assertEquals(2, b.get(), "listener B should have fired again")
        } finally {
            t.close()
        }
    }

    @Test(timeout = 10_000)
    fun `bursts of scheduleFrame coalesce into a single follow-up frame`() {
        val firstStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val twoFramesRan = CountDownLatch(2)
        val frames = AtomicInteger(0)
        val t = ticker {
            val n = frames.incrementAndGet()
            if (n == 1) {
                firstStarted.countDown()
                offThread { release.await() } // hold the first frame in-flight
            }
            0L
        }
        t.subscribe { twoFramesRan.countDown() }
        try {
            t.scheduleFrame()
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "first frame never started")
            repeat(50) { t.scheduleFrame() } // burst while frame 1 is in-flight
            release.countDown()
            assertTrue(twoFramesRan.await(5, TimeUnit.SECONDS), "coalesced follow-up never ran")
            Thread.sleep(200)
            awaitEdtIdle()
            assertEquals(2, frames.get(), "bursts must coalesce into one follow-up frame")
        } finally {
            t.close()
        }
    }

    @Test(timeout = 10_000)
    fun `ticker goes idle and resumes on a later scheduleFrame`() {
        val frames = AtomicInteger(0)
        val round = AtomicReference(CountDownLatch(1))
        val t = ticker { 0L }
        t.subscribe {
            frames.incrementAndGet()
            round.get().countDown()
        }
        try {
            t.scheduleFrame()
            assertTrue(round.get().await(5, TimeUnit.SECONDS), "first frame never ran")
            awaitEdtIdle()
            assertEquals(1, frames.get())

            // Idle: no scheduleFrame -> no spurious frames.
            Thread.sleep(200)
            awaitEdtIdle()
            assertEquals(1, frames.get(), "ticker must not fire frames while idle")

            // Resume.
            round.set(CountDownLatch(1))
            t.scheduleFrame()
            assertTrue(round.get().await(5, TimeUnit.SECONDS), "ticker did not resume from idle")
            awaitEdtIdle()
            assertEquals(2, frames.get())
        } finally {
            t.close()
        }
    }

    @Test(timeout = 10_000)
    fun `close stops further frames`() {
        val count = AtomicInteger(0)
        val first = CountDownLatch(1)
        val t = ticker { 0L }
        t.subscribe { count.incrementAndGet(); first.countDown() }
        t.scheduleFrame()
        assertTrue(first.await(5, TimeUnit.SECONDS))
        awaitEdtIdle()
        val afterFirst = count.get()
        t.close()
        t.scheduleFrame()
        Thread.sleep(200)
        awaitEdtIdle()
        assertEquals(afterFirst, count.get(), "no frames should be delivered after close()")
    }
}
