package org.futo.inputmethod.latin.whistle

import java.io.InputStream

/** Fixed messages only: never filenames, provider errors, audio or transcript values. */
internal enum class WavProblem(val explanation: String) {
    CONTAINER("Expected little-endian RIFF/WAVE (not RF64 or RIFX)."),
    SIZE("Diagnostic WAV container limit is 32 MiB; sizes must match exactly."),
    TRUNCATED("WAV ended before its declared chunk/container size."),
    FORMAT("Expected one PCM format chunk of 16 or 18 bytes; no compressed/extensible audio."),
    RATE("Supported sample rate: 16000 Hz only."),
    CHANNELS("Supported channel count: mono only."),
    BITS("Supported encoding: signed little-endian PCM16 only."),
    ALIGNMENT("PCM byte rate must be 32000 and block alignment 2."),
    DATA("Expected exactly one nonempty, even-sized data chunk after format."),
    CANCELLED("WAV validation cancelled.")
}
internal class WhistleWavException(val problem: WavProblem) : IllegalArgumentException()

/** No read-ahead queue: feed must acknowledge before the next <=16000 samples are read.
 * Finish is called once, only after the complete container and physical EOF validate.
 * Caller owns/ closes input and cancels its inference worker on any exception.
 */
internal object WhistleWav {
    const val MAX_BYTES = 32L * 1024 * 1024
    fun stream(input: InputStream, cancelled: () -> Boolean, feed: (FloatArray) -> Unit, finish: () -> String): String {
        fun reject(p: WavProblem): Nothing = throw WhistleWavException(p)
        fun alive() { if(cancelled()) reject(WavProblem.CANCELLED) }
        fun read(bytes: ByteArray, size: Int = bytes.size) {
            var at = 0
            while(at < size) {
                alive()
                val n = input.read(bytes, at, size - at)
                if(n < 0) reject(WavProblem.TRUNCATED)
                if(n == 0) { val b = input.read(); if(b < 0) reject(WavProblem.TRUNCATED); bytes[at++] = b.toByte() }
                else at += n
            }
            alive()
        }
        fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 255) or ((b[at+1].toInt() and 255) shl 8)
        fun u32(b: ByteArray, at: Int) = u16(b,at).toLong() or (u16(b,at+2).toLong() shl 16)
        fun tag(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)
        val header = ByteArray(12); read(header)
        if(tag(header,0) != "RIFF" || tag(header,8) != "WAVE") reject(WavProblem.CONTAINER)
        val total = u32(header,4) + 8
        if(total !in 12..MAX_BYTES) reject(WavProblem.SIZE)
        val scratch = ByteArray(32000)
        var remaining = total - 12; var format = false; var data = false
        while(remaining > 0) {
            if(remaining < 8) reject(WavProblem.SIZE)
            val chunk = ByteArray(8); read(chunk); remaining -= 8
            val size = u32(chunk,4); val padded = size + size % 2
            if(padded > remaining) reject(WavProblem.SIZE)
            when(tag(chunk,0)) {
                "fmt " -> {
                    if(format || data || size !in listOf(16L,18L)) reject(WavProblem.FORMAT)
                    val f = ByteArray(size.toInt()); read(f)
                    if(u16(f,0) != 1 || (size == 18L && u16(f,16) != 0)) reject(WavProblem.FORMAT)
                    if(u16(f,2) != 1) reject(WavProblem.CHANNELS)
                    if(u32(f,4) != 16000L) reject(WavProblem.RATE)
                    if(u16(f,14) != 16) reject(WavProblem.BITS)
                    if(u32(f,8) != 32000L || u16(f,12) != 2) reject(WavProblem.ALIGNMENT)
                    format = true
                }
                "data" -> {
                    if(!format || data || size == 0L || size % 2 != 0L) reject(WavProblem.DATA)
                    data = true
                    var left = size
                    while(left > 0) {
                        val n = minOf(left, scratch.size.toLong()).toInt(); read(scratch,n)
                        val pcm = FloatArray(n/2) { i -> u16(scratch,i*2).toShort().toFloat()/32768f }
                        alive(); feed(pcm); alive(); left -= n
                    }
                }
                else -> {
                    var left = size
                    while(left > 0) { val n = minOf(left,scratch.size.toLong()).toInt(); read(scratch,n); left -= n }
                }
            }
            if(size % 2 != 0L) read(ByteArray(1))
            remaining -= padded
        }
        if(!data) reject(WavProblem.DATA)
        alive(); if(input.read() != -1) reject(WavProblem.SIZE)
        alive(); return finish()
    }
}
