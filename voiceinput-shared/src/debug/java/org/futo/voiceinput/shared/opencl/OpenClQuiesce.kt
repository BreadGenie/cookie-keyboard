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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.suspendCoroutine

/**
 * v43 debug-only serial-worker quiescence fence.
 *
 * The v37 cleanup removed the service's `DRAIN` message and its client
 * `awaitIdle()` barrier as debug-only code. The v40-v42 comparison harness
 * replaced that barrier with a production `OpenClClient.prepare(file)` call
 * after cancellation. That is semantically wrong for cleanup:
 *
 *  - `prepare` is a full request. It hashes/verifies the model, reloads and
 *    prepares a native engine, and can fail for model/startup reasons that
 *    say nothing about whether the worker is idle.
 *  - It runs through the cancellable request continuation
 *    (`withContext(Dispatchers.Main)` + `suspendCancellableCoroutine`). A
 *    cancellation at that boundary can surface as `JobCancellationException`
 *    even after the serial worker has already returned.
 *  - It exercises resident/cancellation state that cleanup must not reset.
 *
 * This file restores the v37 serial barrier with two safety changes:
 *  - `DRAIN` is enqueued on the same single-thread worker as RUN/PREPARE and
 *    replies with the request ledger's idle state. No model, audio, engine
 *    or cancellation state is touched.
 *  - The wait is an explicitly `NonCancellable` Main bind plus a
 *    non-cancellable [OpenClFenceWait]. A cancelled caller can never discard
 *    an ack; a missing ack leaves the harness UNKNOWN with the gate held.
 *
 * Only the debug comparison harness calls this. No production path does.
 */
internal data class OpenClFenceAck(val idle: Boolean)

/** Content-free stage label for a fence that could not prove quiescence. */
internal class OpenClFenceException(
    val stage: String,
    cause: Throwable? = null,
) : Exception("opencl quiescence fence failed at $stage", cause)

internal object ComparisonFence {
    /** Exact success label recorded in the cleanup report. */
    const val ACKED = "native_quiesced"
    const val BUSY = "native_busy"
    const val KIND = "serial_worker_quiesce_ack"
}

/**
 * One-shot waiter for the serial-worker ack.
 *
 * This is deliberately not a CancellableContinuation: the ack is proof that
 * the service worker returned, so a cancellation of the calling coroutine
 * must never discard it. If the caller is torn down, the waiter still
 * completes when the service replies; the UI's bounded wait decides when to
 * show UNKNOWN. A missing ack leaves the voice gate held.
 */
internal class OpenClFenceWait {
    private val lock = Any()
    private var waiter: ((Result<OpenClFenceAck>) -> Unit)? = null
    private var result: Result<OpenClFenceAck>? = null

    suspend fun await(): OpenClFenceAck = suspendCoroutine { continuation ->
        val settled = synchronized(lock) {
            result.also {
                if (it == null) {
                    waiter = { completed -> continuation.resumeWith(completed) }
                }
            }
        }
        if (settled != null) continuation.resumeWith(settled)
    }

    fun ack(idle: Boolean) = complete(Result.success(OpenClFenceAck(idle)))

    fun fail(stage: String, cause: Throwable? = null) =
        complete(Result.failure(OpenClFenceException(stage, cause)))

    private fun complete(next: Result<OpenClFenceAck>) {
        val notify = synchronized(lock) {
            if (result != null) return
            result = next
            waiter.also { waiter = null }
        }
        notify?.invoke(next)
    }
}

/**
 * Binds the debug service, sends `DRAIN`, and waits for the worker's reply.
 *
 * Failure is always a structured [OpenClFenceException] stage; a service
 * death, failed bind or failed send is never treated as success. The v37
 * client completed optimistically on those paths; this one does not.
 */
internal suspend fun awaitOpenClQuiescence(context: Context): OpenClFenceAck {
    val wait = OpenClFenceWait()
    var bound = false
    var connection: ServiceConnection? = null

    withContext(NonCancellable + Dispatchers.Main) {
        val id = java.util.UUID.randomUUID().toString()
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.data.getString("id") == id) {
                when (message.what) {
                    OpenClService.RESULT ->
                        wait.ack(message.data.getString("text") == "idle")
                    OpenClService.STARTUP_ERROR -> wait.fail("service_startup")
                    OpenClService.ERROR -> wait.fail("service_error")
                    OpenClService.CANCELLED -> wait.fail("service_cancelled")
                    else -> Unit
                }
            }
            true
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    Messenger(binder).send(Message.obtain(null, OpenClService.DRAIN).apply {
                        replyTo = reply
                        data = Bundle().apply { putString("id", id) }
                    })
                } catch (e: RemoteException) {
                    wait.fail("send", e)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                wait.fail("service_lost")
            }

            override fun onBindingDied(name: ComponentName) {
                wait.fail("binding_died")
            }

            override fun onNullBinding(name: ComponentName) {
                wait.fail("null_binding")
            }
        }
        try {
            bound = context.bindService(
                Intent(context, OpenClService::class.java),
                connection!!,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) wait.fail("bind")
        } catch (e: SecurityException) {
            wait.fail("bind_security", e)
        } catch (e: Exception) {
            wait.fail("bind", e)
        }
    }

    return try {
        wait.await()
    } finally {
        // The fence owns only this connection; pre-existing idle leases from
        // the normal/fast arms are released by the drain after the ack.
        if (bound) runCatching { context.unbindService(connection!!) }
    }
}
