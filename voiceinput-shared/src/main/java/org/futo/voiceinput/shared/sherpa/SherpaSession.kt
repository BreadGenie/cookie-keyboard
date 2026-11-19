package org.futo.voiceinput.shared.sherpa

/** No Android or JNI dependencies: shared protocol invariants and cancellation boundary. */
internal class SherpaSession(val generation: Long) {
    enum class State { NEW, LOADING, READY, FEEDING, DECODING, DONE, INVALID }
    var state = State.NEW; private set
    private var sequence = 0L
    private var pending = -1L
    private var count = 0
    @Synchronized fun begin(op: Int, seq: Long, samples: Int = 0) {
        check(seq == sequence && pending == -1L)
        check(when (op) { LOAD -> state == State.NEW; FEED, STOP -> state == State.READY; else -> false })
        if(op == FEED) { check(samples in 1..CHUNK && count <= MAX_SAMPLES - samples); count += samples }
        pending = seq
        state = when(op) { LOAD -> State.LOADING; FEED -> State.FEEDING; else -> State.DECODING }
    }
    @Synchronized fun accept(g: Long, seq: Long, text: String?): String? {
        check(state != State.INVALID && g == generation && seq == pending && seq == sequence)
        check(state in listOf(State.LOADING, State.FEEDING, State.DECODING))
        val final = state == State.DECODING
        check(if(final) text != null && text.length <= MAX_TEXT else text == null)
        sequence++; pending = -1
        state = if(final) State.DONE else State.READY
        return if(final) text else null
    }
    @Synchronized fun invalidate() { state = State.INVALID; pending = -1 }
    companion object {
        const val TOKEN = "cookie.sherpa.v1"
        const val LOAD = 1; const val FEED = 2; const val STOP = 3; const val CANCEL = 4
        const val CHUNK = 16000
        // Resource failure, never a successful truncation or automatic stop.
        const val MAX_SAMPLES = 16000 * 60 * 20
        const val MAX_TEXT = 65536
    }
}

/** A process lease is released only by Binder death of the worker that owns it. */
internal class SherpaLease {
    private var owner: Long? = null
    @Synchronized fun acquire(g: Long): Boolean { if(owner != null) return false; owner = g; return true }
    @Synchronized fun release(g: Long) { check(owner == g); owner = null }
    /** True while some worker may own native state. */
    @Synchronized fun held(): Boolean = owner != null
    /** Binder DeathRecipient evidence (alias of [release]). */
    fun died(g: Long) = release(g)
}

internal enum class SherpaConnectOutcome {
    /** linkToDeath succeeded and this connection took the lease. */
    ACQUIRED,
    /** A duplicate onServiceConnected arrived after this connection linked. */
    ALREADY_LINKED,
    /** The attempt was already abandoned/timed out; never link or load. */
    ABANDONED,
    /** linkToDeath failed, so the Binder was already dead. No native call is possible. */
    LINK_FAILED,
    /** Another connection owns the worker; leave its lease and worker untouched. */
    LEASE_HELD,
}

/**
 * One connection attempt's authority over the process lease.
 *
 * The Binder death recipient is registered first, then the lease is acquired. `connect()`
 * returns only after [connected] succeeds, and the first native request (`LOAD`) happens
 * after `connect()`. Therefore a failure before the Binder callback cannot have loaded
 * native worker state and needs no death proof; once acquired, only [death] (an actual
 * Binder death) may release the lease, so a replacement never starts while the old worker
 * may still own native state. Every release is single-shot.
 */
internal class SherpaConnection(private val lease: SherpaLease, private val identity: Long) {
    private enum class Phase { NEW, CONNECTING, LINKED, FAILED }
    private val lock = Any()
    private var phase = Phase.NEW
    private var acquired = false
    private val released = java.util.concurrent.atomic.AtomicBoolean()

    /** Reserves the single connection attempt for this client. */
    fun begin(): Boolean = synchronized(lock) {
        if(phase != Phase.NEW) false else { phase = Phase.CONNECTING; true }
    }

    /**
     * Runs in onServiceConnected, before connect() returns. [linkToDeath] must register
     * the death recipient; if it throws, the Binder is already dead. [onAcquired] publishes
     * the Binder before [linked] becomes true, so a waiter can never proceed to `LOAD`
     * without a Binder.
     */
    fun connected(onAcquired: () -> Unit = {}, linkToDeath: () -> Unit): SherpaConnectOutcome = synchronized(lock) {
        when {
            phase == Phase.LINKED -> SherpaConnectOutcome.ALREADY_LINKED
            phase != Phase.CONNECTING -> SherpaConnectOutcome.ABANDONED
            else -> try {
                linkToDeath()
                if(lease.acquire(identity)) {
                    acquired = true
                    onAcquired()
                    phase = Phase.LINKED
                    SherpaConnectOutcome.ACQUIRED
                } else {
                    phase = Phase.FAILED
                    SherpaConnectOutcome.LEASE_HELD
                }
            } catch(_: Exception) {
                phase = Phase.FAILED
                SherpaConnectOutcome.LINK_FAILED
            }
        }
    }

    /** Ends an attempt that has not linked. Returns true only if this call won. */
    fun abandon(): Boolean = synchronized(lock) {
        if(phase != Phase.CONNECTING) false else { phase = Phase.FAILED; true }
    }

    fun linked(): Boolean = synchronized(lock) { phase == Phase.LINKED }

    /** Binder death is the only evidence that can release an acquired lease. */
    fun death() = synchronized(lock) {
        if(acquired && released.compareAndSet(false, true)) lease.release(identity)
    }
}
