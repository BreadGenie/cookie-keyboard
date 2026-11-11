package org.futo.voiceinput.shared

import org.futo.voiceinput.shared.ggml.InvalidModelException
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import org.futo.voiceinput.shared.whisper.MultiModelRunner

/** Stable persisted identifiers, independent of display names or enum order. */
enum class RecognitionEngine(val preferenceValue: String) {
    Whisper("whisper"), Parakeet("parakeet"), CohereTranscribe("cohere_transcribe");

    companion object {
        fun fromPreference(value: String?, parakeetReady: Boolean = false): RecognitionEngine =
            entries.firstOrNull { it.preferenceValue == value } ?:
            if (value.isNullOrEmpty() && parakeetReady) Parakeet else Whisper
    }
}

/** A recording of mono 16 kHz float samples in, one final transcript out.
 * Internal progress must never insert text into an editor. Recording and final
 * delivery remain owned by AudioRecognizer and VoiceResultDelivery respectively.
 */
interface FinalRecognizer {
    suspend fun preload()
    suspend fun recognize(samples: FloatArray, callback: ModelInferenceCallback): String
    fun cancel(preserveFastResidency: Boolean = false)
}

/** Optional final-only streaming. feed must copy or consume before returning. */
interface StreamingFinalRecognizer : FinalRecognizer {
    /** Prepare a fresh capture queue after a previous reset or completed session. */
    fun beginRecording()
    fun feed(samples: FloatArray)
    suspend fun finish(callback: ModelInferenceCallback): String
}

/** The existing Whisper/OpenCL/Fast policy and callbacks stay in MultiModelRunner. */
internal class WhisperFinalRecognizer(
    private val runner: MultiModelRunner,
    private val runConfiguration: MultiModelRunConfiguration,
    private val decodingConfiguration: DecodingConfiguration
) : FinalRecognizer {
    override suspend fun preload() = runner.preload(runConfiguration, decodingConfiguration)
    override suspend fun recognize(samples: FloatArray, callback: ModelInferenceCallback): String =
        runner.run(samples, runConfiguration, decodingConfiguration, callback)
    override fun cancel(preserveFastResidency: Boolean) = runner.cancelAll(preserveFastResidency)
}

/** No runtime dependency or silent Whisper substitution for an unprovisioned engine. */
internal fun createFinalRecognizer(
    engine: RecognitionEngine,
    sherpa: (() -> FinalRecognizer)? = null,
    whisper: () -> FinalRecognizer
): FinalRecognizer = when (engine) {
    RecognitionEngine.Whisper -> whisper()
    else -> sherpa?.takeIf { engine == RecognitionEngine.Parakeet }?.invoke() ?: object : FinalRecognizer {
        override suspend fun preload(): Unit = throw InvalidModelException()
        override suspend fun recognize(samples: FloatArray, callback: ModelInferenceCallback): String =
            throw InvalidModelException()
        override fun cancel(preserveFastResidency: Boolean) = Unit
    }
}
