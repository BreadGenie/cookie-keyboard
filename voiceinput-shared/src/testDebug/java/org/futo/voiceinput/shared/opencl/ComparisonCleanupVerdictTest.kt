package org.futo.voiceinput.shared.opencl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * v42 regression for the user's JSON invariant:
 *
 * if no native work was ever started, a preflight failure must end with
 * `cleanup_state=drained`, `cleanup_detail=null`, and exactly one gate
 * release. And a run that did start native work may never report a drain that
 * recorded `true` as `unconfirmed` merely because its return value was lost.
 *
 * These tests drive the real [ComparisonCleanup] with production-shaped report
 * sources: nested [NonCancellable] bodies, a real dispatcher hop, and a real
 * cancelled-Job exception caught where the production drain catches it.
 */
class ComparisonCleanupVerdictTest {
    private class NoNativeReport : ComparisonReportSource {
        val closeCalls = AtomicInteger()
        val skipRecords = AtomicInteger()
        val notes: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun snapshot(outcome: String, reportState: String) =
            """{"schema":1,"report_state":"$reportState","outcome":"$outcome",""" +
                """"cleanup_state":"${notes.lastOrNull() ?: "not_started"}",""" +
                """"cleanup_detail":null,"cleanup_skipped_no_native_work":true}"""

        override fun noteCleanup(state: String, detail: String?) {
            notes.add(if (detail == null) state else "$state:$detail")
        }

        override suspend fun close(): Boolean {
            closeCalls.incrementAndGet()
            throw AssertionError("a run with no native work must never enter close()")
        }

        override val requiresNativeDrain: Boolean get() = false

        override fun recordNoNativeWorkCleanup() {
            skipRecords.incrementAndGet()
        }
    }

    private class ProductionShapedReport(
        private val loseVerdictAtBoundary: Boolean = false,
        private val throwBeforeVerdict: Boolean = false,
    ) : ComparisonReportSource {
        val closeCalls = AtomicInteger()
        val caughtHop = AtomicReference<String?>(null)
        @Volatile var verdict: Boolean? = null
        @Volatile var note = "not_started"

        override fun snapshot(outcome: String, reportState: String) =
            """{"schema":1,"report_state":"$reportState","outcome":"$outcome",""" +
                """"cleanup_state":"${note.take(80)}","cleanup_detail":null}"""

        override fun noteCleanup(state: String, detail: String?) {
            note = if (detail == null) state else "$state:$detail"
        }

        override fun lastDrainVerdict(): Boolean? = verdict

        override suspend fun close(): Boolean = withContext(NonCancellable) {
            closeCalls.incrementAndGet()
            // Exactly the production shape: the only dispatched hop is the
            // lease-release hop, still under NonCancellable, and a real
            // cancelled-Job exception is caught there (recorded, not fatal,
            // when the GPU arm never ran).
            withContext(NonCancellable + Dispatchers.Default) {
                caughtHop.set(caughtCancellationLabel())
            }
            if (throwBeforeVerdict) throw IllegalStateException("before verdict")
            verdict = true
            if (loseVerdictAtBoundary) throw IllegalStateException("lost at boundary")
            true
        }
    }

    private class Harness(report: ComparisonReportSource, uiWaitMs: Long = 2_000) {
        val releases = AtomicInteger()
        val saves: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val coordinator = ComparisonCleanup(
            scope = CoroutineScope(Job() + Dispatchers.Default),
            writer = ComparisonReportWriter(512 * 1024) { json -> saves.add(json) },
            onConfirmedDrain = { releases.incrementAndGet() },
        )
        val job = coordinator.start(report, "error_IllegalStateException", uiWaitMs)
    }

    private suspend fun awaitPhase(coordinator: ComparisonCleanup, phase: CleanupPhase, timeoutMs: Long = 5_000) =
        withTimeout(timeoutMs) { while (coordinator.state.phase != phase) delay(5) }

    @Test
    fun preflightWithNoNativeWorkSkipsCloseEntirelyAndReleasesTheGateOnce() = runBlocking {
        val report = NoNativeReport()
        val h = Harness(report)

        awaitPhase(h.coordinator, CleanupPhase.Drained)

        assertEquals("close() must not run when the run owns no native request", 0, report.closeCalls.get())
        assertEquals("the explicit skip record is written once", 1, report.skipRecords.get())
        assertEquals(1, h.releases.get())
        assertEquals(listOf("draining", "drained"), report.notes)
        assertNull(h.coordinator.state.note)
        val final = h.saves.last()
        assertTrue(final.contains("\"report_state\":\"final\""))
        assertTrue(final.contains("\"cleanup_state\":\"drained\""))
        assertFalse(final.contains("unconfirmed"))
        assertFalse(final.contains("JobCancellationException"))
        assertTrue(ComparisonUi.canStart(h.coordinator.state, markerBlocked = false))
    }

    @Test
    fun productionShapedCloseCaughtHopCancellationStillReportsDrained() = runBlocking {
        val report = ProductionShapedReport()
        val h = Harness(report)

        awaitPhase(h.coordinator, CleanupPhase.Drained)

        assertEquals("JobCancellationException", report.caughtHop.get())
        assertEquals(1, report.closeCalls.get())
        assertEquals(1, h.releases.get())
        assertEquals("drained", report.note)
        assertNull(h.coordinator.state.note)
        assertTrue(h.saves.last().contains("\"cleanup_state\":\"drained\""))
        assertFalse(h.saves.last().contains("unconfirmed"))
    }

    @Test
    fun aVerdictRecordedBeforeALostReturnStillSurfacesAsDrained() = runBlocking {
        val report = ProductionShapedReport(loseVerdictAtBoundary = true)
        val h = Harness(report)

        awaitPhase(h.coordinator, CleanupPhase.Drained)

        assertEquals(1, report.closeCalls.get())
        assertEquals(1, h.releases.get())
        assertEquals("drained", report.note)
        assertNull("a recorded drain must not carry a stale exception detail", h.coordinator.state.note)
        assertFalse(h.saves.last().contains("unconfirmed"))
    }

    @Test
    fun exceptionBeforeTheVerdictStaysUnconfirmedWithTheGateHeld() = runBlocking {
        val report = ProductionShapedReport(throwBeforeVerdict = true)
        val h = Harness(report)

        awaitPhase(h.coordinator, CleanupPhase.Unconfirmed)

        assertEquals(1, report.closeCalls.get())
        assertEquals("no drain verdict was recorded, so the gate must stay held", 0, h.releases.get())
        assertEquals("unconfirmed:IllegalStateException", report.note)
        assertFalse(ComparisonUi.canStart(h.coordinator.state, markerBlocked = false))
        assertTrue(h.saves.last().contains("\"report_state\":\"final\""))
    }
}

/** A genuine JobCancellationException from a real cancelled Job. */
private suspend fun caughtCancellationLabel(): String {
    val parent = Job()
    val pending = CoroutineScope(parent).async { awaitCancellation() }
    parent.cancel()
    return try {
        pending.await()
        "none"
    } catch (e: Exception) {
        e.javaClass.simpleName
    }
}
