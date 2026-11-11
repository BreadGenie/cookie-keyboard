package org.futo.voiceinput.shared.provisioning

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.*
import org.futo.voiceinput.shared.RecognitionEngine

class ParakeetStoreTest {
    private val contents = listOf("encoder", "decoder", "joiner", "tokens", "vad")
    private val pins = contents.map { text -> ModelArtifact("$text.onnx", "unused", text.length.toLong(),
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }) }
    private fun fixture(test: (File, ParakeetStore) -> Unit) {
        val root = Files.createTempDirectory("parakeet-import").toFile()
        try { test(root, ParakeetStore(root, pins)) } finally { root.deleteRecursively() }
    }
    private fun sources(texts: List<String>) = texts.map { text -> { ByteArrayInputStream(text.toByteArray()) as InputStream } }
    private fun zip(entries: List<Pair<String, String>>, stored: Boolean = false): List<() -> InputStream> {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> entries.forEach { (name, text) ->
            val bytes = text.toByteArray()
            val entry = ZipEntry(name)
            if(stored) { entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.crc = CRC32().apply { update(bytes) }.value }
            zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
        } }
        return listOf({ ByteArrayInputStream(out.toByteArray()) })
    }
    private fun failure(store: ParakeetStore, sources: List<() -> InputStream>): String {
        try { store.importFiles(sources); fail("Expected failure") } catch(e: IOException) {
            assertFalse(store.isReady()); assertFalse(File(store.directory, "ready").exists()); return e.message!!
        }
        error("unreachable")
    }
    @Test fun storedZipHappyPathPublishesAllFive() = fixture { _, store ->
        store.importFiles(zip(contents.map { "$it.onnx" to it }, true), { _, _ ->
            assertFalse(File(store.directory, "ready").exists())
        })
        assertTrue(store.isReady()); pins.forEach { assertTrue(ParakeetStore.verify(File(store.directory, it.name), it)) }
    }
    @Test fun individualFilesIgnoreNamesAndOrder() = fixture { root, store ->
        val renamed = contents.reversed().mapIndexed { i, text -> File(root, "browser ($i).bin").apply { writeText(text) } }
        store.importFiles(renamed.map { file -> { file.inputStream() } })
        assertTrue(store.isReady())
    }
    @Test fun corruptFileFailsWithoutMarker() = fixture { _, store ->
        assertTrue(failure(store, sources(contents.dropLast(1) + "bad")).contains("corrupt"))
    }
    @Test fun missingFileNamesRole() = fixture { _, store ->
        assertTrue(failure(store, sources(contents.dropLast(1))).contains("vad.onnx"))
    }
    @Test fun duplicatesAreIgnored() = fixture { _, store ->
        store.importFiles(sources(contents + contents)); assertTrue(store.isReady())
    }
    @Test fun unknownZipEntriesIgnoredAndRenamedEntriesIdentified() = fixture { _, store ->
        store.importFiles(zip(listOf("readme" to "unknown") + contents.mapIndexed { i, text -> "renamed$i" to text }))
        assertTrue(store.isReady()); assertFalse(File(store.directory, "readme").exists())
    }
    @Test fun traversalRejectedEvenForUnknownEntry() = fixture { _, store ->
        assertTrue(failure(store, zip(contents.map { it to it } + ("../escape" to "x"))).contains("Unsafe"))
    }
    @Test fun cancellationCleansStageAndCanRetry() = fixture { root, store ->
        try { store.importFiles(sources(contents), checkCancelled = { throw java.util.concurrent.CancellationException() }); fail() }
        catch(_: java.util.concurrent.CancellationException) { }
        assertFalse(File(root, "sherpa/parakeet-download").exists()); assertFalse(store.isReady())
        store.importFiles(sources(contents)); assertTrue(store.isReady())
    }
    @Test fun oversizedZipIsBounded() = fixture { _, store ->
        assertTrue(failure(store, zip(listOf("unknown" to "x".repeat(1100000)))).contains("size limit"))
    }
    @Test fun deleteAndDefaultUnchanged() = fixture { _, store ->
        store.importFiles(sources(contents)); store.delete(); assertFalse(store.isReady())
        assertEquals(RecognitionEngine.Whisper, RecognitionEngine.fromPreference(null))
        assertEquals(RecognitionEngine.Parakeet, RecognitionEngine.fromPreference("parakeet"))
    }
    @Test fun pinnedSizesAndManifestIdentity() = fixture { _, store ->
        assertEquals(661932380L, ParakeetStore(File("unused")).totalBytes)
        store.importFiles(sources(contents)); File(store.directory, "ready").writeText("old")
        assertFalse(store.isReady())
    }
}
