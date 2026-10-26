package org.futo.voiceinput.shared.opencl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the seam actually called by the debug Activity. The coordinator
 * tests above cover behavior; this test prevents the Activity from drifting
 * back to the v39 unbounded, post-drain-only, 100 ms cancel-polling path.
 */
class ComparisonHarnessWiringTest {
    private val activity = File("../src/debug/java/org/futo/inputmethod/latin/SttComparisonActivity.kt").readText()
    private val comparison = File("src/debug/java/org/futo/voiceinput/shared/opencl/PhoneComparison.kt").readText()
    private val cleanup = File("src/debug/java/org/futo/voiceinput/shared/opencl/ComparisonCleanup.kt").readText()

    @Test
    fun activityOwnsCleanupThroughTheBoundedCoordinator() {
        assertTrue(activity.contains("ComparisonCleanup("))
        assertTrue(activity.contains("cleanup.start(comparison, outcome, UI_WAIT_MS)"))
        assertTrue(activity.contains("ComparisonCleanupHost.active"))
        assertTrue(activity.contains("ComparisonReportWriter(REPORT_MAX_BYTES)"))
        assertTrue(activity.contains("marker.write()"))
        assertTrue(activity.contains("marker.clear()"))
        // A cancel/switch/deadline can be stuck inside blocking native work
        // before close() is reachable; the Activity still has a bounded
        // terminal message and never claims cleanup started.
        assertTrue(activity.contains("cancelWatchdog"))
        assertTrue(activity.contains("ComparisonUi.cancelUnconfirmed(UI_WAIT_MS)"))
        assertTrue(activity.contains("handler.postDelayed(cancelWatchdog, UI_WAIT_MS)"))
        assertFalse(activity.contains("withTimeout("))
    }

    @Test
    fun activityNoLongerPollsCancelEvery100msOrWaitsUnboundedOnClose() {
        assertFalse(activity.contains("repeatNativeCancel"))
        assertFalse(activity.contains("postDelayed(this, 100)"))
        assertFalse(activity.contains("withContext(NonCancellable)"))
        assertFalse(activity.contains("comparison.close()"))
    }

    @Test
    fun comparisonSavesARowBeforeCleanupAndExposesABoundedSnapshot() {
        assertTrue(comparison.contains("override fun snapshot(outcome: String, reportState: String)"))
        assertTrue(comparison.contains("onRow(snapshot(\"in_progress\", \"running\"))"))
        val saveIndex = comparison.indexOf("onRow(snapshot(")
        val cleanupIndex = comparison.indexOf("manager.cleanUp(); cpu = null", saveIndex)
        assertTrue("incremental save must precede per-visit CPU cleanup", saveIndex in 0 until cleanupIndex)
        assertTrue(comparison.contains("transcript_cap_chars"))
        assertTrue(comparison.contains("require(json.toByteArray(Charsets.UTF_8).size <= maxReportBytes)"))
    }

    @Test
    fun fixedThreeArmMatrixAndTimingClocksAreUnchanged() {
        assertTrue(ComparisonPlan.modes == listOf("stock_cpu_beam5", "normal_opencl_beam5", "fast_guarded_beam1_retry5"))
        assertTrue(comparison.contains("val request = now()"))
        assertTrue(comparison.contains("apiMs = ms(request)"))
        assertTrue(comparison.contains("ComparisonPlan.order(round)"))
    }

    @Test
    fun comparisonResolvesProductionEnglish244InsteadOfAbortingOnTheBuiltinFallback() {
        // v41: the phone's active locale resolved to the built-in tiny.en
        // asset; aborting with an internal message gave no copyable cause and
        // no path forward. Production priority is preserved and the bounded
        // debug catalog fallback is checksum-driven, never a filename scan.
        assertTrue(comparison.contains("ComparisonModel.choose("))
        assertTrue(comparison.contains("ComparisonModel.pinnedEnglish244(ENGLISH_MODELS)"))
        assertTrue(comparison.contains("model_source"))
        assertTrue(comparison.contains("dictation_model_source"))
        assertTrue(comparison.contains("model_action_required"))
        assertTrue(comparison.contains("dictation_builtin_asset_sha256"))
        assertTrue(comparison.contains("require(sha == OpenClPolicy.MODEL_SHA)"))
        assertFalse(comparison.contains("\"Active model is not file-backed English-244\""))
        assertFalse("no directory scan may be introduced", comparison.contains("listFiles("))
        assertFalse("no filename-only acceptance", comparison.contains("ggmlFile == \"voice-input-english-244.bin\""))
        assertTrue(activity.contains("model_action_required"))
    }

