package org.futo.voiceinput.shared.opencl

import androidx.annotation.Keep

/**
 * Same native library and run path as [NativeOpenCl], but its engine is
 * created with encoder-only flash-attention (encoder FA graphs, ordinary decoder graphs
 * with matching ordinary cross-KV layout). Loaded only in :experimental_opencl. No raw
 * native handles cross threads or processes. Live Fast dictation runs here
 * (FastDictationRunner -> OpenClEncoderFlashClient runGuardedBeamVerified
 * seam).
 */
@Keep
internal class NativeOpenClEncoderFlash(val onLoaded: () -> Unit, val onPartial: (String) -> Unit) {
    init { System.loadLibrary("futo_opencl") }
    @Keep private val nativeHandle: Long = create()
    private external fun create(): Long
    external fun prepare(modelFd: Int, cache: String): Boolean
    external fun close()
    external fun timings(): DoubleArray
    /**
     * v24-final kernel-cache diagnostic: run-call [hits, misses, saves,
     * rejects, load_ms, compile_ms(total), src_builds, src_direct,
     * direct_ms] deltas (implicit prepare + decode). Starts as
     * unknown (NaN); only successful runs commit measured values.
     */
    external fun clCacheStats(): DoubleArray
    /**
     * v24-final kernel-cache diagnostic: prepare-call deltas (cold context
     * load included). Prewarm rows carry this call's delta; recognize rows
     * carry prepare+run summed (see ClCacheStats.sum). Unknown (NaN) when
     * the prepare call was cancelled or failed.
     */
    external fun clCachePrepareStats(): DoubleArray
    @Keep fun loaded() = onLoaded()
    @Keep fun partial(text: String) = onPartial(text.trim())
    external fun reset()
    external fun cancel()
    /**
     * Guarded beam 1: one fast beam-1 attempt plus at most one
     * beam-5 retry inside a single call (shared atomic abort invalidates
     * both; no loop). Returns a first-line guard header plus final text
     * (see GuardPolicy.parse). Exact-zero PCM skips decode deterministically;
     * there is no amplitude gate and no word filter. Per-segment no-speech
     * probabilities ride the header as diagnostics only (never gated).
     */
    external fun runGuardedBeam(modelFd: Int, samples: FloatArray, prompt: String, suppress: Boolean, cache: String): String
}
