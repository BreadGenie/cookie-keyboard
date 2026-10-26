package org.futo.inputmethod.latin

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.AtomicFile
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import org.futo.inputmethod.latin.uix.ResourceHelper
import org.futo.inputmethod.latin.utils.JniUtils
import org.futo.voiceinput.shared.opencl.CleanupPhase
import org.futo.voiceinput.shared.opencl.CleanupState
import org.futo.voiceinput.shared.opencl.ComparisonCleanup
import org.futo.voiceinput.shared.opencl.ComparisonCleanupHost
import org.futo.voiceinput.shared.opencl.ComparisonMarker
import org.futo.voiceinput.shared.opencl.ComparisonModelImportException
import org.futo.voiceinput.shared.opencl.ComparisonModelPreflight
import org.futo.voiceinput.shared.opencl.ComparisonModelStore
import org.futo.voiceinput.shared.opencl.ComparisonPlan
import org.futo.voiceinput.shared.opencl.ComparisonPreflight
import org.futo.voiceinput.shared.opencl.ComparisonReportWriter
import org.futo.voiceinput.shared.opencl.ComparisonUi
import org.futo.voiceinput.shared.opencl.PhoneComparison
import org.futo.voiceinput.shared.opencl.VoiceWorkGate
import org.json.JSONObject
import java.io.File

