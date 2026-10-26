package org.futo.voiceinput.shared.opencl

import org.junit.Assert.*
import org.junit.Test

/**
 * v24-final kernel-cache diagnostic: deterministic host tests for counter
 * semantics, row rendering/parsing, and the two-process phone comparison.
 * No native code, no device, no audio/transcript content.
 *
 * Classifier names describe only what happened inside the measured span: a
 * cache hit in the span does NOT imply a fast encode, and source compiles in
 * the span do not by themselves explain any encode gap.
 */
class ClCacheStatsTest {
    private fun row(cl: String) =
        "utc_ms=1792826302749 backend=opencl_fast phase=recognize context_hit=false " +
            "encode_ms=1549.649 decode_ms=151.034 kernel_restart_cache=unknown " +
            "guard=FAST_ACCEPT retry=0 $cl verification=resident_reverified " +
            "bytes_hashed=264477561 resident_generation=1 proc_pid=1234 proc_nonce=5678"

    private fun delta(
        hits: Double = 0.0, miss: Double = 0.0, save: Double = 0.0, reject: Double = 0.0,
        loadMs: Double = 0.0, compileMs: Double = 0.0,
        builds: Double = 0.0, direct: Double = 0.0, directMs: Double = 0.0
    ) = doubleArrayOf(hits, miss, save, reject, loadMs, compileMs, builds, direct, directMs)

    @Test fun formatRendersExactFieldBlock() {
        assertEquals(
            "cl_hits=0.0 cl_miss=14.0 cl_save=14.0 cl_reject=0.0 cl_load_ms=0.0 " +
                "cl_compile_ms=1310.5 cl_src_builds=14.0 cl_src_direct=0.0 cl_direct_ms=0.0",
            ClCacheStats.format(delta(miss = 14.0, save = 14.0, compileMs = 1310.5, builds = 14.0))
        )
    }

    @Test fun formatRejectsWrongSizeOrNegativeOrNonFinite() {
        for (bad in listOf(
            DoubleArray(6),
            DoubleArray(8),
            DoubleArray(10),
            delta(miss = -1.0),
            delta().also { it[0] = Double.NaN },
            delta(loadMs = Double.POSITIVE_INFINITY),
            delta(builds = 0.5),
            delta(direct = 1.5),
        )) {
            try {
                ClCacheStats.format(bad)
                fail("expected require() to throw for ${bad.toList()}")
            } catch (_: IllegalArgumentException) {
            }
            assertEquals(ClCacheStats.UNKNOWN_BLOCK, ClCacheStats.render(bad))
        }
    }

    @Test fun renderUnknownBlockParsesToNull() {
        assertNull(ClCacheStats.parse(row(ClCacheStats.UNKNOWN_BLOCK)))
        assertNull(ClCacheStats.parse(row(ClCacheStats.render(DoubleArray(9) { Double.NaN }))))
    }

    @Test fun firstProcessRowHasSourceCompilesButNoHits() {
        // First process after a cold start: every distinct program missed and
        // was compiled from source, then persisted. No hits, no direct (lazy
        // FA not yet exercised on this off-mode row) builds.
        val s = ClCacheStats.parse(row(ClCacheStats.format(delta(miss = 14.0, save = 14.0, compileMs = 1310.5, builds = 14.0))))
        assertNotNull(s)
        s!!
        assertEquals(0L, s.hits)
        assertEquals(14L, s.misses)
        assertEquals(14L, s.saves)
        assertEquals(0L, s.rejects)
        assertEquals(1310.5, s.compileMs, 0.0)
        assertEquals(14L, s.srcBuilds)
        assertEquals(0L, s.srcDirect)
        assertEquals(0.0, s.directMs!!, 0.0)
        assertEquals(14L, ClCacheStats.srcWrapped(s))
        assertEquals(1310.5, ClCacheStats.wrappedMs(s)!!, 0.0)
        assertTrue(ClCacheStats.hasSourceCompileInSpan(s))
        assertFalse(ClCacheStats.hasDirectCompileInSpan(s))
        assertFalse(ClCacheStats.hasCacheHitInSpan(s))
    }

    @Test fun fastFirstEncodeRowRoutesThroughCache() {
        // Post lazy-fa-cache: live Fast (encoder-flash) first encode routes
        // lazy FA variant programs through the binary cache, so their source
        // builds count as wrapped (cached-path), not direct. A cold first
        // process shows misses+saves with builds>0 and direct==0; a warm
        // second process shows hits with no source builds at all.
        val cold = ClCacheStats.parse(
            row(ClCacheStats.format(delta(miss = 30.0, save = 30.0, compileMs = 1549.6, builds = 42.0, direct = 0.0, directMs = 0.0)))
        )!!
        assertTrue(ClCacheStats.hasSourceCompileInSpan(cold))
        assertFalse(ClCacheStats.hasDirectCompileInSpan(cold))
        assertFalse(ClCacheStats.hasCacheHitInSpan(cold))
        assertEquals(42L, ClCacheStats.srcWrapped(cold))
        assertEquals(1549.6, ClCacheStats.wrappedMs(cold)!!, 0.001)
        val warm = ClCacheStats.parse(
            row(ClCacheStats.format(delta(hits = 42.0, loadMs = 61.8)))
        )!!
        assertTrue(ClCacheStats.hasCacheHitInSpan(warm))
        assertFalse(ClCacheStats.hasSourceCompileInSpan(warm))
        assertFalse(ClCacheStats.hasDirectCompileInSpan(warm))
    }

