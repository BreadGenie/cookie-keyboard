package org.futo.voiceinput.shared.sherpa

import android.app.Service
import android.content.Intent
import android.os.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.futo.voiceinput.shared.sherpa.SherpaSession.Companion.TOKEN
import org.futo.voiceinput.shared.sherpa.SherpaSession.Companion.LOAD
import org.futo.voiceinput.shared.sherpa.SherpaSession.Companion.FEED
import org.futo.voiceinput.shared.sherpa.SherpaSession.Companion.STOP
import org.futo.voiceinput.shared.sherpa.SherpaSession.Companion.CANCEL
import org.futo.voiceinput.shared.sherpa.SherpaSession.Companion.CHUNK

/** Blocking inference has one owner. Binder cancellation can kill it during load/decode. */
class SherpaService : Service() {
    private var isolated = false
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean()
    private val poisoned = AtomicBoolean()
    private val metrics = SherpaMetrics(SystemClock::elapsedRealtime) {
        Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong()
    }
    private val worker = SherpaWorker { kind, path ->
        val directory = File(path).canonicalFile
        check(directory.path.startsWith(filesDir.canonicalPath + File.separator))
        metrics.coldLoad { SherpaModels.load(kind, directory, metrics) }
    }
    override fun onCreate() {
        super.onCreate()
        val name = if(Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else
            (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        check(name == applicationInfo.processName + ":asr")
        isolated = true
        check(Build.VERSION.SDK_INT >= 27)
    }
    private fun terminate(): Nothing {
        check(isolated); poisoned.set(true); Process.killProcess(Process.myPid())
        error("Worker exit failed")
    }
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if(code == INTERFACE_TRANSACTION) { reply?.writeString(TOKEN); return true }
            check(getCallingUid() == applicationInfo.uid)
            data.enforceInterface(TOKEN)
            // Private process lifetime cancellation, independent of the blocked inference owner.
            if(code == CANCEL) { check(data.dataAvail() == 0); terminate() }
            if(code !in LOAD..STOP) return false
            if(poisoned.get() || !busy.compareAndSet(false, true)) terminate()
            try {
                if(code == LOAD) check(data.dataAvail() <= 32768)
                val generation = data.readLong(); val seq = data.readLong()
                check(generation > 0)
                val kind = if(code == LOAD) checkNotNull(data.readString()) else null
                val path = if(code == LOAD) checkNotNull(data.readString()) else null
                val hotwords = if(code == LOAD) SherpaHotwords.validate(checkNotNull(data.readString())) else ""
                check(kind == null || kind.length in 1..64)
                check(path == null || path.length in 1..4096)
                val pcm = if(code == FEED) {
                    val n = data.readInt(); check(n in 1..CHUNK)
                    FloatArray(n).also { data.readFloatArray(it); check(it.all { x -> x.isFinite() && x in -1f..1f }) }
                } else null
                check(data.dataAvail() == 0)
                val text = executor.submit<String?> {
                    check(!poisoned.get())
                    val result = worker.execute(code, generation, seq, kind, path, pcm, hotwords)
                    check(!poisoned.get()); result
                }.get()
                reply!!.writeNoException(); reply.writeLong(generation); reply.writeLong(seq); reply.writeString(text)
                if(code == LOAD || code == STOP) {
                    metrics.sample()
                    reply.writeLongArray(metrics.values())
                }
                busy.set(false)
                return true
            } catch(_: Throwable) {
                // OOM, JNI errors, malformed requests and native failures poison the process.
                // Do not marshal Throwable messages (may contain audio/text/paths).
                terminate()
            }
        }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onUnbind(intent: Intent): Boolean { terminate() }
    override fun onDestroy() { terminate() }
}
