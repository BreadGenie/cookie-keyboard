package org.futo.inputmethod.latin.uix.actions

import kotlinx.coroutines.*
import org.futo.voiceinput.shared.FinalRecognizer
import org.futo.voiceinput.shared.types.*
import org.junit.Assert.*
import org.junit.Test

class RecognizerSeamDeliveryTest {
    private val callback = object : ModelInferenceCallback {
        override fun updateStatus(state: InferenceState) = Unit
        override fun languageDetected(language: Language) = Unit
        override fun partialResult(string: String) = Unit
    }
    private class FakeEngine(private val decode: suspend () -> String) : FinalRecognizer {
        var cancelled = false
        override suspend fun preload() = Unit
        override suspend fun recognize(samples: FloatArray, callback: ModelInferenceCallback) = decode()
        override fun cancel(preserveFastResidency: Boolean) { cancelled = true }
    }
    private class Delivery {
        val queue = ArrayDeque<() -> Unit>()
        val inserts = mutableListOf<String>()
        var completed = 0
        val target = VoiceResultDelivery({ queue.addLast(it) }, { it },
            { inserts.add(it) }, {}, { completed++ })
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
    }

    @Test fun missingEngineKeyDefersToVerifiedModelReadiness() {
        val setting = org.futo.inputmethod.latin.uix.VOICE_INPUT_ENGINE
        assertEquals("voice_input_engine", setting.key.name)
        assertEquals("", setting.default)
        assertEquals(org.futo.voiceinput.shared.RecognitionEngine.Whisper,
            org.futo.voiceinput.shared.RecognitionEngine.fromPreference(setting.default, false))
        assertEquals(org.futo.voiceinput.shared.RecognitionEngine.Parakeet,
            org.futo.voiceinput.shared.RecognitionEngine.fromPreference(setting.default, true))
    }

    @Test fun fakeFinalReachesExistingDeliveryExactlyOnce() = runBlocking {
        val delivery = Delivery()
        val engine = FakeEngine { "final" }
        val text = engine.recognize(floatArrayOf(), callback)
        delivery.target.deliver(text)
        delivery.target.deliver(text)
        delivery.drain()
        assertEquals(listOf("final"), delivery.inserts)
        assertEquals(1, delivery.completed)
    }

    @Test fun cancellationRejectsEvenANonCooperativeLateDecode() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val engine = FakeEngine {
            entered.complete(Unit)
            // Simulate a blocking worker that returns text even after cancellation.
            try { withContext(NonCancellable) { finish.await() } }
            catch (_: CancellationException) { }
            "late"
        }
        val delivery = Delivery()
        val job = launch { delivery.target.deliver(engine.recognize(floatArrayOf(), callback)) }
        entered.await()
        delivery.target.invalidate()
        engine.cancel()
        job.cancel()
        finish.complete(Unit)
        job.join()
        delivery.drain()
        assertTrue(engine.cancelled)
        assertTrue(delivery.inserts.isEmpty())
        assertEquals(0, delivery.completed)
    }

    @Test fun blankFakeFinalDoesNotInsert() = runBlocking {
        val delivery = Delivery()
        delivery.target.deliver(FakeEngine { " \n\t" }.recognize(floatArrayOf(), callback))
        delivery.drain()
        assertTrue(delivery.inserts.isEmpty())
        assertEquals(1, delivery.completed)
    }
}
