package org.futo.voiceinput.shared.sherpa

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SherpaWorkerTest {
    private class FakeEngine : SherpaEngine {
        var decodes = 0
        var decodeAction: () -> Unit = {}
        var fail = false
        var received = 0
        override fun decode(samples: FloatArray): String {
            decodes++; received = samples.size; decodeAction()
            if(fail) throw OutOfMemoryError()
            return "final"
        }
        override fun close() = Unit
    }
    private class Harness {
        val engine = FakeEngine()
        var loads = 0
        var cancelled = false
        var releases = mutableListOf<Boolean>()
        var loadAction: () -> Unit = {}
        val worker = SherpaWorker { _, _ -> loads++; loadAction(); engine }
        val transport = object : SherpaTransport {
            override fun request(op: Int, generation: Long, sequence: Long, kind: String?, path: String?, samples: FloatArray?, hotwords: String) =
                worker.execute(op, generation, sequence, kind, path, samples?.copyOf(), hotwords)
            override fun cancel() { cancelled = true }
        }
        fun work(g: Long = 1) = SherpaWork(g, "parakeet", "/private/model", { transport }, { releases.add(it) })
    }
    private fun fails(action: () -> Unit) {
        try { action(); fail("Expected rejection") } catch(_: SherpaFailure) { }
    }
    @Test fun boundedChunksAndExactlyOneFinalThenWarmReuse() {
        val h = Harness(); val first = h.work(); first.load()
        assertEquals("final", first.decode(FloatArray(32001)))
        assertEquals(32001, h.engine.received)
        val second = h.work(2); second.load(); assertEquals("final", second.decode(FloatArray(1)))
        assertEquals(1, h.loads); assertEquals(2, h.engine.decodes)
        assertEquals(listOf(false, false), h.releases)
    }
    @Test fun cancelDuringLoadDropsLateAck() {
        val h = Harness(); val w = h.work(); h.loadAction = { w.cancel() }
        fails { w.load() }; assertTrue(h.cancelled); fails { w.decode(FloatArray(1)) }
        assertEquals(0, h.engine.decodes)
    }
    @Test fun cancelDuringDecodeDropsLateText() {
        val h = Harness(); val w = h.work(); w.load(); h.engine.decodeAction = { w.cancel() }
        fails { w.decode(FloatArray(1)) }; assertTrue(h.cancelled); assertFalse(h.releases.contains(false))
    }
    @Test fun deathOrOomDuringDecodeIsAnExplicitFailure() {
        val h = Harness(); val w = h.work(); w.load(); h.engine.fail = true
        fails { w.decode(FloatArray(1)) }; assertTrue(h.cancelled); assertEquals(listOf(true), h.releases)
        try { h.worker.execute(SherpaSession.LOAD, 2, 0, "parakeet", "/private/model", null); fail() }
        catch(_: IllegalStateException) { }
    }
    @Test fun binderDeathMidDecodeFailsWithoutFinal() {
        var killed = false
        val remote = object : SherpaTransport {
            override fun request(op: Int, generation: Long, sequence: Long, kind: String?, path: String?, samples: FloatArray?, hotwords: String): String? {
                if(op == SherpaSession.STOP) throw SherpaFailure() // DeadObjectException at transport boundary
                return null
            }
            override fun cancel() { killed = true }
        }
        val releases = mutableListOf<Boolean>()
        val w = SherpaWork(1, "parakeet", "/private/model", { remote }, { releases.add(it) })
        w.load(); fails { w.decode(FloatArray(1)) }
        assertTrue(killed); assertEquals(listOf(true), releases)
    }
    @Test fun cancellationBeforeConnectCompletesCannotLoad() {
        lateinit var work: SherpaWork
        var requests = 0
        var killed = false
        val remote = object : SherpaTransport {
            override fun connect() { work.cancel() }
            override fun request(op: Int, generation: Long, sequence: Long, kind: String?, path: String?, samples: FloatArray?, hotwords: String): String? {
                requests++; return null
            }
            override fun cancel() { killed = true }
        }
        work = SherpaWork(1, "parakeet", "/private/model", { remote }, {})
        fails { work.load() }; assertEquals(0, requests); assertTrue(killed)
    }
    @Test fun duplicateStopCannotDecodeTwice() {
        val h = Harness(); val w = h.work(); w.load(); w.decode(FloatArray(1))
        fails { w.decode(FloatArray(0)) }; assertEquals(1, h.engine.decodes)
    }
    @Test fun staleGenerationResultAndDuplicateAckAreRejected() {
        val s = SherpaSession(2); s.begin(SherpaSession.LOAD, 0)
        try { s.accept(1, 0, null); fail() } catch(_: IllegalStateException) { }
        s.accept(2, 0, null)
        try { s.accept(2, 0, null); fail() } catch(_: IllegalStateException) { }
        s.invalidate()
        try { s.accept(2, 0, null); fail() } catch(_: IllegalStateException) { }
    }
    @Test fun missingModelPathIsRejectedBeforeJniLoad() {
        try { SherpaModels.files("parakeet", File("/nonexistent-cookie-sherpa-model")); fail() }
        catch(_: IllegalArgumentException) { }
    }
    @Test fun replacementWaitsForConfirmedDeath() {
        val gate = SherpaLease(); assertTrue(gate.acquire(1)); assertFalse(gate.acquire(2))
        // Cancel, timeout and service disconnection do not release the gate.
        assertFalse(gate.acquire(3)); gate.died(1); assertTrue(gate.acquire(4))
    }
    @Test fun nonfiniteAndOversizedAudioFailsWithoutSuccessfulTruncation() {
        val h = Harness(); val w = h.work(); w.load()
        fails { w.decode(floatArrayOf(Float.NaN)) }; assertEquals(0, h.engine.decodes)
        val s = SherpaSession(1); s.begin(SherpaSession.LOAD, 0); s.accept(1, 0, null)
        try { s.begin(SherpaSession.FEED, 1, SherpaSession.CHUNK + 1); fail() }
        catch(_: IllegalStateException) { }
    }
    @Test fun streamingFeedDecodesBeforeStopAndStopIsExactlyOnce() {
        var feeds = 0; var stops = 0; var begins = 0
        val engine = object : SherpaStreamingEngine {
            override fun begin() { begins++ }
            override fun feed(samples: FloatArray) { feeds++; assertEquals(0.5f, samples[0]) }
            override fun finish(): String { stops++; return "private final" }
            override fun decode(samples: FloatArray): String = error("batch route")
            override fun close() = Unit
        }
        val worker = SherpaWorker { _, _ -> engine }
        val remote = object : SherpaTransport {
            override fun request(op: Int, generation: Long, sequence: Long, kind: String?, path: String?, samples: FloatArray?, hotwords: String) =
                worker.execute(op, generation, sequence, kind, path, samples?.copyOf(), hotwords)
            override fun cancel() = Unit
        }
        val w = SherpaWork(1, "parakeet", "/model", { remote }, {})
        w.load(); w.feed(floatArrayOf(0.5f)); w.feed(floatArrayOf(0.5f))
        assertEquals(2, feeds); assertEquals(0, stops)
        assertEquals("private final", w.finish()); fails { w.finish() }
        assertEquals(1, stops); assertEquals(1, begins)
    }
    @Test fun cancelAndDeathDuringRecordingInvalidateLateFeedAndPreventStop() {
        for(death in listOf(false, true)) {
            lateinit var work: SherpaWork
            var stops = 0; var killed = false
            val remote = object : SherpaTransport {
                override fun request(op: Int, generation: Long, sequence: Long, kind: String?, path: String?, samples: FloatArray?, hotwords: String): String? {
                    if(op == SherpaSession.FEED) {
                        if(death) throw SherpaFailure() else work.cancel()
                    }
                    if(op == SherpaSession.STOP) { stops++; return "late" }
                    return null
                }
                override fun cancel() { killed = true }
            }
            work = SherpaWork(1, "parakeet", "/model", { remote }, {})
            work.load(); fails { work.feed(FloatArray(10)) }; fails { work.finish() }
            assertEquals(0, stops); assertTrue(killed)
        }
    }

}
