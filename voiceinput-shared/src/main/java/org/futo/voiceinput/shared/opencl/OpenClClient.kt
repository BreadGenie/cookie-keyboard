package org.futo.voiceinput.shared.opencl

import android.content.*
import android.os.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.futo.voiceinput.shared.ggml.InferenceCancelledException
import java.io.DataOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class OpenClClient(private val context: Context) {
    suspend fun prepare(model: File) = request(model, null, "", false, {}, {})
    suspend fun run(model: File, samples: FloatArray, prompt: String, suppress: Boolean,
                    onLoaded: () -> Unit, onPartial: (String) -> Unit): String =
        request(model, samples, prompt, suppress, onLoaded, onPartial)
    private suspend fun request(model: File, samples: FloatArray?, prompt: String, suppress: Boolean,
                    onLoaded: () -> Unit, onPartial: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val transferStarted = SystemClock.elapsedRealtime()
        // An unlinked private file avoids Binder's transaction limit and abandoned audio.
        val audio = if (samples == null) null else try {
            val file = File.createTempFile("opencl-audio-", ".tmp", context.cacheDir)
            try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE) }
            finally { file.delete() }
        } catch (_: java.io.IOException) { throw OpenClStartupException() }
        try {
            if (audio != null && samples != null) try {
            DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(audio.fileDescriptor)).buffered()).use { output ->
                output.writeInt(samples.size)
                samples.forEach { output.writeFloat(it) }
            }
            android.system.Os.lseek(audio.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
            } catch (_: java.io.IOException) { throw OpenClStartupException() }
              catch (_: android.system.ErrnoException) { throw OpenClStartupException() }
            val descriptor = try { ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY) }
                catch (_: java.io.IOException) { throw OpenClStartupException() }
            val modelFd = descriptor
            try {
                return@withContext withContext(Dispatchers.Main) {
                    val delivery = OpenClDelivery()
                    val id = java.util.UUID.randomUUID().toString()
                    val leaseGeneration = OpenClLeases.generation
                    val requestStarted = SystemClock.elapsedRealtime()
                    var remote: Messenger? = null
                    var bound = false
                    var connection: ServiceConnection? = null
                    var reply: Messenger? = null
                    try {
                        suspendCancellableCoroutine { continuation ->
                            fun failure(startup: Boolean) {
                                if (delivery.finish()) continuation.resumeWithException(
                                    if (startup) OpenClStartupException() else OpenClRecognitionException())
                            }
                            reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                                if (message.data.getString("id") == id && delivery.isActive() && continuation.isActive) {
                                    when (message.what) {
                                        OpenClService.METRICS -> OpenClDiagnostics.record(context, "backend=opencl " + message.data.getString("text", ""))
                                        OpenClService.LOADED -> onLoaded()
                                        OpenClService.PARTIAL -> onPartial(message.data.getString("text", ""))
                                        OpenClService.RESULT -> if (delivery.finish()) {
                                            OpenClDiagnostics.record(context, "backend=opencl phase=${if (samples == null) "prewarm" else "request"} client_ms=${SystemClock.elapsedRealtime()-requestStarted} audio_prepare_ms=${requestStarted-transferStarted}")
                                            continuation.resume(message.data.getString("text", ""))
                                        }
                                        OpenClService.CANCELLED -> if (delivery.finish()) continuation.resumeWithException(InferenceCancelledException())
                                        OpenClService.STARTUP_ERROR -> failure(true)
                                        OpenClService.ERROR -> failure(false)
                                    }
                                }
                                true
                            })
                            connection = object : ServiceConnection {
                                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                                    if (!delivery.isActive() || !continuation.isActive) return
                                    remote = Messenger(binder)
                                    try {
                                        remote!!.send(Message.obtain(null, if (samples == null) OpenClService.PREPARE else OpenClService.RUN).apply {
                                            replyTo = reply
                                            data = Bundle().apply {
                                                putString("id", id)
                                                putParcelable("model", modelFd); putParcelable("audio", audio)
                                                putString("prompt", prompt); putBoolean("suppress", suppress)
                                            }
                                        })
                                    } catch (_: RemoteException) { failure(false) }
                                }
                                override fun onServiceDisconnected(name: ComponentName) = failure(false)
                                override fun onBindingDied(name: ComponentName) = failure(false)
                                override fun onNullBinding(name: ComponentName) = failure(true)
                            }
                            continuation.invokeOnCancellation {
                                delivery.finish()
                                try { remote?.send(Message.obtain(null, OpenClService.CANCEL).apply {
                                    replyTo = reply; data = Bundle().apply { putString("id", id) }
                                }) } catch (_: RemoteException) { }
                            }
                            try {
                                bound = context.bindService(Intent(context, OpenClService::class.java), connection!!, Context.BIND_AUTO_CREATE)
                                if (!bound) failure(true)
                            } catch (_: SecurityException) { failure(true) }
                        }
                    } finally {
                        delivery.finish()
                        if (bound) OpenClLeases.retain(context, connection!!, leaseGeneration)
                    }
                }
            } finally { runCatching { descriptor.close() } }
        } finally { runCatching { audio?.close() } }
    }
}
