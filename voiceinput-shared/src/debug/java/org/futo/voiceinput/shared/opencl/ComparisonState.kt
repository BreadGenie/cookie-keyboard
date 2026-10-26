package org.futo.voiceinput.shared.opencl

/** Cleanup phases shown by the debug comparison Activity. */
enum class CleanupPhase { Idle, SavingPartial, Draining, Drained, Unconfirmed }

/**
 * [note] is an explicit reason when [phase] is [CleanupPhase.Unconfirmed];
 * "ui_wait_timeout" means the bounded UI wait expired while the owned drain
 * was still running. That is not a claim that native work stopped.
 */
data class CleanupState(
    val phase: CleanupPhase,
    val note: String? = null,
    val waitedMs: Long? = null,
)

/**
 * The real report owner (PhoneComparison) implements this. [snapshot] must be
 * bounded and must not require a completed drain; [close] is the production
 * CPU/OpenCL cleanup fence and may block for as long as native work runs.
 *
 * v42: [requiresNativeDrain] is an explicit resource ledger, not a guess. A
 * preflight failure that reached neither a CPU model obtain nor a GPU prepare
 * owns no native request at all, so the coordinator must not invoke [close]
 * (no cancel, no manager cleanup, no main-thread lease hop) and must not report
 * a drain perversely as unconfirmed. The default stays `true` so every other
 * implementation keeps the conservative production path.
 */
interface ComparisonReportSource {
    fun snapshot(outcome: String, reportState: String): String
    fun noteCleanup(state: String, detail: String? = null)
    suspend fun close(): Boolean

    /** True once this run actually prepared/obtained native work. */
    val requiresNativeDrain: Boolean get() = true

    /**
     * Verdict already recorded by the drain during the current [close].
     * Null before that point. Used only when the return value is lost at the
     * suspension boundary, so a recorded `true` can never surface as
     * `cleanup_state=unconfirmed`.
     */
    fun lastDrainVerdict(): Boolean? = null

    /** Records the confirmed no-native-work cleanup without any dispatch hop. */
    fun recordNoNativeWorkCleanup() {}
}
