package org.futo.voiceinput.shared.sherpa

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SherpaIncrementalTest {
    private fun equivalent(scores: FloatArray, tail: Int = 0, chunk: Int = 1600) {
        val size = scores.size * 512 - tail
        val audio = FloatArray(size) { (it % 97) / 100f }
        var index = 0
        val speech = detectSherpaSpeech(audio) { scores[index++] }
        val expected = planSherpaWindows(speech, size, probabilities = scores)
        index = 0
        val seen = mutableListOf<FloatArray>()
        val decoder = SherpaIncremental({ scores[index++] }, { pcm, _ -> seen.add(pcm.copyOf()); SherpaTokens(arrayOf("▁repeat"), floatArrayOf(if(seen.size == 1) 0f else 1.5f)) })
        var offset = 0
        while(offset < size) {
            val end = minOf(offset + chunk, size)
            decoder.feed(audio.copyOfRange(offset, end)); offset = end
        }
        val result = decoder.finish()
        assertEquals(expected, decoder.windows)
        assertEquals(expected.joinToString(" ") { "repeat" }, result)
        expected.zip(seen).forEachIndexed { i, (range, pcm) -> assertArrayEquals(sherpaDecodeRange(range, size, forcedLeft = expected.getOrNull(i-1)?.end == range.start, forcedRight = expected.getOrNull(i+1)?.start == range.end).let { audio.copyOfRange(it.start, it.end) }, pcm, 0f) }
        try { decoder.finish(); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun incrementalEqualsBatchForPausesForcedCutsTiesAndPartialFrames() {
        equivalent(FloatArray(2100) { 0.8f })
        equivalent(FloatArray(2300) { if(it % 700 in 680..699) 0.01f else 0.9f }, 171, 113)
        equivalent(FloatArray(2000) { if(it < 1400) 0.8f else 0f }, 311, 16000)
        equivalent(FloatArray(100) { 0f }, 1)
        for(seed in 0..9) {
            val random = Random(seed)
            val scores = FloatArray(2400) { if(random.nextInt(5) == 0) 0.1f else 0.7f }
            for(i in 0..15) {
                val start = random.nextInt(2350)
                for(j in start until start + random.nextInt(1, 40)) scores[j] = 0.02f
            }
            equivalent(scores, seed * 17, 997)
        }
    }
    @Test fun twoFiveAndTenMinutePlansRemainEquivalent() {
        for(seconds in listOf(120, 300, 600)) {
            equivalent(FloatArray((seconds * 16000 + 511) / 512) {
                if(it % 400 < 375) 0.8f else 0f
            }, tail = ((seconds * 16000 + 511) / 512) * 512 - seconds * 16000)
        }
    }
    @Test fun longSpeechDecodesBeforeStopAndSilenceKeepsPcmBounded() {
        var calls = 0
        val decoder = SherpaIncremental({ 0.9f }, { _, during -> assertTrue(during); calls++; SherpaTokens(arrayOf("▁word"), floatArrayOf(1f)) })
        repeat(220) { decoder.feed(FloatArray(1600) { 0.1f }) }
        assertTrue(calls > 0)
        decoder.clear()
        val silence = SherpaIncremental({ 0f }, { _, _ -> error("silence decoded") })
        repeat(6000) { silence.feed(FloatArray(1600)) }
        assertEquals("", silence.finish())
    }
    @Test fun queueBoundsIncludeInflightAndNeverSilentlyDrop() = runBlocking {
        val queue = SherpaFeedQueue(8)
        val source = FloatArray(4) { 0.5f }
        queue.offer(source); source.fill(0f); queue.offer(FloatArray(4))
        try { queue.offer(FloatArray(1)); fail() } catch (_: IllegalStateException) { }
        queue.close()
        var calls = 0
        queue.consume { if(calls++ == 0) assertEquals(0.5f, it[0]) }
        assertEquals(2, calls)
    }
    @Test fun cancellingQueueDiscardsAudioAndRejectsFurtherCapture() = runBlocking {
        val queue = SherpaFeedQueue(8)
        queue.offer(FloatArray(4)); queue.cancel()
        queue.consume { error("cancelled PCM escaped") }
        try { queue.offer(FloatArray(1)); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun inflightAudioStillCountsAgainstBacklogAndFailedFeedClosesQueue() = runBlocking {
        val queue = SherpaFeedQueue(4)
        queue.offer(FloatArray(4))
        var offered = false
        try {
            queue.consume {
                try { queue.offer(FloatArray(1)); offered = true } catch (_: IllegalStateException) { }
                error("worker died")
            }
            fail()
        } catch (_: IllegalStateException) { }
        assertFalse(offered)
        try { queue.offer(FloatArray(1)); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun totalResourceCeilingFailsRatherThanTruncates() {
        val session = SherpaSession(1)
        session.begin(SherpaSession.LOAD, 0); session.accept(1, 0, null)
        var sequence = 1L
        repeat(SherpaSession.MAX_SAMPLES / SherpaSession.CHUNK) {
            session.begin(SherpaSession.FEED, sequence, SherpaSession.CHUNK)
            session.accept(1, sequence++, null)
        }
        try { session.begin(SherpaSession.FEED, sequence, 1); fail() } catch (_: IllegalStateException) { }
    }

}
