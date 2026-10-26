package org.futo.voiceinput.shared.opencl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Drives the real v42 bounded copy/verify/rename code with small injected
 * fixtures. No 264 MB file is needed: the expected byte count and pin are
 * constructor parameters, exactly like the phone configuration uses the real
 * pinned English-244 values.
 */
class ComparisonModelStoreTest {
    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "comparison-store-${System.nanoTime()}").apply {
            assertTrue(mkdirs())
        }

    private fun fixture(size: Int, seed: Int = 0): ByteArray =
        ByteArray(size) { ((it + seed) % 251).toByte() }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun store(dir: File, bytes: ByteArray, shaOverride: String? = null) =
        ComparisonModelStore(dir, bytes.size.toLong(), shaOverride ?: sha(bytes))

    private fun target(dir: File) = File(dir, ComparisonModelStore.FILE_NAME)
    private fun temp(dir: File) = File(dir, ComparisonModelStore.TEMP_NAME)

    private suspend fun failureOf(block: suspend () -> Unit): ComparisonModelImportException {
        try {
            block()
        } catch (e: ComparisonModelImportException) {
            return e
        }
        fail("expected a content-free ComparisonModelImportException")
        throw AssertionError()
    }

    @Test
    fun validSelectedFileIsCopiedVerifiedAndSealed() = runBlocking {
        val dir = tempDir()
        val bytes = fixture(64 * 1024 + 7)
        val store = store(dir, bytes)

        val imported = store.import(bytes.inputStream())

        assertEquals(target(dir), imported)
        assertArrayEquals(bytes, imported.readBytes())
        assertEquals(bytes.size.toLong(), imported.length())
        assertEquals(imported, store.verifiedFile())
        assertFalse("the temporary file must not survive a successful import", temp(dir).exists())
    }

    @Test
    fun wrongHashIsRejectedAndThePreviousVerifiedCopySurvives() = runBlocking {
        val dir = tempDir()
        val good = fixture(32 * 1024, seed = 1)
        val store = store(dir, good)
        store.import(good.inputStream())

        val wrong = fixture(32 * 1024, seed = 2)
        val failure = failureOf { store.import(wrong.inputStream()) }

        assertEquals(ComparisonModelImportException.WRONG_HASH, failure.reason)
        assertArrayEquals(good, target(dir).readBytes())
        assertEquals(target(dir), store.verifiedFile())
        assertFalse(temp(dir).exists())
    }

    @Test
    fun shortOrMalformedFileIsRejectedWithTheExpectedSize() = runBlocking {
        val dir = tempDir()
        val good = fixture(16 * 1024)
        val store = store(dir, good)
        store.import(good.inputStream())

        val failure = failureOf { store.import(fixture(16 * 1024 - 1, seed = 4).inputStream()) }
        assertEquals(ComparisonModelImportException.WRONG_SIZE, failure.reason)
        assertEquals(16L * 1024 - 1, failure.observedBytes)
        assertEquals(target(dir), store.verifiedFile())

        val empty = failureOf { store.import(ByteArray(0).inputStream()) }
        assertEquals(ComparisonModelImportException.WRONG_SIZE, empty.reason)
        assertEquals(target(dir), store.verifiedFile())
    }

    @Test
    fun oversizedStreamStopsAtTheBoundAndKeepsThePreviousVerifiedCopy() = runBlocking {
        val dir = tempDir()
        val good = fixture(8 * 1024)
        val store = store(dir, good)
        store.import(good.inputStream())

        val failure = failureOf { store.import(fixture(32 * 1024, seed = 9).inputStream()) }
        assertEquals(ComparisonModelImportException.SIZE_OVERFLOW, failure.reason)
        assertTrue("the read must stop as soon as the bound is exceeded", failure.observedBytes!! >= 8L * 1024 + 1)
        assertArrayEquals(good, target(dir).readBytes())
        assertEquals(target(dir), store.verifiedFile())
        assertFalse(temp(dir).exists())
    }

    @Test
    fun cancelledCopyDeletesOnlyTheTemporaryFileAndKeepsThePreviousVerifiedCopy() = runBlocking {
        val dir = tempDir()
        val good = fixture(16 * 1024, seed = 3)
        val store = store(dir, good)
        store.import(good.inputStream())
        val previous = target(dir).readBytes()

        val failure = try {
            store.import(CancellingStream(good, failAfter = 4096))
            null
        } catch (e: CancellationException) {
            e
        }
        assertNotNull("cancellation must propagate", failure)
        assertArrayEquals(previous, target(dir).readBytes())
        assertEquals(target(dir), store.verifiedFile())
        assertFalse("a cancelled copy must not leave a temporary model", temp(dir).exists())
    }

    @Test
    fun failedFirstImportLeavesNothingVerifiedOrSealed() = runBlocking {
        val dir = tempDir()
        val intended = fixture(8 * 1024)
        val store = store(dir, intended)

        val failure = failureOf { store.import(fixture(8 * 1024, seed = 7).inputStream()) }

        assertEquals(ComparisonModelImportException.WRONG_HASH, failure.reason)
        assertFalse(target(dir).exists())
        assertNull(store.verifiedFile())
        assertFalse(temp(dir).exists())
    }

    @Test
    fun aTargetWithoutTheMatchingSealIsNotOfferedAsVerified() = runBlocking {
        val dir = tempDir()
        val bytes = fixture(4 * 1024)
        val store = store(dir, bytes)
        store.import(bytes.inputStream())

        File(dir, ComparisonModelStore.SEAL_NAME).delete()
        assertNull("the cheap gate needs the seal", store.verifiedFile())
        // A different pin must never accept the existing copy.
        val otherPin = store(dir, bytes, shaOverride = "0".repeat(64))
        assertNull(otherPin.verifiedFile())
    }

    private class CancellingStream(
        private val data: ByteArray,
        private val failAfter: Int,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= failAfter) throw CancellationException("copy cancelled")
            val n = minOf(len, failAfter - position, data.size - position)
            if (n <= 0) return -1
            System.arraycopy(data, position, b, off, n)
            position += n
            return n
        }
    }
}
