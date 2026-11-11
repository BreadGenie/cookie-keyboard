package org.futo.voiceinput.shared.provisioning

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class ParakeetVocabularyTest {
    private fun pin(name: String, text: ByteArray) = ModelArtifact(name, "fixture", text.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(text).joinToString("") { "%02x".format(it) })
    private val tokens = "<unk> 0\n▁a 1\n\nb 2\n".toByteArray()
    private val vocab = "<unk>\t-1.0\n▁a\t-1.0\nb\t-1.0\n".toByteArray()
    private fun fixture(test: (File, ParakeetStore) -> Unit) {
        val root = Files.createTempDirectory("bpe-upgrade").toFile()
        try { test(root, ParakeetStore(root, listOf(pin("tokens.txt", tokens)), pin("bpe.vocab", vocab))) }
        finally { root.deleteRecursively() }
    }
    @Test fun derivationPreservesOrderUtf8AndFinalLf() {
        assertArrayEquals(vocab, ParakeetStore.deriveVocabulary(String(tokens, Charsets.UTF_8)))
    }
    @Test fun realPinnedTokensProduceExactPublishedVocabularyPin() {
        val path = listOf(File("../artifacts/parakeet-model/tokens.txt"), File("../../artifacts/parakeet-model/tokens.txt"))
            .firstOrNull { it.isFile }
        // The portable fixture above always runs; full pin check runs when locally staged.
        org.junit.Assume.assumeTrue("Pinned tokens.txt not staged", path != null)
        assertTrue(ParakeetStore.verify(path!!, ParakeetStore.PINNED.single { it.name == "tokens.txt" }))
        val bytes = ParakeetStore.deriveVocabulary(path.readText(Charsets.UTF_8))
        assertEquals(10494, bytes.size)
        assertEquals(ParakeetStore.BPE.sha256, pin("bpe.vocab", bytes).sha256)
        // Every allowed non-space policy character has an explicit singleton, so encoding
        // can always fall back to known pieces (including the word boundary singleton).
        val pieces = path.readLines().map { it.substringBefore(' ') }.toSet()
        ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'-".map { it.toString() } + "▁")
            .forEach { assertTrue(pieces.contains(it)) }
    }
    @Test fun importIncludesVerifiedVocabularyBeforeReadyMarker() = fixture { _, store ->
        store.importFiles(listOf({ tokens.inputStream() }))
        assertTrue(store.isReady()); assertArrayEquals(vocab, File(store.directory, "bpe.vocab").readBytes())
        assertFalse(File(store.directory, "bpe.vocab.tmp").exists())
    }
    @Test fun legacyReadyInstallGeneratesWithoutReimportAndRepairsCorruptVocabulary() = fixture { root, store ->
        store.directory.mkdirs()
        File(store.directory, "tokens.txt").writeBytes(tokens)
        File(store.directory, "ready").writeText(store.artifacts.joinToString("\n") { "${it.name}:${it.bytes}:${it.sha256}" })
        assertFalse(File(store.directory, "bpe.vocab").exists())
        assertTrue(store.isReady())
        File(store.directory, "bpe.vocab").writeBytes(ByteArray(vocab.size))
        assertTrue(store.isReady()); assertArrayEquals(vocab, File(store.directory, "bpe.vocab").readBytes())
        store.importFiles(listOf({ error("Must not re-import") }))
        assertFalse(File(root, "sherpa/parakeet-download").exists())
    }
    @Test fun legacyCorruptTokensCannotGenerateAndNeverBecomeReady() = fixture { _, store ->
        store.directory.mkdirs()
        File(store.directory, "tokens.txt").writeBytes(ByteArray(tokens.size))
        File(store.directory, "ready").writeText(store.artifacts.joinToString("\n") { "${it.name}:${it.bytes}:${it.sha256}" })
        assertFalse(store.isReady()); assertFalse(File(store.directory, "bpe.vocab").exists())
    }
    @Test fun readinessDoesNotBlockSettingsDuringImport() = fixture { root, store ->
        val copying = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.FutureTask<Unit> {
            store.importFiles(listOf({ tokens.inputStream() }), { _, _ ->
                copying.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            })
        }
        Thread(result).start()
        try {
            assertTrue(copying.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Separate store instances share the provisioning owner.
            val observer = ParakeetStore(root, store.artifacts, pin("bpe.vocab", vocab))
            assertFalse(observer.isReady())
        } finally { release.countDown() }
        result.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(store.isReady())
    }
    @Test fun wrongDerivedPinPreventsPublicationAndCleansStaging() = fixture { root, _ ->
        val store = ParakeetStore(root, listOf(pin("tokens.txt", tokens)), pin("bpe.vocab", "wrong".toByteArray()))
        try { store.importFiles(listOf({ tokens.inputStream() })); fail() } catch(_: IOException) { }
        assertFalse(store.isReady()); assertFalse(File(store.directory, "ready").exists())
        assertFalse(File(root, "sherpa/parakeet-download").exists())
    }
}
