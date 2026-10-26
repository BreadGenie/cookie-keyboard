package org.futo.voiceinput.shared.opencl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only cleanup ownership for the comparison harness.
 *
 * A comparison holds [VoiceWorkGate] from before model resolution until the
 * production CPU/OpenCL cleanup has actually returned. That cleanup can wait
 * behind blocking native work, so it must never be cancelled, timed out, or
 * released by the Activity merely because the UI stops waiting. This class
 * therefore owns the drain in a process-lifetime [CoroutineScope], saves a
 * partial report before starting the drain, and reports an explicit
 * "unconfirmed" terminal state instead of leaving a spinner.
 *
 * The bounded wait is a *display* bound only. It never cancels [close] and
 * never releases the gate; a late successful drain still transitions to
 * [CleanupPhase.Drained] and releases the gate exactly once.
 */
class ComparisonCleanup(
    private val scope: CoroutineScope,
    private val writer: ComparisonReportWriter,
    private val onConfirmedDrain: () -> Unit,
    initialObserver: (CleanupState) -> Unit = {},
) {
    private val lock = Any()
    private var drainJob: Job? = null
    private var timeoutJob: Job? = null

    @Volatile
    private var observer: (CleanupState) -> Unit = initialObserver

    @Volatile
    var state: CleanupState = CleanupState(CleanupPhase.Idle)
        private set

    /** Re-attach the current screen after an Activity recreation. */
    fun observe(callback: (CleanupState) -> Unit) {
        observer = callback
        callback(state)
    }

    /**
     * Starts exactly once. The returned job is the owned drain and is never
     * cancelled by Activity teardown. [uiWaitMs] only bounds the displayed
     * "draining" phase; it does not bound native work.
     */
    fun start(report: ComparisonReportSource, outcome: String, uiWaitMs: Long): Job {
        synchronized(lock) {
            drainJob?.let { return it }
            transition(CleanupPhase.SavingPartial)
            val job = scope.launch {
                // v41: the owned drain must finish even if this Job is
                // cancelled by a caller (Activity teardown, a parent scope, or
                // a future bug). Without this, a cancellation that lands while
                // close() is suspended aborts the final report, the terminal
                // state and the one-time gate release — the phone's
                // JobCancellationException/unconfirmed symptom. Native work is
                // never preempted here; only the bookkeeping is made
                // uncancellable.
                withContext(NonCancellable) {
                    // Persist what is already known BEFORE touching native cleanup.
                    val partial = snapshotOrFallback(report, outcome, "pre_drain", null)
                    writer.offer(partial)
                    report.safeNote("draining", null)
                    transition(CleanupPhase.Draining)

                    var drained = false
                    var detail: String? = null
                    // v42: this is an explicit resource ledger. A preflight
                    // failure that never reached a CPU obtain or a GPU prepare
                    // owns no native request, so there is nothing to cancel,
                    // close, fence or unbind. Skipping the entire close path
                    // also avoids the main-thread dispatch boundary that the
                    // v41 phone may have observed as a lost verdict.
                    val needsNative = runCatching { report.requiresNativeDrain }.getOrDefault(true)
                    if (!needsNative) {
                        drained = true
                        runCatching { report.recordNoNativeWorkCleanup() }
                    } else {
                        try {
                            drained = report.close()
                        } catch (t: Throwable) {
                            detail = t.javaClass.simpleName
                            // A drain that already recorded its verdict must
                            // not surface as unconfirmed merely because the
                            // return value was lost at a suspension boundary.
                            if (runCatching { report.lastDrainVerdict() }.getOrNull() == true) {
                                drained = true
                                detail = null
                            }
                        }
                    }
                    report.safeNote(if (drained) "drained" else "unconfirmed", detail)

                    // Terminal JSON is written even when CPU/GPU cleanup threw, and
                    // before the gate is released, so a released run has a durable
                    // terminal/partial report.
                    val finalJson = snapshotOrFallback(report, outcome, "final", detail)
                    writer.offer(finalJson)

                    var confirmedError: String? = null
                    if (drained) {
                        try {
                            onConfirmedDrain()
                        } catch (t: Throwable) {
                            confirmedError = t.javaClass.simpleName
                        }
                    }

                    timeoutJob?.cancel()
                    transition(
                        if (drained) CleanupPhase.Drained else CleanupPhase.Unconfirmed,
                        detail ?: confirmedError,
                    )
                }
            }
            drainJob = job
            timeoutJob = scope.launch {
                delay(uiWaitMs)
                var notify = false
                synchronized(lock) {
                    val current = state
                    if (current.phase == CleanupPhase.SavingPartial || current.phase == CleanupPhase.Draining) {
                        state = CleanupState(CleanupPhase.Unconfirmed, "ui_wait_timeout", uiWaitMs)
                        notify = true
                    }
                }
                if (notify) notifyObserver()
            }
            if (job.isCompleted) timeoutJob?.cancel()
            return job
        }
    }

    private fun transition(phase: CleanupPhase, note: String? = null, waitedMs: Long? = null) {
        state = CleanupState(phase, note, waitedMs)
        notifyObserver()
    }

    private fun notifyObserver() {
        runCatching { observer(state) }
    }

    private fun snapshotOrFallback(
        report: ComparisonReportSource,
        outcome: String,
        reportState: String,
        detail: String?,
    ): String = try {
        report.snapshot(outcome, reportState)
    } catch (t: Throwable) {
        val error = t.javaClass.simpleName
        """{"schema":1,"report_state":"$reportState","outcome":"${clean(outcome)}",""" +
            """"snapshot_error":"$error","cleanup_detail":"${clean(detail)}"}"""
    }

    private fun ComparisonReportSource.safeNote(state: String, detail: String?) {
        runCatching { noteCleanup(state, detail) }
    }

    private fun clean(value: String?): String =
        value.orEmpty().replace("\\", "\\\\").replace("\"", "'").take(200)
}

