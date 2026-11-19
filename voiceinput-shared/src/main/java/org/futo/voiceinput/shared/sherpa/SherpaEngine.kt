package org.futo.voiceinput.shared.sherpa

import com.k2fsa.sherpa.onnx.*
import java.io.File

/** Only this factory knows model-specific configuration. Called exclusively in :asr. */
internal interface SherpaEngine {
    fun setHotwords(hotwords: String) { require(hotwords.isEmpty()) }
    fun decode(samples: FloatArray): String
    fun close()
}
internal interface SherpaStreamingEngine : SherpaEngine {
    fun begin()
    fun feed(samples: FloatArray)
    fun finish(): String
}
internal object SherpaModels {
    fun files(kind: String, directory: File): List<File> {
        require(kind == "parakeet")
        return listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "silero_vad.onnx", "bpe.vocab")
            .map { File(directory, it).also { f -> require(f.isFile && f.length() > 0) } }
    }
    fun load(kind: String, directory: File, metrics: SherpaMetrics): SherpaEngine {
        files(kind, directory)
        val recognizer = OfflineRecognizer(config = config(directory))
        return engine(recognizer, directory, metrics)
    }
    fun config(directory: File): OfflineRecognizerConfig {
        val (encoder, decoder, joiner, tokens) = files("parakeet", directory)
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = encoder.path, decoder = decoder.path, joiner = joiner.path),
                modelingUnit = "bpe", bpeVocab = File(directory, "bpe.vocab").path,
                tokens = tokens.path, numThreads = 4, debug = false, provider = "cpu", modelType = "nemo_transducer"),
            decodingMethod = "modified_beam_search", maxActivePaths = 4, hotwordsScore = 2.0f, hotwordsFile = "")
    }
    private fun engine(recognizer: OfflineRecognizer, directory: File, metrics: SherpaMetrics): SherpaEngine {
        return object : SherpaStreamingEngine {
            private var hotwords = ""
            override fun setHotwords(hotwords: String) {
                check(recording == null)
                this.hotwords = SherpaHotwords.validate(hotwords)
            }
            private var vad: Vad? = null
            private var recording: SherpaIncremental? = null
            override fun begin() {
                check(recording == null)
                metrics.beginRecording()
                vad = Vad(config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = File(directory, "silero_vad.onnx").path,
                        threshold = 0.5f, minSilenceDuration = 0.5f,
                        minSpeechDuration = 0.25f, windowSize = 512,
                        maxSpeechDuration = 20f),
                    sampleRate = 16000, numThreads = 1, provider = "cpu", debug = false))
                recording = SherpaIncremental({ checkNotNull(vad).compute(it) },
                    { pcm, during -> metrics.window(during) { decodeWindow(pcm) } })
            }
            override fun feed(samples: FloatArray) = checkNotNull(recording).feed(samples)
            override fun finish(): String {
                try { return checkNotNull(recording).finish() }
                finally { recording = null; vad?.release(); vad = null }
            }
            override fun decode(samples: FloatArray): String {
                begin(); feed(samples); return finish()
            }

            private fun decodeWindow(samples: FloatArray): SherpaTokens {
                check(samples.size <= SHERPA_WINDOW_SAMPLES + 2 * SHERPA_CONTEXT_SAMPLES)
                return decodeSherpaTokenStream(hotwords,
                    { hints -> if(hints.isEmpty()) recognizer.createStream() else recognizer.createStream(hints) },
                    { stream ->
                        stream.acceptWaveform(samples, 16000)
                        recognizer.decode(stream)
                        recognizer.getResult(stream).let { SherpaTokens(it.tokens, it.timestamps, it.text) }
                    }, { stream -> stream.release() })
            }
            override fun close() { recording?.clear(); vad?.release(); recognizer.release() }
        }
    }
}
