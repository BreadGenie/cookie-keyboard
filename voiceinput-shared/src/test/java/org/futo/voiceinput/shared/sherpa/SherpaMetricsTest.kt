package org.futo.voiceinput.shared.sherpa

import org.junit.Assert.*
import org.junit.Test

class SherpaMetricsTest {
    @Test fun coldLoadAndRecordingCountersHaveSeparateLifetimes() {
        var time = 0L
        var pss = 100L
        val metrics = SherpaMetrics({ time }, { pss })
        metrics.coldLoad { time += 123 }
        metrics.beginRecording()
        metrics.window { time += 10; pss = 200 }
        metrics.window { time += 20; pss = 150 }
        assertArrayEquals(longArrayOf(123, 2, 30, 200, 30, 0), metrics.values())
        metrics.beginRecording()
        assertArrayEquals(longArrayOf(123, 0, 0, 200, 0, 0), metrics.values())
        assertEquals("phase=recording_decode engine=parakeet cold_model_load_ms=123 windows=0 decode_ms=0 asr_sampled_peak_pss_kb=200 tail_decode_ms=0 windows_during_recording=0 backend=cpu",
            sherpaDiagnostic(SherpaSession.STOP, metrics.values()))
        assertTrue(sherpaDiagnostic(SherpaSession.LOAD, metrics.values()).startsWith("phase=model_load engine=parakeet"))
    }
    @Test fun unavailablePssDoesNotFailRecognitionAndFailedWindowIsNotSuccessful() {
        var time = 0L
        val metrics = SherpaMetrics({ time }, { error("unavailable") })
        metrics.coldLoad { time += 7 }
        try { metrics.window { error("decode failed") }; fail() } catch (_: IllegalStateException) { }
        assertArrayEquals(longArrayOf(7, 0, 0, -1, 0, 0), metrics.values())
    }
    @Test fun incrementalAndTailCountersAreSeparateAndRowsIdentifyCpu() {
        var now = 0L
        val metrics = SherpaMetrics({ now }, { -1 })
        metrics.beginRecording()
        metrics.window(true) { now += 13 }
        metrics.window(false) { now += 7 }
        assertEquals(1L, metrics.windowsDuringRecording)
        assertEquals(7L, metrics.tailDecodeMs)
        assertEquals(20L, metrics.decodeMs)
        val row = sherpaDiagnostic(SherpaSession.STOP, metrics.values())
        assertTrue(row.contains("backend=cpu"))
        assertFalse(row.contains("backend_requested=fast"))
        assertTrue(row.contains("tail_decode_ms=7 windows_during_recording=1"))
    }

}
