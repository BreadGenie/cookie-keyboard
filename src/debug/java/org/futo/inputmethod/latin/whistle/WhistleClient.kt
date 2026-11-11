package org.futo.inputmethod.latin.whistle

import android.content.*
import android.os.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.futo.inputmethod.latin.whistle.WhistleSession.Companion.LOAD
import org.futo.inputmethod.latin.whistle.WhistleSession.Companion.FEED
import org.futo.inputmethod.latin.whistle.WhistleSession.Companion.FINISH
import org.futo.inputmethod.latin.whistle.WhistleSession.Companion.CANCEL
import org.futo.inputmethod.latin.whistle.WhistleSession.Companion.TOKEN

/** One lease across all diagnostic screens. Unconfirmed death permanently holds it. */
internal class WhistleClient(private val context: Context) {
    companion object { private val occupied = ReclamationGate(); private val generations = AtomicLong(System.nanoTime()) }
    private val session = WhistleSession(generations.incrementAndGet())
    private val invalid = AtomicBoolean()
    private val inFlight = AtomicBoolean()
    private val terminating = AtomicBoolean()
    private val deathSeen = AtomicBoolean()
    private val connected = CountDownLatch(1)
    private val dead = CountDownLatch(1)
    @Volatile private var remote: IBinder? = null
    private var sequence = 0L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var terminal: String? = null
    @Volatile private var controlFailure: String? = null
    fun cleanupDiagnostic(): String? = controlFailure
    private val timeout = Runnable { terminal = WhistleDiagnostics.report(WhistleStage.CONTROL, WhistleCode.WATCHDOG); cancel() }
    fun diagnostic(): String? = terminal
    private fun <T> at(stage: WhistleStage, action: () -> T): T = WhistleDiagnostics.at(stage, action)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if(deathSeen.get()) return
            remote = service
            try { service.linkToDeath({
                deathSeen.set(true); main.removeCallbacks(timeout); session.died()
                try { context.unbindService(this) } finally { occupied.confirmedDeath(session.generation); dead.countDown() }
            }, 0) } catch(e: RemoteException) {
                // linkToDeath throwing does not establish this lease's death callback.
                terminal = WhistleDiagnostics.report(WhistleStage.CONNECT, WhistleCode.EXCEPTION, e); invalid.set(true); connected.countDown(); return
            }
            connected.countDown()
            if(invalid.get()) terminate()
        }
        override fun onServiceDisconnected(name: ComponentName) { /* DeathRecipient is authoritative; no reconnect. */ }
        override fun onNullBinding(name: ComponentName) { terminal = WhistleDiagnostics.report(WhistleStage.BIND, WhistleCode.REJECTED); invalid.set(true); connected.countDown() }
        override fun onBindingDied(name: ComponentName) {
            // Expected after acknowledged FINISH requests reclamation (or cancel,
            // which already invalidated delivery). This callback is never death proof.
            if(!terminating.get()) {
                terminal = WhistleDiagnostics.report(WhistleStage.BIND, WhistleCode.WORKER_LOST)
                invalid.set(true)
            }
            connected.countDown()
        }
    }
    fun connect() {
        check(Looper.myLooper() != Looper.getMainLooper())
        at(WhistleStage.LEASE) { check(occupied.acquire(session.generation)) }
        val intent = Intent(context, WhistleService::class.java)
        val started = try { context.startService(intent) }
        catch(e: Exception) { throw WhistleDiagnostics.failure(WhistleStage.START, WhistleCode.EXCEPTION, e) }
        if(started == null) { occupied.notStarted(session.generation); throw WhistleDiagnostics.failure(WhistleStage.START, WhistleCode.REJECTED) }
        // No AUTO_CREATE/rebind: a killed process must never recreate itself behind the lease.
        val accepted = try { context.bindService(intent, connection, 0) } catch(e: Exception) { throw WhistleDiagnostics.failure(WhistleStage.BIND, WhistleCode.EXCEPTION, e) }
        if(!accepted) {
            context.stopService(intent)
            // A process may have started. stopService is not death evidence: retain lease.
            throw WhistleDiagnostics.failure(WhistleStage.BIND, WhistleCode.REJECTED)
        }
        main.postDelayed(timeout, 30000)
        if(!connected.await(10, TimeUnit.SECONDS) || invalid.get()) { cancel(); throw WhistleDiagnostics.failure(WhistleStage.CONNECT, WhistleCode.TIMEOUT) }
    }
    private fun call(op: Int, model: File? = null, pcm: FloatArray? = null): String? {
        check(inFlight.compareAndSet(false, true)) { cancel(); "PCM backlog" }
        var stage = WhistleStage.REQUEST
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            check(!invalid.get()); session.begin(op, sequence)
            data.writeInterfaceToken(TOKEN); data.writeLong(session.generation); data.writeLong(sequence)
            if(op == LOAD) ParcelFileDescriptor.open(checkNotNull(model), ParcelFileDescriptor.MODE_READ_ONLY).use { data.writeParcelable(it, 0) }
            if(op == FEED) { check(pcm != null && pcm.size in 1..16000 && pcm.all { it.isFinite() && it in -1f..1f }); data.writeInt(pcm.size); data.writeFloatArray(pcm) }
            stage = WhistleStage.TRANSACT
            check(checkNotNull(remote).transact(op, data, reply, 0)); reply.readException()
            stage = WhistleStage.ACK
            val g = reply.readLong(); val seq = reply.readLong()
            check(g == session.generation && seq == sequence)
            val outcome = reply.readInt(); check(outcome in 0..1)
            val size = reply.readInt(); check(size in -1..262143)
            reply.setDataPosition(reply.dataPosition() - 4)
            val bytes = reply.createByteArray(); check(reply.dataAvail() == 0)
            if(outcome == 1) { check(bytes != null && bytes.size <= 2048); throw WhistleDiagnostics.checkedRemote(bytes.toString(Charsets.UTF_8)) }
            check(!invalid.get())
            stage = WhistleStage.SCHEMA
            val result = session.accept(g, seq, bytes?.toString(Charsets.UTF_8)); sequence++
            check(!invalid.get())
            return result
        } catch(e: Exception) { val failure = WhistleDiagnostics.failure(stage, WhistleCode.EXCEPTION, e); cancel(); throw failure }
        finally { data.recycle(); reply.recycle(); inFlight.set(false) }
    }
    fun load(model: File) { call(LOAD, model) }
    fun feed(pcm: FloatArray) { call(FEED, pcm = pcm) }
    /** Diagnostic returns a count only; zero PCM is not labelled public speech. */
    fun finishAndReclaim(): Int {
        return finishTextAndReclaim().length
    }
    /** Only the opt-in diagnostic may display this ephemeral result. */
    fun finishTextAndReclaim(): String {
        val result = call(FINISH) ?: error("No final result")
        terminate()
        if(!dead.await(10, TimeUnit.SECONDS)) throw WhistleDiagnostics.failure(WhistleStage.DEATH, WhistleCode.TIMEOUT)
        main.removeCallbacks(timeout)
        check(!invalid.get())
        return result
    }
    fun deliveryAllowed(): Boolean = !invalid.get()
    fun cancel() { invalid.set(true); session.invalidate(); terminate() }
    fun awaitDeath(): Boolean = dead.await(10, TimeUnit.SECONDS)
    private fun terminate() {
        val binder = remote ?: return // cancellation before connect is handled by connection callback
        if(!terminating.compareAndSet(false, true)) return
        Thread({
            val p = Parcel.obtain(); val r = Parcel.obtain()
            try { p.writeInterfaceToken(TOKEN); p.writeLong(session.generation); if(!binder.transact(CANCEL, p, r, 0)) controlFailure = WhistleDiagnostics.report(WhistleStage.CONTROL, WhistleCode.REJECTED) }
            catch(e: Exception) { controlFailure = WhistleDiagnostics.report(WhistleStage.CONTROL, WhistleCode.EXCEPTION, e) /* Only DeathRecipient releases the lease. */ }
            finally { p.recycle(); r.recycle() }
        }, "whistle-reclaim").start()
    }
}
