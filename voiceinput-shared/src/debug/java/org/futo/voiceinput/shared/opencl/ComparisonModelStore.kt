package org.futo.voiceinput.shared.opencl

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Content-free failure of a user-selected model import. The reason and the
 * observed byte count are safe to show; no URI, filename or filesystem path is
 * ever included.
 */
class ComparisonModelImportException(
    val reason: String,
    /** Observed bytes when the failure happened after reading data. */
    val observedBytes: Long? = null,
) : Exception(reason) {
    companion object {
        const val WRONG_SIZE = "wrong_size"
        const val SIZE_OVERFLOW = "size_overflow"
        const val WRONG_HASH = "wrong_hash"
        const val RENAME_FAILED = "rename_failed"
        const val READ_FAILED = "read_failed"
    }
}

/**
 * Debug-only private store for a comparison model the user explicitly picked
 * through the system document picker (v42).
 *
 * The copy is bounded by the expected byte count, hashed in the same streaming
 * pass, written to a temporary private file, and renamed into place only after
 * the exact size and full SHA-256 match. A failure or cancellation deletes the
 * temporary file and never touches the previously verified comparison model.
 *
 * This class has no Android dependency so the real copy/verify/rename code is
 * exercised on the host JVM with small injected fixtures. It never reads a
 * model into RAM as a whole.
 */
class ComparisonModelStore(
    private val dir: File,
    private val expectedBytes: Long = EXPECTED_BYTES,
    private val expectedSha: String = EXPECTED_SHA,
) {
    private val target = File(dir, FILE_NAME)
    private val temp = File(dir, TEMP_NAME)
    private val seal = File(dir, SEAL_NAME)

    /**
     * A previously imported comparison model that still matches the sealed
     * size and SHA. The full content hash is re-verified by PhoneComparison
     * immediately before any benchmark arm runs; this cheap sealed check only
     * decides whether the Start button may be offered.
     */
    fun verifiedFile(): File? {
        if (!target.isFile) return null
        if (target.length() != expectedBytes) return null
        val sealed = runCatching { seal.readText(Charsets.UTF_8).trim() }.getOrNull() ?: return null
        return if (sealed == "$expectedSha $expectedBytes") target else null
    }

    /**
     * Blocking copy; call on a background dispatcher. The supplied stream is
     * owned and closed here. [onProgress] runs on the calling thread after each
     * chunk and must not throw (the caller only posts a bounded UI update).
     */
    suspend fun import(input: InputStream, onProgress: (Long) -> Unit = {}): File {
        if (!dir.isDirectory && !dir.mkdirs()) {
            runCatching { input.close() }
            throw ComparisonModelImportException(ComparisonModelImportException.READ_FAILED)
        }
        // A stale temporary from a killed process is never a verified model.
        temp.delete()
        var total = 0L
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            input.use { source ->
                FileOutputStream(temp).use { out ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = source.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > expectedBytes) {
                            throw ComparisonModelImportException(
                                ComparisonModelImportException.SIZE_OVERFLOW,
                                total,
                            )
                        }
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        onProgress(total)
                    }
                    out.flush()
                    // Make the bytes durable before they can replace a
                    // previously verified copy.
                    out.fd.sync()
                }
            }
            if (total != expectedBytes) {
                throw ComparisonModelImportException(
                    ComparisonModelImportException.WRONG_SIZE,
                    total,
                )
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!sha.equals(expectedSha, ignoreCase = true)) {
                throw ComparisonModelImportException(
                    ComparisonModelImportException.WRONG_HASH,
                    total,
                )
            }
            if (temp.length() != expectedBytes) {
                throw ComparisonModelImportException(
                    ComparisonModelImportException.WRONG_SIZE,
                    temp.length(),
                )
            }
            // The seal describes the pinned content, not a path. Writing it
            // before the atomic rename means a failed rename leaves the old
            // verified file and a seal that still describes it correctly.
            writeSeal()
            if (!temp.renameTo(target)) {
                throw ComparisonModelImportException(
                    ComparisonModelImportException.RENAME_FAILED,
                    total,
                )
            }
            return target
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            throw t
        }
    }

    private fun writeSeal() {
        val tmp = File(dir, SEAL_NAME + ".tmp")
        tmp.writeText("$expectedSha $expectedBytes\n", Charsets.UTF_8)
        if (!tmp.renameTo(seal)) {
            tmp.delete()
            throw ComparisonModelImportException(ComparisonModelImportException.RENAME_FAILED)
        }
    }

    companion object {
        /** Exact bytes of the pinned English-244 ggml file. */
        const val EXPECTED_BYTES = 264_477_561L
        /** Pinned English-244 SHA-256, shared with the benchmark's content gate. */
        const val EXPECTED_SHA = OpenClPolicy.MODEL_SHA
        const val DIR_NAME = "comparison-model"
        const val FILE_NAME = "english-244.bin"
        const val TEMP_NAME = "english-244.bin.tmp"
        const val SEAL_NAME = "english-244.bin.verified"
        private const val CHUNK_BYTES = 256 * 1024
    }
}
