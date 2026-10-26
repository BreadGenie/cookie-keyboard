package org.futo.inputmethod.latin

import android.content.Context
import android.os.UserManager
import android.view.inputmethod.InputMethodManager
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import org.futo.inputmethod.latin.uix.DataStoreHelper
import org.futo.inputmethod.latin.uix.FileKind
import org.futo.inputmethod.latin.uix.ResourceHelper
import org.futo.inputmethod.latin.uix.getPreferencesDataStoreFile
import org.futo.inputmethod.latin.uix.preferenceKeyFor
import org.futo.voiceinput.shared.opencl.ComparisonModelMapping
import org.futo.voiceinput.shared.opencl.ComparisonModelMappingCore
import org.futo.voiceinput.shared.opencl.ModelKeyProbe
import java.io.File
import java.util.Locale

/**
 * Read-only model mapping for the debug comparison Activity.
 *
 * Post-v43 correction: a standalone debug Activity calls
 * `RichInputMethodManager.init` before `LatinIME.onCreate`, so the production
 * `DataStoreHelper` cache can still be empty. In that state RIMM's
 * `getActiveSubtype()` falls back to `en_US:` and the resolver reports the
 * builtin tiny model even when the user's persisted keyboard subtype is
 * `en_IN` with a non-empty imported file. The previous report labelled that
 * cold debug default as the active/dictation locale, which was misleading.
 *
 * This report now records three independent facts separately:
 *
 *  1. the locale the *debug process* passed to the production resolver and
 *     why it has that value (cold RIMM default vs initialized cache);
 *  2. the locale of the keyboard actually selected on the device, from
 *     `InputMethodManager.currentInputMethodSubtype` and, failing that, the
 *     persisted FUTO `activeSubtype` value in the DataStore file;
 *  3. the everyday model implied by the authoritative DataStore file read for
 *     that actual locale (imported file vs builtin), independent of the cold
 *     in-process resolver cache.
 *
 * It never initializes the production cache. `DataStoreHelper.init` installs
 * a process-global collector and can create/update the DataStore (including a
 * subtype write under direct boot), so it is not a read-only operation and is
 * not safe to run from a debug Activity that may share the IME process. When
 * the actual locale cannot be established, the report says so explicitly
 * instead of borrowing the cold default's answer.
 *
 * Values, filenames, URIs and paths are never recorded; probes carry only the
 * production setting-key name and three booleans.
 */
object SttModelMapping {
    private val CANDIDATE_LOCALE_FORMS = { locale: Locale ->
        listOf(
            locale.toString(),
            locale.language,
            "${locale.language}_${locale.country.ifEmpty { locale.language }}",
            "${locale.language.lowercase()}_${locale.country.ifEmpty { locale.language }.uppercase()}",
        ).distinct()
    }

    /** Production's persisted subtype key (`Subtypes.ActiveSubtype`). */
    private val ACTIVE_SUBTYPE = stringPreferencesKey("activeSubtype")

