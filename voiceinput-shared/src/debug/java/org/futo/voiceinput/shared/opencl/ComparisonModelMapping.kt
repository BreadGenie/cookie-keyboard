package org.futo.voiceinput.shared.opencl

import java.util.Locale

/**
 * v43 read-only debug model-mapping report.
 *
 * The v41/v42 reports could say `dictation_model_source=builtin_asset` but not
 * *which* production setting key was attempted, whether that key existed,
 * whether the recorded file existed, or whether another English region had a
 * usable import. This report answers those questions without changing any
 * preference or dictation behavior.
 *
 * Privacy boundary: a [ModelKeyProbe] contains only the production setting-key
 * name (for example `resource_VoiceInput_en_IN`) and booleans. The recorded
 * preference value (a private filename) and its filesystem path are never
 * copied into the probe or the report.
 */
data class ModelKeyProbe(
    val key: String,
    val present: Boolean,
    val nonEmpty: Boolean,
    val fileExists: Boolean,
)

/**
 * The bounded, content-free mapping result. All locale/key fields are safe to
 * show: they are derived from the active keyboard subtype and the production
 * setting-key naming scheme, not from a chosen file.
 */
data class ComparisonModelMapping(
    val devicePackage: String,
    val deviceFlavor: String,
    val deviceBuildType: String,
    /**
     * Locale the *debug process* passed to the production resolver. With a
     * cold `DataStoreHelper` cache this is the RIMM fallback (`en_US:`), not
     * necessarily the keyboard subtype the user actually selected.
     */
    val localeTag: String,
    val localeToString: String,
    val localeLanguage: String,
    val localeCountry: String,
    /** In production lookup order, deduplicated by the caller. */
    val candidateKeys: List<ModelKeyProbe>,
    /** English `resource_VoiceInput_*` keys only, bounded and sorted. */
    val englishKeys: List<ModelKeyProbe>,
    val effectiveKey: String?,
    val effectiveLocale: String?,
    /**
     * True only when the production `DataStoreHelper` cache has been
     * initialized in this process (LatinIME.onCreate). A cold debug Activity
     * can therefore see the built-in fallback even when the authoritative
     * DataStore file contains an import.
     */
    val resolverCacheInitialized: Boolean?,
    val resolverSource: String,
    val authoritativeSource: String,
    val authoritativeKey: String?,
    val explanation: String,
    /** Why the debug resolver locale above has that value. */
    val debugLocaleSource: String = "unknown",
    /** `InputMethodManager.currentInputMethodSubtype` locale, if any. */
    val systemSubtypeLocaleTag: String? = null,
    /** Persisted FUTO `activeSubtype` locale from the DataStore file, if any. */
    val persistedSubtypeLocaleTag: String? = null,
    /**
     * The locale of the keyboard actually selected on the device. Preference
     * order is the live system subtype, then the persisted FUTO subtype. This
     * is never inferred from the cold debug resolver.
     */
    val actualLocaleTag: String = localeTag,
    val actualLocaleToString: String = localeToString,
    val actualLocaleLanguage: String = localeLanguage,
    val actualLocaleCountry: String = localeCountry,
    val actualLocaleSource: String = ComparisonModelMappingCore.UNKNOWN,
    val actualLocaleKnown: Boolean = false,
    /**
     * `imported_file` when the actual locale's first non-empty production key
     * has an existing file in the authoritative DataStore read, otherwise
     * `builtin_asset`; `unknown` when the actual locale itself is unknown.
     * This is the everyday model the next real keyboard process would resolve,
     * not this debug process's cold-cache resolver answer.
     */
    val actualModelSource: String = ComparisonModelMappingCore.ACTUAL_MODEL_UNKNOWN,
    val actualModelKnown: Boolean = false,
    /** True only when the in-process resolver answer is backed by an initialized cache. */
    val resolverAuthoritative: Boolean = resolverCacheInitialized == true,
    /** Which locale produced [candidateKeys]: `actual_keyboard_locale` or `debug_resolver_locale`. */
    val candidateLocaleSource: String = ComparisonModelMappingCore.DEBUG_RESOLVER_LOCALE,
) {
    /** Bounded on-screen summary; keys and booleans only, never a value/path. */
    fun summary(): String = buildString {
        append("Mapping ")
        append(devicePackage).append(" (").append(deviceFlavor).append('/').append(deviceBuildType).append(")")
        append(" · debug resolver ").append(localeToString)
        append(" [tag ").append(localeTag).append("]")
        append(" · actual keyboard ")
        if (actualLocaleKnown) {
            append(actualLocaleToString).append(" [tag ").append(actualLocaleTag).append("]")
        } else {
            append("unknown")
        }
        append(" via ").append(actualLocaleSource)
        append(" · system subtype ").append(systemSubtypeLocaleTag ?: "unknown")
        append(" · persisted subtype ").append(persistedSubtypeLocaleTag ?: "unknown")
        append(" · actual model ").append(actualModelSource)
        append(" · effective ").append(effectiveKey ?: "none")
        append(" · resolver ").append(resolverSource.take(80))
        append(" (cache ").append(resolverCacheInitialized?.toString() ?: "unknown")
        append(", authoritative ").append(resolverAuthoritative).append(")")
        append(" · ").append(explanation)
        append(" · English keys ").append(englishKeys.size)
        if (candidateKeys.isNotEmpty()) {
            append(" · attempts for ").append(candidateLocaleSource)
            append(" ")
            append(candidateKeys.joinToString(",") { probe ->
                val state = when {
                    probe.nonEmpty && probe.fileExists -> "file"
                    probe.nonEmpty -> "missing_file"
                    probe.present -> "empty"
                    else -> "absent"
                }
                "${probe.key}:$state"
            }.take(240))
        }
    }.take(1100)
}

