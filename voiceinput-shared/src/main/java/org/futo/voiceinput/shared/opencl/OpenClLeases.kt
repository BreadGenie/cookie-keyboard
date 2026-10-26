package org.futo.voiceinput.shared.opencl

import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.ComponentName
import android.os.IBinder
import android.content.Context
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper

/** Main-thread-only idle bindings. Active calls retain their own binding until terminal cleanup. */
internal object OpenClLeases {
    private val handler = Handler(Looper.getMainLooper())
    private val idle = mutableMapOf<ServiceConnection, Pair<Context, Runnable>>()
    private var registered = false
    /** Bumped on [release]; read from any thread (volatile). */
    @Volatile var generation = 0
        private set
    /**
     * v21 fast-mode residency epoch. Bumped whenever the warm service binding
     * set may no longer hold the encoder-flash context the fast token was
     * verified against: explicit [release]/[releaseFast], idle-lease expiry,
     * or observed idle-lease death. Successful [retain] does NOT bump: a
     * replaced idle binding keeps the same live service process and context.
     * Read from any thread (volatile); mutated only on the main thread except
     * [releaseFast], which is a plain counter bump safe from anywhere.
     *
     * This is a Kotlin-side binding epoch, not an auth secret: the numeric
     * resident token is still validated service-side (a fresh service
     * instance starts generation 0 with no verified resident, so an old
     * numeric token alone can never skip verification). The epoch only lets
     * the client proactively cold-verify instead of presenting a token that
     * the service would reject anyway.
     */
    @Volatile var fastEpoch: Long = 0L
        private set
    const val IDLE_MS = 5 * 60 * 1000L
    fun retain(context: Context, connection: ServiceConnection, previousGeneration: Int) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val app = context.applicationContext
        if (!registered) {
            registered = true
            app.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onConfigurationChanged(config: Configuration) {}
                override fun onLowMemory() = release()
                override fun onTrimMemory(level: Int) {
                    if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) release()
                }
            })
        }
        if (generation != previousGeneration) {
            app.unbindService(connection)
            return
        }
        // A lease has no request callbacks, continuation or audio captures. Never retain the
        // request's ServiceConnection: its callback graph contains the session FloatArray.
        // v21: the idle lease is also the fast-mode death recipient. If the
        // service process dies while only an idle lease is bound, no active
        // request observes the death, so the lease itself retires the
        // client-side fast epoch; the next fast request cold-verifies.
        val lease = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {}
            override fun onServiceDisconnected(name: ComponentName) { fastEpoch++ }
            override fun onBindingDied(name: ComponentName) { fastEpoch++ }
            override fun onNullBinding(name: ComponentName) { fastEpoch++ }
        }
        try {
            if (app.bindService(Intent(app, OpenClService::class.java), lease, Context.BIND_AUTO_CREATE)) {
                val expire = Runnable {
                    idle.remove(lease)?.let { (ctx, _) ->
                        // v21: the 5-minute warm lease expired, so the service
                        // may be destroyed with its resident context. Retire
                        // the fast epoch; the next fast request cold-verifies.
                        fastEpoch++
                        ctx.unbindService(lease)
                    }
                }
                val old = idle.toMap()
                idle[lease] = app to expire
                old.forEach { (conn, pair) -> handler.removeCallbacks(pair.second); pair.first.unbindService(conn); idle.remove(conn) }
                handler.postDelayed(expire, IDLE_MS)
            }
        } catch (_: SecurityException) {
            // Retention is optional; the completed result must remain deliverable.
        } finally {
            app.unbindService(connection)
        }
    }
    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { release() }; return }
        generation++
        // v21: trim/low-memory, master-toggle off, and stock-fallback paths
        // release every idle binding; any resident encoder-flash context goes
        // with it, so retire the fast epoch as well.
        fastEpoch++
        idle.toMap().forEach { (conn, pair) -> handler.removeCallbacks(pair.second); pair.first.unbindService(conn) }
        idle.clear()
    }

    /**
     * v21: retires only fast-mode residency (Fast toggle off, model/import
     * change observed without a full release). Safe from any thread. Leaves
     * normal-GPU idle bindings and in-flight requests untouched; the
     * in-flight fast request still completes under its captured ticket, but
     * its late token-accept is dropped and the next request cold-verifies.
     */
    fun releaseFast() {
        fastEpoch++
    }
}

/** Public only for the host app's settings; no native library is loaded in the keyboard process. */
object OpenClResources {
    fun releaseIdle() = OpenClLeases.release()
    /** v21: Fast toggle off (or fast-mode invalidation) without touching
     * normal-GPU idle bindings or in-flight work. */
    fun releaseFast() = OpenClLeases.releaseFast()
}