    suspend fun resolve(
        context: Context,
        debugLocale: Locale?,
        resolverSource: String,
    ): ComparisonModelMapping {
        val cacheInitialized = runCatching { DataStoreHelper.isInitializedForDebug() }.getOrNull()
        val systemTag = systemSubtypeLocaleTag(context)
        val preferences = readPreferences(context)
            ?: return unknown(
                context = context,
                debugLocale = debugLocale,
                resolverSource = resolverSource,
                reason = ComparisonModelMappingCore.INSPECTION_FAILED,
                cacheInitialized = cacheInitialized,
                systemTag = systemTag,
                persistedTag = null,
            )

        val persistedTag = persistedSubtypeLocaleTag(preferences)
        val systemLocale = ComparisonModelMappingCore.localeFromTag(systemTag)
        val persistedLocale = ComparisonModelMappingCore.localeFromTag(persistedTag)
        val actualLocale = systemLocale ?: persistedLocale
        val actualSource = when {
            actualLocale == null -> ComparisonModelMappingCore.UNKNOWN
            systemLocale != null -> ComparisonModelMappingCore.SYSTEM_IME_SUBTYPE
            else -> ComparisonModelMappingCore.DATASTORE_ACTIVE_SUBTYPE
        }
        val candidateLocale = actualLocale ?: debugLocale
        val candidateSource = if (actualLocale != null) {
            ComparisonModelMappingCore.ACTUAL_KEYBOARD_LOCALE
        } else {
            ComparisonModelMappingCore.DEBUG_RESOLVER_LOCALE
        }

        val candidates = candidateLocale?.let { locale ->
            CANDIDATE_LOCALE_FORMS(locale).map { localeString ->
                probe(context, preferences, FileKind.VoiceInput.preferenceKeyFor(localeString))
            }
        } ?: emptyList()

        // The production lookup itself. It is cache-dependent by design; the
        // report records that fact instead of replacing it with a guess.
        val effectiveLocale = candidateLocale?.let { locale ->
            runCatching {
                ResourceHelper.findKeyForLocaleAndKind(context, locale, FileKind.VoiceInput)
            }.getOrNull()
        }
        val effectiveKey = effectiveLocale?.let {
            FileKind.VoiceInput.preferenceKeyFor(it).name
        }

        val englishKeys = ComparisonModelMappingCore
            .boundedEnglishKeys(preferences.asMap().keys.map { it.name })
            .map { name -> probe(context, preferences, stringPreferencesKey(name)) }

        val actualKnown = actualLocale != null
        val authoritativeProbe = ComparisonModelMappingCore.authoritative(candidates)
        val authoritativeSource = when {
            !actualKnown -> ComparisonModelMappingCore.ACTUAL_MODEL_UNKNOWN
            authoritativeProbe != null -> ComparisonModelMappingCore.ACTUAL_MODEL_IMPORTED
            else -> ComparisonModelMappingCore.ACTUAL_MODEL_BUILTIN
        }
        // A cold in-process resolver cannot claim what production's real IME
        // process would resolve; do not surface its builtin answer as fact.
        val resolverSourceEffective = if (cacheInitialized == false &&
            resolverSource.startsWith("builtin_asset")
        ) {
            RESOLVER_CACHE_COLD
        } else {
            resolverSource
        }
        val explanation = ComparisonModelMappingCore.classify(
            inspectionOk = true,
            resolverCacheInitialized = cacheInitialized,
            resolverSource = resolverSourceEffective,
            candidates = candidates,
            englishKeys = englishKeys,
            actualKnown = actualKnown,
        )

        return ComparisonModelMapping(
            devicePackage = context.packageName,
            deviceFlavor = BuildConfig.FLAVOR,
            deviceBuildType = BuildConfig.BUILD_TYPE,
            localeTag = debugLocale?.toLanguageTag() ?: "unknown",
            localeToString = debugLocale?.toString() ?: "unknown",
            localeLanguage = debugLocale?.language ?: "unknown",
            localeCountry = debugLocale?.country ?: "unknown",
            candidateKeys = candidates,
            englishKeys = englishKeys,
            effectiveKey = effectiveKey,
            effectiveLocale = effectiveLocale,
            resolverCacheInitialized = cacheInitialized,
            resolverSource = resolverSourceEffective,
            authoritativeSource = authoritativeSource,
            authoritativeKey = authoritativeProbe?.key,
            explanation = explanation,
            debugLocaleSource = debugLocaleSource(cacheInitialized),
            systemSubtypeLocaleTag = systemTag,
            persistedSubtypeLocaleTag = persistedTag,
            actualLocaleTag = actualLocale?.toLanguageTag() ?: "unknown",
            actualLocaleToString = actualLocale?.toString() ?: "unknown",
            actualLocaleLanguage = actualLocale?.language ?: "unknown",
            actualLocaleCountry = actualLocale?.country ?: "unknown",
            actualLocaleSource = actualSource,
            actualLocaleKnown = actualKnown,
            actualModelSource = authoritativeSource,
            actualModelKnown = actualKnown,
            resolverAuthoritative = cacheInitialized == true,
            candidateLocaleSource = candidateSource,
        )
    }