/** Process-lifetime owner for at most one comparison cleanup/drain. */
object ComparisonCleanupHost {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var active: ComparisonCleanup? = null
}

/** Exact harness status strings, kept pure so the bounded terminal state is testable. */
object ComparisonUi {
    const val SAVING = "Saving partial report before cleanup…"
    const val DRAINING = "Draining native work and saving report… Cleanup continues even if you leave this screen."
    const val DRAINED = "Cleanup confirmed. Report saved. A new comparison can start."
    const val ACTIVITY_STOPPED =
        "Comparison cancelled because you left the screen (activity_stopped). " +
            "Switching apps cancels the comparison; keep this screen open for the full run."

    fun unconfirmed(state: CleanupState): String = when (state.note) {
        "ui_wait_timeout" ->
            "Cleanup did not confirm within ${(state.waitedMs ?: 0L) / 1000}s. Native state is UNKNOWN; " +
                "cleanup continues in the background. Start stays disabled. " +
                "Force-stop the app to kill both processes, then reopen."
        else ->
            "Cleanup could not be confirmed${state.note?.let { " ($it)" } ?: ""}. Native state is UNKNOWN; " +
                "Start stays disabled. Force-stop the app to kill both processes, then reopen."
    }

    /**
     * Terminal text for the gap before the coordinator can start: a blocking
     * native inference or per-visit cleanup may never return after a
     * cancel/switch/deadline, so the Activity cannot enter its finally block.
     * This is not a cleanup claim; the native state is explicitly unknown.
     */
    fun cancelUnconfirmed(waitedMs: Long): String =
        "Cancellation requested ${waitedMs / 1000}s ago, but native work has not returned. " +
            "Cleanup has NOT started; native state is UNKNOWN. Start stays disabled. " +
            "Force-stop the app to kill both processes, then reopen."

    /**
     * True while a run still owns work. Completion, not activity, is the
     * question: [Job.cancel] makes a job inactive immediately, but a blocking
     * native inference or per-visit cleanup can keep it incomplete long after.
     * Gating overlap or recovery on [Job.isActive] would therefore miss a
     * cancelled-but-unfinished run.
     */
    fun runUnfinished(job: Job?): Boolean = job != null && !job.isCompleted

    /**
     * Decision for the independent cancellation-gap watchdog. Once the real
     * cleanup coordinator has started, its own bounded state wins; otherwise
     * the watchdog is due while the cancelled run has not completed, even
     * though cancellation already cleared [Job.isActive].
     */
    fun cancelWatchdogDue(job: Job?, coordinatorStarted: Boolean): Boolean =
        !coordinatorStarted && runUnfinished(job)

    fun status(state: CleanupState): String = when (state.phase) {
        CleanupPhase.Idle -> "Waiting to start."
        CleanupPhase.SavingPartial -> SAVING
        CleanupPhase.Draining -> DRAINING
        CleanupPhase.Drained -> DRAINED
        CleanupPhase.Unconfirmed -> unconfirmed(state)
    }

    /** A run may start only after a confirmed drain (or before any run in this process). */
    fun canStart(state: CleanupState, markerBlocked: Boolean): Boolean =
        !markerBlocked && (state.phase == CleanupPhase.Idle || state.phase == CleanupPhase.Drained)

    fun canCopy(hasReport: Boolean): Boolean = hasReport
}

/** Bounded, atomic-save wrapper; a rejected/failed save never destroys the last copyable report. */
class ComparisonReportWriter(
    private val maxBytes: Int = 512 * 1024,
    private val write: (String) -> Unit,
) {
    @Volatile
    var lastSaved: String? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun offer(json: String): Boolean {
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) {
            lastError = "report_too_large:${bytes.size}"
            return false
        }
        return try {
            write(json)
            lastSaved = json
            lastError = null
            true
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName
            false
        }
    }
}