    @Test fun srcBuildsProveCompilationWithCacheDisabled() {
        // Cache disabled: the cache is never consulted, so misses+rejects
        // stay 0, but src_builds still counts every wrapper build.
        val s = ClCacheStats.parse(row(ClCacheStats.format(delta(compileMs = 800.0, builds = 30.0))))!!
        assertEquals(0L, s.misses)
        assertEquals(0L, s.rejects)
        assertTrue(ClCacheStats.hasSourceCompileInSpan(s))
        assertFalse(ClCacheStats.hasDirectCompileInSpan(s))
    }

    @Test fun secondProcessRowHasHitsButNoCompiles() {
        // Second process with a persisting cache dir: binaries loaded, no
        // source compiles in the span.
        val s = ClCacheStats.parse(row(ClCacheStats.format(delta(hits = 14.0, loadMs = 41.2))))!!
        assertTrue(ClCacheStats.hasCacheHitInSpan(s))
        assertFalse(ClCacheStats.hasSourceCompileInSpan(s))
        assertFalse(ClCacheStats.hasDirectCompileInSpan(s))
        assertEquals(41.2, s.loadMs, 0.0)
    }

    @Test fun rejectsDescribePresentButUnusableBinaries() {
        // Files present but refused by the driver: still source compiles in
        // the span (hits == 0), but rejects distinguish it from a clean miss.
        val s = ClCacheStats.parse(
            row(ClCacheStats.format(delta(miss = 14.0, save = 14.0, reject = 3.0, compileMs = 1420.0, builds = 17.0)))
        )!!
        assertEquals(3L, s.rejects)
        assertTrue(ClCacheStats.hasSourceCompileInSpan(s))
        assertFalse(ClCacheStats.hasCacheHitInSpan(s))
    }

    @Test fun prewarmRowsCarryMeasuredPrepareDeltaNotAssumedZero() {
        // Prewarm performs no inference: its row carries the prepare-call
        // delta (measured, possibly nonzero on a cold load), never an
        // unmeasured zero. Unknown renders explicitly when prepare was
        // cancelled, failed, or ran on CPU.
        val measured = ClCacheStats.parse(row(ClCacheStats.render(delta(miss = 14.0, save = 14.0, compileMs = 1310.5, builds = 14.0))))
        assertNotNull(measured)
        assertTrue(ClCacheStats.hasSourceCompileInSpan(measured!!))
        val unknown = ClCacheStats.parse(row(ClCacheStats.render(DoubleArray(9) { Double.NaN })))
        assertNull(unknown)
    }

    @Test fun recognizeRowsSumDisjointPrepareAndRunSpans() {
        val prepare = delta(miss = 14.0, save = 14.0, compileMs = 1310.5, builds = 14.0)
        val run = delta()
        val total = ClCacheStats.sum(prepare, run)
        assertNotNull(total)
        assertEquals(listOf(0.0, 14.0, 14.0, 0.0, 0.0, 1310.5, 14.0, 0.0, 0.0), total!!.toList())
        // Either side unknown (cancel/failed/CPU) poisons the sum: no
        // success-zero is ever rendered for unmeasured work.
        assertNull(ClCacheStats.sum(DoubleArray(9) { Double.NaN }, run))
        assertNull(ClCacheStats.sum(prepare, DoubleArray(9) { Double.NaN }))
    }

    @Test fun legacySixFieldRowsStayComparable() {
        // v24-initial 6-field rows still parse: base comparable, extended unknown.
        val s = ClCacheStats.parse(
            row("cl_hits=14.0 cl_miss=0.0 cl_save=0.0 cl_reject=0.0 cl_load_ms=41.2 cl_compile_ms=0.0")
        )
        assertNotNull(s)
        s!!
        assertEquals(14L, s.hits)
        assertNull(s.srcBuilds)
        assertNull(s.srcDirect)
        assertNull(s.directMs)
        assertNull(ClCacheStats.srcWrapped(s))
        assertNull(ClCacheStats.wrappedMs(s))
        assertTrue(ClCacheStats.hasCacheHitInSpan(s))
        // Without observed src_builds, a miss-only legacy row still implies a
        // wrapped compile (the only _ex path the old timer covered).
        val legacyMiss = ClCacheStats.parse(
            row("cl_hits=0.0 cl_miss=14.0 cl_save=14.0 cl_reject=0.0 cl_load_ms=0.0 cl_compile_ms=1310.5")
        )!!
        assertTrue(ClCacheStats.hasSourceCompileInSpan(legacyMiss))
        assertFalse(ClCacheStats.hasDirectCompileInSpan(legacyMiss))
    }

