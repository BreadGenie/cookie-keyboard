package org.futo.inputmethod.latin.whistle
import org.junit.Assert.*
import org.junit.Test
class ReclamationGateTest {
    @Test fun cancellationAndTimeoutNeverReleaseLease() {
        val gate = ReclamationGate(); val s = WhistleSession(1)
        assertTrue(gate.acquire(1)); s.invalidate()
        repeat(100) { assertFalse(gate.acquire(2)) }
        s.died(); assertFalse(gate.acquire(2)) // Session/result invalidation is independent of process evidence.
        gate.confirmedDeath(1); assertTrue(gate.acquire(2))
    }
    @Test fun staleDeathCannotReleaseReplacement() {
        val gate = ReclamationGate(); assertTrue(gate.acquire(1)); gate.confirmedDeath(1); assertTrue(gate.acquire(2))
        try { gate.confirmedDeath(1); fail() } catch(_: IllegalStateException) {}
        assertFalse(gate.acquire(3)); gate.confirmedDeath(2); assertTrue(gate.acquire(3))
    }
    @Test fun definitiveBindFailureAllowsRetry() {
        val gate = ReclamationGate(); assertTrue(gate.acquire(1)); gate.notStarted(1); assertTrue(gate.acquire(2))
    }
}