    @Test
    fun closeDelegatesItsVerdictToTheSharedDrainAndTheCoordinatorOwnsIt() {
        assertTrue(comparison.contains("ComparisonDrain("))
        assertTrue(comparison.contains("record = { key, value -> report.put(key, value) }"))
        assertTrue(comparison.contains("withContext(NonCancellable + Dispatchers.Main)"))
        // The owned drain survives caller cancellation; the phone's
        // JobCancellationException must not become an UNKNOWN drain.
        assertTrue(cleanup.contains("withContext(NonCancellable)"))
        assertTrue(cleanup.contains("drained = report.close()"))
    }

    @Test
    fun v42NoWorkCleanupIsDecidedByTheExplicitResourceLedger() {
        // The ledger is set BEFORE prepare, so a prepare that is still pending
        // can never be mistaken for "no native work".
        val prepared = comparison.indexOf("nativeWorkStarted = true")
        val branch = comparison.indexOf("when (mode)", prepared)
        assertTrue("nativeWorkStarted must be set before any prepare", prepared in 1 until branch)
        assertTrue(comparison.contains("override val requiresNativeDrain: Boolean get() = nativeWorkStarted"))
        assertTrue(comparison.contains("drainVerdict = drained"))
        assertTrue(cleanup.contains("report.requiresNativeDrain"))
        assertTrue(cleanup.contains("report.recordNoNativeWorkCleanup()"))
        assertTrue(cleanup.contains("report.lastDrainVerdict()"))
        // The no-native shortcut must not contain any production close step.
        val shortcut = cleanup.substringAfter("if (!needsNative)").substringBefore("} else {")
        assertFalse(shortcut.contains("close()"))
        assertFalse(shortcut.contains("manager"))
        assertFalse(shortcut.contains("Dispatchers.Main"))
    }

    @Test
    fun v43CleanupFenceUsesTheSerialQuiescenceAckNotAModelPrepare() {
        val close = comparison.substringAfter("override suspend fun close()")
            .substringBefore("private suspend fun recordBuiltin")
        // The old PREPARE-as-fence both re-verified/re-prepared the model and
        // shared the cancellable request continuation. The debug drain now asks
        // the serial service worker for a no-model, no-audio quiescence ack.
        assertTrue(close.contains("awaitOpenClQuiescence(context)"))
        assertTrue(close.contains("native_idle_after_fence"))
        assertTrue(close.contains("ComparisonFence.ACKED"))
        assertFalse("cleanup must not prepare or verify the model again", close.contains("OpenClClient(context).prepare"))
        assertFalse(close.contains("model_unavailable"))
        val drain = File("src/debug/java/org/futo/voiceinput/shared/opencl/ComparisonDrain.kt").readText()
        assertTrue(drain.contains("ComparisonFence.ACKED"))
        assertTrue(drain.contains("gpu_cleanup_fence_stage"))
        assertTrue(drain.contains("cleanup_lease_release_failure_is_evidence_only"))
        assertTrue(drain.contains("native_cancel_error"))
        val quiesce = File("src/debug/java/org/futo/voiceinput/shared/opencl/OpenClQuiesce.kt").readText()
        assertTrue(quiesce.contains("suspend fun await(): OpenClFenceAck = suspendCoroutine"))
        assertFalse(
            "a cancellable wait could discard the ack",
            quiesce.contains("suspend fun await(): OpenClFenceAck = suspendCancellableCoroutine"),
        )
        assertTrue(quiesce.contains("NonCancellable + Dispatchers.Main"))
        assertTrue(quiesce.contains("wait.fail(\"service_lost\")"))
        val service = File("src/main/java/org/futo/voiceinput/shared/opencl/OpenClService.kt").readText()
        assertTrue(service.contains("DRAIN -> worker.execute"))
        assertTrue(service.contains("const val DRAIN = 11"))
        assertTrue(service.contains("requests.idle()"))
        assertFalse("the fence must not admit a model/audio request", service.substringAfter("DRAIN ->").substringBefore("CANCEL ->").contains("requests.add"))
    }