object ComparisonModelMappingCore {
    const val IMPORTED = "imported_file_effective"
    const val EFFECTIVE_FILE_MISSING = "effective_key_file_missing"
    const val OTHER_REGION = "other_english_region_key_present"
    const val ENGLISH_KEY_UNUSABLE = "english_key_present_but_empty_or_missing_file"
    const val NO_KEY = "no_english_voice_input_key"
    const val CACHE_NOT_INITIALIZED = "resolver_cache_not_initialized"
    const val CACHE_NOT_INITIALIZED_NO_KEY = "resolver_cache_not_initialized_no_import"
    const val RESOLVER_DISAGREES = "resolver_disagrees_with_authoritative_state"
    const val RESOLVER_IMPORTED_FILE_MISSING = "resolver_imported_but_authoritative_file_missing"
    const val INSPECTION_FAILED = "inspection_failed"
    const val MAX_ENGLISH_KEYS = 24

    /** Sources for the actual keyboard locale, in preference order. */
    const val SYSTEM_IME_SUBTYPE = "system_ime_subtype"
    const val DATASTORE_ACTIVE_SUBTYPE = "datastore_active_subtype"
    const val UNKNOWN = "unknown"

    /** Why the debug resolver locale has its value. */
    const val DEBUG_LOCALE_COLD_DEFAULT = "cold_rich_input_method_manager_default"
    const val DEBUG_LOCALE_INITIALIZED = "rich_input_method_manager_initialized"

    /** Which locale the candidate key probes belong to. */
    const val DEBUG_RESOLVER_LOCALE = "debug_resolver_locale"
    const val ACTUAL_KEYBOARD_LOCALE = "actual_keyboard_locale"

    /** What the authoritative DataStore file says everyday dictation would resolve. */
    const val ACTUAL_MODEL_IMPORTED = "imported_file"
    const val ACTUAL_MODEL_BUILTIN = "builtin_asset"
    const val ACTUAL_MODEL_UNKNOWN = "unknown"

    /** The actual keyboard locale could not be read; no model may be claimed. */
    const val ACTUAL_LOCALE_UNKNOWN = "actual_keyboard_locale_unknown"

