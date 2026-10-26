package org.futo.voiceinput.shared.opencl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Drives the real [ComparisonCleanup] that SttComparisonActivity uses.
 * The only substituted boundary is the production close/snapshot pair
 * (PhoneComparison), which cannot run outside Android; ordering, gate
 * ownership, bounded terminal state and report persistence are the real
 * coordinator's behavior.
 */
class ComparisonCleanupTest {
    private class FakeReport(
        private val events: MutableList<String>,
        private val closed: CompletableDeferred<Unit>? = null,
        private val failClose: Throwable? = null,
        private val failSnapshots: Boolean = false,
    ) : ComparisonReportSource {
        var closeCount = 0
            private set
        val notes: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun snapshot(outcome: String, reportState: String): String {
            if (failSnapshots) throw IllegalStateException("snapshot exploded")
            return """{"outcome":"$outcome","report_state":"$reportState"}"""
        }

        override fun noteCleanup(state: String, detail: String?) {
            notes.add(if (detail == null) state else "$state:$detail")
        }

        override suspend fun close(): Boolean {
            closeCount++
            events.add("close")
            closed?.await()
            failClose?.let { throw it }
            return true
        }
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, predicate: () -> Boolean) = runBlocking {
        withTimeout(timeoutMs) { while (!predicate()) delay(5) }
    }

