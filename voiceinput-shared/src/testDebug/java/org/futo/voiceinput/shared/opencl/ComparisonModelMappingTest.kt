package org.futo.voiceinput.shared.opencl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v43 read-only model mapping. These are pure host-JVM tests: the app debug
 * adapter supplies production DataStore key names and booleans; the classifier
 * and catalog bound are the same code the debug Activity uses.
 */
class ComparisonModelMappingTest {
    private fun probe(key: String, nonEmpty: Boolean = true, fileExists: Boolean = true, present: Boolean = true) =
        ModelKeyProbe(key = key, present = present, nonEmpty = nonEmpty, fileExists = fileExists)

    @Test
    fun importedFileIsEffectiveOnlyWhenTheFirstNonEmptyKeyHasAFile() {
        val candidates = listOf(
            probe("resource_VoiceInput_en_IN"),
            probe("resource_VoiceInput_en"),
        )
        assertEquals("resource_VoiceInput_en_IN", ComparisonModelMappingCore.authoritative(candidates)?.key)
        assertEquals(
            ComparisonModelMappingCore.IMPORTED,
            ComparisonModelMappingCore.classify(
                inspectionOk = true,
                resolverCacheInitialized = true,
                resolverSource = "imported_file",
                candidates = candidates,
                englishKeys = candidates,
            ),
        )
    }

    @Test
    fun productionSharpEdgeFirstNonEmptyKeyWithMissingFileFallsBackToBuiltin() {
        // Production findKeyForLocaleAndKind returns the first non-empty key,
        // then findFileForKind checks only that key. A usable `en` file cannot
        // rescue a missing `en_IN` value; the harness must mirror that.
        val candidates = listOf(
            probe("resource_VoiceInput_en_IN", nonEmpty = true, fileExists = false),
            probe("resource_VoiceInput_en", nonEmpty = true, fileExists = true),
        )
        assertNull(ComparisonModelMappingCore.authoritative(candidates))
        assertEquals(
            ComparisonModelMappingCore.EFFECTIVE_FILE_MISSING,
            ComparisonModelMappingCore.classify(
                inspectionOk = true,
                resolverCacheInitialized = true,
                resolverSource = "builtin_asset:tiny.en",
                candidates = candidates,
                englishKeys = candidates,
            ),
        )
    }

    @Test
    fun aColdResolverCacheIsNamedInsteadOfClaimingTheImportIsMissing() {
        val candidates = listOf(probe("resource_VoiceInput_en_IN"))
        assertEquals(
            ComparisonModelMappingCore.CACHE_NOT_INITIALIZED,
            ComparisonModelMappingCore.classify(
                inspectionOk = true,
                resolverCacheInitialized = false,
                resolverSource = "builtin_asset:tiny.en",
                candidates = candidates,
                englishKeys = candidates,
            ),
        )
        assertEquals(
            ComparisonModelMappingCore.CACHE_NOT_INITIALIZED_NO_KEY,
            ComparisonModelMappingCore.classify(
                inspectionOk = true,
                resolverCacheInitialized = false,
                resolverSource = "builtin_asset:tiny.en",
                candidates = listOf(probe("resource_VoiceInput_en_IN", nonEmpty = false, fileExists = false, present = false)),
                englishKeys = emptyList(),
            ),
        )
    }

    @Test
    fun anotherEnglishRegionWithAFileIsReportedAsARegionMismatch() {
        val active = listOf(
            probe("resource_VoiceInput_en_IN", present = false, nonEmpty = false, fileExists = false),
            probe("resource_VoiceInput_en", present = false, nonEmpty = false, fileExists = false),
        )
        val other = listOf(probe("resource_VoiceInput_en_US"))
        assertEquals(
            ComparisonModelMappingCore.OTHER_REGION,
            ComparisonModelMappingCore.classify(
                inspectionOk = true,
                resolverCacheInitialized = true,
                resolverSource = "builtin_asset:tiny.en",
                candidates = active,
                englishKeys = other,
            ),
        )
    }

    @Test
    fun presentButEmptyOrMissingFileIsNotCalledMissingData() {
        val english = listOf(probe("resource_VoiceInput_en_US", nonEmpty = false, fileExists = false))
        assertEquals(
            ComparisonModelMappingCore.ENGLISH_KEY_UNUSABLE,
            ComparisonModelMappingCore.classify(
                inspectionOk = true,
                resolverCacheInitialized = true,
                resolverSource = "builtin_asset:tiny.en",
                candidates = listOf(probe("resource_VoiceInput_en", nonEmpty = false, fileExists = false, present = false)),
                englishKeys = english,
            ),
        )
    }