/** Separate debug launcher; ignores all external intent data. */
class SttComparisonActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var work: Job? = null
    private var runner: PhoneComparison? = null
    @Volatile private var stoppedReason = "cancelled"
    @Volatile private var stopRequested = false
    @Volatile private var cancelWatchdogPosted = false
    private lateinit var status: TextView
    private lateinit var modelStatus: TextView
    private lateinit var mappingStatus: TextView
    private lateinit var start: Button
    private lateinit var cancel: Button
    private lateinit var choose: Button
    private lateinit var copy: Button
    private lateinit var writer: ComparisonReportWriter
    private lateinit var marker: ComparisonMarker
    private lateinit var store: ComparisonModelStore
    private var markerBlocked = false
    private var saved: String? = null
    private var cleanupState = CleanupState(CleanupPhase.Idle)
    private var modelReady = false
    private var importBusy = false
    private var importJob: Job? = null
    private var preflightJob: Job? = null
    private var preflightGeneration = 0
    private val deadline = Runnable { stop("deadline_10min") }

    /**
     * Cancellation can be requested while a blocking native inference is still
     * running, so the coroutine may never reach the Activity's finally block.
     * This watchdog is therefore independent of the cleanup coordinator: it
     * reports an honest terminal state instead of leaving progress text up
     * forever. Once the real coordinator starts, its own bounded state wins.
     */
    private val cancelWatchdog = Runnable {
        if (!stopRequested || isFinishing || isDestroyed) return@Runnable
        val active = ComparisonCleanupHost.active
        val coordinatorStarted = active != null && active.state.phase != CleanupPhase.Idle
        // cancel() clears Job.isActive immediately; a blocking native call can
        // keep the job incomplete much longer. Watch completion so this
        // watchdog actually covers the cancellation gap it exists for.
        if (!ComparisonUi.cancelWatchdogDue(work, coordinatorStarted)) return@Runnable
        markerBlocked = true
        status.text = ComparisonUi.cancelUnconfirmed(UI_WAIT_MS)
        copy.isEnabled = ComparisonUi.canCopy(refreshLatestReport() != null)
        updateControls()
    }

    private val reportFile get() = AtomicFile(File(filesDir, "cpu-opencl-comparison.json"))
    private val markerFile get() = File(filesDir, "cpu-opencl-comparison.cleanup-pending")

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // Target SDK 35 enforces edge-to-edge; draw behind system bars and pad
        // the fixed control area from real insets so it is never hidden.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()

        writer = ComparisonReportWriter(REPORT_MAX_BYTES) { saveAtomic(it) }
        marker = ComparisonMarker(
            markerFile,
            { android.os.Process.myPid().toLong() },
            { runCatching { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1) },
            { openClProcessAlive() },
        )
        store = ComparisonModelStore(
            File(filesDir, ComparisonModelStore.DIR_NAME),
            ComparisonModelStore.EXPECTED_BYTES,
            ComparisonModelStore.EXPECTED_SHA,
        )

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Inset-aware padding: status bar / cutout on top, navigation bar and
        // IME on the bottom, so controls stay visible and tappable.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(dp(16) + bars.left, bars.top + dp(8), dp(16) + bars.right, bars.bottom + dp(8))
            insets
        }

        val header = TextView(this).apply {
            text = "CPU / OpenCL / Fast · public fixture comparison\n" +
                "About 10 minutes maximum plus native cancellation drain. No microphone or editor. " +
                "Start explicitly; keep this screen open — switching apps cancels the comparison."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        // Fixed control area, two rows of two so four actions stay visible in
        // landscape without the explanatory header pushing them off screen.
        // It is a direct child of the root and never inside the report scroll.
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        start = Button(this).apply {
            text = "Start comparison"; minHeight = dp(48); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isEnabled = false
            setOnClickListener { begin() }
        }
        cancel = Button(this).apply {
            text = "Cancel"; minHeight = dp(48); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setOnClickListener { stop("cancelled_by_user") }
        }
        choose = Button(this).apply {
            text = "Choose comparison model"; minHeight = dp(48); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setOnClickListener {
                if (importBusy) {
                    importJob?.cancel()
                    modelStatus.text = "Cancelling the model copy; the previous verified model is kept."
                } else {
                    chooseComparisonModel()
                }
            }
        }
        copy = Button(this).apply {
            text = "Copy report JSON"; isEnabled = false; minHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setOnClickListener {
                val json = refreshLatestReport() ?: return@setOnClickListener
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Public fixture STT comparison", json))
            }
        }
        fun addRow(left: Button, right: Button) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            controls.addView(row)
        }
        addRow(start, cancel)
        addRow(choose, copy)

        modelStatus = TextView(this).apply {
            text = ComparisonModelPreflight.HINT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextIsSelectable(true)
        }
        mappingStatus = TextView(this).apply {
            text = "Model mapping: inspecting active locale and English import keys…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextIsSelectable(true)
        }

        // Bounded scroll area for header, model status text and report output.
        // Weight 1 keeps it below the fixed controls in portrait and landscape.
        status = TextView(this).apply {
            text = "Waiting to start."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextIsSelectable(true)
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(header)
        content.addView(mappingStatus)
        content.addView(status)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        root.addView(controls)
        root.addView(modelStatus)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.gravity = Gravity.TOP
        setContentView(root)
        // Re-dispatch insets to the new hierarchy on all API levels.
        ViewCompat.requestApplyInsets(root)
        refreshLatestReport()?.let {
            copy.isEnabled = ComparisonUi.canCopy(true)
            val state = runCatching { JSONObject(it).optString("report_state") }.getOrDefault("")
            status.append(
                if (state == "final") "\nPrevious local report available to copy."
                else "\nPrevious local report is PARTIAL/INCOMPLETE and available to copy."
            )
        }
        recoverCleanupState()
        refreshModelPreflight()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the system picker must not race the active import.
        if (!importBusy) refreshModelPreflight()
    }

    /**
     * A process-lifetime drain may still be running after Activity teardown.
     * Re-attach to it; otherwise a marker from a dead process decides whether
     * a new run is safe. A marker is cleared only when the OpenCL process is
     * gone (or the drain here was confirmed); never inferred from the Activity.
     */
    private fun recoverCleanupState() {
        val active = ComparisonCleanupHost.active
        if (active != null && active.state.phase != CleanupPhase.Idle) {
            cleanupState = active.state
            renderCleanup(active.state)
            active.observe { s -> handler.post { renderCleanup(s) } }
            return
        }
        if (!marker.exists()) {
            markerBlocked = false
            cleanupState = CleanupState(CleanupPhase.Idle)
            updateControls()
            return
        }
        when (val recovery = marker.recover()) {
            ComparisonMarker.Recovery.NONE, ComparisonMarker.Recovery.CLEARED -> {
                markerBlocked = false
                cleanupState = CleanupState(CleanupPhase.Idle)
                if (recovery == ComparisonMarker.Recovery.CLEARED) {
                    status.text = "Previous cleanup marker cleared: no OpenCL process remains."
                }
            }
            ComparisonMarker.Recovery.SAME_PROCESS -> {
                markerBlocked = true
                cleanupState = CleanupState(CleanupPhase.Unconfirmed, "same_process_marker")
                status.text = "Previous cleanup is unconfirmed in this process. Native state is UNKNOWN; " +
                    "Start stays disabled. Force-stop the app to kill both processes, then reopen."
            }
            ComparisonMarker.Recovery.OTHER_PROCESS_ALIVE -> {
                markerBlocked = true
                cleanupState = CleanupState(CleanupPhase.Unconfirmed, "other_process_alive")
                status.text = "Previous cleanup is unconfirmed and the OpenCL process is still alive. " +
                    "Native state is UNKNOWN; Start stays disabled. " +
                    "Force-stop the app to kill both processes, then reopen."
            }
        }
        updateControls()
    }

    /**
     * The in-memory writer belongs to this Activity instance. After a
     * recreation or while a process-owned drain is finishing in the
     * background, the newest durable report may only exist on disk (or the
     * final write may land after this screen was recreated). Always fall back
     * to the bounded private file so Copy never goes dark while a report is
     * actually available.
     */
    private fun refreshLatestReport(): String? {
        writer.lastSaved?.let { saved = it; return it }
        val fromDisk = runCatching { reportFile.openRead().use { it.readBytes() } }
            .getOrNull()?.takeIf { it.size <= REPORT_MAX_BYTES }?.toString(Charsets.UTF_8)
        if (fromDisk != null) saved = fromDisk
        return saved
    }

    /**
     * Enables Start only when cleanup is safe AND a comparison model is
     * resolvable. Model inspection runs on its own job and never acquires the
     * voice gate, so choosing/copying a model cannot hold or release native
     * work. While a run is unfinished (including cancelled-but-unfinished),
     * neither choosing nor starting is offered.
     */
    private fun updateControls() {
        val running = ComparisonUi.runUnfinished(work)
        val cleanupAllows = ComparisonUi.canStart(cleanupState, markerBlocked)
        start.isEnabled = !running && !importBusy && cleanupAllows && modelReady
        cancel.isEnabled = running
        val chooseAllowed = !running && !importBusy &&
            (cleanupState.phase == CleanupPhase.Idle || cleanupState.phase == CleanupPhase.Drained) &&
            !markerBlocked
        choose.isEnabled = importBusy || chooseAllowed
        choose.text = if (importBusy) "Cancel model copy" else "Choose comparison model"
        copy.isEnabled = ComparisonUi.canCopy(refreshLatestReport() != null)
    }

    private fun refreshModelPreflight() {
        if (importBusy) return
        val generation = ++preflightGeneration
        preflightJob?.cancel()
        val selected = runCatching { store.verifiedFile() }.getOrNull()
        preflightJob = scope.launch {
            val inspected = withContext(Dispatchers.IO) {
                runCatching {
                    RichInputMethodManager.init(applicationContext)
                    val locales = RichInputMethodManager.getInstance().currentSubtypeLocales
                    val english = locales.firstOrNull()?.language == "en"
                    val loader = if (english) {
                        ResourceHelper.tryFindingVoiceInputModelForActiveLocales(applicationContext, locales)
                    } else {
                        null
                    }
                    val result = ComparisonModelPreflight.resolve(applicationContext, english, loader, selected)
                    val mapping = runCatching {
                        SttModelMapping.resolve(applicationContext, locales.firstOrNull(), result.dictationSource)
                    }.getOrElse {
                        SttModelMapping.unknown(applicationContext, locales.firstOrNull(), result.dictationSource)
                    }
                    result to mapping
                }.getOrElse {
                    ComparisonModelPreflight.unavailable(it.javaClass.simpleName) to
                        SttModelMapping.unknown(applicationContext, null, "unavailable")
                }
            }
            if (generation != preflightGeneration || importBusy || isFinishing || isDestroyed) return@launch
            modelReady = inspected.first.ready
            modelStatus.text = inspected.first.message
            mappingStatus.text = "Model mapping (read-only): ${inspected.second.summary()}"
            updateControls()
        }
    }

    /**
     * v42 debug-only model picker. ACTION_OPEN_DOCUMENT returns one existing
     * document; no arbitrary intent extras are accepted or forwarded and no
     * storage permission is requested. The selected URI is read once, copied
     * with a hard byte bound and full SHA-256 into app-private storage, then
     * the temporary file is atomically renamed. A cancel or failure deletes
     * the temporary file and keeps any previously verified model.
     */
    private fun chooseComparisonModel() {
        if (ComparisonUi.runUnfinished(work) || !choose.isEnabled) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_OPEN_MODEL)
        } catch (e: Exception) {
            modelStatus.text = "No system document picker is available (${e.javaClass.simpleName}). " +
                "The previous verified model, if any, is unchanged."
        }
    }

    @Deprecated("Activity result API is the only available path from a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_MODEL) return
        if (resultCode != RESULT_OK) {
            modelStatus.text = "Model selection cancelled. The previous verified model, if any, is unchanged."
            return
        }
        val uri = data?.data
        if (uri == null) {
            modelStatus.text = "The picker returned no file. The previous verified model, if any, is unchanged."
            return
        }
        beginModelImport(uri)
    }

    private fun beginModelImport(uri: Uri) {
        if (importBusy) return
        // Any model inspection that started before this selection is stale.
        preflightGeneration++
        preflightJob?.cancel()
        importBusy = true
        updateControls()
        // Name and declared size are display/early-rejection hints only; the
        // stream bound and full SHA-256 decide acceptance. No path is logged.
        val info = runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameAt = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeAt = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameAt >= 0) cursor.getString(nameAt) else null
                val size = if (sizeAt >= 0 && !cursor.isNull(sizeAt)) cursor.getLong(sizeAt) else null
                name to size
            }
        }.getOrNull()
        val displayName = sanitizeName(info?.first)
        val declaredSize = info?.second
        modelStatus.text = "Copying and verifying $displayName… Do not leave until this finishes."
        importJob = scope.launch {
            try {
                if (declaredSize != null && declaredSize > ComparisonModelStore.EXPECTED_BYTES) {
                    throw ComparisonModelImportException(
                        ComparisonModelImportException.SIZE_OVERFLOW,
                        declaredSize,
                    )
                }
                val file = withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw ComparisonModelImportException(ComparisonModelImportException.READ_FAILED)
                    var lastPercent = -1
                    store.import(input) { copied ->
                        val percent = (copied * 100 / ComparisonModelStore.EXPECTED_BYTES).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            handler.post {
                                if (!isFinishing && !isDestroyed && importBusy) {
                                    modelStatus.text = "Copying and verifying $displayName… $percent%"
                                }
                            }
                        }
                    }
                }
                if (!isFinishing && !isDestroyed) {
                    modelReady = true
                    modelStatus.text = "Selected model ready: English-244 verified private copy " +
                        "(${file.length()} bytes). Start comparison uses this file. " +
                        "Everyday dictation may use a different model and is unchanged."
                }
            } catch (e: CancellationException) {
                modelReady = runCatching { store.verifiedFile() != null }.getOrDefault(false)
                if (!isFinishing && !isDestroyed) {
                    modelStatus.text = "Model copy cancelled. The previous verified model, if any, is unchanged."
                }
            } catch (e: Exception) {
                modelReady = runCatching { store.verifiedFile() != null }.getOrDefault(false)
                if (!isFinishing && !isDestroyed) {
                    modelStatus.text = importFailureMessage(e)
                }
            } finally {
                importBusy = false
                if (!isFinishing && !isDestroyed) updateControls()
            }
        }
    }

    private fun sanitizeName(name: String?): String {
        val cleaned = name.orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(80)
        return cleaned.ifBlank { "the selected file" }
    }

    /** Content-free selection failure text; never a path, URI or filename. */
    private fun importFailureMessage(t: Throwable): String {
        val expected = "%,d".format(java.util.Locale.US, ComparisonModelStore.EXPECTED_BYTES)
        val kept = " The previous verified model, if any, is unchanged."
        return when (t) {
            is ComparisonModelImportException -> when (t.reason) {
                ComparisonModelImportException.WRONG_SIZE ->
                    "Selected file is not English-244: read ${t.observedBytes ?: 0} bytes, expected $expected.$kept"
                ComparisonModelImportException.SIZE_OVERFLOW ->
                    "Selected file is larger than English-244 (expected $expected bytes).$kept"
                ComparisonModelImportException.WRONG_HASH ->
                    "Selected file is not English-244: SHA-256 did not match the pinned English-244 hash.$kept"
                ComparisonModelImportException.RENAME_FAILED ->
                    "The verified copy could not be finalized.$kept"
                else -> "Could not read the selected file.$kept"
            }
            is SecurityException ->
                "The system picker grant was not usable.$kept"
            else ->
                "Model copy failed (${t.javaClass.simpleName}).$kept"
        }
    }

    private fun begin() {
        // A cancelled-but-unfinished run still owns the gate and native work,
        // so overlap protection asks completion, not activity.
        if (importBusy || ComparisonUi.runUnfinished(work) || !start.isEnabled || markerBlocked) return
        val activeCleanup = ComparisonCleanupHost.active
        if (activeCleanup != null &&
            activeCleanup.state.phase != CleanupPhase.Idle &&
            activeCleanup.state.phase != CleanupPhase.Drained
        ) {
            status.text = "Cleanup from the previous run is still unconfirmed. Start stays disabled; force-stop the app, then reopen."
            return
        }
        val selectedModel = runCatching { store.verifiedFile() }.getOrNull()
        val ownership = VoiceWorkGate.acquire() ?: run {
            status.text = "Voice work is busy or a previous comparison has not drained. Close dictation, then try again."
            return
        }
        try {
            marker.write()
        } catch (e: Exception) {
            VoiceWorkGate.release(ownership)
            status.text = "Could not write the cleanup marker: ${e.javaClass.simpleName}. No comparison started."
            return
        }
        markerBlocked = false
        start.isEnabled = false
        choose.isEnabled = false
        copy.isEnabled = ComparisonUi.canCopy(refreshLatestReport() != null)
        stopRequested = false
        cancelWatchdogPosted = false
        stoppedReason = "cancelled"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val comparison = try {
            PhoneComparison(applicationContext)
        } catch (e: Exception) {
            runCatching { marker.clear() }
            VoiceWorkGate.release(ownership)
            status.text = "Could not start the comparison harness: ${e.javaClass.simpleName}. No comparison started."
            return
        }
        comparison.report.put("app_version", BuildConfig.VERSION_NAME).put("app_version_code", BuildConfig.VERSION_CODE)
        runner = comparison
        // UNDISPATCHED guarantees the try/finally is entered before an
        // immediate Activity teardown can cancel the job, so the gate is
        // never abandoned without starting the owned cleanup.
        work = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var outcome = "completed"
            var detail = ""
            handler.postDelayed(deadline, ComparisonPlan.DEADLINE_MS)
            try {
                JniUtils.loadNativeLibrary()
                RichInputMethodManager.init(applicationContext)
                val locales = RichInputMethodManager.getInstance().currentSubtypeLocales
                require(locales.firstOrNull()?.language == "en") { "Select an English keyboard layout first" }
                // Production's loader is still inspected for the report, but a
                // verified user-selected file is a valid comparison source even
                // when the active locale resolves to the built-in asset.
                val loader = withContext(Dispatchers.IO) {
                    ResourceHelper.tryFindingVoiceInputModelForActiveLocales(applicationContext, locales)
                }
                if (selectedModel == null && loader == null) {
                    error("Active English model is not ready; choose a verified English-244 file or provision one in keyboard settings")
                }
                // v43: record the read-only mapping before any arm runs, so a
                // later failure still has the locale/key/cache explanation.
                val mapping = withContext(Dispatchers.IO) {
                    val resolverSource = runCatching {
                        ComparisonModelPreflight.resolve(
                            applicationContext,
                            true,
                            loader,
                            selectedModel,
                        ).dictationSource
                    }.getOrDefault("unknown")
                    runCatching {
                        SttModelMapping.resolve(applicationContext, locales.firstOrNull(), resolverSource)
                    }.getOrElse {
                        SttModelMapping.unknown(applicationContext, locales.firstOrNull(), resolverSource)
                    }
                }
                comparison.recordModelMapping(mapping)
                comparison.run(loader, selectedModel, { progress ->
                    handler.post {
                        if (!stopRequested && !isFinishing && !isDestroyed) status.text = progress
                    }
                }) { json ->
                    // Durable after every sample; Copy is enabled from the first report.
                    withContext(Dispatchers.IO) { writer.offer(json) }
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            copy.isEnabled = ComparisonUi.canCopy(refreshLatestReport() != null)
                        }
                    }
                }
            } catch (e: CancellationException) {
                outcome = stoppedReason
            } catch (e: Exception) {
                outcome = "error_${e.javaClass.simpleName}"
                detail = e.message.orEmpty().take(256)
                comparison.report.put("harness_error", detail)
            } finally {
                handler.removeCallbacks(deadline)
                handler.removeCallbacks(cancelWatchdog)
                cancelWatchdogPosted = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (!isFinishing && !isDestroyed) status.text = ComparisonUi.SAVING
                // Hand cleanup to the process-lifetime owner. It saves the
                // partial report BEFORE touching native cleanup, bounds only
                // the display wait, and releases VoiceWorkGate solely when the
                // production CPU/OpenCL fence actually returns success. A run
                // that never started native work skips the close path entirely.
                val cleanup = ComparisonCleanup(
                    scope = ComparisonCleanupHost.scope,
                    writer = writer,
                    onConfirmedDrain = {
                        runCatching { marker.clear() }
                        VoiceWorkGate.release(ownership)
                    },
                    initialObserver = { s -> handler.post { renderCleanup(s) } },
                )
                ComparisonCleanupHost.active = cleanup
                cleanup.start(comparison, outcome, UI_WAIT_MS)
                runner = null
            }
        }
        updateControls()
    }

    private fun renderCleanup(state: CleanupState) {
        if (isFinishing || isDestroyed) return
        cleanupState = state
        if (state.phase == CleanupPhase.Drained) {
            markerBlocked = false
            runCatching { marker.clear() }
        } else if (state.phase != CleanupPhase.Idle) {
            markerBlocked = true
        }
        status.text = ComparisonUi.status(state)
        appendReportSummary()
        updateControls()
    }

    private fun appendReportSummary() {
        val json = refreshLatestReport() ?: return
        runCatching {
            val obj = JSONObject(json)
            status.append("\n\n${obj.optString("report_state")} · ${obj.optString("outcome")}")
            // v41: a preflight model-resolution failure must be readable on the
            // phone, not only after copying JSON. Bounded, content-free.
            obj.optString("harness_error").takeIf { it.isNotBlank() }?.let {
                status.append("\n\nError: ${it.take(400)}")
            }
            obj.optString("model_action_required").takeIf { it.isNotBlank() }?.let {
                status.append("\n\nAction: ${it.take(400)}")
            }
            obj.optString("comparison_source").takeIf { it.isNotBlank() }?.let {
                status.append("\n\nComparison model source: ${it.take(120)}")
            }
            obj.optString("dictation_model_source").takeIf { it.isNotBlank() }?.let {
                status.append("\n\nDebug-process resolver model (cache-dependent; see actual model below): ${it.take(120)}")
            }
            obj.optString("gpu_cleanup_fence").takeIf { it.isNotBlank() }?.let { fence ->
                val stage = obj.optString("gpu_cleanup_fence_stage")
                status.append(
                    "\n\nCleanup fence: ${fence.take(80)}" +
                        if (stage.isNotBlank()) " at ${stage.take(80)}" else ""
                )
            }
            obj.optString("dictation_mapping_explanation").takeIf { it.isNotBlank() }?.let { reason ->
                val keys = obj.optJSONArray("dictation_mapping_english_keys")?.length() ?: 0
                val actualKnown = obj.optBoolean("dictation_actual_locale_known", false)
                val actual = if (actualKnown) {
                    obj.optString("dictation_actual_locale_to_string").ifBlank { "unknown" } +
                        " [tag ${obj.optString("dictation_actual_locale_tag")}] via " +
                        obj.optString("dictation_actual_locale_source")
                } else {
                    "unknown (" + obj.optString("dictation_actual_locale_source") + ")"
                }
                status.append(
                    "\n\nMapping: ${obj.optString("dictation_package")} " +
                        "(${obj.optString("dictation_flavor")}/${obj.optString("dictation_build_type")})" +
                        " · debug resolver " +
                        obj.optString("dictation_debug_resolver_locale_to_string").ifBlank { "unknown" } +
                        " [tag ${obj.optString("dictation_debug_resolver_locale_tag")}]" +
                        " (${obj.optString("dictation_debug_resolver_locale_source")})" +
                        " · actual keyboard " + actual +
                        " · actual model " + obj.optString("dictation_actual_model_source") +
                        " · effective key " + obj.optString("dictation_effective_key").ifBlank { "none" } +
                        " · resolver cache " +
                        obj.optString("dictation_resolver_cache_initialized").ifBlank { "unknown" } +
                        " (authoritative " + obj.optBoolean("dictation_resolver_authoritative", false) + ")" +
                        " · $reason · $keys English key(s)"
                )
            }
            val summary = obj.optJSONArray("summary") ?: return@runCatching
            for (i in 0 until summary.length()) {
                val s = summary.getJSONObject(i)
                status.append("\n${s.getString("workload")}: ${s.getInt("complete_matched_rounds")}/3 matched rounds\n")
                status.append("Median ms CPU / OpenCL / Fast: ${s.opt("stock_cpu_beam5_median_api_ms")} / ${s.opt("normal_opencl_beam5_median_api_ms")} / ${s.opt("fast_guarded_beam1_retry5_median_api_ms")}\n")
                status.append("CPU/OpenCL ratio ${s.opt("cpu_over_normal_opencl_beam5_median_ratio")}; CPU/Fast policy ratio ${s.opt("cpu_over_fast_guarded_beam1_retry5_median_ratio")}\n")
            }
        }
    }

    private fun saveAtomic(json: String) {
        val stream = reportFile.startWrite()
        try {
            stream.write(json.toByteArray(Charsets.UTF_8))
            reportFile.finishWrite(stream)
        } catch (e: Exception) {
            reportFile.failWrite(stream)
            throw e
        }
    }

    private fun openClProcessAlive(): Boolean = runCatching {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val name = "$packageName:experimental_opencl"
        am.runningAppProcesses?.any { it.processName == name } ?: true
    }.getOrDefault(true)

    private fun stop(reason: String) {
        // Initial "once" guard: a completed run has nothing to stop, while a
        // cancelled-but-unfinished run must still schedule the watchdog.
        if (!ComparisonUi.runUnfinished(work)) return
        if (stopRequested) return
        stopRequested = true
        stoppedReason = reason
        if (reason == "activity_stopped" && !isFinishing && !isDestroyed) {
            // Leaving the screen is an intentional stop, not a native failure.
            // Make that explicit even if the user returns before the durable
            // report is copied.
            status.text = ComparisonUi.ACTIVITY_STOPPED
        }
        runner?.cancel()
        work?.cancel()
        if (!cancelWatchdogPosted) {
            cancelWatchdogPosted = true
            handler.postDelayed(cancelWatchdog, UI_WAIT_MS)
        }
    }

    override fun onStop() { stop("activity_stopped"); super.onStop() }
    override fun onDestroy() {
        stop("activity_destroyed")
        handler.removeCallbacks(deadline)
        handler.removeCallbacks(cancelWatchdog)
        preflightJob?.cancel()
        importJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val REPORT_MAX_BYTES = 512 * 1024
        const val UI_WAIT_MS = 20_000L
        const val REQUEST_OPEN_MODEL = 4201
    }
}
