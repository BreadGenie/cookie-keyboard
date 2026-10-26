package org.futo.voiceinput.shared.opencl

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * v43 quiescence fence seam. The Binder call itself needs an Android device;
 * these tests drive the real waiter and the real request ledger with a real
 * single-thread dispatcher/executor, not an Unconfined fake.
 */
class ComparisonFenceTest {
    @Test
    fun aCancelledWaitingCoroutineStillReceivesTheWorkerAck() = runBlocking {
        val wait = OpenClFenceWait()
        val worker = Executors.newSingleThreadExecutor()
        val dispatcher = worker.asCoroutineDispatcher()
        val seen = AtomicReference<OpenClFenceAck?>()
        try {
            val job = launch(dispatcher) { seen.set(wait.await()) }
            // Let the non-cancellable wait suspend on the dispatcher.
            delay(50)
            job.cancel()
            // The serial worker replies only after its prior task returned.
            wait.ack(idle = true)
            withTimeout(2_000) { while (seen.get() == null) delay(5) }
            assertTrue("the ack proves quiescence and must not be discarded", seen.get()!!.idle)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun aFailedFenceCarriesAStructuredStage() = runBlocking {
        val wait = OpenClFenceWait()
        wait.fail("service_lost")
        val failure = runCatching { wait.await() }.exceptionOrNull()
        assertTrue(failure is OpenClFenceException)
        assertEquals("service_lost", (failure as OpenClFenceException).stage)
    }

    @Test
    fun theFirstWireResultWinsAndLateRepliesAreIgnored() = runBlocking {
        val wait = OpenClFenceWait()
        wait.ack(idle = true)
        wait.fail("late_service_lost")
        assertTrue(wait.await().idle)
    }

    @Test
    fun anAckBeforeAwaitIsNotLost() = runBlocking {
        val wait = OpenClFenceWait()
        wait.ack(idle = false)
        assertFalse(wait.await().idle)
    }

    @Test
    fun realRequestLedgerIsIdleOnlyAfterThePriorWorkerTaskReturns() = runBlocking {
        val requests = OpenClRequests()
        val worker = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ackIdle = AtomicReference<Boolean?>()
        try {
            assertTrue(requests.add("run", Any()))
            worker.execute {
                requests.start("run") {}
                started.countDown()
                release.await()
                requests.finish("run")
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            // This is the DRAIN task: enqueued after RUN on the same serial
            // worker, so it cannot report idle before RUN's finally.
            worker.execute { ackIdle.set(requests.idle()) }
            delay(50)
            assertNull("the fence must be enqueued behind the running task", ackIdle.get())
            release.countDown()
            withTimeout(2_000) { while (ackIdle.get() == null) delay(5) }
            assertTrue(ackIdle.get()!!)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }
}
