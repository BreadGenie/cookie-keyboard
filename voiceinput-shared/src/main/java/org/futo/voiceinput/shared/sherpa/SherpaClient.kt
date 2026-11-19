package org.futo.voiceinput.shared.sherpa

import android.content.*
import android.os.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.futo.voiceinput.shared.sherpa.SherpaSession.Companion.CANCEL
import org.futo.voiceinput.shared.sherpa.SherpaSession.Companion.TOKEN

internal interface SherpaTransport {
    fun connect() = Unit
    fun request(op: Int, generation: Long, sequence: Long, kind: String? = null,
                path: String? = null, samples: FloatArray? = null, hotwords: String = ""): String?
    fun cancel()
}

/**
 * Process lifetime identity differs from recording generation. The lease is taken in the
 * Binder callback (after linkToDeath, before connect() returns), so only Binder death can
 * release it. A failure before that callback cannot have sent LOAD, so it owns no native
 * state and cannot poison a later session.
 */
internal class SherpaClient(private val context: Context, private val identity: Long) : SherpaTransport {
    companion object { val lease = SherpaLease() }
    private val authority = SherpaConnection(lease, identity)
    private val connected = CountDownLatch(1)
    private val invalid = AtomicBoolean()
    private val terminating = AtomicBoolean()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var binder: IBinder? = null
    private val bound = AtomicBoolean()
    private val deathRecipient = IBinder.DeathRecipient {
        invalid.set(true)
        try { unbindOnce() } finally { authority.death() }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if(invalid.get()) {
                // A duplicate callback after linking must leave unbinding/release to the
                // DeathRecipient; only a pre-link callback is safe to tear down here.
                if(!authority.linked()) {
                    // Cancelled before the callback: a worker may have started, but no LOAD
                    // was possible through this connection, so it owns no native state.
                    authority.abandon(); unbindOnce(); stopWorkerIfUnowned()
                }
                connected.countDown(); return
            }
            when(authority.connected(
                linkToDeath = { service.linkToDeath(deathRecipient, 0) },
                onAcquired = { binder = service }
            )) {
                SherpaConnectOutcome.ACQUIRED -> {
                    connected.countDown()
                    if(invalid.get()) terminate()
                }
                // Duplicate callbacks must not link twice or take a second lease.
                SherpaConnectOutcome.ALREADY_LINKED -> Unit
                SherpaConnectOutcome.LEASE_HELD -> {
                    // Another connection owns the worker; never stop or release it.
                    unbindOnce(); connected.countDown()
                }
                SherpaConnectOutcome.ABANDONED,
                SherpaConnectOutcome.LINK_FAILED -> {
                    unbindOnce(); stopWorkerIfUnowned(); connected.countDown()
                }
            }
        }
        // Neither callback is death proof. A linked connection keeps its lease until the
        // DeathRecipient fires; an unlinked one never acquired a lease.
        override fun onServiceDisconnected(name: ComponentName) {
            invalid.set(true); authority.abandon(); connected.countDown()
        }
        override fun onBindingDied(name: ComponentName) {
            invalid.set(true); authority.abandon(); connected.countDown()
        }
        override fun onNullBinding(name: ComponentName) {
            invalid.set(true); authority.abandon(); connected.countDown()
        }
    }
    private val connecting = AtomicBoolean()
    override fun connect() {
        if(!connecting.compareAndSet(false, true)) { check(!invalid.get()); return }
        check(!invalid.get())
        check(Looper.myLooper() != Looper.getMainLooper())
        check(authority.begin())
        val intent = Intent(context, SherpaService::class.java)
        val started = try { context.startService(intent) } catch(_: Exception) { null }
        // No Binder callback means no lease was taken; this path never loaded native state.
        if(started == null) { authority.abandon(); error("ASR unavailable") }
        // Never AUTO_CREATE: cancellation must not implicitly create a replacement.
        val accepted = try { context.bindService(intent, connection, 0) } catch(_: Exception) { false }
        if(!accepted) { authority.abandon(); stopWorkerIfUnowned(); error("ASR unavailable") }
        bound.set(true)
        connected.await(10, TimeUnit.SECONDS)
        if(invalid.get() || !authority.linked()) {
            if(authority.linked()) {
                // Cancel/binding failure after the handshake: the DeathRecipient is now the
                // only release authority, so do not unbind or release here.
                terminate()
            } else {
                authority.abandon(); unbindOnce(); stopWorkerIfUnowned(); terminate()
            }
            error("ASR unavailable")
        }
    }
    private fun unbindOnce() {
        if(bound.compareAndSet(true, false)) try { context.unbindService(connection) } catch(_: Exception) { }
    }
    /** stopService must never touch a worker whose lease belongs to another connection. */
    private fun stopWorkerIfUnowned() {
        if(lease.held()) return
        try { context.stopService(Intent(context, SherpaService::class.java)) } catch(_: Exception) { }
    }
    fun alive() = !invalid.get()
    override fun request(op: Int, generation: Long, sequence: Long, kind: String?, path: String?, samples: FloatArray?, hotwords: String): String? {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        // A stuck native call becomes a failed session; timeout never releases the lease.
        val watchdog = Runnable { cancel() }
        main.postDelayed(watchdog, 5 * 60 * 1000L)
        try {
            check(!invalid.get())
            data.writeInterfaceToken(TOKEN); data.writeLong(generation); data.writeLong(sequence)
            if(op == SherpaSession.LOAD) { data.writeString(kind); data.writeString(path); data.writeString(SherpaHotwords.validate(hotwords)) }
            if(op == SherpaSession.FEED) { data.writeInt(checkNotNull(samples).size); data.writeFloatArray(samples) }
            check(checkNotNull(binder).transact(op, data, reply, 0)); reply.readException()
            check(reply.readLong() == generation && reply.readLong() == sequence)
            val text = reply.readString(); check(text == null || text.length <= SherpaSession.MAX_TEXT)
            val diagnostic = if(op == SherpaSession.LOAD || op == SherpaSession.STOP)
                sherpaDiagnostic(op, checkNotNull(reply.createLongArray())) else null
            check(reply.dataAvail() == 0 && !invalid.get())
            diagnostic?.let { org.futo.voiceinput.shared.opencl.OpenClDiagnostics.record(context, it) }
            return text
        } catch(_: Throwable) { cancel(); throw SherpaFailure() }
        finally { main.removeCallbacks(watchdog); data.recycle(); reply.recycle() }
    }
    override fun cancel() { invalid.set(true); terminate() }
    private fun terminate() {
        val remote = binder ?: return // onServiceConnected performs a pending cancellation.
        if(!terminating.compareAndSet(false, true)) return
        Thread({
            val p = Parcel.obtain(); val r = Parcel.obtain()
            try { p.writeInterfaceToken(TOKEN); remote.transact(CANCEL, p, r, 0) }
            catch(_: Exception) { /* No death callback means no replacement. */ }
            finally { p.recycle(); r.recycle() }
        }, "asr-reclaim").start()
    }
}

