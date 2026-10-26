package org.futo.voiceinput.shared.opencl

import org.futo.voiceinput.shared.ENGLISH_MODELS
import org.futo.voiceinput.shared.types.ModelBuiltInAsset
import org.futo.voiceinput.shared.types.ModelDownloadable
import org.futo.voiceinput.shared.types.ModelFileFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The v41 phone failure was model resolution, not a missing model: the active
 * English locale resolved to the built-in tiny.en asset, which has no
 * file-backed OpenCL source. These tests pin the production priority order
 * (imported file first), prove the built-in asset can never be reported as
 * pinned English-244, and prove the debug-only catalog rescue requires the
 * production checksum rather than a filename.
 */
class ComparisonModelTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "comparison-model-${System.nanoTime()}")
        .apply { mkdirs() }

    private fun model(name: String = "voiceinput_en_US.bin"): File =
        File(dir, name).apply { writeBytes(ByteArray(32) { it.toByte() }) }

    private val builtin = ModelBuiltInAsset(0, "tiny_en_acft_q8_0.bin.not.tflite")
    private val pinned = ModelDownloadable(0, "small_en_acft_q8_0.bin", OpenClPolicy.MODEL_SHA)

    @Test
    fun importedProductionFileIsUsedUnchanged() {
        val file = model()
        val loader = ModelFileFile(0, file)
        val choice = ComparisonModel.choose(null, loader, file, pinned, null)

        assertEquals(ComparisonModelChoice.SOURCE_IMPORTED, choice.source)
        assertSame(loader, choice.loader)
        assertSame(file, choice.file)
        assertNull("no correction is needed when dictation already uses the file", choice.action)
        assertEquals("imported_file", ComparisonModel.kind(loader))
        assertEquals("imported_file", choice.dictationSource)
    }

    @Test
    fun builtinFallbackIsNeverUsedAsEnglish244() {
        val choice = ComparisonModel.choose(null, builtin, null, null, null)

        assertEquals(ComparisonModelChoice.SOURCE_UNAVAILABLE, choice.source)
        assertNull(choice.loader)
        assertNull(choice.file)
        assertEquals("builtin_asset", ComparisonModel.kind(builtin))
        assertTrue(choice.dictationSource.startsWith("builtin_asset:"))
        assertTrue(choice.dictationSource.contains("tiny_en"))

        val action = choice.action!!
        assertTrue(action.contains("Settings -> Languages"))
        assertTrue(action.contains("Voice input model"))
        assertTrue(action.contains("voice-input-english-244.bin"))
        assertTrue(action.contains("58fbe949"))
        assertTrue("the everyday selection is only changed by an import", action.contains("Import"))
        assertFalse("bounded report must not contain a filesystem path", action.contains("/"))
    }

    @Test
    fun pinnedCatalogFileIsOnlyADebugComparisonSource() {
        val file = model("small_en_acft_q8_0.bin")
        val choice = ComparisonModel.choose(null, builtin, null, pinned, file)

        assertEquals(ComparisonModelChoice.SOURCE_PINNED_CATALOG, choice.source)
        assertSame(pinned, choice.loader)
        assertSame(file, choice.file)
        assertNotNull("dictation still uses the built-in fallback and needs the import", choice.action)
        assertTrue(choice.dictationSource.startsWith("builtin_asset:"))
        assertTrue(choice.dictationSource.contains("tiny_en"))
    }

    @Test
    fun declaredCatalogEntryWithoutItsFileIsUnavailable() {
        val choice = ComparisonModel.choose(null, builtin, null, pinned, null)

        assertEquals(ComparisonModelChoice.SOURCE_UNAVAILABLE, choice.source)
        assertNull(choice.file)
        assertNotNull(choice.action)
    }

    @Test
    fun catalogEntryIsSelectedByProductionChecksumNotByFilename() {
        val real = ComparisonModel.pinnedEnglish244(ENGLISH_MODELS)
        assertNotNull("production catalog must still declare the pinned English-244 checksum", real)
        assertEquals("small_en_acft_q8_0.bin", real!!.ggmlFile)
        assertEquals(OpenClPolicy.MODEL_SHA, real.checksum)

        val decoy = ModelDownloadable(0, "voice-input-english-244.bin", "0000000000000000000000000000000000000000000000000000000000000000")
        assertNull(ComparisonModel.pinnedEnglish244(listOf(decoy, builtin)))
        assertSame(real, ComparisonModel.pinnedEnglish244(listOf(decoy, builtin, real)))
    }

    @Test
    fun userSelectedVerifiedFileHasPriorityEvenWhenDictationUsesTheBuiltinAsset() {
        // v42: the user explicitly picked English-244, so the comparison must
        // not depend on the active locale resolving to a file-backed loader.
        val selected = model("english-244.bin")
        val choice = ComparisonModel.choose(selected, builtin, null, pinned, null)

        assertEquals(ComparisonModelChoice.SOURCE_USER_SELECTED, choice.source)
        assertSame(selected, choice.file)
        assertSame("the production loader may stay the built-in asset for traceability", builtin, choice.loader)
        assertNull("the comparison is already provisioned; no import action is required", choice.action)
        assertFalse("everyday dictation still uses tiny.en", choice.matchesDictation)
        assertTrue(choice.dictationSource.startsWith("builtin_asset:"))
        assertEquals("user_selected_verified_file", ComparisonModelChoice.SOURCE_USER_SELECTED)
    }

    @Test
    fun selectedFileIsReportedAsMatchingDictationOnlyWhenItIsTheSameFile() {
        val selected = model("english-244.bin")
        val imported = ModelFileFile(0, selected)
        val same = ComparisonModel.choose(selected, imported, selected, pinned, null)
        assertTrue(same.matchesDictation)

        val different = ComparisonModel.choose(model("comparison-copy.bin"), imported, selected, pinned, null)
        assertFalse(different.matchesDictation)
    }
}
