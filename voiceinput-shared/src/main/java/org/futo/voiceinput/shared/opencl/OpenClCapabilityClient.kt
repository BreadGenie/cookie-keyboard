package org.futo.voiceinput.shared.opencl

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * One bounded bind/query/unbind. This is deliberately not an [OpenClLeases]
 * lease: it must not close, replace, or extend the warm inference binding and
 * must not touch resident tokens.
 */
internal class OpenClCapabilityClient(private val context: Context) {
    suspend fun query(timeoutMs: Long = DEFAULT_TIMEOUT_MS): OpenClCapabilities =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val app = context.applicationContext
                val handler = Handler(Looper.getMainLooper())
                val terminal = AtomicBoolean(false)
                val id = UUID.randomUUID().toString()
                var bound = false
                var connection: ServiceConnection? = null
                var timeoutRunnable: Runnable? = null

                fun cleanup() {
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    connection?.let { if (bound) runCatching { app.unbindService(it) } }
                    bound = false
                }

                fun finish(result: OpenClCapabilities) {
                    if (!terminal.compareAndSet(false, true)) return
                    cleanup()
                    if (continuation.isActive) continuation.resume(result)
                }

                val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                    if (message.what == OpenClService.CAPABILITY_RESULT &&
                        message.data?.getString("id") == id) {
                        val probe = OpenClProbeParser.parse(message.data?.getString("capability"))
                        finish(
                            if (probe == null) {
                                OpenClCapabilities.transportFailure(OpenClCapabilityReason.QUERY_FAILED)
                            } else {
                                OpenClCapabilityEvaluator.evaluate(probe)
                            }
                        )
                    }
                    true
                })

                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        if (terminal.get()) return
                        try {
                            Messenger(binder).send(Message.obtain(null, OpenClService.CAPABILITY).apply {
                                replyTo = reply
                                data = Bundle().apply {
                                    putString("id", id)
                                    putInt("protocol", OpenClCapabilityProtocol.VERSION)
                                }
                            })
                        } catch (_: RemoteException) {
                            finish(OpenClCapabilities.transportFailure(OpenClCapabilityReason.SERVICE_DEAD))
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        finish(OpenClCapabilities.transportFailure(OpenClCapabilityReason.SERVICE_DEAD))
                    }

                    override fun onBindingDied(name: ComponentName) {
                        finish(OpenClCapabilities.transportFailure(OpenClCapabilityReason.SERVICE_DEAD))
                    }

                    override fun onNullBinding(name: ComponentName) {
                        finish(OpenClCapabilities.unavailable(OpenClCapabilityReason.NOT_PACKAGED))
                    }
                }

                timeoutRunnable = Runnable {
                    finish(OpenClCapabilities.transportFailure(OpenClCapabilityReason.TIMEOUT))
                }

                try {
                    bound = app.bindService(
                        Intent(app, OpenClService::class.java),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                    if (!bound) {
                        finish(OpenClCapabilities.transportFailure(OpenClCapabilityReason.QUERY_FAILED))
                    } else {
                        handler.postDelayed(timeoutRunnable!!, timeoutMs)
                    }
                } catch (_: SecurityException) {
                    finish(OpenClCapabilities.transportFailure(OpenClCapabilityReason.QUERY_FAILED))
                }

                continuation.invokeOnCancellation {
                    handler.post {
                        if (terminal.compareAndSet(false, true)) cleanup()
                    }
                }
            }
        }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
    }
}

/**
 * Process-lifetime cache shared by the settings predicate and the runtime gate.
 * The device/build key prevents reuse across an app/ROM update in the same
 * process; the native library itself cannot change inside a process.
 */
object OpenClCapabilityGate {
    private const val TRANSIENT_RETRY_DELAY_MS = 150L
    @Volatile private var cached: OpenClCapabilities? = null
    @Volatile private var cachedKey: String? = null
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val state = kotlinx.coroutines.flow.MutableStateFlow<OpenClCapabilities?>(null)

    /** Shared result observed by every settings row, so a retry updates all. */
    val updates: kotlinx.coroutines.flow.StateFlow<OpenClCapabilities?> = state

    fun peek(): OpenClCapabilities? = cached

    suspend fun ensure(context: Context): OpenClCapabilities {
        val app = context.applicationContext
        val key = cacheKey(app)
        cached?.let { if (cachedKey == key) return it }
        return mutex.withLock {
            cached?.let { if (cachedKey == key) return@withLock it }
            val result = withContext(Dispatchers.IO) {
                // v45: one bounded retry for a transient transport/probe
                // failure (cold service start, binder death, timeout). A
                // definitive UNSUPPORTED/UNAVAILABLE is cached immediately.
                var attempt = OpenClCapabilityClient(app).query()
                if (attempt.retryable) {
                    kotlinx.coroutines.delay(TRANSIENT_RETRY_DELAY_MS)
                    attempt = OpenClCapabilityClient(app).query()
                }
                attempt
            }
            cached = result
            cachedKey = key
            state.value = result
            OpenClDiagnostics.record(app, result.diagnostic())
            result
        }
    }

    /**
     * One user-initiated re-check, bounded to one more probe attempt by
     * [ensure]. Only a transient UNKNOWN result is cleared; a definitive
     * SUPPORTED/UNSUPPORTED answer is not re-probed.
     */
    suspend fun retryTransient(context: Context): OpenClCapabilities {
        val app = context.applicationContext
        mutex.withLock {
            if (cached?.retryable == true) {
                cached = null
                cachedKey = null
                state.value = null
            }
        }
        return ensure(app)
    }

    private fun cacheKey(context: Context): String {
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        return listOf(
            android.os.Build.FINGERPRINT,
            android.os.Build.VERSION.SDK_INT.toString(),
            android.os.Build.BOARD,
            android.os.Build.DEVICE,
            android.os.Build.HARDWARE,
            context.packageName,
            versionName,
        ).joinToString("|")
    }
}