    private fun cleanup(
        report: FakeReport,
        releases: MutableList<String>,
        writerEvents: MutableList<String>,
        uiWaitMs: Long,
        outcome: String = "completed",
    ): Pair<ComparisonCleanup, Job> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val writer = ComparisonReportWriter(512 * 1024) { json ->
            writerEvents.add("save:$json")
        }
        val coordinator = ComparisonCleanup(
            scope = scope,
            writer = writer,
            onConfirmedDrain = { releases.add("release") },
        )
        val job = coordinator.start(report, outcome, uiWaitMs)
        return coordinator to job
    }

    @Test
    fun partialReportIsSavedBeforeDrainStartsAndFinalAfter() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val gate = CompletableDeferred<Unit>()
        val report = object : ComparisonReportSource {
            var closeCount = 0
                private set
            override fun snapshot(outcome: String, reportState: String) =
                """{"outcome":"$outcome","report_state":"$reportState"}"""
            override fun noteCleanup(state: String, detail: String?) {
                order.add("note:$state")
            }
            override suspend fun close(): Boolean {
                closeCount++
                order.add("close")
                gate.await()
                return true
            }
        }
        val releases = Collections.synchronizedList(mutableListOf<String>())
        val writer = ComparisonReportWriter(512 * 1024) { json ->
            order.add(if (json.contains("\"pre_drain\"")) "save:pre_drain" else "save:final")
        }
        val coordinator = ComparisonCleanup(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            writer = writer,
            onConfirmedDrain = { releases.add("release") },
        )
        coordinator.start(report, "completed", uiWaitMs = 2_000)

        awaitUntil { order.contains("save:pre_drain") && order.contains("close") }
        assertTrue(order.indexOf("save:pre_drain") < order.indexOf("close"))
        assertEquals(1, order.count { it == "save:pre_drain" })

        gate.complete(Unit)
        awaitUntil { coordinator.state.phase == CleanupPhase.Drained }
        assertEquals(1, releases.size)
        assertEquals(listOf("release"), releases)
        assertTrue(writer.lastSaved!!.contains("\"report_state\":\"final\""))
    }

    @Test
    fun blockingDrainReachesBoundedTerminalWithoutReleasingGate() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val gate = CompletableDeferred<Unit>()
        val report = FakeReport(events, closed = gate)
        val releases = Collections.synchronizedList(mutableListOf<String>())
        val saves = Collections.synchronizedList(mutableListOf<String>())
        val (coordinator, _) = cleanup(report, releases, saves, uiWaitMs = 80)

        awaitUntil { coordinator.state.phase == CleanupPhase.Unconfirmed }
        assertEquals("ui_wait_timeout", coordinator.state.note)
        assertEquals(80L, coordinator.state.waitedMs)
        assertEquals("native work is still running; gate must stay held", 0, releases.size)
        val text = ComparisonUi.status(coordinator.state)
        assertTrue(text.contains("did not confirm"))
        assertTrue(text.contains("UNKNOWN"))
        assertTrue(text.contains("Start stays disabled"))
        assertTrue(text.contains("Force-stop"))
        assertFalse(ComparisonUi.canStart(coordinator.state, markerBlocked = false))
        assertTrue(ComparisonUi.canCopy(saves.isNotEmpty()))
        assertTrue("partial must already be copyable", saves.any { it.contains("\"report_state\":\"pre_drain\"") })

        gate.complete(Unit)
        awaitUntil { coordinator.state.phase == CleanupPhase.Drained }
        assertEquals(1, releases.size)
        assertTrue(ComparisonUi.canStart(coordinator.state, markerBlocked = false))
        assertTrue(saves.last().contains("\"report_state\":\"final\""))
    }

    @Test
    fun deadlineWithBlockedDrainStillSavesPartialBeforeDrain() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val gate = CompletableDeferred<Unit>()
        val report = FakeReport(events, closed = gate)
        val releases = Collections.synchronizedList(mutableListOf<String>())
        val saves = Collections.synchronizedList(mutableListOf<String>())
        val (coordinator, _) = cleanup(report, releases, saves, uiWaitMs = 80, outcome = "deadline_5min")

        awaitUntil { coordinator.state.phase == CleanupPhase.Unconfirmed }
        assertTrue("pre-drain partial must exist even when the fence never returns",
            saves.any { it.contains("\"report_state\":\"pre_drain\"") && it.contains("deadline_5min") })
        assertEquals(0, releases.size)
        assertFalse(ComparisonUi.canStart(coordinator.state, markerBlocked = true))

        gate.complete(Unit)
        awaitUntil { coordinator.state.phase == CleanupPhase.Drained }
        assertEquals(1, releases.size)
    }

    @Test
    fun cancelledActivityWaitingDoesNotCancelOwnedDrain() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val gate = CompletableDeferred<Unit>()
        val report = FakeReport(events, closed = gate)
        val releases = Collections.synchronizedList(mutableListOf<String>())
        val saves = Collections.synchronizedList(mutableListOf<String>())
        val (coordinator, job) = cleanup(report, releases, saves, uiWaitMs = 2_000)

        val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        activityScope.launch { job.join() }
        activityScope.cancel()

        gate.complete(Unit)
        awaitUntil { coordinator.state.phase == CleanupPhase.Drained }
        assertEquals(1, report.closeCount)
        assertEquals(1, releases.size)
    }

    @Test
    fun closeExceptionStillSavesTerminalReportAndRetainsGate() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val report = FakeReport(events, failClose = IllegalStateException("driver wedged"))
        val releases = Collections.synchronizedList(mutableListOf<String>())
        val saves = Collections.synchronizedList(mutableListOf<String>())
        val (coordinator, _) = cleanup(report, releases, saves, uiWaitMs = 500)

        awaitUntil { coordinator.state.phase == CleanupPhase.Unconfirmed }
        assertEquals(0, releases.size)
        assertFalse(ComparisonUi.canStart(coordinator.state, markerBlocked = false))
        assertTrue(saves.last().contains("\"report_state\":\"final\""))
        assertTrue(report.notes.contains("draining"))
        assertTrue(report.notes.any { it.startsWith("unconfirmed") })
    }

    @Test
    fun snapshotFailureStillWritesMinimalCopyableTerminalReport() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val report = FakeReport(events, failSnapshots = true)
        val releases = Collections.synchronizedList(mutableListOf<String>())
        val saves = Collections.synchronizedList(mutableListOf<String>())
        val (coordinator, _) = cleanup(report, releases, saves, uiWaitMs = 500)

        awaitUntil { coordinator.state.phase == CleanupPhase.Drained }
        val fallback = saves.last()
        assertTrue(fallback.contains("\"outcome\""))
        assertTrue(fallback.contains("\"snapshot_error\""))
        assertEquals(1, releases.size)
    }

    @Test
    fun boundedWriterNeverLosesLastGoodReport() {
        var stored: String? = null
        val writer = ComparisonReportWriter(maxBytes = 16) { stored = it }
        assertTrue(writer.offer("{\"a\":1}"))
        val good = writer.lastSaved
        assertFalse(writer.offer("{\"a\":\"" + "x".repeat(40) + "\"}"))
        assertEquals(good, writer.lastSaved)
        assertTrue(writer.lastError!!.startsWith("report_too_large"))

        val throwing = ComparisonReportWriter(maxBytes = 1024) { throw java.io.IOException("disk") }
        assertFalse(throwing.offer("{\"a\":1}"))
        assertEquals("IOException", throwing.lastError)
    }

    @Test
    fun doubleStartIsIdempotent() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val gate = CompletableDeferred<Unit>()
        val report = FakeReport(events, closed = gate)
        val releases = Collections.synchronizedList(mutableListOf<String>())
        val saves = Collections.synchronizedList(mutableListOf<String>())
        val (coordinator, job) = cleanup(report, releases, saves, uiWaitMs = 500)
        val second = coordinator.start(report, "completed", 500)
        assertSame(job, second)
        gate.complete(Unit)
        awaitUntil { coordinator.state.phase == CleanupPhase.Drained }
        assertEquals(1, report.closeCount)
        assertEquals(1, releases.size)
    }

    @Test
    fun terminalStatusIsExplicitAndNeverTheDrainingSpinner() {
        assertEquals(ComparisonUi.DRAINING, ComparisonUi.status(CleanupState(CleanupPhase.Draining)))
        assertTrue(ComparisonUi.status(CleanupState(CleanupPhase.Drained)).contains("Cleanup confirmed"))
        val unconfirmed = ComparisonUi.status(CleanupState(CleanupPhase.Unconfirmed, "NativeDeadlock"))
        assertTrue(unconfirmed.contains("NativeDeadlock"))
        assertTrue(unconfirmed.contains("UNKNOWN"))
        assertTrue(unconfirmed.contains("Force-stop"))
        assertFalse(unconfirmed.contains("Draining native work"))
        val cancelGap = ComparisonUi.cancelUnconfirmed(20_000)
        assertTrue(cancelGap.contains("Cleanup has NOT started"))
        assertTrue(cancelGap.contains("UNKNOWN"))
        assertTrue(cancelGap.contains("Start stays disabled"))
        assertTrue(cancelGap.contains("Force-stop"))
        assertFalse(cancelGap.contains("cleanup continues"))
        assertTrue(ComparisonUi.canStart(CleanupState(CleanupPhase.Idle), markerBlocked = false))
        assertFalse(ComparisonUi.canStart(CleanupState(CleanupPhase.Idle), markerBlocked = true))
        assertFalse(ComparisonUi.canStart(CleanupState(CleanupPhase.SavingPartial), markerBlocked = false))
        assertFalse(ComparisonUi.canStart(CleanupState(CleanupPhase.Unconfirmed), markerBlocked = false))
        assertTrue(ComparisonUi.canStart(CleanupState(CleanupPhase.Drained), markerBlocked = false))
        assertNotNull(ComparisonUi.DRAINED)
    }
}
