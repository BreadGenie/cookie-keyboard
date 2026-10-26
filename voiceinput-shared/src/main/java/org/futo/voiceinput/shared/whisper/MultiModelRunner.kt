package org.futo.voiceinput.shared.whisper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import org.futo.voiceinput.shared.opencl.*
import org.futo.voiceinput.shared.types.openClFile
import org.futo.voiceinput.shared.ggml.BailLanguageException
import org.futo.voiceinput.shared.ggml.DecodingMode
import org.futo.voiceinput.shared.ggml.InferenceCancelledException
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.types.getLanguageFromWhisperString
import org.futo.voiceinput.shared.types.toWhisperString


data class MultiModelRunConfiguration(
    val primaryModel: ModelLoader,
    val languageSpecificModels: Map<Language, ModelLoader>,
    val experimentalOpenCl: Boolean = false,
    /** Opt-in everyday Fast dictation. Gated on [experimentalOpenCl]; default
     * off so existing saved settings never migrate. */
    val fastDictation: Boolean = false
)

data class DecodingConfiguration(
    val glossary: List<String>,
    val languages: Set<Language>,
    val suppressSymbols: Boolean
)

class MultiModelRunner(
    private val modelManager: ModelManager
) {
    /** v21: the fast resident-token lifecycle is owned by [modelManager]
     * (shared across this keyboard session's successive voice windows), not
     * by this per-window runner. A new window reuses the warm token while
     * the warm lease lives; v20 kept a private session here and cold-reloaded
     * every window. */
    private val fastSession get() = modelManager.fastResident

    private fun fastKey(file: java.io.File, runConfiguration: MultiModelRunConfiguration): String =
        runConfiguration.primaryModel.key(modelManager.context).toString() + "|" + file.absolutePath

    private fun fastEligible(runConfiguration: MultiModelRunConfiguration, decodingConfiguration: DecodingConfiguration, file: java.io.File?): Boolean =
        file != null && FastDictation.eligible(runConfiguration.experimentalOpenCl, runConfiguration.fastDictation,
            decodingConfiguration.languages.map { it.toWhisperString() }.toSet(),
            runConfiguration.languageSpecificModels.isNotEmpty(), true)

    suspend fun preload(runConfiguration: MultiModelRunConfiguration, decodingConfiguration: DecodingConfiguration) = coroutineScope {
        val file = runConfiguration.primaryModel.openClFile(modelManager.context)
        val fastModelEligible = fastEligible(runConfiguration, decodingConfiguration, file)
        val openClModelEligible = OpenClPolicy.eligible(runConfiguration.experimentalOpenCl,
            decodingConfiguration.languages.map { it.toWhisperString() }.toSet(),
            runConfiguration.languageSpecificModels.isNotEmpty(), file != null)
        // v44: the one bounded capability probe is awaited here (recording
        // start) only when the model/language gate would otherwise use OpenCL.
        // It is process-cached and serialized with inference in the service.
        val capability = if (runConfiguration.experimentalOpenCl && (fastModelEligible || openClModelEligible)) {
            OpenClCapabilityGate.ensure(modelManager.context.applicationContext)
        } else {
            OpenClCapabilityGate.peek()
        }
        val fastRuntimeEligible = fastModelEligible && capability?.supported == true
        val openClRuntimeEligible = OpenClPolicy.runtimeEligible(runConfiguration.experimentalOpenCl,
            capability, decodingConfiguration.languages.map { it.toWhisperString() }.toSet(),
            runConfiguration.languageSpecificModels.isNotEmpty(), file != null)
        if (fastRuntimeEligible) {
            try {
                FastDictationRunner(AndroidFastEngine(modelManager.context.applicationContext), fastSession)
                    .prepare(file!!, fastKey(file, runConfiguration))
            } catch (_: OpenClStartupException) {
                // Prewarming is opportunistic; run retains the existing single stock fallback.
                OpenClDiagnostics.record(modelManager.context, "mode_requested=fast mode_actual=none phase=prewarm startup_error=true")
            } catch (_: OpenClRecognitionException) {
                OpenClDiagnostics.record(modelManager.context, "mode_requested=fast mode_actual=none phase=prewarm service_error=true")
            } catch (_: InferenceCancelledException) { }
            return@coroutineScope
        }
        if (openClRuntimeEligible) {
            try {
                OpenClClient(modelManager.context.applicationContext).prepare(file!!)
            } catch (_: OpenClStartupException) {
                // Prewarming is opportunistic; run retains the existing single stock fallback.
                OpenClDiagnostics.record(modelManager.context, "backend=opencl phase=prewarm startup_error=true")
            } catch (_: OpenClRecognitionException) {
                OpenClDiagnostics.record(modelManager.context, "backend=opencl phase=prewarm service_error=true")
            } catch (_: InferenceCancelledException) { }
            fastSession.invalidate()
            return@coroutineScope
        }
        // v44: a disabled/missing/unsupported capability must not leave a warm
        // OpenCL lease or fast token behind, even if a restored preference says
        // the user previously opted in. The preference itself is never edited.
        fastSession.invalidate()
        OpenClResources.releaseIdle()
        val jobs = mutableListOf<Job>()

        jobs.add(launch(Dispatchers.Default) {
            modelManager.obtainModel(runConfiguration.primaryModel)
        })

        if (runConfiguration.languageSpecificModels.count() < 2) {
            runConfiguration.languageSpecificModels.forEach {
                jobs.add(launch(Dispatchers.Default) {
                    modelManager.obtainModel(it.value)
                })
            }
        }

        jobs.forEach { it.join() }
    }

    @Throws(InferenceCancelledException::class)
    suspend fun run(
        samples: FloatArray,
        runConfiguration: MultiModelRunConfiguration,
        decodingConfiguration: DecodingConfiguration,
        callback: ModelInferenceCallback
    ): String = coroutineScope {
        callback.updateStatus(InferenceState.LoadingModel)

        val allowedLanguages = decodingConfiguration.languages.map { it.toWhisperString() }.toTypedArray()
        val bailLanguages = runConfiguration.languageSpecificModels.filter { it.value != runConfiguration.primaryModel }.keys.map { it.toWhisperString() }.toTypedArray()

        val glossary = if(decodingConfiguration.glossary.isNotEmpty()) {
            "(Glossary: " + decodingConfiguration.glossary.joinToString(separator = ", ") + ")"
        } else {
            ""
        }

        val file = runConfiguration.primaryModel.openClFile(modelManager.context)
        val fastModelEligible = fastEligible(runConfiguration, decodingConfiguration, file)
        val openClModelEligible = OpenClPolicy.eligible(runConfiguration.experimentalOpenCl, allowedLanguages.toSet(),
            runConfiguration.languageSpecificModels.isNotEmpty(), file != null)
        // Runtime never blocks on a new probe: prewarm owns the bounded query.
        // UNKNOWN (not queried yet) and every non-SUPPORTED result route CPU.
        val capability = OpenClCapabilityGate.peek()
        val fastRuntimeEligible = fastModelEligible && capability?.supported == true
        val openClRuntimeEligible = OpenClPolicy.runtimeEligible(runConfiguration.experimentalOpenCl,
            capability, allowedLanguages.toSet(),
            runConfiguration.languageSpecificModels.isNotEmpty(), file != null)
        if (fastRuntimeEligible) {
            return@coroutineScope OpenClRouting.run(
                experimental = {
                    val (fastText, telemetry) = FastDictationRunner(
                        AndroidFastEngine(modelManager.context.applicationContext), fastSession
                    ).run(
                        file!!, fastKey(file, runConfiguration), samples, glossary, decodingConfiguration.suppressSymbols,
                        onLoaded = { Toast.makeText(modelManager.context, "Fast dictation (experimental)", Toast.LENGTH_SHORT).show(); callback.updateStatus(InferenceState.Encoding) }
                    )
                    OpenClDiagnostics.record(modelManager.context,
                        "mode_requested=fast mode_actual=opencl_fast phase=recognize guard=${telemetry.guard} retry=${telemetry.retry} " +
                        "verification=${telemetry.verification} verification_ms=${telemetry.verificationMs} bytes_hashed=${telemetry.bytesHashed} " +
                        "resident_hit=${telemetry.residentHit} resident_generation=${telemetry.residentGeneration} resident_token=${telemetry.residentToken} " +
                        "fast_ms=${telemetry.fastMs} retry_ms=${telemetry.retryMs} total_ms=${telemetry.totalMs}")
                    fastText.trim()
                },
                stock = { inferStock(samples, runConfiguration, decodingConfiguration, callback, allowedLanguages, bailLanguages, glossary) },
                unavailable = { withContext(Dispatchers.Main) {
                    Toast.makeText(modelManager.context, "Fast dictation unavailable; using stock CPU", Toast.LENGTH_LONG).show()
                } }
            )
        }
        if (openClRuntimeEligible) {
            return@coroutineScope OpenClRouting.run(
                experimental = {
                    OpenClClient(modelManager.context.applicationContext).run(
                            file!!, samples, glossary, decodingConfiguration.suppressSymbols,
                            onLoaded = { Toast.makeText(modelManager.context, "Experimental OpenCL GPU", Toast.LENGTH_SHORT).show(); callback.updateStatus(InferenceState.Encoding) },
                            onPartial = { callback.partialResult(it) }
                        ).trim()
                },
                stock = { inferStock(samples, runConfiguration, decodingConfiguration, callback, allowedLanguages, bailLanguages, glossary) },
                unavailable = { withContext(Dispatchers.Main) {
                    Toast.makeText(modelManager.context, "OpenCL unavailable or model unsupported; using stock CPU", Toast.LENGTH_LONG).show()
                } }
            )
        }
        OpenClResources.releaseIdle()
        // Fast did not run (unsupported model/language, disabled, missing file,
        // or an unsupported/unknown capability): drop any token so a later
        // window reverifies explicitly.
        fastSession.invalidate()
        // Capability gating is silent: only the existing model/language reason
        // keeps its existing user-visible hint. No unsolicited driver warning.
        if (runConfiguration.experimentalOpenCl && !openClModelEligible) withContext(Dispatchers.Main) {
            Toast.makeText(modelManager.context, "Stock CPU: OpenCL requires English-244 and English only", Toast.LENGTH_LONG).show()
        }
        inferStock(samples, runConfiguration, decodingConfiguration, callback, allowedLanguages, bailLanguages, glossary)
    }

    private suspend fun inferStock(samples: FloatArray, runConfiguration: MultiModelRunConfiguration,
        decodingConfiguration: DecodingConfiguration, callback: ModelInferenceCallback,
        allowedLanguages: Array<String>, bailLanguages: Array<String>, glossary: String): String {
        val started = android.os.SystemClock.elapsedRealtime()
        val primaryModel = modelManager.obtainModel(runConfiguration.primaryModel)
        val ready = android.os.SystemClock.elapsedRealtime()
        val result = try {
            callback.updateStatus(InferenceState.Encoding)
            primaryModel.infer(
                samples = samples,
                prompt = glossary,
                languages = allowedLanguages,
                bailLanguages = bailLanguages,
                decodingMode = DecodingMode.BeamSearch5,
                suppressNonSpeechTokens = decodingConfiguration.suppressSymbols,
                partialResultCallback = {
                    callback.partialResult(it)
                }
            )
        } catch(e: BailLanguageException) {
            callback.updateStatus(InferenceState.SwitchingModel)
            val language = getLanguageFromWhisperString(e.language)

            val specificModelLoader = runConfiguration.languageSpecificModels[language]!!
            val specificModel = modelManager.obtainModel(specificModelLoader)

            specificModel.infer(
                samples = samples,
                prompt = glossary,
                languages = arrayOf(e.language),
                bailLanguages = arrayOf(),
                decodingMode = DecodingMode.BeamSearch5,
                suppressNonSpeechTokens = decodingConfiguration.suppressSymbols,
                partialResultCallback = {
                    callback.partialResult(it)
                }
            )
        }

        OpenClDiagnostics.record(modelManager.context, "backend=stock phase=recognize load_ms=${ready-started} inference_ms=${android.os.SystemClock.elapsedRealtime()-ready} audio_ms=${samples.size/16}")
        return result
    }

    fun cancelAll(preserveFastResidency: Boolean = false) {
        fastSession.finishWindow(completed = preserveFastResidency)
        modelManager.cancelAll(preserveFastResidency)
    }
}