    @Test fun legacyRowsWithoutFieldsStayUnknown() {
        // Phone hit status must stay unknown when not observed: a v23 row
        // with no cl_* fields parses to null, never to a default.
        assertNull(ClCacheStats.parse("utc_ms=1 phase=recognize encode_ms=1549.649 kernel_restart_cache=unknown"))
        assertNull(ClCacheStats.parse(row("guard_extra=1").replace("cl_hits", "xx_hits")))
        assertNull(ClCacheStats.parse(""))
    }

    @Test fun partialFieldsStayUnknown() {
        assertNull(ClCacheStats.parse(row("cl_hits=14.0 cl_miss=0.0")))
        assertNull(ClCacheStats.parse(row("cl_hits=14.0 cl_miss=0.0 cl_save=0.0 cl_reject=0.0 cl_load_ms=1.0")))
        // Partially extended rows (some but not all v24-final fields) parse
        // to null, never to a default.
        assertNull(
            ClCacheStats.parse(
                row("cl_hits=0.0 cl_miss=14.0 cl_save=14.0 cl_reject=0.0 cl_load_ms=0.0 cl_compile_ms=1.0 cl_src_builds=14.0")
            )
        )
        assertNull(
            ClCacheStats.parse(
                row("cl_hits=0.0 cl_miss=14.0 cl_save=14.0 cl_reject=0.0 cl_load_ms=0.0 cl_compile_ms=1.0 cl_src_builds=14.0 cl_src_direct=0.0")
            )
        )
    }

    @Test fun parserIsStrictFullTokenFiniteNonNegativeIntegral() {
        fun clBlock(hits: String) =
            "cl_hits=$hits cl_miss=0.0 cl_save=0.0 cl_reject=0.0 cl_load_ms=1.0 " +
                "cl_compile_ms=0.0 cl_src_builds=0.0 cl_src_direct=0.0 cl_direct_ms=0.0"
        // Full-token exponent is accepted when it denotes a finite
        // non-negative integral count.
        val exp = ClCacheStats.parse(row(clBlock("1.4E1")))
        assertNotNull(exp)
        assertEquals(14L, exp!!.hits)
        // Partial tokens, bare exponents, non-finite, negative, and
        // non-integral values all parse to null (never defaulted).
        assertNull(ClCacheStats.parse(row(clBlock("14.0x"))))
        assertNull(ClCacheStats.parse(row(clBlock("1.0E"))))
        assertNull(ClCacheStats.parse(row(clBlock("NaN"))))
        assertNull(ClCacheStats.parse(row(clBlock("Infinity"))))
        assertNull(ClCacheStats.parse(row(clBlock("-1.0"))))
        assertNull(ClCacheStats.parse(row(clBlock("14.5"))))
        assertNull(ClCacheStats.parse(row(clBlock("1e-3"))))
        // A key that merely ends with the field name must not match.
        assertNull(
            ClCacheStats.parse(
                row("xxcl_hits=5.0 cl_miss=0.0 cl_save=0.0 cl_reject=0.0 cl_load_ms=1.0 cl_compile_ms=0.0")
            )
        )
    }

    @Test fun mixedHitMissRejectSpan() {
        // One process, mixed span: some programs hit, one was rejected and
        // recompiled, the rest missed. Both signals coexist in one row.
        val s = ClCacheStats.parse(
            row(ClCacheStats.format(delta(hits = 10.0, miss = 3.0, save = 4.0, reject = 1.0, loadMs = 12.5, compileMs = 300.0, builds = 4.0)))
        )!!
        assertTrue(ClCacheStats.hasCacheHitInSpan(s))
        assertTrue(ClCacheStats.hasSourceCompileInSpan(s))
        assertEquals(4L, s.saves)
    }

    @Test fun coldWarmDeltaAcrossTwoProcesses() {
        // The two-process phone procedure: first-process recognize row vs
        // second-process recognize row. A persisting cache turns
        // source-compiles into hits with no source-compile wall.
        // (Attribution only: hits in the span do not imply a fast encode.)
        val first = ClCacheStats.parse(row(ClCacheStats.format(delta(miss = 14.0, save = 14.0, compileMs = 1310.5, builds = 14.0))))!!
        val second = ClCacheStats.parse(row(ClCacheStats.format(delta(hits = 14.0, loadMs = 41.2))))!!
        assertTrue(ClCacheStats.hasSourceCompileInSpan(first))
        assertTrue(ClCacheStats.hasCacheHitInSpan(second))
        assertTrue(second.compileMs < first.compileMs)
        assertEquals(14L, second.hits - first.hits)
    }
}