    @Test
    fun noEnglishKeyAndInspectionFailureStayDistinctAndExplicit() {
        assertEquals(
            ComparisonModelMappingCore.NO_KEY,
            ComparisonModelMappingCore.classify(
                inspectionOk = true,
                resolverCacheInitialized = true,
                resolverSource = "builtin_asset:tiny.en",
                candidates = emptyList(),
                englishKeys = emptyList(),
            ),
        )
        assertEquals(
            ComparisonModelMappingCore.INSPECTION_FAILED,
            ComparisonModelMappingCore.classify(
                inspectionOk = false,
                resolverCacheInitialized = null,
                resolverSource = "unknown",
                candidates = emptyList(),
                englishKeys = emptyList(),
            ),
        )
    }

    @Test
    fun englishKeyCatalogIsBoundedToSafeEnglishKeyNamesOnly() {
        val names = buildList {
            add("resource_VoiceInput_en_IN")
            add("resource_VoiceInput_en")
            add("resource_VoiceInput_en_US")
            add("resource_VoiceInput_fr_FR")
            add("resource_VoiceInput_en_IN") // duplicate
            add("resourcename_VoiceInput_en_IN") // not a value key
            add("resource_VoiceInput_../../secret")
            add("resource_VoiceInput_" + "en_".repeat(40)) // too long
            for (i in 0 until 40) add("resource_VoiceInput_en_R${i.toString().padStart(2, '0')}")
        }
        val bounded = ComparisonModelMappingCore.boundedEnglishKeys(names)
        assertTrue(bounded.size <= ComparisonModelMappingCore.MAX_ENGLISH_KEYS)
        assertEquals(bounded.distinct(), bounded)
        assertTrue(bounded.all { it.startsWith("resource_VoiceInput_en") })
        assertFalse(bounded.any { it.contains("secret") })
        assertFalse(bounded.any { it.contains("fr_FR") })
        assertFalse(bounded.contains("resourcename_VoiceInput_en_IN"))
        assertEquals("resource_VoiceInput_en", bounded.first())
    }

    @Test
    fun boundedLocaleTagAcceptsSystemAndPersistedSubtypeShapesOnly() {
        // System subtype locale and FUTO's persisted `locale:extra:tag` shape.
        assertEquals("en-IN", ComparisonModelMappingCore.boundedLocaleTag("en_IN"))
        assertEquals("en-IN", ComparisonModelMappingCore.boundedLocaleTag("en-IN"))
        assertEquals("en-IN", ComparisonModelMappingCore.boundedLocaleTag("en_IN:KeyboardLayoutSet=qwerty:en-IN"))
        assertNull(ComparisonModelMappingCore.boundedLocaleTag(null))
        assertNull(ComparisonModelMappingCore.boundedLocaleTag(""))
        assertNull(ComparisonModelMappingCore.boundedLocaleTag("en IN"))
        assertNull(ComparisonModelMappingCore.boundedLocaleTag("en_IN/../../secret"))
        assertNull(ComparisonModelMappingCore.boundedLocaleTag("en_".repeat(30)))
        assertEquals("en", ComparisonModelMappingCore.localeFromTag("en")?.language)
        assertEquals("IN", ComparisonModelMappingCore.localeFromTag("en_IN")?.country)
        assertNull(ComparisonModelMappingCore.localeFromTag("und"))
        assertNull(ComparisonModelMappingCore.localeFromTag(null))
    }

    @Test
    fun actualModelComesFromTheActualLocaleFileNotTheColdDebugDefault() {
        val enInImport = listOf(probe("resource_VoiceInput_en_IN"))
        assertEquals(
            ComparisonModelMappingCore.ACTUAL_MODEL_IMPORTED,
            ComparisonModelMappingCore.actualModelSource(actualKnown = true, candidates = enInImport),
        )
        assertEquals(
            ComparisonModelMappingCore.ACTUAL_MODEL_BUILTIN,
            ComparisonModelMappingCore.actualModelSource(
                actualKnown = true,
                candidates = listOf(probe("resource_VoiceInput_en_IN", present = false, nonEmpty = false, fileExists = false)),
            ),
        )
        assertEquals(
            ComparisonModelMappingCore.ACTUAL_MODEL_UNKNOWN,
            ComparisonModelMappingCore.actualModelSource(actualKnown = false, candidates = enInImport),
        )
    }

