package org.futo.voiceinput.shared.opencl

/**
 * Guarded beam-1 policy (historical experiment notes are local-only and are
 * not part of this repository). Pure logic +
 * result parsing; no audio, no model, no text matching of any kind.
 *
 * Segment no-speech probabilities are diagnostics only; [expectedDecision]
 * takes no no-speech input. Quiet nonzero speech is never amplitude-gated.
 *
 * The native guarded call applies this same policy atomically
 * (single fast attempt + at most one retry inside one JNI call, shared
 * abort). This object's [expectedDecision] is an independent mirror used by
 * the replay as a cross-check: a native rationale that disagrees with the
 * mirror sets guard_mismatch=1 in the diagnostics row (never silent).
 *
 * No-filter proof by construction: [expectedDecision] takes NO text
 * parameter, so no word ("return" or any other) can influence the decision.
 * Empty-text policy: the retry decision keys on counted TOKENS (tokens==0),
 * not on empty text — a stored-but-empty text with tokens>0 does NOT retry
 * (no pointless retries on model-legitimate blanks), while a fully empty
 * fast output (0 tokens, e.g. the runtime's internal is_no_speech segment
 * drop, which stores nothing) retries once with beam5, same as v15.
 * The only skip path is byte-exact all-zero PCM (deterministic digital
 * no-signal). There is no amplitude/RMS gate anywhere in this file.
 */
internal object GuardPolicy {
    /** Provenance: whisper.cpp compiled-in default logprob_thold.
     * EXPERIMENTAL debug-only diagnostic, not a calibrated confidence. */
    const val LOGPROB_RETRY_THOLD = -1.0
    const val FAST_BEAM = 1
    const val RETRY_BEAM = 5

    enum class Rationale {
        NO_SIGNAL_DIGITAL_ZERO,
        FAST_ACCEPT,
        RETRY_EMPTY_FAST,
        RETRY_SIGNAL_MISSING,
        RETRY_LOW_CONFIDENCE,
    }

    data class GuardResult(
        val rationale: Rationale,
        val fastChars: Int,
        val fastSegments: Int,
        val fastTokens: Long,
        val fastAvgLogprob: Double,
        val fastMissingLp: Long,
        /** v16: per-attempt no-speech diagnostics (measurement only, never
         * gated). min/max are NaN when no stored segment had a valid
         * probability; [fastNoSpeech] holds one entry per stored segment in
         * order (NaN = explicit unknown, never prob 0); empty when the
         * attempt stored zero segments. */
        val fastNoSpeechMin: Double,
        val fastNoSpeechMax: Double,
        val fastNoSpeechValid: Long,
        val fastNoSpeechUnknown: Long,
        val fastNoSpeech: List<Double>,
        val retry: Int,
        val retryBeam: Int,
        val retryChars: Int,
        val retrySegments: Int,
        val retryTokens: Long,
        val retryAvgLogprob: Double,
        val retryMissingLp: Long,
        val retryNoSpeechMin: Double,
        val retryNoSpeechMax: Double,
        val retryNoSpeechValid: Long,
        val retryNoSpeechUnknown: Long,
        val retryNoSpeech: List<Double>,
        val fastMs: Double,
        val retryMs: Double,
        val totalMs: Double,
        val fastFaExecuted: Long,
        val fastEncBuilt: Long,
        val fastDecBuilt: Long,
        val retryFaExecuted: Long,
        val retryEncBuilt: Long,
        val retryDecBuilt: Long,
        val fastText: String,
        val finalText: String,
    )

    /** Byte-exact all-zero float PCM ONLY. Near-zero nonzero audio
     * (quiet voice, room noise) returns false. */
    fun isExactZero(samples: FloatArray): Boolean {
        for (v in samples) if (v != 0.0f) return false
        return true
    }

    /** Pure policy mirror. exactZero/tokens/avgLogprob/missingLp only;
     * text is deliberately NOT a parameter (no-filter by construction), and
     * no-speech diagnostics are deliberately NOT parameters either (v16
     * measurement only: the v15 guard policy is preserved unchanged for
     * experimental comparison, no threshold refit, no joint heuristic). */
    fun expectedDecision(exactZero: Boolean, tokens: Long, avgLogprob: Double, missingLp: Long): Rationale {
        if (exactZero) return Rationale.NO_SIGNAL_DIGITAL_ZERO
        if (tokens == 0L && missingLp == 0L) return Rationale.RETRY_EMPTY_FAST
        if (missingLp > 0) return Rationale.RETRY_SIGNAL_MISSING
        // !(avg >= thold) also catches NaN: unknown evidence retries.
        if (!(avgLogprob >= LOGPROB_RETRY_THOLD)) return Rationale.RETRY_LOW_CONFIDENCE
        return Rationale.FAST_ACCEPT
    }

