package org.futo.voiceinput.shared.opencl

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GuardPolicyTest {
    @Test fun constantsArePinnedWithProvenance() {
        // -1.0 is whisper.cpp's compiled-in default logprob_thold, reused as
        // an EXPERIMENTAL debug-only diagnostic (SPEC.md). Not calibrated.
        assertEquals(-1.0, GuardPolicy.LOGPROB_RETRY_THOLD, 0.0)
        assertEquals(1, GuardPolicy.FAST_BEAM)
        assertEquals(5, GuardPolicy.RETRY_BEAM)
    }

    @Test fun exactZeroIsExact() {
        assertTrue(GuardPolicy.isExactZero(FloatArray(16000)))
        assertTrue(GuardPolicy.isExactZero(floatArrayOf(0.0f, -0.0f, 0.0f)))
        // Near-zero NONZERO audio (quiet voice, room noise) never skips.
        assertFalse(GuardPolicy.isExactZero(floatArrayOf(0.0f, 1.0f / 32768.0f, 0.0f)))
        assertFalse(GuardPolicy.isExactZero(floatArrayOf(0.0f, -1e-9f)))
        assertFalse(GuardPolicy.isExactZero(floatArrayOf(Float.MIN_VALUE)))
    }

    @Test fun decisionMatrix() {
        val D = GuardPolicy::expectedDecision
        assertEquals(GuardPolicy.Rationale.NO_SIGNAL_DIGITAL_ZERO, D(true, 26, -0.1, 0))
        assertEquals(GuardPolicy.Rationale.RETRY_EMPTY_FAST, D(false, 0, Double.NaN, 0))
        assertEquals(GuardPolicy.Rationale.RETRY_SIGNAL_MISSING, D(false, 0, Double.NaN, 3))
        assertEquals(GuardPolicy.Rationale.RETRY_SIGNAL_MISSING, D(false, 10, -0.1, 1))
        assertEquals(GuardPolicy.Rationale.RETRY_LOW_CONFIDENCE, D(false, 2, -3.828, 0))
        assertEquals(GuardPolicy.Rationale.RETRY_LOW_CONFIDENCE, D(false, 2, -1.000001, 0))
        // NaN average with usable tokens is impossible, but an unknown
        // signal must retry, never accept.
        assertEquals(GuardPolicy.Rationale.RETRY_LOW_CONFIDENCE, D(false, 5, Double.NaN, 0))
        assertEquals(GuardPolicy.Rationale.RETRY_LOW_CONFIDENCE, D(false, 5, Double.NEGATIVE_INFINITY, 0))
        assertEquals(GuardPolicy.Rationale.FAST_ACCEPT, D(false, 26, -0.131095, 0))
        assertEquals(GuardPolicy.Rationale.FAST_ACCEPT, D(false, 26, -1.0, 0))
        assertEquals(GuardPolicy.Rationale.FAST_ACCEPT, D(false, 1, 0.0, 0))
    }

    @Test fun decisionTakesNoText() {
        // No-filter by construction: the decision signature has no text
        // parameter, so no spoken word can gate it. Asserted on source
        // (kotlin-reflect is not on the unit-test classpath).
        val policy = File("src/main/java/org/futo/voiceinput/shared/opencl/GuardPolicy.kt").readText()
        val sig = policy.lines().first { it.contains("fun expectedDecision(") }
        assertFalse("decision takes text: $sig", sig.contains("String"))
        assertFalse("decision takes text: $sig", sig.contains("text", ignoreCase = true))
        assertTrue(sig.contains("exactZero") && sig.contains("tokens") && sig.contains("avgLogprob") && sig.contains("missingLp"))
        // v16: no-speech diagnostics are measurement only — the decision
        // must not gain a no-speech parameter (no joint heuristic, no
        // silent gate). Threshold stays uncalibrated whisper-default reuse.
        assertFalse("decision gates no-speech: $sig", sig.contains("ospeech", ignoreCase = true))
        assertFalse("decision gates no-speech: $sig", sig.contains("NoSpeech"))
    }

    @Test fun emptyTextWithTokensNeverRetries() {
        // Retry keys on counted TOKENS, not on empty text: a stored output
        // with tokens>0 is accepted/confidence-checked regardless of what
        // the text looks like (text cannot even be passed in), so
        // model-legitimate blanks never cause pointless retries. A fully
        // empty fast output (0 tokens) retries once, same as v15.
        val D = GuardPolicy::expectedDecision
        assertEquals(GuardPolicy.Rationale.FAST_ACCEPT, D(false, 3, -0.2, 0))
        assertEquals(GuardPolicy.Rationale.RETRY_LOW_CONFIDENCE, D(false, 3, -3.8, 0))
        assertEquals(GuardPolicy.Rationale.RETRY_EMPTY_FAST, D(false, 0, Double.NaN, 0))
    }

    @Test fun noSpeechClassificationIsSafe() {
        // Mirrors the native validity rule: finite inside [0,1] passes
        // through; NaN, infinite, negative, and >1 (invalid p) are explicit
        // unknown (null) — never prob 0, never a confident input, and no
        // confidence heuristic is applied to them.
        assertEquals(0.0, GuardPolicy.classifyNoSpeech(0.0))
        assertEquals(0.6, GuardPolicy.classifyNoSpeech(0.6)!!, 1e-9)
        assertEquals(1.0, GuardPolicy.classifyNoSpeech(1.0))
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, -1.0, 1.000001, 2.0)) {
            assertNull("unsafe no-speech value accepted: $bad", GuardPolicy.classifyNoSpeech(bad))
        }
    }

    @Test fun parseRoundTripAndBounds() {
        val header = "guard=RETRY_LOW_CONFIDENCE fast_beam=1 fast_chars=6 fast_segments=1 " +
            "fast_tokens=2 fast_avg_logprob=-3.828 fast_missing_lp=0 " +
            "fast_nospeech_min=0.91 fast_nospeech_max=0.91 fast_nospeech_valid=1 fast_nospeech_unknown=0 fast_nospeech=0.91 " +
            "retry=1 retry_beam=5 " +
            "retry_chars=3 retry_segments=1 retry_tokens=1 retry_avg_logprob=-0.5 retry_missing_lp=0 " +
            "retry_nospeech_min=0.02 retry_nospeech_max=0.02 retry_nospeech_valid=1 retry_nospeech_unknown=0 retry_nospeech=0.02 " +
            "fast_ms=500.0 retry_ms=1300.0 total_ms=1805.0 thold_logprob=-1 " +
            "fast_fa_executed=0 fast_enc_built=12 fast_dec_built=0 " +
            "retry_fa_executed=0 retry_enc_built=12 retry_dec_built=0"
        val parsed = GuardPolicy.parse(header + "\nreturn\nabc")
        assertEquals(GuardPolicy.Rationale.RETRY_LOW_CONFIDENCE, parsed.rationale)
        assertEquals("return", parsed.fastText)
        assertEquals("abc", parsed.finalText)
        assertEquals(1, parsed.retry)
        assertEquals(5, parsed.retryBeam)
        assertEquals(-3.828, parsed.fastAvgLogprob, 1e-9)
        // v16 no-speech diagnostics: per-segment list plus min/max/counts,
        // never a hiding mean; unknown is explicit NaN, never prob 0.
        assertEquals(listOf(0.91), parsed.fastNoSpeech)
        assertEquals(0.91, parsed.fastNoSpeechMin, 1e-9)
        assertEquals(0.91, parsed.fastNoSpeechMax, 1e-9)
        assertEquals(1L, parsed.fastNoSpeechValid)
        assertEquals(0L, parsed.fastNoSpeechUnknown)
        assertEquals(listOf(0.02), parsed.retryNoSpeech)
        // Multi-segment row: min/max span the individuals, unknown explicit.
        val multi = GuardPolicy.parse(
            header.replace("fast_nospeech_min=0.91 fast_nospeech_max=0.91 fast_nospeech_valid=1 fast_nospeech_unknown=0 fast_nospeech=0.91",
                "fast_nospeech_min=0.05 fast_nospeech_max=0.9 fast_nospeech_valid=2 fast_nospeech_unknown=1 fast_nospeech=0.9,NaN,0.05") + "\nreturn\nabc")
        assertEquals(3, multi.fastNoSpeech.size)
        assertEquals(0.9, multi.fastNoSpeech[0], 1e-9)
        assertTrue(multi.fastNoSpeech[1].isNaN())
        assertEquals(0.05, multi.fastNoSpeech[2], 1e-9)
        assertEquals(0.05, multi.fastNoSpeechMin, 1e-9)
        assertEquals(0.9, multi.fastNoSpeechMax, 1e-9)
        assertEquals(2L, multi.fastNoSpeechValid)
        assertEquals(1L, multi.fastNoSpeechUnknown)
        // Embedded newline + multibyte text survives byte-exact framing
        // (byte length computed, never hand-counted).
        val fastTricky = "line1\nline2 héllo"
        val trickyHeader = header.replace(
            "fast_chars=6",
            "fast_chars=${fastTricky.toByteArray(Charsets.UTF_8).size}",
        )
        val tricky = GuardPolicy.parse(trickyHeader + "\n$fastTricky\nfinal ✓")
        assertEquals(fastTricky, tricky.fastText)
        assertEquals("final ✓", tricky.finalText)
        // Skip path: empty fast and final text, no-speech explicitly unknown.
        val skipHeader = header.replace("guard=RETRY_LOW_CONFIDENCE", "guard=NO_SIGNAL_DIGITAL_ZERO")
            .replace("fast_chars=6", "fast_chars=0").replace("retry=1 retry_beam=5", "retry=0 retry_beam=0")
            .replace("fast_avg_logprob=-3.828", "fast_avg_logprob=NaN")
            .replace("fast_nospeech_min=0.91 fast_nospeech_max=0.91 fast_nospeech_valid=1 fast_nospeech_unknown=0 fast_nospeech=0.91",
                "fast_nospeech_min=NaN fast_nospeech_max=NaN fast_nospeech_valid=0 fast_nospeech_unknown=0 fast_nospeech=")
            .replace("retry_chars=3", "retry_chars=0")
            .replace("retry_nospeech_min=0.02 retry_nospeech_max=0.02 retry_nospeech_valid=1 retry_nospeech_unknown=0 retry_nospeech=0.02",
                "retry_nospeech_min=NaN retry_nospeech_max=NaN retry_nospeech_valid=0 retry_nospeech_unknown=0 retry_nospeech=")
        val skip = GuardPolicy.parse(skipHeader + "\n\n")
        assertEquals("", skip.fastText)
        assertEquals("", skip.finalText)
        assertTrue(skip.fastAvgLogprob.isNaN())
        // No output => no-speech unknown, never prob 0.
        assertTrue(skip.fastNoSpeechMin.isNaN())
        assertTrue(skip.fastNoSpeechMax.isNaN())
        assertEquals(0L, skip.fastNoSpeechValid)
        assertTrue(skip.fastNoSpeech.isEmpty())
        assertTrue(skip.retryNoSpeechMin.isNaN())
        // Bound violations rejected, never silently accepted (including a
        // header with silently dropped no-speech diagnostics).
        for (bad in listOf(
            header.replace("retry=1", "retry=2"),
            header.replace("retry_beam=5", "retry_beam=3"),
            header.replace("thold_logprob=-1", "thold_logprob=-0.5"),
            header.replace("fast_beam=1", "fast_beam=3"),
            header.replace(" fast_nospeech=0.91", ""),
            header.replace(" retry_nospeech=0.02", ""),
            "guard=FAST_ACCEPT",
            header + "\nshort",
        )) {
            try { GuardPolicy.parse(bad); fail("invalid guarded result accepted: $bad") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
