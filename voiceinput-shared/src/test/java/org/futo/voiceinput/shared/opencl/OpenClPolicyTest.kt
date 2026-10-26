package org.futo.voiceinput.shared.opencl

import org.junit.Assert.*
import org.junit.Test

class OpenClPolicyTest {
    @Test fun stockIsDefaultAndUnsupportedLanguagesKeepStock() {
        assertFalse(OpenClPolicy.eligible(false, setOf("en"), false, true))
        assertFalse(OpenClPolicy.eligible(true, emptySet(), false, true))
        assertFalse(OpenClPolicy.eligible(true, setOf("en", "de"), false, true))
        assertFalse(OpenClPolicy.eligible(true, setOf("en"), true, true))
        assertFalse(OpenClPolicy.eligible(true, setOf("en"), false, false))
        assertTrue(OpenClPolicy.eligible(true, setOf("en"), false, true))
    }
    @Test fun cancellationRejectsLatePartialsResultsAndErrors() {
        val request = OpenClDelivery()
        assertTrue(request.finish()) // cancellation
        assertFalse(request.isActive())
        assertFalse(request.finish()) // late success
        assertFalse(request.finish()) // late failure
    }
    @Test fun duplicateOrRacingTerminalRepliesDeliverOnce() {
        val request = OpenClDelivery()
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..20).map { Thread { if (request.finish()) accepted.incrementAndGet() } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(1, accepted.get())
    }
}
