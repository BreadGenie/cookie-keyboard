package org.futo.voiceinput.shared.opencl

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

class ResidentModelStoreTest {
    private fun shaHex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Counts ACTUAL bytes read, so tests assert I/O behavior, not labels. */
    private class CountingInput(delegate: InputStream) : InputStream() {
        private val inner = delegate
        var bytes = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) bytes++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) bytes += it }
    }

    @Test fun coldVerifyConsumesAllBytesAndIssuesToken() {
        val model = ByteArray(300_000) { (it * 31).toByte() }
        val counting = CountingInput(ByteArrayInputStream(model))
        val sink = ByteArrayOutputStream()
        val bytes = SealedModelSnapshot.copyAndVerify(counting, sink, shaHex(model))
        assertEquals(model.size.toLong(), bytes)
        // Every source byte was actually read (no metadata shortcut).
        assertEquals(model.size.toLong(), counting.bytes)
        // Sealed: the snapshot IS the hashed bytes.
        assertArrayEquals(model, sink.toByteArray())
    }

    @Test fun sealedSnapshotPinsBytesAgainstSourceMutation() {
        val model = ByteArray(100_000) { (it * 17).toByte() }
        val expected = shaHex(model)
        val sink = ByteArrayOutputStream()
        val bytes = SealedModelSnapshot.copyAndVerify(ByteArrayInputStream(model), sink, expected)
        assertEquals(model.size.toLong(), bytes)
        // Mutating the "source file" after the sealed copy cannot change
        // the verified snapshot (the ownership property warm reuse relies on).
        model.fill(0x7F.toByte())
        assertEquals(expected, shaHex(sink.toByteArray()))
    }

    @Test fun wrongShaRejectsAndWritesNothingUsable() {
        val model = ByteArray(10_000) { it.toByte() }
        val sink = ByteArrayOutputStream()
        try {
            SealedModelSnapshot.copyAndVerify(ByteArrayInputStream(model), sink, "0".repeat(64))
            fail("wrong SHA must reject")
        } catch (_: ModelVerificationException) { }
    }

    @Test fun emptySnapshotRejects() {
        try {
            SealedModelSnapshot.copyAndVerify(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), shaHex(ByteArray(0)))
            fail("empty snapshot must reject")
        } catch (_: ModelVerificationException) { }
    }

    @Test fun cancelledCopyRejects() {
        val model = ByteArray(500_000) { it.toByte() }
        var calls = 0
        try {
            SealedModelSnapshot.copyAndVerify(ByteArrayInputStream(model), ByteArrayOutputStream(), shaHex(model)) {
                calls++
                calls > 2
            }
            fail("cancelled copy must reject")
        } catch (_: ModelVerificationException) { }
        assertTrue(calls > 2)
    }

    @Test fun slotLifecycleTokensAndInvalidation() {
        val slot = ResidentModelSlot()
        assertEquals(0L, slot.token())
        assertFalse(slot.accepts(0L))
        assertFalse(slot.accepts(1L))
        val t1 = slot.noteColdVerified(OpenClPolicy.MODEL_SHA, 264_000_000L)
        assertEquals(1L, t1)
        assertTrue(slot.accepts(t1))
        assertFalse(slot.accepts(0L))
        assertFalse(slot.accepts(t1 + 1))
        // A fresh cold load rotates the generation: the old token goes stale.
        val t2 = slot.noteColdVerified(OpenClPolicy.MODEL_SHA, 264_000_000L)
        assertEquals(2L, t2)
        assertFalse(slot.accepts(t1))
        assertTrue(slot.accepts(t2))
        // Close/failure/cancel/trim invalidate: old tokens go stale.
        slot.invalidate()
        assertFalse(slot.accepts(t2))
        slot.invalidate() // idempotent
        assertFalse(slot.accepts(t2))
    }

    @Test fun slotRejectsUnlistedModelAndEmptySnapshot() {
        val slot = ResidentModelSlot()
        try {
            slot.noteColdVerified("f".repeat(64), 100L)
            fail("unlisted model must not anchor residency")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0L, slot.token())
        try {
            slot.noteColdVerified(OpenClPolicy.MODEL_SHA, 0L)
            fail("empty snapshot must not anchor residency")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0L, slot.token())
    }

    @Test fun warmHitReadsZeroBytesByConstruction() {
        // The service warm path performs no model reads at all; this pins
        // the counting-stream half of that contract (the host test pins the
        // native half: warm prepare/run succeed on an unrelated FD).
        val model = ByteArray(50_000) { it.toByte() }
        val counting = CountingInput(ByteArrayInputStream(model))
        val slot = ResidentModelSlot()
        val token = slot.noteColdVerified(OpenClPolicy.MODEL_SHA, model.size.toLong())
        assertTrue(slot.accepts(token))
        // No read happens on the warm path: the stream stays untouched.
        assertEquals(0L, counting.bytes)
    }

    @Test fun verifyModeDefaultsToFull() {
        assertEquals(VerifyMode.FULL, VerifyMode.parse(null))
        assertEquals(VerifyMode.FULL, VerifyMode.parse("full"))
        assertEquals(VerifyMode.FULL, VerifyMode.parse("resident_verified"))
        assertEquals(VerifyMode.RESIDENT, VerifyMode.parse("resident"))
    }
}
