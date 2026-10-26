package org.futo.voiceinput.shared.opencl

import java.security.MessageDigest

object ComparisonPlan {
    /**
     * Wall-clock bound for the whole three-workload matrix (v43).
     *
     * Static budget from the v42 phone measurements, including the separate
     * full-shape warmup every visit still requires for comparability:
     *  - 12 s workload: CPU ~3.3 s + normal ~1.4 s + Fast ~0.4 s per visit;
     *    three rounds plus prepares/warmups is well under a minute.
     *  - 60 s workload: CPU ~39 s + normal ~15.7 s + Fast ~4.6 s warmup and
     *    measured call; three rounds is roughly 3 minutes before prepares.
     *  - silence 10 s: faster than the 12 s arm.
     * The v42 first normal prepare was 13.9 s; all prepares, the 264 MB SHA
     * gate and fixture reads add tens of seconds. Five minutes was too tight
     * for the measured long matrix, so the bounded comparison window is ten
     * minutes. This does not bound native cancellation drain and does not
     * cut off a production recording.
     */
    const val DEADLINE_MS = 10 * 60 * 1000L

    val modes = listOf("stock_cpu_beam5", "normal_opencl_beam5", "fast_guarded_beam1_retry5")
    fun order(round: Int) = (0..2).map { modes[(it + round) % 3] }
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    data class Sample(val round: Int, val mode: String, val ms: Double?, val error: String?)
    fun matched(rows: List<Sample>): List<Int> = (0..2).filter { round ->
        modes.all { mode -> rows.count { it.round == round && it.mode == mode && it.error == null && it.ms != null } == 1 } &&
            rows.none { it.round == round && it.error != null }
    }
    fun median(values: List<Double>): Double? = values.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    fun elapsedMs(start: Long, end: Long): Double { require(end >= start); return (end - start) / 1_000_000.0 }
}
