package org.futo.voiceinput.shared.opencl

/**
 * v24-final persistent OpenCL kernel-cache diagnostics: rendering and parsing
 * for the trailing `cl_*` fields the service appends to every METRICS row
 * (see OpenClService). Content-free: counters, durations and hit/miss flags
 * only.
 *
 * Counter semantics describe actual native calls/success per measured span,
 * never filesystem existence (see integration/prepare.py v24-final block):
 *  - hits: binary loaded and built, source compile skipped
 *  - misses: nothing loadable, source compile followed on the wrapper path
 *  - rejects: a file was present but unusable (bad magic/size, format
 *    mismatch, driver refused the binary), source compile followed
 *  - saves: fresh binary persisted after a source compile
 *  - load_ms: cumulative binary load+build wall on the hit path
 *  - compile_ms: cumulative source-build wall across EVERY
 *    build_program_from_source_ex call in the span (cached-wrapper path AND
 *    any cache-bypassing calls). v24-initial measured the wrapped
 *    path only; v24-final fixes that coverage gap.
 *  - src_builds: every _ex call in the span (compilation proof even when the
 *    cache is disabled, where misses+rejects stay 0)
 *  - src_direct: direct (cache-bypassing, non-wrapper) _ex calls;
 *    wrapped = builds - direct. After lazy-fa-cache routes the lazy
 *    flash-attention builds through the binary cache, FA source builds
 *    count as wrapped (cached-path); direct is expected to read 0 on FA
 *    spans and nonzero only if a build bypassed the cache entirely.
 *  - direct_ms: direct _ex wall; wrapped wall = compile_ms - direct_ms
 *
 * Rows from older builds carry the first 6 fields only: [parse] still reads
 * them (extended values stay null, never defaulted) and the phone GPU hit
 * status stays unknown, never defaulted. Cancelled or failed calls render
 * [UNKNOWN_BLOCK] (also parses to null): unknown is explicit, never
 * success-zero. A committed all-zero block is different: it is a measured
 * zero (the native span observed no counter movement).
 *
 * Classifier names describe only what happened inside the measured span: a
 * cache hit in the span does NOT imply a fast encode, and source compiles in
 * the span do not by themselves explain any encode gap (see H3 in the spec).
 */
internal object ClCacheStats {
    data class Snapshot(
        val hits: Long,
        val misses: Long,
        val saves: Long,
        val rejects: Long,
        val loadMs: Double,
        val compileMs: Double,
        val srcBuilds: Long?,
        val srcDirect: Long?,
        val directMs: Double?
    )

    /** Rendered when a call's deltas are invalid (cancel/exception/CPU). Parses to null. */
    const val UNKNOWN_BLOCK =
        "cl_hits=unknown cl_miss=unknown cl_save=unknown " +
            "cl_reject=unknown cl_load_ms=unknown cl_compile_ms=unknown " +
            "cl_src_builds=unknown cl_src_direct=unknown cl_direct_ms=unknown"

