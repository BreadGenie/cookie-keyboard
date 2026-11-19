package org.futo.inputmethod.latin.whistle

import org.junit.Assert.*
import org.junit.Test

class WhistleProtocolTest {
    private fun json(text: String = "hello", pending: String = "", received: String = "1") = """{"text":"$text","pending":"$pending","words":[],"language":"en","received":$received,"pass_ms":1} """
    private fun ready() = WhistleSession(7).apply { begin(1, 0); accept(7, 0, null) }
    private fun rejects(action: () -> Unit) { try { action(); fail("Expected rejection") } catch(_: IllegalStateException) {} }
    @Test fun orderedCommittedPiecesAndFinalTail() {
        val s = ready(); s.begin(2, 1); assertNull(s.accept(7, 1, json(pending = "provisional")))
        s.begin(3, 2); assertEquals("hello tail", s.accept(7, 2, json("tail")))
        rejects { s.begin(3, 3) }
    }
    @Test fun cancellationAtEveryBoundary() {
        for(op in listOf(1, 2, 3)) {
            val s = if(op == 1) WhistleSession(7) else ready()
            val seq = if(op == 1) 0L else 1L
            s.invalidate(); rejects { s.begin(op, seq) }
            val t = if(op == 1) WhistleSession(7) else ready()
            t.begin(op, seq); t.invalidate(); assertNull(t.accept(7, seq, if(op == 1) null else json()))
            val u = if(op == 1) WhistleSession(7) else ready()
            u.begin(op, seq); u.accept(7, seq, if(op == 1) null else json()); u.invalidate()
            assertEquals(WhistleSession.State.INVALID, u.state)
        }
    }
    @Test fun deathRejectsLateRepliesAndReuse() {
        val s = ready(); s.begin(2, 1); s.died(); assertNull(s.accept(7, 1, json())); rejects { s.begin(1, 0) }
    }
    @Test fun wrongGenerationSequenceAndBackpressure() {
        ready().apply { rejects { begin(2, 2) } }
        ready().apply { begin(2, 1); rejects { begin(2, 1) }; rejects { accept(8, 1, json()) } }
        ready().apply { begin(2, 1); rejects { accept(7, 2, json()) } }
    }
    @Test fun malformedSchemaAndOutputFailWholeSession() {
        for(raw in listOf("{}", "[]", "garbage", json().replace("\"hello\"", "1"), json(received = "-1"), json(received = "\"1\""), json().replace("\"en\"", "\"de\""), json().replace("[]", "{}"), json().replace("[]", "[{}]"), json().replace("\"pass_ms\":1", "\"pass_ms\":null"), json("x".repeat(65537)), json("x".repeat(262144)))) {
            val s = ready(); s.begin(2, 1); rejects { s.accept(7, 1, raw) }; assertEquals(WhistleSession.State.INVALID, s.state)
        }
    }
    @Test fun finalPendingAndRegressingDurationRejected() {
        ready().apply { begin(3, 1); rejects { accept(7, 1, json(pending = "tail")) } }
        ready().apply { begin(2, 1); accept(7, 1, json()); begin(3, 2); rejects { accept(7, 2, json(received = "0")) } }
    }
    @Test fun transcriptLimitIsErrorNotTruncation() {
        val s = ready(); s.begin(2, 1); s.accept(7, 1, json("x".repeat(65536))); s.begin(3, 2); rejects { s.accept(7, 2, json("y")) }
        assertEquals(WhistleSession.State.INVALID, s.state)
    }
}
