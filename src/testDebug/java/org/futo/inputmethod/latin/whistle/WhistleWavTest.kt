package org.futo.inputmethod.latin.whistle

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class WhistleWavTest {
    private fun le(n: Long, bytes: Int) = ByteArray(bytes) { (n shr (it*8)).toByte() }
    private fun chunk(tag: String, b: ByteArray) = tag.toByteArray() + le(b.size.toLong(),4) + b + if(b.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
    private fun fmt(rate: Long = 16000, channels: Long = 1, bits: Long = 16, encoding: Long = 1, align: Long = 2) = chunk("fmt ",le(encoding,2)+le(channels,2)+le(rate,4)+le(32000,4)+le(align,2)+le(bits,2))
    private fun riff(body: ByteArray) = "RIFF".toByteArray()+le((body.size+4).toLong(),4)+"WAVE".toByteArray()+body
    private fun wav(samples: Int) = riff(fmt()+chunk("data",ByteArray(samples*2) { (it%256).toByte() }))
    private fun reject(bytes: ByteArray, reason: WavProblem) {
        var finishes=0
        try { WhistleWav.stream(ByteArrayInputStream(bytes), {false}, {}, {finishes++; "bad"}); fail("accepted WAV") }
        catch(e: WhistleWavException) { assertEquals(reason,e.problem) }
        assertEquals(0,finishes)
    }
    @Test fun validatesFormatsAndSizesPrecisely() {
        reject(wav(1).apply { this[0]=0 },WavProblem.CONTAINER)
        for((f,p) in listOf(fmt(rate=8000) to WavProblem.RATE,fmt(channels=2) to WavProblem.CHANNELS,fmt(bits=8) to WavProblem.BITS,fmt(encoding=3) to WavProblem.FORMAT,fmt(align=4) to WavProblem.ALIGNMENT))
            reject(riff(f+chunk("data",ByteArray(2))),p)
        reject(riff(fmt()+chunk("data",ByteArray(3))),WavProblem.DATA)
        reject(riff(fmt()+chunk("data",ByteArray(0))),WavProblem.DATA)
        reject(riff(chunk("data",ByteArray(2))+fmt()),WavProblem.DATA)
        reject(riff(fmt()+fmt()+chunk("data",ByteArray(2))),WavProblem.FORMAT)
        reject(riff(fmt()+chunk("data",ByteArray(2))+chunk("data",ByteArray(2))),WavProblem.DATA)
        reject(wav(1)+byteArrayOf(1),WavProblem.SIZE)
        reject(wav(1).apply { le(WhistleWav.MAX_BYTES,4).copyInto(this,4) },WavProblem.SIZE)
        reject(wav(1).apply { le(1000,4).copyInto(this,40) },WavProblem.SIZE)
    }
    @Test fun everyTruncationRejectsWithoutFinish() {
        val bytes=wav(3)
        for(n in bytes.indices) reject(bytes.copyOf(n),WavProblem.TRUNCATED)
        reject(riff(fmt()+chunk("data",ByteArray(2))+byteArrayOf(0)),WavProblem.SIZE)
    }
    @Test fun boundedChunksWithShortReadsAndExactFinalTail() {
        for(count in listOf(1,15999,16000,16001,32001)) {
            val input=object : ByteArrayInputStream(wav(count)) { override fun read(b: ByteArray, off: Int, len: Int) = super.read(b,off,minOf(len,3)) }
            var seen=0;var calls=0;var finishes=0
            val result=WhistleWav.stream(input,{false},{ pcm ->
                assertTrue(pcm.size in 1..16000); calls++
                pcm.forEach { sample ->
                    val lo=(seen*2)%256; val hi=(seen*2+1)%256
                    assertEquals(((lo or (hi shl 8)).toShort().toFloat()/32768f),sample,0f);seen++
                }
            },{ finishes++; "final tail" })
            assertEquals(count,seen);assertEquals((count+15999)/16000,calls);assertEquals(1,finishes);assertEquals("final tail",result)
        }
    }
    @Test fun cancellationAndFeedFailureNeverFinish() {
        for(cancelAt in listOf(0,1,2)) {
            var feeds=0;var finishes=0
            try { WhistleWav.stream(ByteArrayInputStream(wav(32001)), {feeds>=cancelAt}, {feeds++}, {finishes++; ""}); fail() }
            catch(e: WhistleWavException) { assertEquals(WavProblem.CANCELLED,e.problem) }
            assertEquals(cancelAt,feeds);assertEquals(0,finishes)
        }
        var finishes=0
        try { WhistleWav.stream(ByteArrayInputStream(wav(1)), {false}, {error("ack rejected")}, {finishes++; ""}); fail() } catch(_: IllegalStateException) {}
        assertEquals(0,finishes)
    }
    @Test fun unknownChunksPaddingAndTrailingChunkValidateBeforeFinish() {
        val b=riff(chunk("JUNK",ByteArray(3))+fmt()+chunk("data",byteArrayOf(0,0))+chunk("LIST",ByteArray(5)))
        assertEquals("done",WhistleWav.stream(ByteArrayInputStream(b),{false},{},{"done"}))
        reject(b.copyOf(b.size-1),WavProblem.TRUNCATED)
    }
}
