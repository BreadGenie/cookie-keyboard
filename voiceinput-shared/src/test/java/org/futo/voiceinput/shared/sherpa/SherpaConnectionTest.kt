package org.futo.voiceinput.shared.sherpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle scenarios for the Binder handshake. A connection may own the process lease
 * only after it can witness death; a pre-Binder failure must never poison a later session,
 * and a linked worker must never be replaced before its Binder actually dies.
 */
class SherpaConnectionTest {
    private fun linked(lease: SherpaLease, identity: Long): SherpaConnection {
        val connection = SherpaConnection(lease, identity)
        assertTrue(connection.begin())
        var deaths = 0
        assertEquals(SherpaConnectOutcome.ACQUIRED, connection.connected { deaths++ })
        assertEquals(1, deaths)
        return connection
    }

    @Test
    fun startOrBindFailureBeforeBinderLeavesNoLeaseAndNextSessionStarts() {
        val lease = SherpaLease()
        val failed = SherpaConnection(lease, 1)
        assertTrue(failed.begin())
        // startService rejected, bindService returned false, or the 10 s handshake timed out.
        assertTrue(failed.abandon())

        // No LOAD could have been sent, so replacing an idle worker is safe.
        assertTrue(lease.acquire(2))
        assertFalse(lease.acquire(3))
    }

    @Test
    fun linkToDeathIsRegisteredBeforeTheLeaseIsTaken() {
        val lease = SherpaLease()
        val connection = SherpaConnection(lease, 1)
        assertTrue(connection.begin())

        var deathLinked = false
        var binderPublished = false
        val outcome = connection.connected(
            linkToDeath = {
                deathLinked = true
                // Proves ordering: the lease is still free while death is linked.
                assertFalse(lease.held())
            },
            onAcquired = {
                binderPublished = true
                // A waiter must never see LINKED before the Binder is available.
                assertFalse(connection.linked())
            }
        )

        assertEquals(SherpaConnectOutcome.ACQUIRED, outcome)
        assertTrue(deathLinked)
        assertTrue(binderPublished)
        assertTrue(connection.linked())
        assertFalse("linked worker blocks a replacement", lease.acquire(2))
    }

    @Test
    fun linkedWorkerIsReplacedOnlyAfterBinderDeath() {
        val lease = SherpaLease()
        val old = linked(lease, 1)

        // Cancel, stopService, onBindingDied and onServiceDisconnected are not death proof.
        assertFalse(lease.acquire(2))
        old.death()
        assertTrue(lease.acquire(2))
    }

    @Test
    fun delayedOnServiceConnectedAfterTimeoutCannotTakeTheNextSession() {
        val lease = SherpaLease()
        val timedOut = SherpaConnection(lease, 1)
        assertTrue(timedOut.begin())
        assertTrue(timedOut.abandon())

        val next = linked(lease, 2)
        var lateLink = false
        // Android may still deliver a queued callback after unbind/timeout.
        assertEquals(SherpaConnectOutcome.ABANDONED, timedOut.connected { lateLink = true })
        assertFalse("late callback must not register death or take a lease", lateLink)
        assertFalse(lease.acquire(3))

        next.death()
        assertTrue(lease.acquire(3))
    }

    @Test
    fun linkToDeathOnAnAlreadyDeadBinderTakesNoLease() {
        val lease = SherpaLease()
        val connection = SherpaConnection(lease, 1)
        assertTrue(connection.begin())

        val outcome = connection.connected { throw IllegalStateException("Binder already dead") }

        assertEquals(SherpaConnectOutcome.LINK_FAILED, outcome)
        assertFalse(connection.linked())
        assertTrue("a dead Binder cannot own native state", lease.acquire(2))
    }

    @Test
    fun leaseContentionNeverReleasesOrStopsTheCurrentOwner() {
        val lease = SherpaLease()
        val owner = linked(lease, 1)
        val contender = SherpaConnection(lease, 2)
        assertTrue(contender.begin())

        assertEquals(SherpaConnectOutcome.LEASE_HELD, contender.connected { })
        contender.death() // a stale death must not free the owner's lease
        assertFalse(lease.acquire(3))

        owner.death()
        assertTrue(lease.acquire(3))
    }

    @Test
    fun duplicateCallbacksAndDuplicateDeathAreIdempotent() {
        val lease = SherpaLease()
        val connection = SherpaConnection(lease, 1)
        assertTrue(connection.begin())
        var links = 0
        assertEquals(SherpaConnectOutcome.ACQUIRED, connection.connected { links++ })
        assertEquals(SherpaConnectOutcome.ALREADY_LINKED, connection.connected { links++ })
        assertEquals(1, links)

        connection.death(); connection.death()
        assertTrue(lease.acquire(2))
        connection.death() // late duplicate cannot release the replacement
        assertFalse(lease.acquire(3))
    }

    @Test
    fun cancellationBeforeTheBinderCallbackNeverLinksOrOwnsALease() {
        val lease = SherpaLease()
        val connection = SherpaConnection(lease, 1)
        assertTrue(connection.begin())
        assertTrue(connection.abandon())

        var linked = false
        assertEquals(SherpaConnectOutcome.ABANDONED, connection.connected { linked = true })
        assertFalse(linked)
        assertTrue(lease.acquire(2))
    }
}
