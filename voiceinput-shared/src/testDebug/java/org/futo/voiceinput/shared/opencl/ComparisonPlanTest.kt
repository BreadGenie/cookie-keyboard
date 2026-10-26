package org.futo.voiceinput.shared.opencl

import org.junit.Assert.*
import org.junit.Test

class ComparisonPlanTest {
    @Test fun eachModeOccupiesEachPositionOnce() {
        for (position in 0..2) assertEquals(ComparisonPlan.modes.toSet(), (0..2).map { ComparisonPlan.order(it)[position] }.toSet())
    }
    @Test fun failedMissingOrDuplicateArmsExcludeRound() {
        val full = ComparisonPlan.modes.map { ComparisonPlan.Sample(0, it, 10.0, null) }
        assertEquals(listOf(0), ComparisonPlan.matched(full))
        assertTrue(ComparisonPlan.matched(full.dropLast(1)).isEmpty())
        assertTrue(ComparisonPlan.matched(full + full.first()).isEmpty())
        assertTrue(ComparisonPlan.matched(full.dropLast(1) + ComparisonPlan.Sample(0, ComparisonPlan.modes.last(), null, "cancelled")).isEmpty())
    }
    @Test fun hashAndTimingDoNotRequireTranscriptRetention() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ComparisonPlan.hash(byteArrayOf()))
        assertEquals(1.25, ComparisonPlan.elapsedMs(7_000_000, 8_250_000), 0.0)
        assertEquals(20.0, ComparisonPlan.median(listOf(30.0, 10.0, 20.0))!!, 0.0)
        assertNull(ComparisonPlan.median(emptyList()))
    }
}
