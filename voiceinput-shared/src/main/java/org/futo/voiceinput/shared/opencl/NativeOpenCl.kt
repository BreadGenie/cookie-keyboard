package org.futo.voiceinput.shared.opencl

import androidx.annotation.Keep

/** Loaded only in :experimental_opencl. No raw native handles cross threads or processes. */
@Keep
internal class NativeOpenCl(val onLoaded: () -> Unit, val onPartial: (String) -> Unit) {
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
    external fun run(modelFd: Int, samples: FloatArray, prompt: String, suppress: Boolean, cache: String): String
}