    private fun lp(raw: String): Double =
        if (raw.equals("NaN", ignoreCase = true)) Double.NaN else raw.toDouble()

    /** Mirrors the native validity rule for a reported no-speech
     * probability: finite and inside [0,1]. Anything else (NaN, infinite,
     * negative, or >1) is explicit unknown (null), never prob 0 and never a
     * confident input — there is no confidence heuristic applied to it. */
    fun classifyNoSpeech(p: Double): Double? =
        if (p.isFinite() && p >= 0.0 && p <= 1.0) p else null

    private fun nsList(raw: String): List<Double> =
        if (raw.isEmpty()) emptyList() else raw.split(",").map { lp(it) }

    /** Parses one native packed result: first-line header + '\n' + fast text
     * (exactly fast_chars BYTES) + '\n' + final text. Byte-exact framing
     * survives embedded newlines and multibyte UTF-8. Enforces the retry
     * bound and threshold provenance pins. */
    fun parse(packed: String): GuardResult {
        val bytes = packed.toByteArray(Charsets.UTF_8)
        val cut = bytes.indexOf(0x0A.toByte())
        require(cut >= 0) { "guarded result missing header line" }
        val fields = String(bytes.copyOfRange(0, cut), Charsets.UTF_8).split(" ")
            .map { token ->
                val e = token.indexOf('=')
                require(e > 0) { "guarded header token without '=': $token" }
                token.substring(0, e) to token.substring(e + 1)
            }.toMap()
        fun str(key: String): String = fields[key] ?: throw IllegalArgumentException("guarded header missing $key")
        fun long(key: String): Long = str(key).toLong()
        fun int(key: String): Int = str(key).toInt()
        fun dbl(key: String): Double = str(key).toDouble()
        val rationale = Rationale.valueOf(str("guard"))
        require(int("fast_beam") == FAST_BEAM) { "fast beam not $FAST_BEAM" }
        val retry = int("retry")
        require(retry == 0 || retry == 1) { "retry out of bound: $retry" }
        require(int("retry_beam") == if (retry == 1) RETRY_BEAM else 0) { "retry beam mismatch" }
        require(dbl("thold_logprob") == LOGPROB_RETRY_THOLD) { "threshold provenance changed" }
        val fastChars = int("fast_chars")
        require(bytes.size >= cut + 1 + fastChars + 1) { "guarded result truncated fast text" }
        require(bytes[cut + 1 + fastChars] == 0x0A.toByte()) { "guarded fast-text framing broken" }
        val fastText = String(bytes.copyOfRange(cut + 1, cut + 1 + fastChars), Charsets.UTF_8)
        val finalText = String(bytes.copyOfRange(cut + 1 + fastChars + 1, bytes.size), Charsets.UTF_8)
        return GuardResult(
            rationale = rationale,
            fastChars = int("fast_chars"),
            fastSegments = int("fast_segments"),
            fastTokens = long("fast_tokens"),
            fastAvgLogprob = lp(str("fast_avg_logprob")),
            fastMissingLp = long("fast_missing_lp"),
            fastNoSpeechMin = lp(str("fast_nospeech_min")),
            fastNoSpeechMax = lp(str("fast_nospeech_max")),
            fastNoSpeechValid = long("fast_nospeech_valid"),
            fastNoSpeechUnknown = long("fast_nospeech_unknown"),
            fastNoSpeech = nsList(str("fast_nospeech")),
            retry = retry,
            retryBeam = int("retry_beam"),
            retryChars = int("retry_chars"),
            retrySegments = int("retry_segments"),
            retryTokens = long("retry_tokens"),
            retryAvgLogprob = lp(str("retry_avg_logprob")),
            retryMissingLp = long("retry_missing_lp"),
            retryNoSpeechMin = lp(str("retry_nospeech_min")),
            retryNoSpeechMax = lp(str("retry_nospeech_max")),
            retryNoSpeechValid = long("retry_nospeech_valid"),
            retryNoSpeechUnknown = long("retry_nospeech_unknown"),
            retryNoSpeech = nsList(str("retry_nospeech")),
            fastMs = dbl("fast_ms"),
            retryMs = dbl("retry_ms"),
            totalMs = dbl("total_ms"),
            fastFaExecuted = long("fast_fa_executed"),
            fastEncBuilt = long("fast_enc_built"),
            fastDecBuilt = long("fast_dec_built"),
            retryFaExecuted = long("retry_fa_executed"),
            retryEncBuilt = long("retry_enc_built"),
            retryDecBuilt = long("retry_dec_built"),
            fastText = fastText,
            finalText = finalText,
        )
    }
}
