package org.futo.inputmethod.latin.whistle

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.content.res.Configuration
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Debug only. Explicit model/WAV selection; never editor or history delivery. */
class WhistleDiagnosticActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var readiness: TextView
    private lateinit var importButton: Button
    private lateinit var silenceButton: Button
    private lateinit var restartButton: Button
    private lateinit var wavButton: Button
    private lateinit var transcriptView: TextView
    private lateinit var revealButton: Button
    private lateinit var copyTextButton: Button
    private var transcript: String? = null
    @Volatile private var audioInput: java.io.InputStream? = null
    @Volatile private var client: WhistleClient? = null
    @Volatile private var destroyed = false
    private var running = false
    private var verified = false
    @Volatile private var userCancelled = false
    private var report = "No test run."
    private val model get() = File(noBackupFilesDir, "whistle-diagnostic.cact")
    override fun onCreate(state: Bundle?) {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
        val spacing = dp(16)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
        }
        fun text(value: String) = TextView(this).apply {
            text = value
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
            layout.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            textSize = 16f
            // Keep disabled labels legible; native disabled backgrounds and readiness explain availability.
            setTextColor(textColors.defaultColor)
            isAllCaps = false
            setSingleLine(false)
            minHeight = dp(48)
            setOnClickListener { action() }
            layout.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        text("Whistle validation").apply { textSize = 22f; if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
        // Put import before explanatory/status text, which can grow without hiding actions.
        importButton = button("Import model") { if(!running) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/octet-stream"; addCategory(Intent.CATEGORY_OPENABLE) }, 1) }
        readiness = text("")
        silenceButton = button("Run silence test") { run(false) }
        restartButton = button("Test cancel and restart") { run(true) }
        wavButton = button("Select WAV for speech test") {
            if(!running) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            }, 2)
        }
        button("Cancel") { cancelValidation(); show("User cancellation requested. Replacement still requires Binder death.") }
        revealButton = button("Show transcript in diagnostic") { transcriptView.text = transcript ?: "No completed speech transcript." }
        copyTextButton = button("Copy transcript (clipboard)") {
            transcript?.let { value ->
                val clip = android.content.ClipData.newPlainText("Whistle transcript", value)
                if(Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
            }
        }
        transcriptView = text("").apply { isSaveEnabled = false }

        button("Copy diagnostic report") {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Whistle diagnostic",
                WhistleDiagnostics.comparisonReport(org.futo.inputmethod.latin.BuildConfig.VERSION_CODE,
                    org.futo.inputmethod.latin.BuildConfig.VERSION_NAME, report,
                    org.futo.voiceinput.shared.opencl.OpenClDiagnostics.export(this))))
        }
        text("No microphone, editor delivery or automatic submission. Select a local little-endian RIFF WAV: 16000 Hz, mono, signed PCM16, maximum 32 MiB. Selecting a file starts its speech test; no audio copy or persistent URI permission is retained. Transcript stays in memory until cancel, next test, or leaving this screen. Show/copy requires a separate tap; copying places private text on the system clipboard. Import the pinned 16,919,407-byte whistle.cact model first. Phone speech validation remains pending.")
        status = text("No test run.").apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = true
            val background = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.colorBackground, background, true)
            setBackgroundColor(background.data)
            addView(layout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (Build.VERSION.SDK_INT < 30) fitsSystemWindows = true
            else setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
        updateControls()
        running = true; updateControls()
        executor.execute {
            val result = try { WhistleModel.verify(model) } catch(_: Exception) { false }
            runOnUiThread { verified = result; running = false; if(!destroyed) updateControls() }
        }
    }
    private fun updateControls() {
        val ready = verified && model.isFile
        readiness.text = if (running) "Verification or validation busy. Cancel remains available."
            else if (ready) "Verified model ready. Choose a test."
            else "Model required. Tap Import model to enable tests."
        importButton.isEnabled = !running
        silenceButton.isEnabled = ready && !running
        restartButton.isEnabled = ready && !running
        wavButton.isEnabled = ready && !running
        revealButton.isEnabled = !running && transcript != null
        copyTextButton.isEnabled = !running && transcript != null
    }
    private fun show(message: String) { runOnUiThread { if(!destroyed) { status.text = message; updateControls() } } }
    private fun record(value: String, message: String) { runOnUiThread { if(!destroyed) {
        report = "Whistle debug v${org.futo.inputmethod.latin.BuildConfig.VERSION_CODE} (${org.futo.inputmethod.latin.BuildConfig.VERSION_NAME})\n$value\nNo microphone or editor delivery. Replacement requires confirmed Binder death."
        status.text = "$message\n$report"; updateControls()
    } } }
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if(running) return
        if(request == 2) {
            if(result == RESULT_OK && data?.data != null) run(false, data.data)
            return
        }
        if(request != 1) return
        if(result != RESULT_OK || data?.data == null) { record(WhistleDiagnostics.report(WhistleStage.IMPORT, WhistleCode.USER_CANCEL), "Import cancelled; existing verified copy, if any, is unchanged."); return }
        val uri = data.data!!
        running = true
        record("stage=IMPORT", "Importing and verifying private copy.")
        updateControls()
        executor.execute {
            val temp = File(noBackupFilesDir, "whistle-import.tmp")
            try {
                val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
                contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { out ->
                    val buffer = ByteArray(65536)
                    while(true) { val n = input.read(buffer); if(n < 0) break
                        count += n; check(count <= 16919407); digest.update(buffer, 0, n); out.write(buffer, 0, n)
                    }
                } }
                check(count == 16919407L && digest.digest().joinToString("") { "%02x".format(it) } == "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb")
                check(temp.renameTo(model)); verified = true; record("stage=IMPORT code=VERIFIED", "Private model verified. No inference run.")
            } catch(e: Exception) { record(WhistleDiagnostics.report(WhistleStage.IMPORT, WhistleCode.EXCEPTION, e), "Import failed. No replacement activated; existing verified copy, if any, is unchanged.") }
            finally { temp.delete(); runOnUiThread { running = false; if(!destroyed) updateControls() } }
        }
    }
    private fun run(restart: Boolean, wav: android.net.Uri? = null) {
        if(running || !verified || !model.isFile) return
        clearTranscript(); running = true; userCancelled = false; record("stage=MODEL_VERIFY", "Validation running; cancel remains independent.")
        executor.execute {
            var phase = WhistleStage.MODEL_VERIFY
            try {
                verified = WhistleModel.verify(model)
                if(!verified) throw WhistleDiagnostics.failure(phase, if(model.isFile) WhistleCode.UNVERIFIED else WhistleCode.ABSENT)
                check(!destroyed && !userCancelled)
                phase = WhistleStage.CONNECT
                var c = WhistleClient(applicationContext); client = c; c.connect()
                if(restart) {
                    // Native may be blocked when the independent control transaction arrives.
                    val first = c
                    val killer = Thread { Thread.sleep(100); first.cancel() }; killer.start()
                    try { check(!destroyed); first.load(model) } catch(_: Exception) { }
                    killer.join(); if(!first.awaitDeath()) throw WhistleDiagnostics.failure(WhistleStage.DEATH, WhistleCode.TIMEOUT)
                    check(!destroyed && !userCancelled); c = WhistleClient(applicationContext); client = c; c.connect()
                }
                check(!destroyed && !userCancelled); phase = WhistleStage.LOAD; show("Phase: load"); c.load(model)
                phase = WhistleStage.FEED
                val finalText = if(wav == null) {
                    show("Phase: feed zero PCM"); c.feed(FloatArray(16000))
                    phase = WhistleStage.FINISH; show("Phase: finish and reclaim")
                    c.finishTextAndReclaim()
                } else {
                    show("Phase: stream selected WAV; cancel remains available")
                    val input = contentResolver.openInputStream(wav) ?: error("Input unavailable")
                    audioInput = input
                    try { input.use {
                        WhistleWav.stream(it, { destroyed || userCancelled }, { pcm -> c.feed(pcm) }, {
                            phase = WhistleStage.FINISH; show("Phase: finish and reclaim")
                            c.finishTextAndReclaim()
                        })
                    } } finally { audioInput = null }
                }
                val chars = finalText.length
                val completed = c
                runOnUiThread {
                    if(!destroyed && !userCancelled && completed.deliveryAllowed()) {
                        if(wav != null) transcript = finalText
                        record("stage=DEATH code=CONFIRMED outputCharacters=$chars", if(wav != null)
                            "Speech test completed; Binder death confirmed. Transcript available only through Show / Copy transcript."
                            else if(chars == 0) "Silence test passed." else "Silence test failed: nonzero output.")
                    }
                }
            } catch(e: Exception) {
                val diagnostic = if(e is WhistleWavException && !userCancelled) "stage=FEED code=WAV_${e.problem}"
                    else if(userCancelled) WhistleDiagnostics.report(phase, WhistleCode.USER_CANCEL)
                    else if(e is WhistleFailure) e.report
                    else client?.diagnostic() ?: WhistleDiagnostics.report(phase, WhistleCode.EXCEPTION, e)
                val c = client; c?.cancel()
                val death = c?.awaitDeath() == true
                record("operation=$phase\n$diagnostic\ndeath=" + (if(death) "CONFIRMED" else "UNCONFIRMED") + (if(!death) c?.cleanupDiagnostic()?.let { "\n$it" } ?: "" else ""),
                    if(userCancelled) "Validation cancelled by user. No text inserted." else if(e is WhistleWavException) e.problem.explanation else "Validation failed. No text inserted.")
            }
            finally { runOnUiThread { client = null; running = false; if(!destroyed) updateControls() } }
        }
    }
    private fun clearTranscript() { transcript = null; transcriptView.text = "" }
    private fun cancelValidation() {
        clearTranscript()
        if(running) {
            userCancelled = true; client?.cancel()
            val input = audioInput
            if(input != null) Thread({ try { input.close() } catch(_: Exception) {} }, "whistle-close-wav").start()
        }
        updateControls()
    }
    override fun onStop() { cancelValidation(); super.onStop() }
    override fun onDestroy() { destroyed = true; client?.cancel(); executor.shutdown(); super.onDestroy() }
}
