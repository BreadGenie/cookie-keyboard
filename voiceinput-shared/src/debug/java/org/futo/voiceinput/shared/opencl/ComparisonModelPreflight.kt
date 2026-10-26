package org.futo.voiceinput.shared.opencl

import android.content.Context
import org.futo.voiceinput.shared.ENGLISH_MODELS
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.types.openClFile
import java.io.File

/**
 * Bounded, content-free model readiness for the v42 debug Activity.
 *
 * The Activity needs to offer Start only when a comparison model is actually
 * resolvable: either the user's sealed private copy, production's file-backed
 * dictation model, or the already-present production catalog file. Model
 * readiness deliberately does not depend on the locale resolver returning a
 * *file-backed* loader, because the v41 phone run showed that an active English
 * layout can legitimately resolve to the built-in tiny.en asset. The active
 * English layout condition itself is still enforced.
 *
 * No path, URI or filename is put into the returned message.
 */
class ComparisonPreflight(
    val englishActive: Boolean,
    val ready: Boolean,
    val source: String?,
    val selectedVerified: Boolean,
    val dictationSource: String,
    val message: String,
    val action: String?,
)

object ComparisonModelPreflight {
    /** Shown while nothing verifiable exists yet; the chooser hint is explicit. */
    const val HINT =
        "No verified English-244 comparison model yet. Tap Choose comparison model and select the downloaded " +
            "English-244 .bin (for example voice-input-english-244.bin). The file is checked by exact size " +
            "and SHA-256; its name is not trusted. Everyday dictation is not changed."

    fun resolve(
        context: Context,
        englishActive: Boolean,
        dictationLoader: ModelLoader?,
        selectedFile: File?,
    ): ComparisonPreflight {
        val selected = selectedFile?.takeIf { it.isFile }
        val dictationFile = dictationLoader?.openClFile(context)?.takeIf { it.isFile }
        val catalog = ComparisonModel.pinnedEnglish244(ENGLISH_MODELS)
        val catalogFile = catalog?.let { File(context.filesDir, it.ggmlFile) }?.takeIf { it.isFile }
        val choice = ComparisonModel.choose(selected, dictationLoader, dictationFile, catalog, catalogFile)
        val selectedVerified = selected != null &&
            choice.source == ComparisonModelChoice.SOURCE_USER_SELECTED
        val ready = englishActive && choice.file != null
        val message = when {
            !englishActive ->
                "Select an English keyboard layout first. Start stays disabled; no comparison will run."
            !ready -> HINT
            choice.source == ComparisonModelChoice.SOURCE_USER_SELECTED ->
                "Selected model ready: English-244 verified private copy of $EXPECTED_BYTES_LABEL bytes. " +
                    "Start comparison uses this file. Everyday dictation may use a different model."
            choice.source == ComparisonModelChoice.SOURCE_IMPORTED ->
                "Active English dictation model file is available. It is fully SHA-256 verified before any arm " +
                    "runs. Tap Start comparison."
            choice.source == ComparisonModelChoice.SOURCE_PINNED_CATALOG ->
                "Production English-244 catalog file is available. It is fully SHA-256 verified before any arm " +
                    "runs. Tap Start comparison."
            else -> HINT
        }
        return ComparisonPreflight(
            englishActive = englishActive,
            ready = ready,
            source = choice.source,
            selectedVerified = selectedVerified,
            dictationSource = choice.dictationSource,
            message = message,
            action = choice.action,
        )
    }

    fun unavailable(error: String): ComparisonPreflight = ComparisonPreflight(
        englishActive = false,
        ready = false,
        source = null,
        selectedVerified = false,
        dictationSource = "unknown",
        message = "Could not inspect the active keyboard layout ($error). Start stays disabled until the " +
            "active English layout can be inspected; no comparison will run.",
        action = null,
    )

    private val EXPECTED_BYTES_LABEL: String =
        "%,d".format(java.util.Locale.US, ComparisonModelStore.EXPECTED_BYTES)
}
