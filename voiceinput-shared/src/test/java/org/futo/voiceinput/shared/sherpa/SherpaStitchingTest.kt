package org.futo.voiceinput.shared.sherpa

import org.junit.Assert.*
import org.junit.Test

class SherpaStitchingTest {
    @Test fun straddlingWordBelongsToItsStartAndPunctuationFollowsIt() {
        val tokens = SherpaTokens(arrayOf("▁cross", "ing", "▁.", "▁next"), floatArrayOf(0.9f, 1.1f, 1.2f, 1.3f))
        assertEquals("crossing.", stitchSherpaWords(SherpaWindow(0,16000), 0, tokens))
        assertEquals("next", stitchSherpaWords(SherpaWindow(16000,32000), 0, tokens))
    }
    @Test fun genuineRepeatsAreKeptByTimeIncludingExactBoundary() {
        val tokens = SherpaTokens(arrayOf("▁go", "▁go", "!"), floatArrayOf(0.5f,1f,1.1f))
        assertEquals("go", stitchSherpaWords(SherpaWindow(0,16000),0,tokens))
        assertEquals("go!", stitchSherpaWords(SherpaWindow(16000,32000),0,tokens))
        assertEquals("go!", stitchSherpaWords(SherpaWindow(32000,48000),16000,tokens))
    }
    @Test fun edgesClampAndEmptyWindowsProduceNoText() {
        assertEquals(SherpaWindow(0,20000), sherpaDecodeRange(SherpaWindow(0,8000),20000))
        assertEquals(SherpaWindow(0,20000), sherpaDecodeRange(SherpaWindow(12000,20000),20000))
        assertEquals("", decodeSherpaStitchedWindows(FloatArray(1), emptyList()) { error("decode") })
        assertEquals("", stitchSherpaWords(SherpaWindow(0,1),0,SherpaTokens(emptyArray(),floatArrayOf())))
    }
    @Test fun failureReturnsNoPartialAndClearsPcm() {
        val copies = mutableListOf<FloatArray>()
        try {
            decodeSherpaStitchedWindows(FloatArray(32000) { 0.5f },listOf(SherpaWindow(0,16000),SherpaWindow(16000,32000))) {
                copies.add(it)
                if(copies.size == 2) error("failed")
                SherpaTokens(arrayOf("▁private"),floatArrayOf(0f))
            }
            fail()
        } catch (_: IllegalStateException) { }
        assertEquals(2,copies.size)
        assertTrue(copies.all { a -> a.all { it == 0f } })
    }
    @Test fun waitsForRightContextAndStopClampsTail() {
        var calls = 0
        val stream = SherpaIncremental({ if(calls == -1) 0f else 0.9f }, { _, _ ->
            calls++; SherpaTokens(emptyArray(),floatArrayOf())
        })
        stream.feed(FloatArray(20*16000))
        assertEquals(0,calls)
        stream.feed(FloatArray(23000))
        assertEquals(0, calls)
        stream.feed(FloatArray(1600))
        assertTrue(calls > 0)
        stream.finish()
        val tail = SherpaIncremental({ 0.9f }, { pcm, during ->
            assertFalse(during); assertEquals(8000,pcm.size); SherpaTokens(emptyArray(),floatArrayOf())
        })
        tail.feed(FloatArray(8000)); tail.finish()
    }
    @Test fun feedFailurePoisonsSessionAndRejectsFinish() {
        var calls = 0
        val stream = SherpaIncremental({ 0.9f }, { _, _ ->
            if(++calls == 2) error("decode failed")
            SherpaTokens(arrayOf("▁private"), floatArrayOf(1f))
        })
        try { stream.feed(FloatArray(45*16000)); fail() } catch (_: IllegalStateException) { }
        try { stream.finish(); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun standaloneBoundaryStartsAWordAndMalformedTimestampsFail() {
        val tokens = SherpaTokens(arrayOf("▁old", "▁", "new", "."),floatArrayOf(0.5f,1f,1.1f,1.2f))
        assertEquals("old",stitchSherpaWords(SherpaWindow(0,16000),0,tokens))
        assertEquals("new.",stitchSherpaWords(SherpaWindow(16000,32000),0,tokens))
        for (bad in listOf(floatArrayOf(),floatArrayOf(Float.NaN),floatArrayOf(-1f))) {
            try { stitchSherpaWords(SherpaWindow(0,16000),0,SherpaTokens(arrayOf("▁word"),bad)); fail() }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun hotwordsReachEveryContextDecodeAndStreamsReleaseOnFailure() {
        val seen = mutableListOf<String>()
        var released = 0
        val incremental = SherpaIncremental({ 0.9f }, { _, _ ->
            decodeSherpaTokenStream("Sean O'Connor/Siobhan Murphy", { seen.add(it); seen.size },
                { SherpaTokens(emptyArray(),floatArrayOf()) }, { released++ })
        })
        incremental.feed(FloatArray(45*16000)); incremental.finish()
        assertTrue(seen.size >= 3)
        assertTrue(seen.all { it == "Sean O'Connor/Siobhan Murphy" })
        assertEquals(seen.size,released)
        try {
            decodeSherpaTokenStream("", { assertEquals("",it); 0 }, { error("failed") }, { released++ })
            fail()
        } catch (_: IllegalStateException) { }
        assertEquals(seen.size+1,released)
    }
    @Test fun nativeNormalizedSpaceBoundariesMatchSentencePieceBoundaries() {
        val raw = arrayOf("▁cross", "ing", "▁.", "▁", "next")
        val times = floatArrayOf(0.9f,1.1f,1.2f,1.3f,1.4f)
        for (range in listOf(SherpaWindow(0,16000),SherpaWindow(16000,32000))) {
            assertEquals(stitchSherpaWords(range,0,SherpaTokens(raw,times)),
                stitchSherpaWords(range,0,SherpaTokens(raw.map { it.replace("▁"," ") }.toTypedArray(),times)))
        }
    }
    @Test fun closedVadWindowDecodesWithoutContextWait() {
        var frames = 0
        var calls = 0
        val stream = SherpaIncremental({ if (frames++ < 16) 0.9f else 0f }, { pcm, during ->
            assertTrue(during)
            assertEquals(8192 + 1600, pcm.size)
            calls++
            SherpaTokens(arrayOf(" word"), floatArrayOf(0f), "word")
        })
        stream.feed(FloatArray(16384))
        assertEquals(1, calls)
        assertEquals("word", stream.finish())
    }
    @Test fun vadOnlyPcmAndTextMatchIsolatedBaseline() {
        val audio = FloatArray(100000) { it / 100000f }
        val windows = listOf(SherpaWindow(1000, 20000), SherpaWindow(40000, 80000))
        var i = 0
        val text = decodeSherpaStitchedWindows(audio, windows) { pcm ->
            val w = windows[i++]
            assertArrayEquals(audio.copyOfRange(w.start, w.end), pcm, 0f)
            // A timestamp beyond the VAD edge must remain unfiltered.
            SherpaTokens(arrayOf(" word"), floatArrayOf(99f), "Original punctuation!")
        }
        assertEquals("Original punctuation! Original punctuation!", text)
    }
    @Test fun mixedPlanContextsAndFiltersOnlyContiguousSides() {
        val audio = FloatArray(100000) { it / 100000f }
        val windows = listOf(SherpaWindow(1000, 30000), SherpaWindow(30000, 60000), SherpaWindow(80000, 90000))
        val ranges = listOf(SherpaWindow(1000, 54000), SherpaWindow(6000, 60000), SherpaWindow(80000, 90000))
        var i = 0
        decodeSherpaStitchedWindows(audio, windows) { pcm ->
            val r = ranges[i++]
            assertArrayEquals(audio.copyOfRange(r.start, r.end), pcm, 0f)
            SherpaTokens(emptyArray(), floatArrayOf())
        }
        val words = SherpaTokens(arrayOf(" early", " middle", " late"), floatArrayOf(0f, 1f, 3f))
        val owned = SherpaWindow(16000, 32000)
        assertEquals("early middle", stitchSherpaWords(owned, 0, words, false, true))
        assertEquals("middle late", stitchSherpaWords(owned, 0, words, true, false))
        assertEquals(23 * 16000, sherpaDecodeRange(SherpaWindow(24000,344000),400000).let { it.end-it.start })
    }
}
