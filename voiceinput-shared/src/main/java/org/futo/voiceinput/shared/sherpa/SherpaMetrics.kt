package org.futo.voiceinput.shared.sherpa

/** Content-free worker measurements. PSS is sampled after load and each window,
 * so its maximum is a lower bound on the true instantaneous process peak. */
internal class SherpaMetrics(private val now: () -> Long, private val pssKb: () -> Long) {
    var coldLoadMs = -1L; private set
    var windows = 0L; private set
    var decodeMs = 0L; private set
    var sampledPeakPssKb = -1L; private set
    fun sample() {
        val value = runCatching { pssKb() }.getOrDefault(-1L)
        sampledPeakPssKb = maxOf(sampledPeakPssKb, value)
    }
    fun <T> coldLoad(action: () -> T): T {
        val start = now()
        return action().also { coldLoadMs = now() - start; sample() }
    }
    var tailDecodeMs = 0L; private set
    var windowsDuringRecording = 0L; private set
    fun beginRecording() { windows = 0; decodeMs = 0; tailDecodeMs = 0; windowsDuringRecording = 0 }
    fun <T> window(duringRecording: Boolean = false, action: () -> T): T {
        val start = now()
        return action().also { windows++; decodeMs += now() - start
            if(duringRecording) windowsDuringRecording++ else tailDecodeMs += now() - start
            sample() }
    }
    fun values() = longArrayOf(coldLoadMs, windows, decodeMs, sampledPeakPssKb, tailDecodeMs, windowsDuringRecording)
}

internal fun sherpaDiagnostic(op: Int, values: LongArray): String {
    require(values.size == 6 && values[4] >= 0 && values[5] >= 0 && values[0] >= -1 && values[1] >= 0 && values[2] >= 0 && values[3] >= -1)
    val phase = if (op == SherpaSession.LOAD) "model_load" else {
        require(op == SherpaSession.STOP); "recording_decode"
    }
    return "phase=$phase engine=parakeet cold_model_load_ms=${values[0]} windows=${values[1]} decode_ms=${values[2]} asr_sampled_peak_pss_kb=${values[3]} tail_decode_ms=${values[4]} windows_during_recording=${values[5]} backend=cpu"
}
