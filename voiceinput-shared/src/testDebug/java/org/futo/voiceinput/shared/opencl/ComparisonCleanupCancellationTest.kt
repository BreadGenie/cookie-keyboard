package org.futo.voiceinput.shared.opencl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The v40 phone report showed `cleanup_detail=JobCancellationException` and an
 * unconfirmed drain after a run that never started native work. Whatever
 * cancelled the caller, the owned drain must still finish: final report
 * written, terminal state explicit, voice gate released exactly once. These
 * tests drive the real [ComparisonCleanup] with a report source whose
 * `close()` suspends on a cancellable handoff (as the production
 * main-thread lease hop does). Without the coordinator's NonCancellable
 * ownership the cancellation lands inside `close()` and the drain is reported
 * unconfirmed.
 */
class ComparisonCleanupCancellationTest {
    private class SuspendingReport(
        private val entered: CompletableDeferred<Unit>,
        private val gate: CompletableDeferred<Unit>,
        private val fail: Throwable? = null,
    ) : ComparisonReportSource {
        val closeCount = AtomicInteger()
        val notes: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun snapshot(outcome: String, reportState: String) =
            """{"schema":1,"outcome":"$outcome","report_state":"$reportState"}"""

        override fun noteCleanup(state: String, detail: String?) {
            notes.add(if (detail == null) state else "$state:$detail")
        }

        override suspend fun close(): Boolean {
            closeCount.incrementAndGet()
            entered.complete(Unit)
            gate.await()
            fail?.let { throw it }
            return true
        }
    }

    private class Harness(
        report: ComparisonReportSource,
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) {
        val releases = AtomicInteger()
        val saves: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val coordinator = ComparisonCleanup(
            scope = scope,
            writer = ComparisonReportWriter(512 * 1024) { json -> saves.add(json) },
            onConfirmedDrain = { releases.incrementAndGet() },
        )
        val job: Job = coordinator.start(report, "error_IllegalStateException", uiWaitMs = 400)
    }

    private suspend fun awaitPhase(coordinator: ComparisonCleanup, phase: CleanupPhase, timeoutMs: Long = 5_000) =
        withTimeout(timeoutMs) { while (coordinator.state.phase != phase) delay(5) }

    @Test
    fun cancelledDrainJobStillWritesTerminalReportAndReleasesTheGateOnce() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val report = SuspendingReport(entered, gate)
        val h = Harness(report)

        withTimeout(5_000) { entered.await() }
        // The v40 phone symptom: cancellation arrives while cleanup is in the
        // main-thread handoff. It must not abort the owned drain.
        h.job.cancel()
        gate.complete(Unit)
        awaitPhase(h.coordinator, CleanupPhase.Drained)

        assertEquals(1, h.releases.get())
        assertEquals(1, report.closeCount.get())
        assertNull(h.coordinator.state.note)
        assertTrue(h.saves.last().contains("\"report_state\":\"final\""))
        assertTrue(h.saves.last().contains("error_IllegalStateException"))
        assertTrue(ComparisonUi.canStart(h.coordinator.state, markerBlocked = false))
        assertTrue(report.notes.contains("drained"))
    }

    @Test
    fun cancelledParentScopeCannotTurnACompletedDrainIntoUnknown() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val report = SuspendingReport(entered, gate)
        val h = Harness(report)

        withTimeout(5_000) { entered.await() }
        h.scope.coroutineContext[Job]!!.cancelChildren()
        gate.complete(Unit)
        awaitPhase(h.coordinator, CleanupPhase.Drained)

        assertEquals(1, h.releases.get())
        assertNull(h.coordinator.state.note)
        assertNotEquals(CleanupPhase.Unconfirmed, h.coordinator.state.phase)
    }

    @Test
    fun realNativeOngoingStaysUnknownWithTheGateHeld() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val report = SuspendingReport(entered, gate)
        val h = Harness(report)

        withTimeout(5_000) { entered.await() }
        awaitPhase(h.coordinator, CleanupPhase.Unconfirmed, timeoutMs = 3_000)

        assertEquals("ui_wait_timeout", h.coordinator.state.note)
        assertEquals(0, h.releases.get())
        assertFalse(ComparisonUi.canStart(h.coordinator.state, markerBlocked = true))

        // A late successful drain still releases the gate exactly once.
        gate.complete(Unit)
        awaitPhase(h.coordinator, CleanupPhase.Drained)
        assertEquals(1, h.releases.get())
    }

    @Test
    fun cancellingAfterADrainedRunReleasesAndSavesNothingAgain() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        val report = SuspendingReport(entered, gate)
        val h = Harness(report)

        awaitPhase(h.coordinator, CleanupPhase.Drained)
        h.job.cancel()
        delay(50)

        assertEquals(1, h.releases.get())
        assertEquals(1, h.saves.count { it.contains("\"report_state\":\"final\"") })
        assertEquals(CleanupPhase.Drained, h.coordinator.state.phase)
    }
}