    fun unknown(
        context: Context,
        debugLocale: Locale?,
        resolverSource: String,
        reason: String = ComparisonModelMappingCore.INSPECTION_FAILED,
        cacheInitialized: Boolean? = runCatching { DataStoreHelper.isInitializedForDebug() }.getOrNull(),
        systemTag: String? = null,
        persistedTag: String? = null,
    ): ComparisonModelMapping {
        val systemLocale = ComparisonModelMappingCore.localeFromTag(systemTag)
        val persistedLocale = ComparisonModelMappingCore.localeFromTag(persistedTag)
        val actualLocale = systemLocale ?: persistedLocale
        val actualSource = when {
            actualLocale == null -> ComparisonModelMappingCore.UNKNOWN
            systemLocale != null -> ComparisonModelMappingCore.SYSTEM_IME_SUBTYPE
            else -> ComparisonModelMappingCore.DATASTORE_ACTIVE_SUBTYPE
        }
        return ComparisonModelMapping(
            devicePackage = context.packageName,
            deviceFlavor = BuildConfig.FLAVOR,
            deviceBuildType = BuildConfig.BUILD_TYPE,
            localeTag = debugLocale?.toLanguageTag() ?: "unknown",
            localeToString = debugLocale?.toString() ?: "unknown",
            localeLanguage = debugLocale?.language ?: "unknown",
            localeCountry = debugLocale?.country ?: "unknown",
            candidateKeys = emptyList(),
            englishKeys = emptyList(),
            effectiveKey = null,
            effectiveLocale = null,
            resolverCacheInitialized = cacheInitialized,
            resolverSource = resolverSource,
            authoritativeSource = ComparisonModelMappingCore.ACTUAL_MODEL_UNKNOWN,
            authoritativeKey = null,
            explanation = reason,
            debugLocaleSource = debugLocaleSource(cacheInitialized),
            systemSubtypeLocaleTag = systemTag,
            persistedSubtypeLocaleTag = persistedTag,
            actualLocaleTag = actualLocale?.toLanguageTag() ?: "unknown",
            actualLocaleToString = actualLocale?.toString() ?: "unknown",
            actualLocaleLanguage = actualLocale?.language ?: "unknown",
            actualLocaleCountry = actualLocale?.country ?: "unknown",
            actualLocaleSource = actualSource,
            actualLocaleKnown = actualLocale != null,
            actualModelSource = ComparisonModelMappingCore.ACTUAL_MODEL_UNKNOWN,
            actualModelKnown = false,
            resolverAuthoritative = cacheInitialized == true,
            candidateLocaleSource = ComparisonModelMappingCore.DEBUG_RESOLVER_LOCALE,
        )
    }

    private fun debugLocaleSource(cacheInitialized: Boolean?): String = when (cacheInitialized) {
        true -> ComparisonModelMappingCore.DEBUG_LOCALE_INITIALIZED
        false -> ComparisonModelMappingCore.DEBUG_LOCALE_COLD_DEFAULT
        null -> ComparisonModelMappingCore.UNKNOWN
    }

    /**
     * The system's current subtype is the keyboard the user actually has
     * selected. It is read-only and does not initialize RIMM or DataStore.
     */
    private fun systemSubtypeLocaleTag(context: Context): String? = runCatching {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return null
        val subtype = manager.currentInputMethodSubtype ?: return null
        @Suppress("DEPRECATION")
        val raw = subtype.locale?.takeIf { it.isNotBlank() } ?: subtype.languageTag
        ComparisonModelMappingCore.boundedLocaleTag(raw)
    }.getOrNull()

    /** The FUTO keyboard's own persisted selection, independent of RIMM's cache. */
    private fun persistedSubtypeLocaleTag(preferences: Preferences): String? =
        ComparisonModelMappingCore.boundedLocaleTag(preferences[ACTIVE_SUBTYPE])

    private suspend fun readPreferences(context: Context): Preferences? = withContext(Dispatchers.IO) {
        runCatching {
            val user = context.getSystemService(Context.USER_SERVICE) as UserManager
            if (!user.isUserUnlocked) return@runCatching null
            val file = context.getPreferencesDataStoreFile()
            if (!file.isFile) {
                preferencesOf()
            } else {
                file.source().buffer().use { PreferencesSerializer.readFrom(it) }
            }
        }.getOrNull()
    }

    private fun probe(
        context: Context,
        preferences: Preferences,
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
    ): ModelKeyProbe {
        val present = preferences.asMap().containsKey(key)
        val value = preferences[key]
        val nonEmpty = !value.isNullOrEmpty()
        val fileExists = if (nonEmpty) {
            val dir = context.getExternalFilesDir(null)
            dir != null && File(dir, value).isFile
        } else {
            false
        }
        return ModelKeyProbe(
            key = key.name,
            present = present,
            nonEmpty = nonEmpty,
            fileExists = fileExists,
        )
    }

    private const val RESOLVER_CACHE_COLD = "unknown_cache_not_initialized"
}
