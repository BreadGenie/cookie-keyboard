package org.futo.voiceinput.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * A failure observed after reset()/cancel() closed the feed queue belongs to an ended
 * session: it must not report modelLoadingFailed on top of the outcome that already won.
 */
class RecordingSessionGuardTest {
    @Test
    fun currentSessionReportsItsOwnFailureExactlyOnce() {
        val guard = RecordingSessionGuard()
        val epoch = guard.begin()
        assertTrue(guard.claim(epoch))
        assertFalse(guard.claim(epoch))
    }

    @Test
    fun resetOrCancelMakesALateFailureStale() {
        val guard = RecordingSessionGuard()
        val recording = guard.begin()
        guard.begin() // reset()/cancel() closed the feed queue
        assertFalse(guard.claim(recording))
    }

    @Test
    fun aNewSessionReportsEvenAfterAnEarlierSessionWasSuppressed() {
        val guard = RecordingSessionGuard()
        val first = guard.begin()
        guard.begin()
        assertFalse(guard.claim(first))
        val second = guard.current()
        assertTrue(guard.claim(second))
    }

    @Test
    fun resetDuringAReportedFailureEndsTheSession() {
        val guard = RecordingSessionGuard()
        val epoch = guard.begin()
        assertTrue(guard.claim(epoch))
        guard.begin() // the failure handler itself calls reset()
        assertFalse(guard.claim(epoch))
    }

    @Test
    fun concurrentPreloadAndRecorderFailuresReportExactlyOneTerminalOutcome() {
        val guard = RecordingSessionGuard()
        repeat(50) {
            val epoch = guard.begin()
            val start = CountDownLatch(1)
            val winners = AtomicInteger()
            val threads = (0 until 4).map {
                Thread {
                    start.await()
                    if(guard.claim(epoch)) winners.incrementAndGet()
                }
            }
            threads.forEach { it.start() }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(1, winners.get())
        }
    }
}
