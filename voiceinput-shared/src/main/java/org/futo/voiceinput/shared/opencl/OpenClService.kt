package org.futo.voiceinput.shared.opencl

import android.app.Service
import android.content.Intent
import android.os.*
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Same UID, separate process: vendor assertions and signals cannot kill the IME process. */
class OpenClService : Service() {
    private val worker = WORKER
    private val requests = OpenClRequests()
    @Volatile private var engine: NativeOpenCl? = null
    @Volatile private var encoderFlashEngine: NativeOpenClEncoderFlash? = null
    // v24: content-free per-service-instance marker. PID plus a random
    // instance nonce: same PID + same nonce means the same service instance
    // measured both rows; a changed nonce at the same PID means the service
    // was recreated; a changed PID means a new process. Full model rehash
    // alone cannot prove a new PID, so phone analysis must check proc_pid.
    private val procNonce: Long =
        java.util.UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE
    // Verified-resident slots, one per engine. Serial worker only:
    // a slot binds a generation nonce to the exact snapshot bytes hashed at
    // cold load. Close/failure/cold-cancel/trim/lease-release invalidates;
    // service recreation resets instance state. Fast clients present tokens
    // only after observing successful verification.
    private var residentOff = ResidentModelSlot()
    private var residentEncFlash = ResidentModelSlot()
    // v18: Kotlin-side context liveness per engine (serial worker only).
    // Native prepare() both loads AND reports, so cold (which must
    // sealed-verify BEFORE any load) is tracked here; cleared on close.
    private var loadedOff = false
    private var loadedEncFlash = false
    private val replies = mutableMapOf<String, Messenger>() // main thread only
    private var current: Pair<String, Messenger>? = null // worker only
    private var currentEncoderFlash: Pair<String, Messenger>? = null // worker only
    private val handler: Handler = Handler(Looper.getMainLooper()) { message ->
        val id = message.data.getString("id")
        val reply = message.replyTo
        if (id != null && reply != null) when (message.what) {
            CAPABILITY -> {
                // v45: capture on the main thread. Looper.recycleUnchecked()
                // nulls Message.data/what immediately after this handler returns,
                // so the v44 read inside worker.execute always saw null and
                // answered query_failed -> UNKNOWN -> hidden controls.
                val request = OpenClCapabilityRequest.capture(id) {
                    message.data?.getInt("protocol", 0)
                }
                // Metadata-only availability probe. It runs on the same serial
                // worker as inference so driver/platform queries cannot race an
                // active ggml command queue. It creates no engine, model,
                // context, program, image, or audio transfer; it does not
                // reset/cancel requests or resident tokens, and it never closes
                // the warm context. A driver assertion can only kill this
                // isolated :experimental_opencl process.
                worker.execute {
                    val raw = OpenClCapabilityWorker.reply(request?.protocolAccepted == true) {
                        NativeOpenClCapability.query()
                    }
                    sendCapability(reply, request?.id ?: id, raw)
                }
            }
            DRAIN -> worker.execute {
                // v43 debug-only serial quiescence barrier. This is the v37
                // DRAIN route restored with one addition: the reply carries
                // the request ledger's idle state. Because it runs on the same
                // single-thread worker as RUN/PREPARE, it cannot reply until
                // every previously queued native task has returned. It reads
                // no model, prepares nothing and does not touch cancellation
                // or resident state; no production caller sends it.
                send(reply, id, RESULT, if (requests.idle()) "idle" else "busy")
            }
            CANCEL -> requests.cancel(id, reply.binder) {
                // Cancelling an idle engine only sets its atomic; harmless under the gate.
                engine?.cancel(); encoderFlashEngine?.cancel()
            }
            RUN, PREPARE, RUN_ENCODER_FLASH, PREPARE_ENCODER_FLASH -> {
                val prewarm = message.what == PREPARE || message.what == PREPARE_ENCODER_FLASH
                val encFlash = message.what == RUN_ENCODER_FLASH || message.what == PREPARE_ENCODER_FLASH
                val data = message.data
                data.classLoader = ParcelFileDescriptor::class.java.classLoader
                @Suppress("DEPRECATION") val model = data.getParcelable<ParcelFileDescriptor>("model")
                @Suppress("DEPRECATION") val audio = data.getParcelable<ParcelFileDescriptor>("audio")
                if (!requests.add(id, reply.binder)) {
                    model?.close(); audio?.close()
                    send(reply, id, ERROR)
                } else {
                    replies[id] = reply
                    val enqueued = SystemClock.elapsedRealtime()
                    worker.execute {
                        var loaded = false
                        val started = SystemClock.elapsedRealtime()
                        // v18: per-request engine helpers (serial worker only).
                        // ensureEngine mirrors the admission reset block so a
                        // stale-token reload can recreate after close.
                        fun ensureEngine() {
                            if (encFlash) {
                                if (encoderFlashEngine == null) encoderFlashEngine = NativeOpenClEncoderFlash(onLoaded = {}, onPartial = {
                                    currentEncoderFlash?.let { (requestId, receiver) ->
                                        if (!requests.cancelled(requestId)) send(receiver, requestId, PARTIAL, it)
                                    }
                                })
                                encoderFlashEngine!!.reset()
                            } else {
                                if (engine == null) engine = NativeOpenCl(onLoaded = {}, onPartial = {
                                    current?.let { (requestId, receiver) ->
                                        if (!requests.cancelled(requestId)) send(receiver, requestId, PARTIAL, it)
                                    }
                                })
                                engine!!.reset()
                            }
                        }
                        val slot = if (encFlash) residentEncFlash else residentOff
                        fun markLoaded(value: Boolean) {
                            if (encFlash) loadedEncFlash = value else loadedOff = value
                        }
                        fun isLoaded(): Boolean =
                            if (encFlash) loadedEncFlash else loadedOff
                        fun closeEngine() {
                            if (encFlash) { encoderFlashEngine?.close(); encoderFlashEngine = null }
                            else { engine?.close(); engine = null }
                            markLoaded(false)
                            slot.invalidate()
                        }
                        try {
                            val accepted = requests.start(id) { ensureEngine() }
                            if (!accepted) throw java.util.concurrent.CancellationException()
                            if (encFlash) currentEncoderFlash = id to reply else current = id to reply
                            require(model != null)
                            // Ordinary OpenCL defaults to full verification. Fast uses
                            // resident mode with a verified generation token.
                            val verifyMode = VerifyMode.parse(data.getString("verify_mode"))
                            val presentedToken = data.getLong("resident_token", 0L)
                            val hashStart = SystemClock.elapsedRealtime()
                            val cache = File(noBackupFilesDir, "experimental-opencl-kernels").apply { mkdirs() }
                            var verification = ResidentModelSlot.Verification.FULL_HASH
                            var bytesHashed = 0L
                            var residentHit = 0
                            // v24-final kernel-cache diagnostic: per-call binary-cache deltas from
                            // the engine that ran. Both start as unknown (NaN): prewarm
                            // performs no inference, so only the prepare-call delta is
                            // measured there; recognize rows sum the disjoint prepare+run
                            // spans. Cancelled or failed calls keep unknown; those rows are
                            // excluded from analysis.
                            var clPrepare = DoubleArray(9) { Double.NaN }
                            var clRun = DoubleArray(9) { Double.NaN }
                            // Split timestamp between verification work and
                            // the native prepare call, so hash_ms stays
                            // comparable with pre-v18 rows and load_ms keeps
                            // measuring prepare (warm: ~0; cold: real load).
                            var verifiedAt = hashStart
                            val reused: Boolean
                            if (verifyMode == VerifyMode.RESIDENT && slot.accepts(presentedToken)) {
                                // Verified resident: the modern loader copied
                                // every weight into backend-owned buffers at
                                // cold load and never re-reads the file, so
                                // ZERO model bytes are read here. prepare()
                                // still runs for liveness and the
                                // context_hit signal; a fresh load on this
                                // path would be UNVERIFIED bytes, so fail
                                // closed instead of silently loading.
                                verifiedAt = SystemClock.elapsedRealtime()
                                reused = if (encFlash) encoderFlashEngine!!.prepare(model.fd, cache.path)
                                    else engine!!.prepare(model.fd, cache.path)
                                if (!reused) { closeEngine(); throw IllegalStateException("Resident context lost") }
                                verification = ResidentModelSlot.Verification.RESIDENT_VERIFIED
                                residentHit = 1
                            } else if (verifyMode == VerifyMode.RESIDENT || !isLoaded()) {
                                // Cold load (either mode) or explicit
                                // stale-token reload: single-pass sealed
                                // snapshot so the hashed bytes ARE the loaded
                                // bytes (no hash/load TOCTOU on mutable
                                // files). Stale resident mode closes first so
                                // no unverified context can survive.
                                if (verifyMode == VerifyMode.RESIDENT) {
                                    verification = ResidentModelSlot.Verification.RESIDENT_REVERIFIED
                                    closeEngine()
                                    ensureEngine()
                                }
                                if (requests.cancelled(id)) throw java.util.concurrent.CancellationException()
                                val snapshot = File.createTempFile("opencl-model-snapshot-", ".bin", noBackupFilesDir)
                                var snapshotFd: ParcelFileDescriptor? = null
                                try {
                                    snapshotFd = ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_WRITE)
                                    val snapshotOut = ParcelFileDescriptor.AutoCloseOutputStream(snapshotFd)
                                    ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(model.fileDescriptor)).use { input ->
                                        // SealedModelSnapshot throws on
                                        // SHA mismatch, IO failure, or
                                        // cancel: partial snapshots never
                                        // anchor residency.
                                        bytesHashed = SealedModelSnapshot.copyAndVerify(
                                            input, snapshotOut, OpenClPolicy.MODEL_SHA,
                                            isCancelled = { requests.cancelled(id) },
                                        )
                                    }
                                    if (requests.cancelled(id)) throw java.util.concurrent.CancellationException()
                                    verifiedAt = SystemClock.elapsedRealtime()
                                    reused = if (encFlash) encoderFlashEngine!!.prepare(snapshotFd.fd, cache.path)
                                        else engine!!.prepare(snapshotFd.fd, cache.path)
                                    // A warm hit here would mean the close
                                    // above did not drop the context: fail
                                    // closed rather than trust it.
                                    if (reused) { closeEngine(); throw IllegalStateException("Sealed reload unexpectedly warm") }
                                    slot.noteColdVerified(OpenClPolicy.MODEL_SHA, bytesHashed)
                                    markLoaded(true)
                                } finally {
                                    runCatching { snapshotFd?.close() }
                                    runCatching { snapshot.delete() }
                                }
                            } else {
                                // FULL baseline on a warm context: today's
                                // exact behavior (hash every supplied byte,
                                // strict allowlist SHA, then warm prepare).
                                // FD identity/mtime alone never establishes
                                // that an import is unchanged.
                                val digest = MessageDigest.getInstance("SHA-256")
                                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(model.fileDescriptor)).use { input ->
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        if (requests.cancelled(id)) throw java.util.concurrent.CancellationException()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        digest.update(buffer, 0, n)
                                        bytesHashed += n
                                    }
                                }
                                require(digest.digest().joinToString("") { "%02x".format(it) } == OpenClPolicy.MODEL_SHA)
                                verifiedAt = SystemClock.elapsedRealtime()
                                reused = if (encFlash) encoderFlashEngine!!.prepare(model.fd, cache.path)
                                    else engine!!.prepare(model.fd, cache.path)
                                // A fresh load here would carry the old
                                // double-read TOCTOU: fail closed and force
                                // an explicit sealed reverify next time.
                                if (!reused) { closeEngine(); throw IllegalStateException("Warm baseline unexpectedly cold") }
                            }
                            // v24: prepare-call binary-cache delta from the engine that just
                            // prepared (cold context load included). NaN when the prepare
                            // call was cancelled/failed or on CPU builds: renders explicit
                            // unknown, never success-zero.
                            clPrepare = try {
                                if (encFlash) encoderFlashEngine!!.clCachePrepareStats()
                                else engine!!.clCachePrepareStats()
                            } catch (_: Exception) { DoubleArray(9) { Double.NaN } }
                            val hashed = verifiedAt
                            if (requests.cancelled(id)) throw java.util.concurrent.CancellationException()
                            val ready = SystemClock.elapsedRealtime()
                            loaded = true
                            if (requests.cancelled(id)) throw java.util.concurrent.CancellationException()
                            send(reply, id, LOADED)
                            var copyMs = 0L
                            var decodeMs = 0L
                            var nativeTiming = DoubleArray(23)
                            val result = run {
                                if (prewarm) "" else {
                                require(audio != null)
                                val samples = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(audio.fileDescriptor)).buffered()).use { input ->
                                    val count = input.readInt()
                                    require(count > 0 && count.toLong() * 4 + 4 == audio.statSize)
                                    FloatArray(count) { input.readFloat() }
                                }
                                val copied = SystemClock.elapsedRealtime()
                                copyMs = copied - ready
                                val text = if (encFlash) encoderFlashEngine!!.runGuardedBeam(model.fd, samples, data.getString("prompt", ""), data.getBoolean("suppress"), cache.path)
                                    else engine!!.run(model.fd, samples, data.getString("prompt", ""), data.getBoolean("suppress"), cache.path)
                                text.also {
                                    decodeMs = SystemClock.elapsedRealtime() - copied
                                    nativeTiming = if (encFlash) encoderFlashEngine!!.timings() else engine!!.timings()
                                    clRun = try {
                                        if (encFlash) encoderFlashEngine!!.clCacheStats() else engine!!.clCacheStats()
                                    } catch (_: Exception) { DoubleArray(9) { Double.NaN } }
                                }
                            } }
                            // Prepare and run spans are disjoint; failed spans remain unknown.
                            val clBlock = if (prewarm) ClCacheStats.render(clPrepare) else {
                                val total = ClCacheStats.sum(clPrepare, clRun)
                                if (total != null) ClCacheStats.format(total) else ClCacheStats.UNKNOWN_BLOCK
                            }
                            send(reply, id, METRICS, "phase=${if (prewarm) "prewarm" else "recognize"} context_hit=$reused queue_ms=${started-enqueued} engine_create_ms=${hashStart-started} hash_ms=${hashed-hashStart} load_ms=${ready-hashed} audio_read_ms=$copyMs inference_ms=$decodeMs ${OpenClMetrics.format(nativeTiming)} kernel_restart_cache=unknown${if (encFlash) " flash_requested=encoder-only flash_decoder=0 " + OpenClMetrics.guard(result) else ""} $clBlock verification=${verification.name.lowercase()} verification_ms=${hashed-hashStart} bytes_hashed=$bytesHashed resident_generation=${slot.token()} resident_hit=$residentHit resident_token=${slot.token()} proc_pid=${android.os.Process.myPid()} proc_nonce=$procNonce")
                            send(reply, id, if (requests.cancelled(id) || result.contains("<>CANCELLED<>")) CANCELLED else RESULT, result)
                        } catch (_: java.util.concurrent.CancellationException) {
                            // v18: a cancelled request invalidates the
                            // resident token (cold-cancel partial state must
                            // never anchor residency) while keeping today's
                            // context lifecycle: the owned context itself is
                            // untouched, so live warm behavior is unchanged.
                            slot.invalidate()
                            send(reply, id, CANCELLED)
                        } catch (_: Exception) {
                            closeEngine()
                            send(reply, id, if (requests.cancelled(id)) CANCELLED else if (loaded) ERROR else STARTUP_ERROR)
                        } catch (_: LinkageError) {
                            send(reply, id, STARTUP_ERROR)
                        } finally {
                            if (encFlash) currentEncoderFlash = null else current = null
                            model?.close(); audio?.close()
                            requests.finish(id)
                            handler.post { replies.remove(id) }
                        }
                    }
                }
            }
        }
        true
    }
    private fun send(reply: Messenger, id: String, what: Int, text: String = "") {
        try { reply.send(Message.obtain(null, what).apply { data = Bundle().apply { putString("id", id); putString("text", text) } }) }
        catch (_: RemoteException) { requests.cancel(id, reply.binder) { engine?.cancel(); encoderFlashEngine?.cancel() } }
    }
    private fun sendCapability(reply: Messenger, id: String, raw: String) {
        val bounded = if (raw.length <= OpenClCapabilityProtocol.MAX_PROBE_CHARS) raw
            else OpenClCapabilityProtocol.QUERY_FAILED_PROBE
        try {
            reply.send(Message.obtain(null, CAPABILITY_RESULT).apply {
                data = Bundle().apply { putString("id", id); putString("capability", bounded) }
            })
        } catch (_: RemoteException) {
            // The caller timed out or died; no inference state exists to cancel.
        }
    }
    override fun onBind(intent: Intent): IBinder = Messenger(handler).binder
    override fun onDestroy() {
        replies.forEach { (id, reply) -> requests.cancel(id, reply.binder) { engine?.cancel(); encoderFlashEngine?.cancel() } }
        // Never kill the process here: Android may already be binding a new service instance.
        // Each instance owns its own native state; closing waits behind its last worker task.
        // v18: instance death resets all resident generations (slots are
        // instance state, so old tokens go stale by construction).
        worker.execute { engine?.close(); engine = null; encoderFlashEngine?.close(); encoderFlashEngine = null; loadedOff = false; loadedEncFlash = false; residentOff.invalidate(); residentEncFlash.invalidate() }
        super.onDestroy()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) releaseContextWhenIdle()
    }
    override fun onLowMemory() { super.onLowMemory(); releaseContextWhenIdle() }
    private fun releaseContextWhenIdle() {
        // Release both idle product engines under memory pressure.
        // v18: closing drops every resident binding with it (stale tokens
        // must explicitly reload+verify; slots reset, never transferred).
        worker.execute { if (requests.idle()) { engine?.close(); engine = null; encoderFlashEngine?.close(); encoderFlashEngine = null; loadedOff = false; loadedEncFlash = false; residentOff.invalidate(); residentEncFlash.invalidate() } }
    }
    companion object {
        // Serial across service recreation too: an old driver call must drain before a new
        // instance can initialize GGML. This executor owns no model after service destruction.
        private val WORKER = Executors.newSingleThreadExecutor()
        const val RUN = 1; const val CANCEL = 2; const val LOADED = 3; const val PARTIAL = 4
        const val RESULT = 5; const val CANCELLED = 6; const val STARTUP_ERROR = 7; const val ERROR = 8
        const val PREPARE = 9; const val METRICS = 10
        /** v43 debug-only serial-worker quiescence barrier (v37 route restored). */
        const val DRAIN = 11
        /** v44 metadata-only capability query and its bounded reply. */
        const val CAPABILITY = 12; const val CAPABILITY_RESULT = 13
        const val RUN_ENCODER_FLASH = 15; const val PREPARE_ENCODER_FLASH = 16
    }
}
