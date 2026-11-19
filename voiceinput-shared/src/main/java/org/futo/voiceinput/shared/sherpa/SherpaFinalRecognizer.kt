package org.futo.voiceinput.shared.sherpa

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.futo.voiceinput.shared.StreamingFinalRecognizer
import org.futo.voiceinput.shared.ggml.InvalidModelException
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Provisioning supplies an immutable, verified private model directory (ticket 07). */
class SherpaFinalRecognizer(private val context: Context, private val modelDirectory: File,
                            private val kind: String = "parakeet",
                            private val glossary: () -> List<String> = { emptyList() }) : StreamingFinalRecognizer {
    companion object { private val generations = AtomicLong(System.nanoTime()) }
    @Volatile private var work: SherpaWork? = null
    private val cancellations = AtomicLong()
    @Volatile private var queue = SherpaFeedQueue()
    private var feeder = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val hotwords = SherpaHotwordSnapshot(glossary)
    private var feeding: kotlinx.coroutines.Deferred<Unit>? = null
    @Synchronized override fun beginRecording() {
        check(work == null || work!!.terminal())
        queue.cancel(); feeder.cancel()
        queue = SherpaFeedQueue()
        feeder = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        feeding = null
        hotwords.begin()
    }
    override fun feed(samples: FloatArray) = queue.offer(samples)
    override suspend fun finish(callback: ModelInferenceCallback): String {
        queue.close()
        try { feeding?.await(); return withContext(Dispatchers.IO) { checkNotNull(work).finish() } }
        catch (_: Exception) { cancel(); throw InvalidModelException() }
        finally { feeder.cancel() }
    }
    override suspend fun preload() {
        val epoch = cancellations.get()
        withContext(Dispatchers.IO) {
            if(Build.VERSION.SDK_INT < 27) throw InvalidModelException()
            var loading: SherpaWork? = null
            try {
                SherpaModels.files(kind, modelDirectory)
                val generation = generations.incrementAndGet()
                val next = SherpaWork(generation, kind, modelDirectory.path,
                    { SherpaWorkers.acquire(context, generation) },
                    { failed -> SherpaWorkers.release(generation, failed) }, hotwords.value())
                loading = next
                synchronized(this@SherpaFinalRecognizer) {
                    check(cancellations.get() == epoch && (work == null || work!!.terminal()))
                    work = next
                }
                next.load()
                val recordingQueue = queue
                feeding = feeder.async {
                    try { recordingQueue.consume { next.feed(it) } }
                    catch (e: Throwable) { next.cancel(); throw e }
                }
            } catch(_: Throwable) {
                loading?.cancel()
                throw InvalidModelException()
            }
        }
    }
    override suspend fun recognize(samples: FloatArray, callback: ModelInferenceCallback): String {
        try {
            var offset = 0
            while(offset < samples.size) {
                val end = minOf(offset + SherpaSession.CHUNK, samples.size)
                val chunk = samples.copyOfRange(offset, end)
                try { withContext(Dispatchers.IO) { checkNotNull(work).feed(chunk) } }
                finally { chunk.fill(0f) }
                offset = end
            }
            return finish(callback)
        } catch (_: Exception) { cancel(); throw InvalidModelException() }
    }
    override fun cancel(preserveFastResidency: Boolean) {
        synchronized(this) { cancellations.incrementAndGet(); queue.cancel(); feeder.cancel(); work?.cancel() }
    }
}

/** Testable recording owner. Transport replies have no authority after invalidation. */
internal class SherpaWork(private val generation: Long, private val kind: String, private val path: String,
                          private val acquire: () -> SherpaTransport,
                          private val release: (Boolean) -> Unit,
                          hotwords: String = "") {
    private val hotwords = SherpaHotwords.validate(hotwords)
    private val session = SherpaSession(generation)
    @Volatile private var invalid = false
    @Volatile private var done = false
    @Volatile private var transport: SherpaTransport? = null
    private var sequence = 0L // single caller; session rejects duplicate concurrent Stop
    fun terminal() = invalid || done
    private fun call(op: Int, samples: FloatArray? = null): String? {
        check(!invalid)
        session.begin(op, sequence, samples?.size ?: 0)
        val text = checkNotNull(transport).request(op, generation, sequence, kind, path, samples, if(op == SherpaSession.LOAD) hotwords else "")
        check(!invalid)
        return session.accept(generation, sequence++, text)
    }
    fun load() {
        try {
            check(!invalid)
            val remote = acquire(); transport = remote
            if(invalid) { remote.cancel(); release(true); throw SherpaFailure() }
            // connect() returns only after linkToDeath and lease acquisition; the first
            // request (LOAD) below is the first point native worker state can be created.
            remote.connect()
            check(!invalid)
            call(SherpaSession.LOAD)
        } catch(_: Throwable) { cancel(); throw SherpaFailure() }
    }
    fun decode(samples: FloatArray): String {
        try {
            check(samples.size <= SherpaSession.MAX_SAMPLES && samples.all { it.isFinite() && it in -1f..1f })
            var offset = 0
            while(offset < samples.size) {
                val end = minOf(offset + SherpaSession.CHUNK, samples.size)
                val chunk = samples.copyOfRange(offset, end)
                try { call(SherpaSession.FEED, chunk) } finally { chunk.fill(0f) }
                offset = end
            }
            return finish()
        } catch(_: Throwable) { cancel(); throw SherpaFailure() }
    }
    fun feed(samples: FloatArray) {
        try { call(SherpaSession.FEED, samples) }
        catch (_: Throwable) { cancel(); throw SherpaFailure() }
    }
    fun finish(): String {
        try {
            val text = checkNotNull(call(SherpaSession.STOP))
            synchronized(this) { check(!invalid); done = true; release(false) }
            return text
        } catch(_: Throwable) { cancel(); throw SherpaFailure() }
    }
    @Synchronized fun cancel() {
        if(done || invalid) return
        invalid = true; session.invalidate()
        // release also handles a cancellation that races acquisition/connect.
        release(true); transport?.cancel()
    }
}
