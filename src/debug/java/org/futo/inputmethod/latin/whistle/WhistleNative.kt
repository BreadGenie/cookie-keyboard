package org.futo.inputmethod.latin.whistle

/** Referenced only by WhistleService; never initialized in the keyboard process. */
internal object WhistleNative {
    init { System.loadLibrary("cookie_whistle") }
    external fun load(generation: Long, fd: Int): Boolean
    external fun feed(generation: Long, pcm: FloatArray): ByteArray?
    external fun finish(generation: Long): ByteArray?
}
