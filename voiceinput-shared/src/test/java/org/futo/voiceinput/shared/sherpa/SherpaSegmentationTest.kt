package org.futo.voiceinput.shared.sherpa

import org.junit.Assert.*
import org.junit.Test

class SherpaSegmentationTest {
    @Test fun longPlansCoverEverySpeechSampleOnceInOrder() {
        for (minutes in listOf(2, 5, 10)) {
            val total = minutes * 60 * 16000
            val speech = (0 until total step 11 * 16000).map {
                SherpaWindow(it, minOf(it + 10 * 16000, total))
            }
            val windows = planSherpaWindows(speech, total)
            assertTrue(windows.all { it.end - it.start <= SHERPA_WINDOW_SAMPLES })
            assertTrue(windows.zipWithNext().all { (a, b) -> a.end <= b.start })
            for (segment in speech) {
                assertEquals(segment.end - segment.start, windows.sumOf {
                    maxOf(0, minOf(it.end, segment.end) - maxOf(it.start, segment.start))
                })
            }
        }
    }
    @Test fun continuousSpeechIsForceCutWithNoGaps() {
        val total = 70 * 16000
        val windows = planSherpaWindows(listOf(SherpaWindow(0, total)), total)
        assertEquals(listOf(320000, 320000, 320000, 160000), windows.map { it.end - it.start })
        assertEquals(0, windows.first().start)
        assertEquals(total, windows.last().end)
        assertTrue(windows.zipWithNext().all { (a, b) -> a.end == b.start })
    }
    @Test fun forcedCutUsesLowestFrameOnlyInLastFiveSeconds() {
        val total = 45 * 16000 + 13
        val scores = FloatArray((total + 511) / 512) { 0.9f }
        scores[14 * 16000 / 512] = 0f // Too early, even though lower.
        val low = 17 * 16000 / 512
        scores[low] = 0.1f
        scores[20 * 16000 / 512 + 1] = 0f // Beyond ceiling.
        val windows = planSherpaWindows(listOf(SherpaWindow(0, total)), total,
            padding = 0, probabilities = scores)
        assertEquals(low * 512 + 256, windows.first().end)
        assertEquals(0, windows.first().start)
        assertEquals(total, windows.last().end)
        assertEquals(total, windows.sumOf { it.end - it.start })
        assertTrue(windows.zipWithNext().all { (a, b) -> a.end == b.start })
        assertTrue(windows.all { it.end - it.start in 1..SHERPA_WINDOW_SAMPLES })
    }
    @Test fun flatScoresChooseLatestEligibleFrameAndShortTailStaysWhole() {
        val total = SHERPA_WINDOW_SAMPLES + 1
        val scores = FloatArray((total + 511) / 512) { 1f }
        val windows = planSherpaWindows(listOf(SherpaWindow(0, total)), total,
            padding = 0, probabilities = scores)
        assertEquals(listOf(SherpaWindow(0, 319744), SherpaWindow(319744, total)), windows)
        assertEquals(listOf(SherpaWindow(0, SHERPA_WINDOW_SAMPLES)), planSherpaWindows(
            listOf(SherpaWindow(0, SHERPA_WINDOW_SAMPLES)), SHERPA_WINDOW_SAMPLES,
            probabilities = FloatArray(625) { 0.5f }))
    }
    @Test fun probabilityCutsPreservePaddedMergedRegionsAndSeparateSilence() {
        val total = 70 * 16000 + 7
        val speech = listOf(SherpaWindow(123, 25 * 16000),
            SherpaWindow(25 * 16000 + 1000, 40 * 16000),
            SherpaWindow(50 * 16000, total))
        val scores = FloatArray((total + 511) / 512) { (it % 73) / 73f }
        val windows = planSherpaWindows(speech, total, probabilities = scores)
        val baseline = planSherpaWindows(speech, total)
        // The selected cut cannot change which original samples are owned.
        for (sample in 0 until total) {
            assertEquals(baseline.any { sample >= it.start && sample < it.end },
                windows.any { sample >= it.start && sample < it.end })
        }
        assertTrue(windows.zipWithNext().all { (a, b) -> a.end <= b.start })
        assertTrue(windows.all { it.end - it.start <= SHERPA_WINDOW_SAMPLES })
    }
    @Test fun malformedProbabilityFramesFailInsteadOfChoosingACut() {
        for (scores in listOf(floatArrayOf(Float.NaN), floatArrayOf(-0.1f),
            floatArrayOf(1.1f), FloatArray(2))) {
            try {
                planSherpaWindows(listOf(SherpaWindow(0, 512)), 512, probabilities = scores)
                fail("Invalid frame scores accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun paddingClampsAndMergesWithoutDuplicatingEdges() {
        assertEquals(listOf(SherpaWindow(0, 5000)), planSherpaWindows(
            listOf(SherpaWindow(0, 1000), SherpaWindow(2000, 5000)), 5000))
        assertEquals(listOf(SherpaWindow(400, 2600)), planSherpaWindows(
            listOf(SherpaWindow(2000, 2400)), 2600))
    }
    @Test fun probabilityGateKeepsContinuousSpeechAndFinalPartialFrame() {
        val total = 70 * 16000 + 13
        var frames = 0
        val speech = detectSherpaSpeech(FloatArray(total)) {
            assertEquals(512, it.size)
            frames++
            1f
        }
        assertEquals((total + 511) / 512, frames)
        assertEquals(listOf(SherpaWindow(0, total)), speech)
        assertEquals(total, planSherpaWindows(speech, total).sumOf { it.end - it.start })
        assertTrue(detectSherpaSpeech(FloatArray(16000)) { 0f }.isEmpty())
    }
    @Test fun silenceBoundariesSeparateSpeechAndVadFailureEscapes() {
        var frame = 0
        val regions = detectSherpaSpeech(FloatArray(60 * 512)) {
            val i = frame++
            if (i < 10 || i >= 40) 1f else 0f
        }
        assertEquals(listOf(SherpaWindow(0, 5120), SherpaWindow(20480, 30720)), regions)
        try {
            detectSherpaSpeech(FloatArray(512)) { error("VAD failed") }
            fail()
        } catch (_: IllegalStateException) { }
    }
    @Test fun silenceDoesNotDecode() {
        assertEquals("", decodeSherpaWindows(FloatArray(100), planSherpaWindows(emptyList(), 100)) {
            error("Silence must never decode")
        })
        assertTrue(planSherpaWindows(emptyList(), 0).isEmpty())
    }
    @Test fun repeatedTextIsRetainedAndTemporaryPcmCleared() {
        val copies = ArrayList<FloatArray>()
        assertEquals("again again", decodeSherpaWindows(floatArrayOf(1f, 1f),
            listOf(SherpaWindow(0, 1), SherpaWindow(1, 2))) { copies.add(it); " again " })
        assertTrue(copies.all { it.all { sample -> sample == 0f } })
    }
    @Test fun middleWindowFailurePoisonsWorkerAndReturnsNoPartialText() {
        var calls = 0
        val engine = object : SherpaEngine {
            override fun decode(samples: FloatArray) = decodeSherpaWindows(samples,
                listOf(SherpaWindow(0, 1), SherpaWindow(1, 2), SherpaWindow(2, 3))) {
                calls++
                if (calls == 2) error("decode failed")
                "partial"
            }
            override fun close() = Unit
        }
        val worker = SherpaWorker { _, _ -> engine }
        worker.execute(SherpaSession.LOAD, 1, 0, "parakeet", "private", null)
        worker.execute(SherpaSession.FEED, 1, 1, null, null, FloatArray(3))
        var result: String? = null
        try { result = worker.execute(SherpaSession.STOP, 1, 2, null, null, null); fail() }
        catch (_: IllegalStateException) { }
        assertNull(result)
        assertEquals(2, calls)
        try { worker.execute(SherpaSession.LOAD, 2, 0, "parakeet", "private", null); fail() }
        catch (_: IllegalStateException) { }
    }
}
