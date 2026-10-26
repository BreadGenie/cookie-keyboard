package org.futo.voiceinput.shared.opencl

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OpenClRoutingTest {
    @Test fun managedLoadFailureUsesStockExactlyOnce() = runBlocking {
        var gpu = 0; var cpu = 0; var notice = 0
        val result = OpenClRouting.run(
            { gpu++; throw OpenClStartupException() }, { cpu++; "stock transcript" }, { notice++ })
        assertEquals("stock transcript", result)
        assertEquals(1, gpu); assertEquals(1, cpu); assertEquals(1, notice)
    }
    @Test fun successfulGpuResultNeverInvokesStock() = runBlocking {
        val prompt = "(Glossary: OnePlus, T3 Code)"
        var observed = ""
        val result = OpenClRouting.run({ observed = prompt; "GPU transcript" },
            { error("unexpected stock") }, { error("unexpected fallback") })
        assertEquals(prompt, observed); assertEquals("GPU transcript", result)
    }
    @Test fun decodeOrProcessDeathDoesNotRetryAndCannotBecomeText() = runBlocking {
        try {
            OpenClRouting.run({ throw OpenClRecognitionException() },
                { error("unsafe retry") }, { error("unsafe fallback") })
            fail("expected recognition error")
        } catch (_: OpenClRecognitionException) { }
    }
    @Test fun cancelDuringLoadPreventsFallbackAndDelivery() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var stock = 0; var delivered = 0
        val task = launch {
            OpenClRouting.run({ entered.complete(Unit); awaitCancellation() },
                { stock++; "late" }, { error("cancel must not fallback") })
            delivered++
        }
        entered.await(); task.cancelAndJoin()
        assertEquals(0, stock); assertEquals(0, delivered)
    }
    @Test fun cancellationBeforeStartupFailurePreventsRetry() = runBlocking {
        val task = launch {
            OpenClRouting.run({ currentCoroutineContext().cancel(); throw OpenClStartupException() },
                { error("cancelled request retried") }, { error("cancelled request notified") })
            error("cancelled request delivered")
        }
        task.join(); assertTrue(task.isCancelled)
    }
}