    private val NUM_TOKEN = Regex("[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    /** Wrapped (cached-path) builds in a snapshot: total minus direct. Null when unobserved. */
    fun srcWrapped(s: Snapshot): Long? =
        if (s.srcBuilds != null && s.srcDirect != null) s.srcBuilds - s.srcDirect else null

    /** Wrapped (cached-path) source wall in a snapshot: total minus direct. Null when unobserved. */
    fun wrappedMs(s: Snapshot): Double? =
        if (s.directMs != null) s.compileMs - s.directMs else null

    /** True for a measured per-call delta: 9 finite non-negative values with integral counts. */
    fun isValidDelta(values: DoubleArray): Boolean {
        if (values.size != 9 || !values.all { it.isFinite() && it >= 0.0 }) return false
        return values[0] % 1.0 == 0.0 && values[1] % 1.0 == 0.0 &&
            values[2] % 1.0 == 0.0 && values[3] % 1.0 == 0.0 &&
            values[6] % 1.0 == 0.0 && values[7] % 1.0 == 0.0
    }

    /** Renders the exact trailing field block the service appends per row. */
    fun format(values: DoubleArray): String {
        require(isValidDelta(values)) { "cl cache delta must be 9 finite non-negative values with integral counts" }
        return "cl_hits=${values[0]} cl_miss=${values[1]} cl_save=${values[2]} " +
            "cl_reject=${values[3]} cl_load_ms=${values[4]} cl_compile_ms=${values[5]} " +
            "cl_src_builds=${values[6]} cl_src_direct=${values[7]} cl_direct_ms=${values[8]}"
    }

    /** Renders [format] for valid deltas, [UNKNOWN_BLOCK] otherwise. Never throws. */
    fun render(values: DoubleArray): String =
        if (isValidDelta(values)) format(values) else UNKNOWN_BLOCK

    /**
     * Sums two disjoint per-call deltas (prepare-call + run-call) into one
     * request total; null when either side is invalid. The spans are
     * sequential native calls on the service's serial worker (the run span's
     * inner prepare() is a no-op once the explicit prepare filled the
     * context), so the sum never double counts.
     */
    fun sum(prepare: DoubleArray, run: DoubleArray): DoubleArray? {
        if (!isValidDelta(prepare) || !isValidDelta(run)) return null
        return DoubleArray(9) { prepare[it] + run[it] }
    }

    /** Parses the trailing block from an exported METRICS row; null when absent or unknown. */
    fun parse(row: String): Snapshot? {
        fun num(key: String): Double? {
            // Key must start the row or follow whitespace, and the value must
            // be one full whitespace-delimited token: partial tokens
            // ("14.0x", "1.0E", "cl_hitsx=5") never match.
            val m = Regex("(?:^|\\s)$key=([^\\s]+)").find(row) ?: return null
            val token = m.groupValues[1]
            if (!NUM_TOKEN.matches(token)) return null
            return token.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        }
        val hits = num("cl_hits") ?: return null
        val misses = num("cl_miss") ?: return null
        val saves = num("cl_save") ?: return null
        val rejects = num("cl_reject") ?: return null
        val loadMs = num("cl_load_ms") ?: return null
        val compileMs = num("cl_compile_ms") ?: return null
        if (hits % 1.0 != 0.0 || misses % 1.0 != 0.0 ||
            saves % 1.0 != 0.0 || rejects % 1.0 != 0.0
        ) return null
        // Extended v24-final fields: all-or-none. Legacy 6-field rows parse
        // with nulls (base comparable, extended unknown); a partially
        // extended row parses to null, never to a default.
        val builds = num("cl_src_builds")
        val direct = num("cl_src_direct")
        val directMs = num("cl_direct_ms")
        val extended = listOf(builds, direct, directMs)
        if (extended.any { it != null } && extended.any { it == null }) return null
        if (builds != null && (builds % 1.0 != 0.0 || direct!! % 1.0 != 0.0)) return null
        return Snapshot(
            hits.toLong(), misses.toLong(), saves.toLong(), rejects.toLong(), loadMs, compileMs,
            builds?.toLong(), direct?.toLong(), directMs
        )
    }

    /**
     * At least one program was built from source inside the measured span.
     * src_builds is the proof: it counts every _ex call even when the cache
     * is disabled (misses+rejects stay 0 there).
     */
    fun hasSourceCompileInSpan(s: Snapshot): Boolean =
        s.misses > 0 || s.rejects > 0 || (s.srcBuilds ?: 0L) > 0L

    /** At least one cache-bypassing source build in the span. Says nothing about encode speed. */
    fun hasDirectCompileInSpan(s: Snapshot): Boolean = (s.srcDirect ?: 0L) > 0L

    /** At least one cached binary was loaded inside the measured span. Says nothing about encode speed. */
    fun hasCacheHitInSpan(s: Snapshot): Boolean = s.hits > 0
}
