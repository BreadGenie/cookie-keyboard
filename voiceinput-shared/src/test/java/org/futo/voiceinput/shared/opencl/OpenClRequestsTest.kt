package org.futo.voiceinput.shared.opencl

import org.junit.Assert.*
import org.junit.Test

class OpenClRequestsTest {
    @Test fun queuedCancelCannotAbortActiveOrResetIntoNextSession() {
        val requests = OpenClRequests()
        val owner = Any()
        var resets = 0
        var aborts = 0
        assertTrue(requests.add("a", owner))
        assertTrue(requests.add("b", owner))
        assertTrue(requests.start("a") { resets++ })
        requests.cancel("b", owner) { aborts++ }
        assertEquals(0, aborts)
        requests.finish("a")
        assertFalse(requests.start("b") { resets++ })
        requests.finish("b")
        assertEquals(1, resets)
    }
    @Test fun foreignOrStaleCancelCannotKillAnotherClient() {
        val requests = OpenClRequests()
        val first = Any()
        val second = Any()
        var aborts = 0
        requests.add("a", first)
        assertFalse(requests.add("a", second))
        requests.start("a") {}
        requests.cancel("a", second) { aborts++ }
        assertFalse(requests.cancelled("a"))
        requests.cancel("a", first) { aborts++ }
        assertTrue(requests.cancelled("a"))
        requests.finish("a")
        requests.add("b", second)
        requests.start("b") {}
        requests.cancel("a", first) { aborts++ }
        assertFalse(requests.cancelled("b"))
        assertEquals(1, aborts)
    }
    @Test fun cancellationAfterResetIsNeverLost() {
        repeat(100) {
            val requests = OpenClRequests()
            val owner = Any()
            requests.add("a", owner)
            val entered = java.util.concurrent.CountDownLatch(1)
            var cancelled = false
            val start = Thread { requests.start("a") { entered.countDown(); Thread.yield(); cancelled = false } }
            val cancel = Thread { entered.await(); requests.cancel("a", owner) { cancelled = true } }
            start.start(); cancel.start(); start.join(); cancel.join()
            assertTrue(cancelled)
        }
    }
}