    @Test
    fun v43FourDebugControlsAndReportRulesAreUnchanged() {
        assertTrue(activity.contains("Start comparison"))
        assertTrue(activity.contains("Cancel"))
        assertTrue(activity.contains("Choose comparison model"))
        assertTrue(activity.contains("Copy report JSON"))
        assertTrue(activity.contains("addRow(start, cancel)"))
        assertTrue(activity.contains("addRow(choose, copy)"))
        assertTrue(activity.contains("WindowInsetsCompat"))
        assertTrue(activity.contains("ComparisonModelStore("))
        assertTrue(comparison.contains("complete_matched_rounds"))
        assertTrue(comparison.contains("median"))
        assertFalse("no p95 from three repeats", comparison.contains("p95"))
        assertFalse(comparison.contains("Profiler"))
    }

    @Test
    fun v43ComparisonBudgetCoversTheMeasuredLongMatrix() {
        assertEquals(600_000L, ComparisonPlan.DEADLINE_MS)
        assertTrue(activity.contains("ComparisonPlan.DEADLINE_MS"))
        assertTrue(activity.contains("deadline_10min"))
        assertFalse(activity.contains("deadline_5min"))
        assertTrue(activity.contains("About 10 minutes maximum"))
        assertTrue(activity.contains("switching apps cancels the comparison"))
        assertTrue(ComparisonUi.ACTIVITY_STOPPED.contains("activity_stopped"))
    }

    @Test
    fun v43ModelMappingIsReadOnlyAndRecordsKeysNotValues() {
        assertTrue(activity.contains("SttModelMapping.resolve"))
        assertTrue(activity.contains("recordModelMapping"))
        assertTrue(activity.contains("mappingStatus"))
        val mapping = File("../src/debug/java/org/futo/inputmethod/latin/SttModelMapping.kt").readText()
        assertTrue(mapping.contains("ResourceHelper.findKeyForLocaleAndKind"))
        assertTrue(mapping.contains("FileKind.VoiceInput.preferenceKeyFor"))
        assertTrue(mapping.contains("getPreferencesDataStoreFile"))
        assertTrue(mapping.contains("isInitializedForDebug"))
        assertTrue(mapping.contains("englishKeys"))
        assertTrue(comparison.contains("dictation_mapping_english_keys"))
        assertFalse("debug mapping must never write a setting", mapping.contains("setSetting"))
        assertFalse("no directory scan is needed", mapping.contains("listFiles("))
        // Values and paths are never serialized: probes carry only booleans
        // and the production setting-key name.
        val core = File("src/debug/java/org/futo/voiceinput/shared/opencl/ComparisonModelMapping.kt").readText()
        assertTrue(comparison.contains("dictation_mapping_values_recorded"))
        assertFalse(core.contains("absolutePath"))
        assertFalse(core.contains(".bin"))
    }

