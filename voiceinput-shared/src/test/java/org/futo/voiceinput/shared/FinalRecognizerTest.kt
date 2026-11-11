package org.futo.voiceinput.shared

import kotlinx.coroutines.runBlocking
import org.futo.voiceinput.shared.ggml.InvalidModelException
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.junit.Assert.*
import org.junit.Test

class FinalRecognizerTest {
    private val fake = object : FinalRecognizer {
        override suspend fun preload() = Unit
        override suspend fun recognize(samples: FloatArray, callback: ModelInferenceCallback) = "final"
        override fun cancel(preserveFastResidency: Boolean) = Unit
    }

    @Test fun missingAndUnknownPreferencesUseTheExistingRoute() {
        listOf(null, "", "future", "whisper").forEach {
            val engine = RecognitionEngine.fromPreference(it)
            assertEquals(RecognitionEngine.Whisper, engine)
            assertSame(fake, createFinalRecognizer(engine) { fake })
        }
    }

    @Test fun existingRunConfigurationDefaultsRemainWhisperWithOpenClAndFastOff() {
        val configuration = org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration(
            BUILTIN_ENGLISH_MODEL, emptyMap())
        assertEquals(RecognitionEngine.Whisper, configuration.engine)
        assertFalse(configuration.experimentalOpenCl)
        assertFalse(configuration.fastDictation)
    }

    @Test fun enginePreferencesRoundTripWithoutOrdinalStorage() {
        RecognitionEngine.entries.forEach {
            assertEquals(it, RecognitionEngine.fromPreference(it.preferenceValue))
        }
    }

    @Test fun unavailableEnginesDoNotLoadWhisper() = runBlocking {
        for (engine in listOf(RecognitionEngine.Parakeet, RecognitionEngine.CohereTranscribe)) {
            val recognizer = createFinalRecognizer(engine) { error("must not load Whisper") }
            try {
                recognizer.preload()
                fail("expected explicit unavailable model error")
            } catch (_: InvalidModelException) { }
            recognizer.cancel()
        }
    }
    @Test fun verifiedReadyDefaultAndExplicitChoicesAreResolvedIndependently() {
        assertEquals(RecognitionEngine.Parakeet, RecognitionEngine.fromPreference(null, true))
        assertEquals(RecognitionEngine.Parakeet, RecognitionEngine.fromPreference("", true))
        assertEquals(RecognitionEngine.Whisper, RecognitionEngine.fromPreference(null, false))
        assertEquals(RecognitionEngine.Whisper, RecognitionEngine.fromPreference("whisper", true))
        assertEquals(RecognitionEngine.Parakeet, RecognitionEngine.fromPreference("parakeet", false))
        assertEquals(RecognitionEngine.Whisper, RecognitionEngine.fromPreference("unknown", true))
    }

}
