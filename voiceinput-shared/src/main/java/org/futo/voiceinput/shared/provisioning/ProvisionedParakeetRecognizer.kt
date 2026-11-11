package org.futo.voiceinput.shared.provisioning

import android.content.Context
import org.futo.voiceinput.shared.FinalRecognizer
import org.futo.voiceinput.shared.ggml.InvalidModelException
import org.futo.voiceinput.shared.sherpa.SherpaFinalRecognizer
import org.futo.voiceinput.shared.types.ModelInferenceCallback

/** A deleted/unpublished model never substitutes Whisper, even with a warm worker. */
class ProvisionedParakeetRecognizer(context: Context, glossary: () -> List<String> = { emptyList() }) : org.futo.voiceinput.shared.StreamingFinalRecognizer {
    private val store = ParakeetStore(context.filesDir)
    private val delegate = SherpaFinalRecognizer(context, store.directory, glossary = glossary)
    override fun beginRecording() = delegate.beginRecording()
    override fun feed(samples: FloatArray) = delegate.feed(samples)
    override suspend fun finish(callback: ModelInferenceCallback): String {
        if(!store.isReady()) throw InvalidModelException()
        return delegate.finish(callback)
    }
    override suspend fun preload() {
        if(!store.isReady()) throw InvalidModelException()
        delegate.preload()
    }
    override suspend fun recognize(samples: FloatArray, callback: ModelInferenceCallback): String {
        if(!store.isReady()) throw InvalidModelException()
        return delegate.recognize(samples, callback)
    }
    override fun cancel(preserveFastResidency: Boolean) = delegate.cancel(preserveFastResidency)
}
