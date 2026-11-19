package org.futo.inputmethod.latin.whistle

import android.app.Service
import android.content.Intent
import android.os.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.futo.inputmethod.latin.whistle.WhistleSession.Companion.LOAD
import org.futo.inputmethod.latin.whistle.WhistleSession.Companion.FEED
import org.futo.inputmethod.latin.whistle.WhistleSession.Companion.FINISH
import org.futo.inputmethod.latin.whistle.WhistleSession.Companion.CANCEL
import org.futo.inputmethod.latin.whistle.WhistleSession.Companion.TOKEN

/** Same UID, private process. Binder control never waits for the inference owner. */
class WhistleService : Service() {
    private var ownWorker = false
    override fun onCreate() {
        super.onCreate()
        val name = if(Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
        else (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }?.processName
        check(name == applicationInfo.processName + ":whistle") { "Whistle process isolation required" }
        ownWorker = true
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean()
    private val invalid = AtomicBoolean()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var session: WhistleSession? = null // executor-owned
    private fun reclaim(): Nothing { check(ownWorker); invalid.set(true); stopSelf(); Process.killProcess(Process.myPid()); throw IllegalStateException("Worker exit failed") }
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if(code == INTERFACE_TRANSACTION) { reply?.writeString(TOKEN); return true }
            check(Binder.getCallingUid() == applicationInfo.uid)
            data.enforceInterface(TOKEN)
            if(code == CANCEL) {
                val g = data.readLong()
                check(g > 0 && (generation.get() == 0L || generation.get() == g) && data.dataAvail() == 0)
                reclaim()
            }
            if(code !in LOAD..FINISH) return false
            val g = data.readLong(); val seq = data.readLong()
            if(g <= 0 || (code == LOAD && !generation.compareAndSet(0, g)) || generation.get() != g) reclaim()
            // No queue: a second outstanding PCM/control request poisons the worker.
            if(invalid.get() || !busy.compareAndSet(false, true)) reclaim()
            val stage = WhistleStage.REQUEST
            var fd: ParcelFileDescriptor? = null
            val pcm: FloatArray?
            try {
                if(code == LOAD) fd = data.readParcelable(ParcelFileDescriptor::class.java.classLoader)
                pcm = if(code == FEED) {
                    val count = data.readInt(); check(count in 1..16000)
                    FloatArray(count).also { data.readFloatArray(it); check(it.all { n -> n.isFinite() && n in -1f..1f }) }
                } else null
                check(data.dataAvail() == 0)
                val ownedFd = fd
                val output = executor.submit<ByteArray?> {
                    WhistleDiagnostics.at(when(code) { LOAD -> WhistleStage.LOAD; FEED -> WhistleStage.FEED; else -> WhistleStage.FINISH }) { try {
                        check(!invalid.get())
                        if(code == LOAD) { check(session == null); session = WhistleSession(g) }
                        val s = checkNotNull(session); check(s.generation == g); s.begin(code, seq)
                        val bytes = when(code) {
                            LOAD -> { if(!WhistleNative.load(g, checkNotNull(ownedFd).fd)) throw WhistleDiagnostics.failure(WhistleStage.LOAD, WhistleCode.NATIVE_REJECTED); null }
                            FEED -> WhistleNative.feed(g, checkNotNull(pcm)) ?: throw WhistleDiagnostics.failure(WhistleStage.FEED, WhistleCode.NATIVE_EMPTY)
                            else -> WhistleNative.finish(g) ?: throw WhistleDiagnostics.failure(WhistleStage.FINISH, WhistleCode.NATIVE_EMPTY)
                        }
                        check(!invalid.get())
                        WhistleDiagnostics.at(WhistleStage.SCHEMA) { s.accept(g, seq, bytes?.toString(Charsets.UTF_8)) }
                        bytes
                    } finally { ownedFd?.close() } }
                }.get()
                check(!invalid.get())
                reply!!.writeNoException(); reply.writeLong(g); reply.writeLong(seq); reply.writeInt(0); reply.writeByteArray(output)
                busy.set(false)
                return true
            } catch(e: Exception) {
                // Poison immediately. Return only sanitized metadata; never accept more inference.
                invalid.set(true)
                val report = WhistleDiagnostics.report(stage, WhistleCode.EXCEPTION, e)
                try { fd?.close() } catch(_: Exception) { }
                // Independent exit fallback; parent still requires DeathRecipient, never this reply.
                Handler(Looper.getMainLooper()).postDelayed({ reclaim() }, 1000)
                reply!!.writeNoException(); reply.writeLong(g); reply.writeLong(seq)
                reply.writeInt(1); reply.writeByteArray(report.toByteArray(Charsets.UTF_8))
                return true
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent): IBinder = binder
    override fun onUnbind(intent: Intent): Boolean { reclaim() }
    override fun onDestroy() { reclaim() }
}
