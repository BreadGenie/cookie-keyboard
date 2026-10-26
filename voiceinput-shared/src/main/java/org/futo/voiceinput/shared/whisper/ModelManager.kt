package org.futo.voiceinput.shared.whisper

import android.content.Context
import org.futo.voiceinput.shared.ggml.WhisperGGML
import org.futo.voiceinput.shared.opencl.OpenClFastSession
import org.futo.voiceinput.shared.types.ModelLoader


class ModelManager(
    val context: Context
) {
    private val loadedModels: HashMap<Any, WhisperGGML> = hashMapOf()

    /**
     * v21 shared fast-mode resident-token owner. Scope proof: this manager
     * lives in VoiceInputPersistentState (one per keyboard session, across
     * every voice window), while RecognizerView/AudioRecognizer/
     * MultiModelRunner are constructed fresh per window. Successive
     * dictations therefore share one warm token while the warm lease lives;
     * [cleanUp]/keyboard close retires it. Never a global static: process
     * death starts empty and cold-verifies.
     */
    internal val fastResident = OpenClFastSession()

    fun obtainModel(model: ModelLoader): WhisperGGML {
        val key = model.key(context)
        if (!loadedModels.contains(key)) {
            loadedModels[key] = model.loadGGML(context)
        }

        return loadedModels[key]!!
    }

    fun cancelAll(preserveFastResidency: Boolean = false) {
        loadedModels.forEach {
            it.value.cancel()
        }
        fastResident.finishWindow(completed = preserveFastResidency)
    }

    suspend fun cleanUp() {
        for (model in loadedModels.entries) {
            model.value.cancel()
            model.value.close()
        }

        loadedModels.clear()
        fastResident.invalidate()
    }
}