    @Test
    fun unknownActualLocaleNeverBorrowsTheDebugDefaultAnswer() {
        // The debug default probes a non-empty imported file, but the actual
        // keyboard locale could not be read. Reporting imported/builtin here
        // would be the exact v43 error.
        assertEquals(
            ComparisonModelMappingCore.ACTUAL_LOCALE_UNKNOWN,
            ComparisonModelMappingCore.classify(
                inspectionOk = true,
                resolverCacheInitialized = false,
                resolverSource = "unknown_cache_not_initialized",
                candidates = listOf(probe("resource_VoiceInput_en_US")),
                englishKeys = listOf(probe("resource_VoiceInput_en_US")),
                actualKnown = false,
            ),
        )
    }

    @Test
    fun coldCacheWithActualEnInImportNamesTheCacheAndTheAuthoritativeModel() {
        val candidates = listOf(probe("resource_VoiceInput_en_IN"))
        assertEquals(
            "resource_VoiceInput_en_IN",
            ComparisonModelMappingCore.authoritative(candidates)?.key,
        )
        assertEquals(
            ComparisonModelMappingCore.CACHE_NOT_INITIALIZED,
            ComparisonModelMappingCore.classify(
                inspectionOk = true,
                resolverCacheInitialized = false,
                resolverSource = "unknown_cache_not_initialized",
                candidates = candidates,
                englishKeys = candidates,
                actualKnown = true,
            ),
        )
        assertEquals(
            ComparisonModelMappingCore.ACTUAL_MODEL_IMPORTED,
            ComparisonModelMappingCore.actualModelSource(actualKnown = true, candidates = candidates),
        )
    }

    @Test
    fun mappingSummaryAndProbesCarryKeysAndBooleansNotValuesOrPaths() {
        val mapping = ComparisonModelMapping(
            devicePackage = "org.futo.inputmethod.latin.unstable",
            deviceFlavor = "unstable",
            deviceBuildType = "debug",
            localeTag = "en-IN",
            localeToString = "en_IN",
            localeLanguage = "en",
            localeCountry = "IN",
            candidateKeys = listOf(probe("resource_VoiceInput_en_IN", nonEmpty = false, fileExists = false, present = false)),
            englishKeys = emptyList(),
            effectiveKey = null,
            effectiveLocale = null,
            resolverCacheInitialized = false,
            resolverSource = "unknown_cache_not_initialized",
            authoritativeSource = "builtin_asset",
            authoritativeKey = null,
            explanation = ComparisonModelMappingCore.CACHE_NOT_INITIALIZED_NO_KEY,
            debugLocaleSource = ComparisonModelMappingCore.DEBUG_LOCALE_COLD_DEFAULT,
            systemSubtypeLocaleTag = "en-IN",
            persistedSubtypeLocaleTag = "en-IN",
            actualLocaleTag = "en-IN",
            actualLocaleToString = "en_IN",
            actualLocaleLanguage = "en",
            actualLocaleCountry = "IN",
            actualLocaleSource = ComparisonModelMappingCore.SYSTEM_IME_SUBTYPE,
            actualLocaleKnown = true,
            actualModelSource = ComparisonModelMappingCore.ACTUAL_MODEL_BUILTIN,
            actualModelKnown = true,
            candidateLocaleSource = ComparisonModelMappingCore.ACTUAL_KEYBOARD_LOCALE,
        )
        val summary = mapping.summary()
        assertTrue(summary.contains("en_IN"))
        assertTrue(summary.contains("resource_VoiceInput_en_IN"))
        assertTrue(summary.contains("cache false"))
        assertTrue(summary.contains("actual keyboard en_IN"))
        assertTrue(summary.contains("system subtype en-IN"))
        assertTrue(summary.contains("actual model builtin_asset"))
        assertFalse(summary.contains("voiceinput_en_IN.bin"))
        assertFalse(summary.contains("/storage/"))
        // The probe type deliberately has no value/path field: only the key
        // name and three booleans.
        val fields = ModelKeyProbe::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(fields.containsAll(setOf("key", "present", "nonEmpty", "fileExists")))
        assertFalse(fields.any { it.contains("value", ignoreCase = true) || it.contains("path", ignoreCase = true) })
    }
}
