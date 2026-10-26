package org.futo.voiceinput.shared.opencl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Drives the real [ComparisonDrain] that `PhoneComparison.close()` uses.
 *
 * Only the native boundaries are substituted; ordering, the verdict rule and
 * the recorded fields are the production code. The v40 phone report was a
 * preflight run that never started native work, yet `lease_release` /
 * `cleanup_detail` were a `JobCancellationException` and the drain was
 * reported unconfirmed. A failed main-thread unbind hop must not turn that
 * case into UNKNOWN, while a run that actually used the GPU arm must stay
 * conservative.
 */
class ComparisonDrainTest {
    private class Recorder {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val fields: MutableMap<String, Any> = Collections.synchronizedMap(linkedMapOf())
        fun record(key: String, value: Any) {
            fields[key] = value
        }
    }

    /** A genuine cancellation exception, as produced by a cancelled real Job. */
    private suspend fun jobCancellation(): Throwable {
        val parent = Job()
        val pending = CoroutineScope(parent).async { awaitCancellation() }
        parent.cancel()
        return runCatching { pending.await() }.exceptionOrNull()!!
    }

    private fun drain(
        rec: Recorder,
        gpuUsed: Boolean,
        closeCpu: suspend () -> Unit = { rec.events.add("close_cpu") },
        gpuFence: suspend () -> String = { rec.events.add("gpu_fence"); ComparisonFence.ACKED },
        releaseLeases: suspend () -> Unit = { rec.events.add("release_leases") },
    ) = ComparisonDrain(
        cancelNative = { rec.events.add("cancel_native") },
        closeCpu = closeCpu,
        gpuWorkUsed = { gpuUsed },
        gpuFence = gpuFence,
        releaseLeases = releaseLeases,
        record = rec::record,
    )

    @Test
    fun preflightWithNoNativeWorkDrainsEvenWhenTheLeaseHopIsCancelled() = runBlocking {
        val rec = Recorder()
        val cancellation = jobCancellation()
        assertEquals("JobCancellationException", cancellation.javaClass.simpleName)

        val drained = drain(
            rec,
            gpuUsed = false,
            gpuFence = { throw AssertionError("a run that never used the GPU must not fence the service") },
            releaseLeases = { throw cancellation },
        ).run()

        assertTrue("no native work started, so cleanup is confirmed", drained)
        assertEquals("JobCancellationException", rec.fields["lease_release"])
        assertEquals(false, rec.fields["cleanup_lease_release_failure_is_fatal"])
        assertEquals(false, rec.fields["cleanup_gpu_arm_used"])
        assertEquals(true, rec.fields["cleanup_drain_confirmed"])
        assertFalse(rec.events.contains("gpu_fence"))
    }

    @Test
    fun cancelPrecedesEveryFenceAndNothingRunsAfterTheVerdict() = runBlocking {
        val rec = Recorder()
        assertTrue(drain(rec, gpuUsed = true).run())
        assertEquals(listOf("cancel_native", "close_cpu", "gpu_fence", "release_leases"), rec.events)
        assertEquals(ComparisonFence.ACKED, rec.fields["gpu_cleanup_fence"])
        assertEquals(ComparisonFence.KIND, rec.fields["cleanup_fence_kind"])
        assertEquals("ok", rec.fields["lease_release"])
    }

    @Test
    fun gpuArmFenceFailureStaysUnconfirmed() = runBlocking {
        val rec = Recorder()
        val drained = drain(
            rec,
            gpuUsed = true,
            gpuFence = { throw IllegalStateException("binder died") },
        ).run()

        assertFalse(drained)
        assertEquals("IllegalStateException", rec.fields["gpu_cleanup_fence"])
        assertEquals(false, rec.fields["cleanup_drain_confirmed"])
    }

    @Test
    fun gpuArmBusyAfterTheSerialFenceStaysUnconfirmed() = runBlocking {
        val rec = Recorder()
        val drained = drain(rec, gpuUsed = true, gpuFence = { ComparisonFence.BUSY }).run()

        assertFalse("a later request in the worker ledger is not quiescent", drained)
        assertEquals(ComparisonFence.BUSY, rec.fields["gpu_cleanup_fence"])
        assertEquals(false, rec.fields["cleanup_drain_confirmed"])
    }

    @Test
    fun gpuFenceFailureRecordsTheStructuredStage() = runBlocking {
        val rec = Recorder()
        val drained = drain(
            rec,
            gpuUsed = true,
            gpuFence = { throw OpenClFenceException("service_lost") },
        ).run()

        assertFalse(drained)
        assertEquals("OpenClFenceException", rec.fields["gpu_cleanup_fence"])
        assertEquals("service_lost", rec.fields["gpu_cleanup_fence_stage"])
        assertEquals(false, rec.fields["cleanup_drain_confirmed"])
    }

    @Test
    fun nativeCancelFailureStaysUnconfirmedWithTheGateHeld() = runBlocking {
        val rec = Recorder()
        val drained = ComparisonDrain(
            cancelNative = { throw IllegalStateException("cancel failed") },
            closeCpu = {},
            gpuWorkUsed = { true },
            gpuFence = { ComparisonFence.ACKED },
            releaseLeases = {},
            record = rec::record,
        ).run()

        assertFalse("a failed production cancel is not a confirmed drain", drained)
        assertEquals("IllegalStateException", rec.fields["native_cancel_error"])
        assertEquals(true, rec.fields["cleanup_lease_release_failure_is_evidence_only"])
    }

    @Test
    fun quiescenceAckConfirmsEvenIfTheIdleLeaseUnbindFails() = runBlocking {
        // v43: the GPU fence is an explicit serial-worker quiescence ack, not
        // a model PREPARE. Once the worker has acknowledged that every prior
        // native task returned, an idle-lease unbind failure is evidence only:
        // an idle binding is not pending native inference.
        val rec = Recorder()
        val drained = drain(
            rec,
            gpuUsed = true,
            gpuFence = { "native_quiesced" },
            releaseLeases = { throw jobCancellation() },
        ).run()

        assertTrue("the explicit worker ack is the proof; the lease hop is not", drained)
        assertEquals("native_quiesced", rec.fields["gpu_cleanup_fence"])
        assertEquals("JobCancellationException", rec.fields["lease_release"])
        assertEquals(false, rec.fields["cleanup_lease_release_failure_is_fatal"])
        assertEquals(true, rec.fields["cleanup_lease_release_failure_is_evidence_only"])
        assertEquals(true, rec.fields["cleanup_drain_confirmed"])
    }

    @Test
    fun cpuCloseFailureStaysUnconfirmed() = runBlocking {
        val rec = Recorder()
        val drained = drain(
            rec,
            gpuUsed = false,
            closeCpu = { throw IllegalStateException("stock close hung") },
        ).run()

        assertFalse(drained)
        assertEquals("IllegalStateException", rec.fields["cpu_cleanup_error"])
    }
}
