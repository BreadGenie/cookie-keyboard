package org.futo.voiceinput.shared.opencl

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * DEBUG-ONLY v18 model-verification policy. Pure JVM: no Android imports,
 * so JVM unit tests exercise the exact ownership/generation lifecycle the
 * service runs on its serial worker.
 *
 * A verified RESIDENT model is one whose bytes were hashed (SHA-256,
 * exact [OpenClPolicy.MODEL_SHA]) in the SAME single pass that produced
 * the snapshot the native loader consumed. The slot then binds a
 * monotonically increasing generation nonce to (sha, byteCount). A warm
 * request presenting the current token may skip ALL model-byte reads
 * (bytes_hashed=0): the modern loader copies every weight into
 * backend-owned buffers at load and never re-reads the file
 * (see resident-model/SPEC.md section 2), so post-load file mutation
 * cannot affect inference.
 *
 * Explicit non-guarantee: a resident_verified warm hit asserts NOTHING
 * about the request's supplied file, which is neither read nor consumed.
 * A swapped source file during residency is silent by design (labeled in
 * telemetry, debug-only). Any stale/missing token, failure, cold-cancel,
 * close, trim, or recreation invalidates: the next resident-mode request
 * closes the engine and re-verifies every snapshot byte (never a
 * size/mtime/FD-identity shortcut).
 *
 * Threading: the service touches a slot only on its serial worker; the
 * class itself is not synchronized.
 */
internal class ResidentModelSlot {
    /** Verification outcome reported in METRICS; never a bare label: the
     * warm variant is reachable only with a valid token AND zero reads. */
    enum class Verification { FULL_HASH, RESIDENT_VERIFIED, RESIDENT_REVERIFIED }

    private var generation: Long = 0L
    private var verifiedSha: String? = null
    private var verifiedBytes: Long = -1L

    /** Current live token, or 0 when no verified resident exists. */
    fun token(): Long = generation

    /** True only for the exact live generation with a verified resident. */
    fun accepts(candidate: Long): Boolean =
        candidate != 0L && candidate == generation && verifiedSha != null

    /**
     * Records a cold verified load: every [byteCount] bytes of the loaded
     * snapshot were hashed to [sha] in the same pass. Issues a fresh
     * generation and returns its token. Requires the strict allowlist SHA.
     */
    fun noteColdVerified(sha: String, byteCount: Long): Long {
        require(sha == OpenClPolicy.MODEL_SHA) { "Unlisted model cannot anchor residency" }
        require(byteCount > 0) { "Empty snapshot cannot anchor residency" }
        generation += 1
        verifiedSha = sha
        verifiedBytes = byteCount
        return generation
    }

    /** Drop the binding. Idempotent. Old tokens go stale. */
    fun invalidate() {
        verifiedSha = null
        verifiedBytes = -1L
    }

    internal fun verifiedBytes(): Long = verifiedBytes
}

/**
 * Single-pass sealed snapshot: copies [source] to [sink] while hashing,
 * so the hashed bytes and the stored bytes are identical by construction
 * (closes the hash-then-load TOCTOU on mutable files). Returns the byte
 * count. Throws [ModelVerificationException] on SHA mismatch, IO failure,
 * or [isCancelled] (partial snapshots must never anchor residency).
 * Neither stream is closed here; the caller owns both.
 */
internal object SealedModelSnapshot {
    fun copyAndVerify(
        source: InputStream,
        sink: OutputStream,
        expectedSha: String,
        isCancelled: () -> Boolean = { false },
    ): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(65536)
        var total = 0L
        while (true) {
            if (isCancelled()) throw ModelVerificationException("Cancelled during sealed model copy")
            val n = try {
                source.read(block)
            } catch (_: java.io.IOException) {
                throw ModelVerificationException("Model snapshot read failed")
            }
            if (n < 0) break
            digest.update(block, 0, n)
            try {
                sink.write(block, 0, n)
            } catch (_: java.io.IOException) {
                throw ModelVerificationException("Model snapshot write failed")
            }
            total += n
        }
        try {
            sink.flush()
        } catch (_: java.io.IOException) {
            throw ModelVerificationException("Model snapshot write failed")
        }
        if (total <= 0) throw ModelVerificationException("Empty model snapshot")
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expectedSha) throw ModelVerificationException("Model SHA mismatch")
        return total
    }
}

internal class ModelVerificationException(message: String) : java.io.IOException(message)

/** Verify-mode wire values for the debug A/B. Live/old callers omit the
 * field and get FULL (today's always-hash behavior, unchanged). */
internal object VerifyMode {
    const val FULL = "full"
    const val RESIDENT = "resident"
    fun parse(raw: String?): String = if (raw == RESIDENT) RESIDENT else FULL
}
