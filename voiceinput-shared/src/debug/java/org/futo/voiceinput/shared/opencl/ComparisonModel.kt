package org.futo.voiceinput.shared.opencl

import org.futo.voiceinput.shared.types.ModelBuiltInAsset
import org.futo.voiceinput.shared.types.ModelDownloadable
import org.futo.voiceinput.shared.types.ModelFileFile
import org.futo.voiceinput.shared.types.ModelLoader
import java.io.File

/**
 * Debug-only model resolution for the comparison harness (v41).
 *
 * The v40 phone run reported `Active model is not file-backed English-244`
 * with every sample/prepare/warmup empty. The cause is resolution, not a
 * missing model: production dictation calls
 * `ResourceHelper.tryFindingVoiceInputModelForLocale`, which returns the
 * imported voice-input file when that locale has one and otherwise the
 * BUILT-IN English fallback `ModelBuiltInAsset("tiny_en_acft_q8_0...")`.
 * `ModelLoader.openClFile` is null for that built-in asset, so the harness
 * aborted before any native work — a correct refusal, an unhelpful report.
 *
 * This resolver keeps production's priority order and only adds bounded
 * comparison sources:
 *
 * 0. v42: a file the user explicitly selected and the app already copied,
 *    bounded and full-SHA verified into private storage. This is a comparison
 *    source only; everyday dictation selection is not touched.
 * 1. The exact loader dictation would use, when it is file-backed. Nothing
 *    is overridden and everyday selection is untouched.
 * 2. The production catalog entry whose declared checksum is the pinned
 *    English-244 SHA (ENGLISH_MODELS' ModelDownloadable). That is a single
 *    declared path, not a directory scan and not a filename guess: the file
 *    found there still has to pass the full SHA gate in PhoneComparison.
 * 3. Nothing. The harness then refuses with an exact, actionable setting
 *    instead of an internal message.
 *
 * No personal path, name, or transcript is ever recorded.
 */
internal data class ComparisonModelChoice(
    /** One of [SOURCE_USER_SELECTED], [SOURCE_IMPORTED], [SOURCE_PINNED_CATALOG], [SOURCE_UNAVAILABLE]. */
    val source: String,
    val loader: ModelLoader?,
    val file: File?,
    /** Bounded description of what everyday dictation resolves to right now. */
    val dictationSource: String,
    /** Exact remedy when dictation is not already on the pinned English-244 file. */
    val action: String?,
    /** True only when the comparison file is the same file dictation resolves. */
    val matchesDictation: Boolean,
) {
    companion object {
        const val SOURCE_USER_SELECTED = "user_selected_verified_file"
        const val SOURCE_IMPORTED = "active_imported_file"
        const val SOURCE_PINNED_CATALOG = "debug_pinned_catalog_file"
        const val SOURCE_UNAVAILABLE = "unavailable"
    }
}

internal object ComparisonModel {
    /** The exact setting path the phone user needs; deliberately content-free. */
    const val ACTION =
        "Settings -> Languages -> English -> Voice input model -> Import, choose the downloaded " +
            "English-244 file (for example voice-input-english-244.bin; the file is checked by exact " +
            "size and SHA-256 58fbe949...3aa8, not by its name) for the active English layout, then " +
            "reopen STT comparison. Everyday dictation selection is only changed by that import."

    /**
     * [selectedFile] is the sealed, already-verified private copy the user
     * chose in the debug picker (v42), or null. [dictationLoader]/
     * [dictationFile] are production's own answer and `openClFile` for it
     * (null when the active model is the built-in asset).
     * [pinnedCatalogLoader]/[pinnedCatalogFile] come from the production model
     * catalog entry whose checksum equals [OpenClPolicy.MODEL_SHA], only when
     * that declared private file already exists. Nothing here downloads,
     * copies, or provisions a model.
     */
    fun choose(
        selectedFile: File?,
        dictationLoader: ModelLoader?,
        dictationFile: File?,
        pinnedCatalogLoader: ModelLoader?,
        pinnedCatalogFile: File?,
    ): ComparisonModelChoice {
        val dictationSource = describe(dictationLoader, dictationFile)
        if (selectedFile != null) {
            return ComparisonModelChoice(
                source = ComparisonModelChoice.SOURCE_USER_SELECTED,
                loader = dictationLoader,
                file = selectedFile,
                dictationSource = dictationSource,
                action = null,
                matchesDictation = sameFile(selectedFile, dictationFile),
            )
        }
        if (dictationLoader != null && dictationFile != null) {
            return ComparisonModelChoice(
                source = ComparisonModelChoice.SOURCE_IMPORTED,
                loader = dictationLoader,
                file = dictationFile,
                dictationSource = dictationSource,
                action = null,
                matchesDictation = true,
            )
        }
        if (pinnedCatalogLoader != null && pinnedCatalogFile != null) {
            return ComparisonModelChoice(
                source = ComparisonModelChoice.SOURCE_PINNED_CATALOG,
                loader = pinnedCatalogLoader,
                file = pinnedCatalogFile,
                dictationSource = dictationSource,
                action = ACTION,
                matchesDictation = false,
            )
        }
        return ComparisonModelChoice(
            source = ComparisonModelChoice.SOURCE_UNAVAILABLE,
            loader = null,
            file = null,
            dictationSource = dictationSource,
            action = ACTION,
            matchesDictation = false,
        )
    }

    /**
     * The production catalog entry for English-244, selected by the declared
     * checksum rather than by a filename: a decoy file with a matching name
     * and a different checksum is never returned.
     */
    fun pinnedEnglish244(models: List<ModelLoader>): ModelDownloadable? =
        models.filterIsInstance<ModelDownloadable>().firstOrNull {
            it.checksum.equals(OpenClPolicy.MODEL_SHA, ignoreCase = true)
        }

    /** Bounded loader kind for the report; never a path. */
    fun kind(loader: ModelLoader?): String = when (loader) {
        null -> "none"
        is ModelFileFile -> "imported_file"
        is ModelDownloadable -> "downloadable_file"
        is ModelBuiltInAsset -> "builtin_asset"
        else -> loader.javaClass.simpleName
    }

    private fun describe(loader: ModelLoader?, file: File?): String = when (loader) {
        null -> "none"
        is ModelBuiltInAsset -> "builtin_asset:${loader.ggmlFile}"
        is ModelFileFile -> if (file != null) "imported_file" else "imported_file_missing"
        is ModelDownloadable -> if (file != null) "downloadable_file" else "downloadable_file_missing"
        else -> kind(loader)
    }

    /** Content-free path identity; never serialized. */
    private fun sameFile(a: File?, b: File?): Boolean {
        if (a == null || b == null) return false
        return runCatching { a.canonicalFile == b.canonicalFile }
            .getOrDefault(a.absolutePath == b.absolutePath)
    }
}