    /**
     * Bounded locale tag from either a system subtype locale (`en_IN`) or a
     * persisted FUTO subtype (`en_IN:KeyboardLayoutSet=...:en-IN`). Only the
     * locale-shaped prefix survives; extra values are never serialized.
     */
    fun boundedLocaleTag(raw: String?): String? {
        val candidate = raw?.trim()?.substringBefore(':')?.takeIf { it.isNotEmpty() } ?: return null
        if (candidate.length > 35) return null
        if (!candidate.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return candidate.replace('_', '-')
    }

    fun localeFromTag(tag: String?): Locale? {
        val bounded = boundedLocaleTag(tag) ?: return null
        val locale = Locale.forLanguageTag(bounded)
        return locale.takeIf { it.language.isNotEmpty() && it.language != "und" }
    }

    /**
     * The everyday model implied by the *actual* keyboard locale. The direct
     * DataStore read is authoritative even when the in-process resolver cache
     * is cold; an unknown actual locale must stay `unknown` rather than
     * borrowing the debug default's answer.
     */
    fun actualModelSource(actualKnown: Boolean, candidates: List<ModelKeyProbe>): String = when {
        !actualKnown -> ACTUAL_MODEL_UNKNOWN
        authoritative(candidates) != null -> ACTUAL_MODEL_IMPORTED
        else -> ACTUAL_MODEL_BUILTIN
    }

    /**
     * Production `findKeyForLocaleAndKind` returns the first *non-empty*
     * candidate key, then `findFileForKind` checks only that key's recorded
     * file. If it is missing, the resolver falls back to builtin even when a
     * later candidate has a usable file. This deliberately mirrors that sharp
     * edge instead of presenting a friendlier answer than production.
     */
    fun authoritative(candidates: List<ModelKeyProbe>): ModelKeyProbe? =
        candidates.firstOrNull { it.nonEmpty }?.takeIf { it.fileExists }

    fun firstNonEmpty(candidates: List<ModelKeyProbe>): ModelKeyProbe? =
        candidates.firstOrNull { it.nonEmpty }

    /**
     * Keys-only English catalog filter. The suffix is the locale part of
     * `resource_VoiceInput_<locale>`; only a safe locale-shaped suffix with
     * language `en` is kept. Values are never inspected here.
     */
    fun boundedEnglishKeys(names: List<String>): List<String> = names
        .asSequence()
        .filter { it.startsWith(PREFIX) && it.length <= 80 }
        .map { it to it.removePrefix(PREFIX) }
        .filter { (_, suffix) ->
            suffix.isNotEmpty() && suffix.length <= 32 &&
                suffix.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' || c == '#' }
        }
        .filter { (_, suffix) ->
            suffix.substringBefore('_').substringBefore('-').equals("en", ignoreCase = true)
        }
        .map { it.first }
        .distinct()
        .sorted()
        .take(MAX_ENGLISH_KEYS)
        .toList()

    /**
     * Explains a builtin result without guessing a file name or a flavor.
     * [resolverCacheInitialized] is true only when the production resolver's
     * in-process cache was initialized; false makes a cold-cache cause
     * explicit instead of calling the import missing. [actualKnown] false
     * means the actual keyboard locale could not be established, so the
     * debug default's answer must not be presented as the real model.
     */
    fun classify(
        inspectionOk: Boolean,
        resolverCacheInitialized: Boolean?,
        resolverSource: String,
        candidates: List<ModelKeyProbe>,
        englishKeys: List<ModelKeyProbe>,
        actualKnown: Boolean = true,
    ): String {
        if (!inspectionOk) return INSPECTION_FAILED
        if (!actualKnown) return ACTUAL_LOCALE_UNKNOWN
        val effective = firstNonEmpty(candidates)
        val authoritative = authoritative(candidates)
        val resolverImported = resolverSource == "imported_file"

        if (authoritative != null) {
            if (resolverImported) return IMPORTED
            if (resolverCacheInitialized == false) return CACHE_NOT_INITIALIZED
            return RESOLVER_DISAGREES
        }
        if (resolverImported) return RESOLVER_IMPORTED_FILE_MISSING
        if (effective != null) return EFFECTIVE_FILE_MISSING
        if (englishKeys.any { it.nonEmpty && it.fileExists }) return OTHER_REGION
        if (englishKeys.any { it.present }) return ENGLISH_KEY_UNUSABLE
        if (resolverCacheInitialized == false) return CACHE_NOT_INITIALIZED_NO_KEY
        return NO_KEY
    }

    const val PREFIX = "resource_VoiceInput_"
}
