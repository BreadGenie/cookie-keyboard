package org.futo.voiceinput.shared.opencl

/** Only actual wall intervals and cheap counters; no sum of worker times. */
internal object OpenClMetrics {
    private val fields = mapOf(0 to "last_encode_ms", 8 to "callbacks", 9 to "output_tokens",
        14 to "threads", 15 to "audio_ctx", 16 to "samples", 17 to "full_ms",
        18 to "readback_nested_ms", 19 to "readback_bytes", 20 to "readback_calls",
        21 to "wait_nested_ms", 22 to "wait_calls", 33 to "embedding_mirror_enabled",
        34 to "embedding_mirror_bytes", 35 to "fa_executed")
    fun format(values: DoubleArray): String = fields.entries
        .filter { it.key < values.size && values[it.key].isFinite() && values[it.key] >= 0 }
        .joinToString(" ") { "${it.value}=${values[it.key]}" }
    fun guard(result: String): String = result.substringBefore('\n').split(' ')
        .filter { it.substringBefore('=') in setOf("guard", "retry", "retry_beam", "fast_ms", "retry_ms", "total_ms") }
        .joinToString(" ")
}