    @Test
    fun postV43MappingSeparatesTheColdDebugDefaultFromTheActualKeyboard() {
        val mapping = File("../src/debug/java/org/futo/inputmethod/latin/SttModelMapping.kt").readText()
        // The actual keyboard comes from the live system subtype first, then
        // the persisted FUTO subtype, never from the cold RIMM default.
        assertTrue(mapping.contains("currentInputMethodSubtype"))
        assertTrue(mapping.contains("ACTIVE_SUBTYPE"))
        assertTrue(mapping.contains("persistedSubtypeLocaleTag"))
        assertTrue(mapping.contains("actualModelSource"))
        assertTrue(mapping.contains("actualKnown"))
        assertTrue(mapping.contains("RESOLVER_CACHE_COLD"))
        // Initializing DataStoreHelper is not read-only (collector + possible
        // direct-boot subtype write); the debug Activity must not do it.
        assertFalse(mapping.contains("DataStoreHelper.init("))
        assertFalse(mapping.contains("setSetting"))
        assertTrue(activity.contains("dictation_actual_locale_known"))
        assertTrue(activity.contains("dictation_debug_resolver_locale_tag"))
        assertTrue(activity.contains("dictation_actual_model_source"))
        assertTrue(comparison.contains("dictation_actual_locale_source"))
        assertTrue(comparison.contains("dictation_system_subtype_locale"))
        assertTrue(comparison.contains("dictation_persisted_subtype_locale"))
        assertTrue(comparison.contains("dictation_resolver_authoritative"))
        assertTrue(comparison.contains("dictation_mapping_candidate_locale_source"))
        val core = File("src/debug/java/org/futo/voiceinput/shared/opencl/ComparisonModelMapping.kt").readText()
        assertTrue(core.contains("SYSTEM_IME_SUBTYPE"))
        assertTrue(core.contains("DATASTORE_ACTIVE_SUBTYPE"))
        assertTrue(core.contains("ACTUAL_LOCALE_UNKNOWN"))
        assertTrue(core.contains("ACTUAL_MODEL_IMPORTED"))
    }

    @Test
    fun v42ModelPickerUsesBoundedSingleDocumentSafWithoutTouchingTheVoiceGate() {
        assertTrue(activity.contains("Intent.ACTION_OPEN_DOCUMENT"))
        assertTrue(activity.contains("addCategory(Intent.CATEGORY_OPENABLE)"))
        assertTrue(activity.contains("ComparisonModelStore("))
        assertTrue(activity.contains("store.import("))
        assertTrue(activity.contains("ComparisonModelStore.EXPECTED_BYTES"))
        assertTrue(activity.contains("ComparisonModelStore.EXPECTED_SHA"))
        assertTrue(activity.contains("Choose comparison model"))
        // One document, no MIME narrowing, no extra supplied data, no
        // persisted grant, no storage permission.
        assertFalse(activity.contains("EXTRA_ALLOW_MULTIPLE"))
        assertFalse(activity.contains("putExtra("))
        assertFalse(activity.contains("takePersistableUriPermission"))
        assertFalse(activity.contains("EXTRA_INITIAL_URI"))
        assertFalse(activity.contains("WRITE_EXTERNAL_STORAGE"))
        // The picker/copy path is separate from the voice gate, marker and
        // cancel watchdog that belong to a running comparison.
        val picker = activity.substringAfter("private fun chooseComparisonModel()")
            .substringBefore("private fun begin()")
        assertFalse(picker.contains("VoiceWorkGate.acquire"))
        assertFalse(picker.contains("marker.write"))
        assertFalse(picker.contains("work?.cancel"))
        assertFalse(picker.contains("cancelWatchdog"))
        assertTrue(picker.contains("onActivityResult"))
        assertTrue(picker.contains("beginModelImport"))
    }

    @Test
    fun v42SelectedModelIsAnExplicitComparisonSourceWithItsOwnReportFields() {
        assertTrue(comparison.contains("selectedModel: File?"))
        assertTrue(comparison.contains("ComparisonModel.choose(selectedFile"))
        assertTrue(comparison.contains("\"comparison_source\""))
        assertTrue(comparison.contains("\"comparison_model_kind\""))
        assertTrue(comparison.contains("\"user_selected_private_copy\""))
        assertTrue(comparison.contains("require(sha == OpenClPolicy.MODEL_SHA)"))
        val model = File("src/debug/java/org/futo/voiceinput/shared/opencl/ComparisonModel.kt").readText()
        assertTrue(model.contains("SOURCE_USER_SELECTED = \"user_selected_verified_file\""))
        assertTrue(model.contains("matchesDictation"))
        // A selected file can only become native work after the full content
        // gate: the ledger flips only after the SHA requirement.
        val gate = comparison.indexOf("require(sha == OpenClPolicy.MODEL_SHA)")
        val prepare = comparison.indexOf("nativeWorkStarted = true")
        assertTrue("selected bytes must pass the SHA gate before prepare", gate in 1 until prepare)
        // The comparison still does not scan a directory or accept by name.
        assertFalse(comparison.contains("listFiles("))
        assertFalse(comparison.contains("canonicalFile"))
    }
}
