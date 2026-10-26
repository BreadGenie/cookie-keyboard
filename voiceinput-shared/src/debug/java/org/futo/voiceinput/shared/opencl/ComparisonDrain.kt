package org.futo.voiceinput.shared.opencl

/**
 * Debug-only cleanup coordinator for the comparison harness (v41-v43).
 *
 * The drain is cancel -> close the CPU context -> GPU quiescence fence -> idle
 * lease release -> verdict. It runs in one place that both the real
 * `PhoneComparison.close()` and the JVM regressions drive. No step releases
 * the voice gate early: the caller only releases it after [run] returns true.
 *
 * v43 GPU fence. v40/v41 reported `gpu_cleanup_fence=JobCancellationException`
 * and `lease_release=JobCancellationException` after a GPU run. The old fence
 * was a production `OpenClClient.prepare(file)`: a full request that
 * re-verified/re-prepared the model through the cancellable request
 * continuation. That is not a quiescence proof, and a boundary cancellation
 * could surface after the worker had already returned. The fence is now the
 * restored v37 serial-worker `DRAIN` ack: no model, no audio, no engine reset,
 * an explicitly NonCancellable wait and a non-cancellable continuation.
 *
 * Verdict rule. `gpu_cleanup_fence=native_quiesced` is the proof that every
 * prior worker task returned. Once that ack exists, an idle-lease unbind
 * failure is evidence only: an idle client binding is not pending native
 * inference. Without the ack the verdict stays UNKNOWN, the gate is held and
 * the report carries a structured `gpu_cleanup_fence_stage`. A cancelled
 * GPU coordinator is never released without the worker ack.
 *
 * A timeout never preempts blocking JNI; this class has no timeout at all.
 */
internal class ComparisonDrain(
    private val cancelNative: () -> Unit,
    private val closeCpu: suspend () -> Unit,
    /** True once this run prepared/decoded through the GPU arms (normal or fast). */
    private val gpuWorkUsed: () -> Boolean,
    /** Returns [ComparisonFence.ACKED] only after the serial worker ack; throws on failure. */
    private val gpuFence: suspend () -> String,
    private val releaseLeases: suspend () -> Unit,
    private val record: (String, Any) -> Unit,
) {
    suspend fun run(): Boolean {
        // A cancel is cooperative and normally cannot throw. If it does, the
        // run stays unconfirmed and the report names the stage instead of
        // letting the exception escape as an unexplained cleanup detail.
        var cancelOk = true
        try {
            cancelNative()
        } catch (e: Exception) {
            cancelOk = false
            record("native_cancel_error", e.javaClass.simpleName)
        }

        var cpuOk = true
        try {
            closeCpu()
        } catch (e: Exception) {
            cpuOk = false
            record("cpu_cleanup_error", e.javaClass.simpleName)
        }

        val gpuUsed = gpuWorkUsed()
        var fenceOk = true
        var fenceAck = false
        if (gpuUsed) {
            try {
                val label = gpuFence()
                fenceAck = label == ComparisonFence.ACKED
                fenceOk = fenceAck
                record("gpu_cleanup_fence", label)
            } catch (e: Exception) {
                fenceOk = false
                record("gpu_cleanup_fence", e.javaClass.simpleName)
                if (e is OpenClFenceException) {
                    record("gpu_cleanup_fence_stage", e.stage)
                }
            }
        }

        try {
            releaseLeases()
            record("lease_release", "ok")
        } catch (e: Exception) {
            // Evidence only when the worker ack proved quiescence. It can
            // leave an idle binding, not pending inference.
            record("lease_release", e.javaClass.simpleName)
        }

        record("cleanup_fence_kind", if (gpuUsed) ComparisonFence.KIND else "not_run_no_gpu")
        record("cleanup_gpu_arm_used", gpuUsed)
        record("cleanup_lease_release_failure_is_fatal", false)
        record("cleanup_lease_release_failure_is_evidence_only", gpuUsed && fenceAck)
        val drained = cancelOk && cpuOk && (!gpuUsed || fenceOk)
        record("cleanup_drain_confirmed", drained)
        return drained
    }
}
