package org.futo.voiceinput.shared.opencl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ComparisonMarkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun marker(
        file: File,
        pid: Long,
        boot: Int,
        alive: () -> Boolean,
        currentPid: Long = pid,
        currentBoot: Int = boot,
    ) = ComparisonMarker(file, { currentPid }, { currentBoot }, alive)

    @Test
    fun missingMarkerNeedsNoRecovery() {
        val file = File(tmp.root, "cleanup.pending")
        assertEquals(ComparisonMarker.Recovery.NONE, marker(file, 7, 3, { true }).recover())
    }

    @Test
    fun sameProcessMarkerBlocksUntilOwnedDrainFinishes() {
        val file = File(tmp.root, "cleanup.pending")
        val m = marker(file, 7, 3, { true })
        m.write()
        assertTrue(file.exists())
        assertEquals(ComparisonMarker.Recovery.SAME_PROCESS, m.recover())
        assertTrue(file.exists())
        assertTrue(m.clear())
        assertFalse(file.exists())
    }

    @Test
    fun deadProcessWithDeadOpenClProcessClearsMarker() {
        val file = File(tmp.root, "cleanup.pending")
        marker(file, 7, 3, { true }).write()
        val next = marker(file, 7, 3, { false }, currentPid = 8, currentBoot = 3)
        assertEquals(ComparisonMarker.Recovery.CLEARED, next.recover())
        assertFalse(file.exists())
    }

    @Test
    fun deadProcessWithLiveOpenClProcessBlocksAndKeepsMarker() {
        val file = File(tmp.root, "cleanup.pending")
        marker(file, 7, 3, { true }).write()
        val next = marker(file, 7, 3, { true }, currentPid = 8, currentBoot = 3)
        assertEquals(ComparisonMarker.Recovery.OTHER_PROCESS_ALIVE, next.recover())
        assertTrue(file.exists())
    }

    @Test
    fun malformedMarkerBlocksEvenWhenNoOpenClProcessRemains() {
        // Fail closed: an unreadable marker cannot prove which process wrote
        // it, so even a dead OpenCL process is not enough to guess native
        // cleanup finished. The user must force-stop/clear the app.
        val file = File(tmp.root, "cleanup.pending")
        file.writeText("garbage")
        assertEquals(ComparisonMarker.Recovery.OTHER_PROCESS_ALIVE, marker(file, 7, 3, { true }).recover())
        assertTrue(file.exists())
        assertEquals(ComparisonMarker.Recovery.OTHER_PROCESS_ALIVE, marker(file, 7, 3, { false }).recover())
        assertTrue(file.exists())
    }

    @Test
    fun bootChangeWithDeadOpenClProcessClearsMarker() {
        val file = File(tmp.root, "cleanup.pending")
        marker(file, 7, 3, { true }).write()
        val next = marker(file, 7, 3, { false }, currentPid = 7, currentBoot = 4)
        assertEquals(ComparisonMarker.Recovery.CLEARED, next.recover())
    }
}