/** Keyboard lifecycle bridge; does not create a service or load JNI. */
object SherpaResidency {
    fun keyboardVisible(visible: Boolean) = SherpaWorkers.visibility(visible)
}

/** Single IME-wide warm worker; idle residency expires one minute after keyboard hide. */
internal object SherpaWorkers {
    private val ids = AtomicLong(System.nanoTime())
    private var client: SherpaClient? = null
    private var owner: Long? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var expiry: Runnable? = null
    private var visible = false
    @Synchronized fun acquire(context: Context, generation: Long): SherpaClient {
        check(owner == null)
        expiry?.let { main.removeCallbacks(it) }; expiry = null
        owner = generation
        val current = client?.takeIf { it.alive() }
        return current ?: SherpaClient(context.applicationContext, ids.incrementAndGet()).also { client = it }
    }
    @Synchronized fun release(generation: Long, cancel: Boolean) {
        if(owner != generation) return
        owner = null
        if(cancel) { client?.cancel(); client = null }
        else if(!visible) scheduleExpiry()
    }
    @Synchronized fun visibility(shown: Boolean) {
        visible = shown
        expiry?.let { main.removeCallbacks(it) }; expiry = null
        if(!shown && owner == null && client != null) scheduleExpiry()
    }
    private fun scheduleExpiry() {
        expiry?.let { main.removeCallbacks(it) }
        val current = client
        val task = Runnable { synchronized(this) {
            if(!visible && owner == null && client === current) { current?.cancel(); client = null }
        } }
        expiry = task; main.postDelayed(task, 60000)
    }
}

/** Fixed error type: never contains remote messages, model paths, audio or recognized text. */
internal class SherpaFailure : Exception()
