package org.futo.voiceinput.shared.opencl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for the v40 review blocker: the Activity's cancellation-gap
 * watchdog gated on `work.isActive`. `stop()` calls `work.cancel()`, which
 * clears `isActive` immediately, while blocking native inference/cleanup can
 * keep the job incomplete. That made the supposedly independent watchdog skip
 * the exact gap it was added for. The decision now uses completion and is
 * exercised here with a real coroutine Job, not only a source grep.
 */
class ComparisonCancelWatchdogTest {
    private fun awaitUntil(timeoutMs: Long = 5_000, predicate: () -> Boolean) = runBlocking {
        withTimeout(timeoutMs) { while (!predicate()) delay(5) }
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun cancelledButIncompleteRunIsWatchedEvenThoughItIsNotActive() = runBlocking {
        val scope = scope()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = scope.launch {
            // Models a blocking native call that ignores cooperative
            // cancellation until it returns: the run cannot reach its
            // cleanup handoff, so only completeness changes.
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        job.cancel()
        awaitUntil { !job.isActive }

        assertFalse("cancellation already cleared isActive", job.isActive)
        assertFalse("blocking native work has not returned", job.isCompleted)
        assertTrue("an unfinished run still owns work", ComparisonUi.runUnfinished(job))
        assertTrue(
            "the old isActive gate skipped exactly this cancelled-but-incomplete state",
            ComparisonUi.cancelWatchdogDue(job, coordinatorStarted = false),
        )
        assertFalse(
            "once the real coordinator starts, its bounded state wins",
            ComparisonUi.cancelWatchdogDue(job, coordinatorStarted = true),
        )

        release.complete(Unit)
        job.join()
        assertTrue(job.isCompleted)
        assertFalse(ComparisonUi.runUnfinished(job))
        assertFalse(ComparisonUi.cancelWatchdogDue(job, coordinatorStarted = false))
        scope.cancel()
    }

    @Test
    fun completedAndAbsentRunsAreNotWatched() = runBlocking {
        assertFalse(ComparisonUi.runUnfinished(null))
        assertFalse(ComparisonUi.cancelWatchdogDue(null, coordinatorStarted = false))

        val scope = scope()
        val completed = scope.launch { delay(1) }
        completed.join()
        assertTrue(completed.isCompleted)
        assertFalse(ComparisonUi.runUnfinished(completed))
        assertFalse(ComparisonUi.cancelWatchdogDue(completed, coordinatorStarted = false))

        val cancelledAndDone = scope.launch { withContext(NonCancellable) { delay(1) } }
        cancelledAndDone.cancel()
        cancelledAndDone.join()
        assertTrue(cancelledAndDone.isCompleted)
        assertFalse(ComparisonUi.cancelWatchdogDue(cancelledAndDone, coordinatorStarted = false))
        scope.cancel()
    }

    @Test
    fun activityCallsiteUsesTheCompletionPredicate() {
        val activity = File("../src/debug/java/org/futo/inputmethod/latin/SttComparisonActivity.kt").readText()
        val start = activity.indexOf("private val cancelWatchdog")
        val end = activity.indexOf("private val reportFile", start)
        assertTrue("cancelWatchdog block must exist", start >= 0 && end > start)
        val watchdog = activity.substring(start, end)
        assertTrue(
            "watchdog must call the tested completion predicate",
            watchdog.contains("ComparisonUi.cancelWatchdogDue(work,"),
        )
        assertFalse(
            "watchdog must not gate on the job's activity",
            watchdog.contains("work?.isActive") || watchdog.contains("work.isActive"),
        )
        assertTrue(
            "overlap/start guards must treat cancelled-but-unfinished as in progress",
            activity.contains("ComparisonUi.runUnfinished(work)"),
        )
    }
}
