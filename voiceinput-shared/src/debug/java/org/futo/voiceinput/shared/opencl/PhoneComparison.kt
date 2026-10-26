package org.futo.voiceinput.shared.opencl

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.*
import org.futo.voiceinput.shared.ENGLISH_MODELS
import org.futo.voiceinput.shared.R
import org.futo.voiceinput.shared.ggml.DecodingMode
import org.futo.voiceinput.shared.ggml.WhisperGGML
import org.futo.voiceinput.shared.types.ModelBuiltInAsset
import org.futo.voiceinput.shared.types.ModelFileFile
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.types.openClFile
import org.futo.voiceinput.shared.whisper.ModelManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Debug only. No microphone, personal prompt, history or editor reference. */
class PhoneComparison(
    private val context: Context,
    private val maxReportBytes: Int = 512 * 1024,
) : ComparisonReportSource {
    private val manager = ModelManager(context)
    private var gpuUsed = false
    @Volatile private var nativeWorkStarted = false
    @Volatile private var cpu: WhisperGGML? = null
    @Volatile private var drainVerdict: Boolean? = null
    private val fast = FastDictationRunner(AndroidFastEngine(context))
    val report = JSONObject().put("schema", 1).put("build_model", Build.MODEL)
        .put("build_fingerprint", Build.FINGERPRINT).put("driver", "unknown")
        .put("peak_cross_process_pss", "unknown").put("energy", "unknown")
        .put("scope", "public unheldout fixture adapter API wall; not editor commit or accuracy acceptance")
        .put("cpu_normal_comparison", "beam5 backend/runtime comparison")
        .put("cpu_fast_comparison", "whole configuration policy comparison; not pure GPU gain")
        .put("prompt", "").put("language", "en").put("suppress", true)
        .put("cpu_threads", "stock JNI sysconf online; outside 2..16 uses6")
        .put("audio_ctx", "max(160,min(1500,ceil(samples/320)+32))")
        .put("residency_budget", "one scoped CPU plus at most two leased GPU contexts; 3GiB available floor, peak unknown")
    private val rows = JSONArray()
    private val prepares = JSONArray()
    private val warmups = JSONArray()
    private val preparedModes = mutableSetOf<String>()
    private val samples = mutableMapOf<String, MutableList<ComparisonPlan.Sample>>()
    @Volatile private var cleanupNote = "not_started"
    @Volatile private var cleanupDetail: String? = null
    private fun now() = SystemClock.elapsedRealtimeNanos()
    private fun ms(start: Long) = ComparisonPlan.elapsedMs(start, now())

    init {
        // Present from the first snapshot: a failure before run() must still
        // produce a copyable partial report.
        report.put("samples", rows).put("prepares", prepares).put("warmups", warmups)
    }

    fun cancel() { cpu?.cancel(); fast.invalidate() }

    /**
     * v42: only a run that actually reached a CPU obtain or a GPU prepare owns
     * native work. The flag is set before prepare, so a prepare that is still
     * pending can never be mistaken for "nothing to clean up".
     */
    override val requiresNativeDrain: Boolean get() = nativeWorkStarted

    override fun lastDrainVerdict(): Boolean? = drainVerdict

    override fun recordNoNativeWorkCleanup() {
        report.put("cleanup_skipped_no_native_work", true)
            .put("cleanup_gpu_arm_used", gpuUsed)
            .put("cleanup_lease_release_failure_is_fatal", false)
            .put("cleanup_drain_confirmed", true)
            .put("cleanup_total_ms", 0.0)
    }

    override fun noteCleanup(state: String, detail: String?) {
        cleanupNote = state
        cleanupDetail = detail
    }

    /**
     * v43 read-only mapping fields. These come from the Activity's
     * authoritative DataStore read plus the production resolver result; they
     * carry only setting-key names and booleans, never a preference value or
     * filesystem path.
     */
    fun recordModelMapping(mapping: ComparisonModelMapping) {
        report.put("dictation_package", mapping.devicePackage)
            .put("dictation_flavor", mapping.deviceFlavor)
            .put("dictation_build_type", mapping.deviceBuildType)
            // The cold-cache RIMM locale is kept under an explicit debug name.
            // It must never be read as the actual keyboard locale.
            .put("dictation_debug_resolver_locale_tag", mapping.localeTag)
            .put("dictation_debug_resolver_locale_to_string", mapping.localeToString)
            .put("dictation_debug_resolver_locale_language", mapping.localeLanguage)
            .put("dictation_debug_resolver_locale_country", mapping.localeCountry)
            .put("dictation_debug_resolver_locale_source", mapping.debugLocaleSource)
            .put("dictation_actual_locale_tag", mapping.actualLocaleTag)
            .put("dictation_actual_locale_to_string", mapping.actualLocaleToString)
            .put("dictation_actual_locale_language", mapping.actualLocaleLanguage)
            .put("dictation_actual_locale_country", mapping.actualLocaleCountry)
            .put("dictation_actual_locale_source", mapping.actualLocaleSource)
            .put("dictation_actual_locale_known", mapping.actualLocaleKnown)
            .put("dictation_system_subtype_locale", mapping.systemSubtypeLocaleTag ?: JSONObject.NULL)
            .put("dictation_persisted_subtype_locale", mapping.persistedSubtypeLocaleTag ?: JSONObject.NULL)
            .put("dictation_actual_model_source", mapping.actualModelSource)
            .put("dictation_actual_model_known", mapping.actualModelKnown)
            .put("dictation_resolver_authoritative", mapping.resolverAuthoritative)
            .put("dictation_mapping_candidate_locale_source", mapping.candidateLocaleSource)
            .put("dictation_effective_key", mapping.effectiveKey ?: JSONObject.NULL)
            .put("dictation_effective_locale", mapping.effectiveLocale ?: JSONObject.NULL)
            .put("dictation_resolver_cache_initialized", mapping.resolverCacheInitialized ?: JSONObject.NULL)
            .put("dictation_resolver_source", mapping.resolverSource)
            .put("dictation_authoritative_source", mapping.authoritativeSource)
            .put("dictation_authoritative_key", mapping.authoritativeKey ?: JSONObject.NULL)
            .put("dictation_mapping_explanation", mapping.explanation)
            .put("dictation_mapping_candidate_keys", keyProbeArray(mapping.candidateKeys))
            .put("dictation_mapping_english_keys", keyProbeArray(mapping.englishKeys))
            .put("dictation_mapping_values_recorded", false)
    }

    private fun keyProbeArray(probes: List<ModelKeyProbe>): JSONArray = JSONArray().apply {
        probes.forEach { probe ->
            put(
                JSONObject().put("key", probe.key)
                    .put("present", probe.present)
                    .put("non_empty", probe.nonEmpty)
                    .put("file_exists", probe.fileExists),
            )
        }
    }

    suspend fun run(
        loader: ModelLoader?,
        selectedModel: File?,
        status: (String) -> Unit,
        onRow: (suspend (String) -> Unit)? = null,
    ) = withContext(Dispatchers.Default) {
        report.put("samples", rows).put("prepares", prepares).put("warmups", warmups)
        val resolution = now()
        // Resolution only: a user-selected sealed private copy (v42) has the
        // highest priority for this comparison, then production's own
        // dictation loader when it is file-backed, then (debug comparison
        // only) the production catalog entry whose declared checksum is the
        // pinned English-244 SHA. No directory scan, filename guess, download,
        // or preference change. The SHA gate below remains the content
        // identity for every arm, including the selected file.
        val selectedFile = selectedModel?.takeIf { it.isFile }
        val dictationFile = loader?.openClFile(context)?.takeIf { it.isFile }
        val catalog = ComparisonModel.pinnedEnglish244(ENGLISH_MODELS)
        val catalogFile = catalog?.let { File(context.filesDir, it.ggmlFile) }?.takeIf { it.isFile }
        val choice = ComparisonModel.choose(selectedFile, loader, dictationFile, catalog, catalogFile)
        report.put("comparison_source", choice.source)
            .put("model_loader_kind", ComparisonModel.kind(loader))
            .put("model_source", choice.source)
            .put("comparison_model_kind", if (selectedFile != null) "user_selected_private_copy" else ComparisonModel.kind(choice.loader))
            .put("dictation_model_source", choice.dictationSource)
            .put("dictation_model_source_scope", "debug_process_resolver_cache_dependent")
            .put("dictation_matches_comparison_model", choice.matchesDictation)
            .put("model_action_required", choice.action ?: JSONObject.NULL)
        recordBuiltinFallbackAsset(loader)
        val file = choice.file
            ?: error(choice.action ?: "No verified English-244 model file is available")
        require(file.isFile) { "Comparison model file is missing. ${choice.action.orEmpty()}" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        require(sha == OpenClPolicy.MODEL_SHA) {
            "The selected file is not English-244 (SHA-256 ${sha.take(16)}..., source ${choice.source}). " +
                choice.action.orEmpty()
        }
        report.put("model_sha256", sha).put("model_bytes", file.length()).put("model_resolution_hash_ms", ms(resolution))
        // Same eager mmap loader semantics as stock ModelFileFile, with scoped
        // descriptor ownership. The loader identity never selects content: the
        // verified [file] above is the only source for every arm.
        val delegate = loader ?: ModelFileFile(R.string.small_en_name, file)
        val scopedLoader = object : ModelLoader by delegate {
            override fun loadGGML(context: Context): WhisperGGML = file.inputStream().use {
                WhisperGGML(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.channel.size()).load())
            }

            override fun key(context: Context): Any = "Comparison|${choice.source}|$sha"
        }
        val pins = listOf(
            Triple("battery-12s", 384000, "d529c88c24f64dce6e59b42360341f5833f4ee3a6e57e894a13cd24b46753811"),
            Triple("battery-60s", 1920000, "95673726ef1517091442ae7cc98177c98e75bda61726f0d1ac86d1ff1d0d77b2"),
            Triple("battery-silence-10s", 320000, "ac2878215ad33205d1732578c89f25ededaad1b676911c540825bcf4baa42d9b")
        )
        for ((workload, bytes, pin) in pins) {
            val read = now()
            val pcm = context.assets.open("comparison/$workload.pcm16").use { it.readBytes() }
            require(pcm.size == bytes && ComparisonPlan.hash(pcm) == pin) { "Fixture integrity failed" }
            val floats = FloatArray(bytes / 2)
            val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in floats.indices) floats[i] = shorts.get(i) / 32768.0f
            report.put("fixture_$workload", JSONObject().put("sha256", pin).put("bytes", bytes).put("read_convert_ms", ms(read)).put("public_unheldout", true))
            for (round in 0..2) for (mode in ComparisonPlan.order(round)) {
                currentCoroutineContext().ensureActive()
                status("$workload · round ${round + 1}/3 · $mode")
                val row = JSONObject().put("workload", workload).put("round", round).put("mode", mode)
                var apiMs: Double? = null
                var error: String? = null
                var stage = "prepare"
                val attemptStart = now()
                try {
                    val memory = ActivityManager.MemoryInfo()
                    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
                    row.put("available_bytes_before", memory.availMem)
                    require(memory.availMem >= 3L * 1024 * 1024 * 1024 && !memory.lowMemory) { "Memory budget unavailable" }
                    val prepare = now()
                    nativeWorkStarted = true
                    when (mode) {
                        ComparisonPlan.modes[0] -> { cpu = manager.obtainModel(scopedLoader); currentCoroutineContext().ensureActive() }
                        ComparisonPlan.modes[1] -> { gpuUsed = true; OpenClClient(context).prepare(file) }
                        else -> { gpuUsed = true; fast.prepare(file, "comparison|$sha") }
                    }
                    prepares.put(JSONObject().put("workload", workload).put("round", round).put("mode", mode).put("prepare_ms", ms(prepare)).put("first_prepare_this_run", preparedModes.add(mode)))
                    suspend fun decode(target: JSONObject?): String = when (mode) {
                        ComparisonPlan.modes[0] -> cpu!!.infer(floats, "", arrayOf("en"), emptyArray(), DecodingMode.BeamSearch5, true, {})
                        ComparisonPlan.modes[1] -> OpenClClient(context).run(file, floats, "", true, {}, {})
                        else -> fast.run(file, "comparison|$sha", floats, "", true, {}).let { (text, t) ->
                            target?.put("guard", t.guard)?.put("retry", t.retry)?.put("verification", t.verification)
                                ?.put("bytes_hashed", t.bytesHashed)?.put("resident_hit", t.residentHit)
                                ?.put("fast_internal_ms", t.fastMs)?.put("retry_internal_ms", t.retryMs)
                            text
                        }
                    }
                    stage = "shape_warmup"
                    val warm = now(); decode(null)
                    warmups.put(JSONObject().put("workload", workload).put("round", round).put("mode", mode).put("api_wall_ms", ms(warm)))
                    currentCoroutineContext().ensureActive()
                    stage = "measured_request"
                    val harness = now()
                    val request = now()
                    val text = decode(row)
                    apiMs = ms(request)
                    currentCoroutineContext().ensureActive()
                    row.put("api_wall_ms", apiMs).put("output_sha256", ComparisonPlan.hash(text.toByteArray(Charsets.UTF_8)))
                        .put("output_chars", text.length).put("public_fixture_transcript", text.take(16384))
                        .put("public_fixture_transcript_truncated", text.length > 16384)
                        .put("rtf", apiMs / (floats.size / 16.0)).put("harness_wall_ms", ms(harness))
                } catch (e: Exception) {
                    error = e.javaClass.simpleName
                    row.put("error", error).put("failed_stage", stage)
                    if (e is CancellationException) throw e
                } finally {
                    row.put("attempt_including_prepare_warmup_ms", ms(attemptStart))
                    rows.put(row)
                    samples.getOrPut(workload) { mutableListOf() }.add(ComparisonPlan.Sample(round, mode, apiMs, error))
                    // Durable after every sample, before any per-visit cleanup:
                    // a later switch/cancel/process kill keeps completed rows.
                    if (onRow != null) {
                        withContext(NonCancellable) {
                            runCatching { onRow(snapshot("in_progress", "running")) }
                        }
                    }
                    // Close on success, failure and cancellation, on the stock inference context.
                    withContext(NonCancellable) { manager.cleanUp(); cpu = null }
                }
            }
        }
    }

    /**
     * The production cleanup fence. Its duration is owned by native work and
     * is deliberately not bounded here; the Activity bounds only its display
     * wait and keeps the voice gate until this returns true.
     *
     * The verdict itself lives in [ComparisonDrain] so the JVM regressions
     * drive the same steps the Activity drives. In particular a preflight
     * failure (no native work started) is drained even if the main-thread
     * lease-release hop fails. A GPU run is confirmed only after the
     * serial-worker quiescence ack; once that ack exists, idle-lease cleanup
     * is evidence-only. Without the ack the screen stays UNKNOWN and the gate
     * stays held.
     */
    override suspend fun close(): Boolean = withContext(NonCancellable) {
        val started = now()
        drainVerdict = null
        val drain = ComparisonDrain(
            cancelNative = { cancel() },
            closeCpu = {
                manager.cleanUp()
                cpu = null
            },
            gpuWorkUsed = { gpuUsed && nativeWorkStarted },
            gpuFence = {
                val start = now()
                // v43: the restored v37 serial-worker barrier, not a model
                // prepare. It touches no model/audio/engine and cannot reset
                // cancellation state. Only "idle after all prior worker tasks"
                // is a successful quiescence proof.
                val ack = awaitOpenClQuiescence(context)
                report.put("gpu_cleanup_fence_ms", ms(start))
                    .put("native_idle_after_fence", ack.idle)
                if (ack.idle) ComparisonFence.ACKED else ComparisonFence.BUSY
            },
            releaseLeases = {
                // The lease bookkeeping is main-thread-only. NonCancellable is
                // repeated here so an owned drain cannot be interrupted between
                // the fence and the report.
                withContext(NonCancellable + Dispatchers.Main) { OpenClResources.releaseIdle() }
            },
            record = { key, value -> report.put(key, value) },
        )
        val drained = drain.run()
        drainVerdict = drained
        report.put("cleanup_total_ms", ms(started))
        drained
    }

    /**
     * Trace of the production built-in fallback bytes. When the active locale
     * has no imported voice-input file, everyday dictation itself uses this
     * asset; recording its hash proves whether this phone's everyday model is
     * the pinned English-244 (it is not) without logging a path.
     */
    private suspend fun recordBuiltinFallbackAsset(loader: ModelLoader?) {
        val builtin = loader as? ModelBuiltInAsset ?: return
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            context.assets.open(builtin.ggmlFile).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    if (!currentCoroutineContext().isActive) throw CancellationException("cancelled while hashing")
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    bytes += n
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            report.put("dictation_builtin_asset", builtin.ggmlFile)
                .put("dictation_builtin_asset_bytes", bytes)
                .put("dictation_builtin_asset_sha256", sha)
                .put("dictation_builtin_is_pinned_english_244", sha == OpenClPolicy.MODEL_SHA)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report.put("dictation_builtin_asset", builtin.ggmlFile)
                .put("dictation_builtin_asset_error", e.javaClass.simpleName)
        }
    }

    /**
     * Bounded snapshot used both incrementally and at terminal. Compact JSON
     * and a shrinking public-transcript cap keep it under the writer budget;
     * completed matched rounds are still the only ones with medians.
     */
    @Synchronized
    override fun snapshot(outcome: String, reportState: String): String {
        val anyError = samples.values.flatten().any { it.error != null }
        report.put("report_state", reportState)
            .put("report_partial", reportState != "final")
            .put("outcome", if (outcome == "completed" && anyError) "completed_with_arm_errors" else outcome)
            .put("cleanup_state", cleanupNote)
            .put("cleanup_detail", cleanupDetail ?: JSONObject.NULL)
            .put("saved_elapsed_realtime_ms", SystemClock.elapsedRealtime())
        val summaries = JSONArray()
        for (workload in listOf("battery-12s", "battery-60s", "battery-silence-10s")) {
            val values = samples[workload].orEmpty()
            val matched = ComparisonPlan.matched(values)
            val summary = JSONObject().put("workload", workload).put("complete_matched_rounds", matched.size)
                .put("excluded_or_unfinished_rounds", 3 - matched.size).put("recorded_attempts", values.size)
            val medians = ComparisonPlan.modes.map { mode -> ComparisonPlan.median(values.filter { it.round in matched && it.mode == mode }.mapNotNull { it.ms }) }
            ComparisonPlan.modes.forEachIndexed { i, mode -> summary.put("${mode}_median_api_ms", medians[i] ?: JSONObject.NULL) }
            for (i in 1..2) summary.put("cpu_over_${ComparisonPlan.modes[i]}_median_ratio", if (medians[0] != null && medians[i] != null && medians[i]!! > 0) medians[0]!! / medians[i]!! else JSONObject.NULL)
            summaries.put(summary)
        }
        report.put("summary", summaries)
        var json = report.toString()
        var cap = 16384
        for (nextCap in intArrayOf(4096, 1024, 0)) {
            if (json.toByteArray(Charsets.UTF_8).size <= maxReportBytes) break
            cap = nextCap
            shrinkTranscripts(cap)
            json = report.toString()
        }
        report.put("transcript_cap_chars", cap)
        json = report.toString()
        require(json.toByteArray(Charsets.UTF_8).size <= maxReportBytes) {
            "report exceeds $maxReportBytes bytes"
        }
        return json
    }

    private fun shrinkTranscripts(cap: Int) {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val full = row.optString("public_fixture_transcript", "")
            if (full.length > cap) {
                row.put("public_fixture_transcript", full.take(cap))
                row.put("public_fixture_transcript_truncated", true)
            }
        }
    }
}
