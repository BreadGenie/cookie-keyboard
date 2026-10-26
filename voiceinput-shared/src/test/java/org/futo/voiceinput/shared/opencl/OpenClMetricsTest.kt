package org.futo.voiceinput.shared.opencl

import org.junit.Assert.*
import org.junit.Test

class OpenClMetricsTest {
    @Test fun ordinaryDiagnosticsExposeWallIntervalsWithoutWorkerSums() {
        val values = DoubleArray(40) { 1.0 }
        values[0] = Double.NaN
        val row = OpenClMetrics.format(values)
        assertTrue(row.contains("full_ms=1.0"))
        assertTrue(row.contains("fa_executed=1.0"))
        assertFalse(row.contains("last_encode_ms"))
        assertFalse(row.contains("decode_combined_ms"))
        assertFalse(row.contains("sampling_inclusive_ms"))
        assertFalse(row.contains("logits_wall_nested_ms"))
    }

    @Test fun guardDiagnosticsNeverCopyPackedTranscriptOrTokenVectors() {
        val packed = "guard=FAST_ACCEPT retry=0 retry_beam=0 fast_ms=50 total_ms=55 fast_tokens=7 fast_nospeech=0.1,0.2\nprivate words\nprivate words"
        assertEquals("guard=FAST_ACCEPT retry=0 retry_beam=0 fast_ms=50 total_ms=55", OpenClMetrics.guard(packed))
        assertEquals("", OpenClMetrics.guard("<>CANCELLED<> flag"))
    }
}
