package org.futo.voiceinput.shared.opencl

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.futo.voiceinput.shared.ggml.InferenceCancelledException
import kotlin.coroutines.coroutineContext

/**
 * Opt-in everyday Fast dictation (v21). Combines only measured wins, behind
 * two explicit user opt-ins (Experimental OpenCL GPU + Fast dictation):
 *
 * - Encoder-only flash-attention engine (ordinary decoder graphs with matching
 *   ordinary cross-KV layout; global decoder FA stays OFF, v12 rejected).
 * - Guarded beam 1: one fast attempt plus at most one beam-5 retry inside one
 *   service call (exact-zero PCM skips decode; VAD/no-speech signals stay
 *   diagnostic-only, never a hard gate or word filter; retry cache OFF).
 * - Verified resident generation: warm requests skip all model-byte reads
 *   only on a live resident token; stale/missing tokens trigger one explicit
 *   sealed reload+verify, never a metadata shortcut.
 *
 * No native change: the fast path rides the audited
 * [OpenClEncoderFlashClient.runGuardedBeamVerified] seam (guarded=true,
 * reuse=false, forced-retry=false). No streaming/endpointer change, no
 * recording auto-stop from any new gate, no benchmark UI.
 *
 * Partial-suppression by construction: [FastEngine] exposes NO partial
 * callback, so speculative fast-attempt text can never be inserted or
 * presented as definitive. Progress is still reported through onLoaded /
 * InferenceState updates; the final text is delivered exactly once from the
 * parsed guard result ([GuardPolicy.parse] strips the packed header, which
 * never reaches the editor, diagnostics, or public text).
 */
internal object FastDictation {
    /** Fast is available only under enabled Experimental OpenCL, English-only,
     * no language-specific models, and the allowlisted English-244 file. */
    fun eligible(
        experimentalOpenCl: Boolean,
        fastDictation: Boolean,
        languages: Set<String>,
        hasLanguageModels: Boolean,
        fileAvailable: Boolean,
    ): Boolean = fastDictation &&
        OpenClPolicy.eligible(experimentalOpenCl, languages, hasLanguageModels, fileAvailable)
}

/**
 * Shared fast-mode resident-token owner (v21). LIVES in [org.futo.voiceinput.shared.whisper.ModelManager]
 * (one instance per persistent keyboard state), NOT per voice window: v20
 * kept one session per MultiModelRunner, so every new dictation window
 * started at token 0 and forced a sealed full reload even while the
 * encoder-flash context was still warm under the 5-minute idle lease.
 *
 * The owner binds the token to (model-selection key, file path) AND the
 * client-side warm-lease epoch ([OpenClLeases.fastEpoch]). The token stays
 * live across successive dictation windows only while the key is unchanged
 * and the lease epoch is unmoved; selection change, disablement,
 * unsupported model/language, cancellation, engine failure, lease
 * expiry/release, trim, service death, Fast toggle off, or import change
 * retires it, and the next resident-mode request performs one explicit
 * sealed reload+verify. File mtime/size are never trusted, and the
 * selection key plus absolute path assert nothing about current file
 * contents (same-path reimport is a new key only for checksum-carrying
 * loaders; the cold-load SHA gate is the content identity).
 *
 * The numeric token alone is NOT a security boundary: a fresh service
 * instance restarts its generation at 0/1, so the client epoch (not the
 * number) decides whether a token may be presented at all, and the service
 * still rejects unknown generations inside the same single call (cold
 * fallback, never an unverified warm hit).
 *
 * Late/resurrected accepts are closed by scoped [Ticket]s: every request
 * captures (key, token, epoch, lease) at start, and [noteToken] applies a
 * token only when the ticket still matches the live owner state. A cancel,
 * invalidate, key switch, or lease bump that lands anywhere inside the
 * request (including between the service METRICS row and the runner return)
 * drops the late accept. All methods are synchronized so the check-and-set
 * is atomic.
 */
internal class OpenClFastSession(
    private val leaseEpoch: () -> Long = { OpenClLeases.fastEpoch },
) {
    /** Scoped update capability captured at request start. */
    internal data class Ticket(val key: String, val token: Long, val epoch: Long, val lease: Long)

    private var modelKey: String? = null
    private var residentToken: Long = 0L
    private var epoch: Long = 0L
    private var tokenLease: Long = -1L

    @Synchronized
    fun begin(key: String): Ticket {
        if (key != modelKey) {
            modelKey = key
            residentToken = 0L
            epoch++
        }
        val live = leaseEpoch()
        if (tokenLease != live) {
            // Warm lease died, expired, was released/trimmed, or Fast was
            // toggled off since this token was issued: force one cold
            // verify instead of presenting a token the service would reject
            // (or, across a same-numbered fresh generation, must not reuse).
            residentToken = 0L
            tokenLease = live
        }
        return Ticket(key, residentToken, epoch, live)
    }

    @Synchronized
    fun noteToken(ticket: Ticket, token: Long) {
        // Stale tickets (cancel/invalidate/key-switch/lease-bump raced past
        // request start) must never resurrect a token for an old generation.
        if (ticket.key != modelKey || ticket.epoch != epoch) return
        if (ticket.lease != leaseEpoch()) return
        if (token != 0L) {
            residentToken = token
            tokenLease = ticket.lease
        }
    }

    /** Drops the binding. Idempotent. Old tokens go stale. Bumps the epoch
     * so in-flight tickets can no longer accept. */
    @Synchronized
    fun invalidate() {
        residentToken = 0L
        epoch++
    }

    /** Successful final-result cleanup retains the verified lease. Real
     * cancellation still invalidates the token and all outstanding tickets. */
    fun finishWindow(completed: Boolean) {
        if (!completed) invalidate()
    }

    @Synchronized
    fun token(): Long {
        if (tokenLease != leaseEpoch()) return 0L
        return residentToken
    }
}

/**
 * Narrow fast-path engine seam. Deliberately partial-free: implementations
 * must NOT forward speculative attempt text anywhere.
 */
internal interface FastEngine {
    suspend fun prepareVerified(
        file: File,
        verifyMode: String,
        residentToken: Long,
        onMetrics: (ServiceMetrics) -> Unit,
    )

    suspend fun runGuardedBeamVerified(
        file: File,
        samples: FloatArray,
        prompt: String,
        suppress: Boolean,
        verifyMode: String,
        residentToken: Long,
        onLoaded: () -> Unit,
        onMetrics: (ServiceMetrics) -> Unit,
    ): GuardPolicy.GuardResult
}

/** Production engine: encoder-only flash entry points only, partials dropped. */
internal class AndroidFastEngine(private val context: Context) : FastEngine {
    private fun client() = OpenClEncoderFlashClient(context.applicationContext)

    override suspend fun prepareVerified(
        file: File,
        verifyMode: String,
        residentToken: Long,
        onMetrics: (ServiceMetrics) -> Unit,
    ) = client().prepareVerified(file, verifyMode, residentToken, onMetrics)

    override suspend fun runGuardedBeamVerified(
        file: File,
        samples: FloatArray,
        prompt: String,
        suppress: Boolean,
        verifyMode: String,
        residentToken: Long,
        onLoaded: () -> Unit,
        onMetrics: (ServiceMetrics) -> Unit,
    ): GuardPolicy.GuardResult = client().runGuardedBeamVerified(
        file, samples, prompt, suppress, verifyMode, residentToken,
        onLoaded, {}, onMetrics,
    )
}

internal data class FastTelemetry(
    val guard: String,
    val retry: Int,
    val verification: String,
    val verificationMs: Long,
    val bytesHashed: Long,
    val residentHit: Int,
    val residentGeneration: Long,
    val residentToken: Long,
    val fastMs: Double,
    val retryMs: Double,
    val totalMs: Double,
)

/**
 * Everyday fast-path adapter: single guarded beam-1 attempt with at most one
 * beam-5 retry, resident verification, encoder reuse OFF, forced retry OFF.
 * Returns ONLY the parsed final text (trimmed); the packed header, fast text,
 * and guard signals never leave this adapter except as content-free
 * telemetry ([FastTelemetry]).
 */
internal class FastDictationRunner(
    private val engine: FastEngine,
    private val session: OpenClFastSession = OpenClFastSession(),
) {
    suspend fun prepare(file: File, key: String) {
        // Scoped ticket: a cancel/selection-change/lease-bump that lands
        // anywhere inside this call drops the late token-accept, so a new
        // request can never inherit a token for a retired generation.
        val ticket = session.begin(key)
        try {
            engine.prepareVerified(file, VerifyMode.RESIDENT, ticket.token) { metrics ->
                session.noteToken(ticket, metrics.residentToken)
            }
            // A coroutine cancelled while the service call was in flight must
            // not leave this prewarm looking live: retire before returning.
            coroutineContext.ensureActive()
        } catch (e: CancellationException) {
            // Coroutine cancel (Stop/hide/switch/lock racing prewarm) or the
            // service-side cancel signal: never leave a token that could skip
            // verification later. Always rethrown.
            session.invalidate()
            throw e
        } catch (e: Exception) {
            // A failed prepare (wrong model, cancelled, dead service) must
            // never leave a token that could skip verification later.
            session.invalidate()
            throw e
        }
    }

    suspend fun run(
        file: File,
        key: String,
        samples: FloatArray,
        prompt: String,
        suppress: Boolean,
        onLoaded: () -> Unit,
    ): Pair<String, FastTelemetry> {
        val ticket = session.begin(key)
        try {
            var metrics: ServiceMetrics? = null
            val guard = engine.runGuardedBeamVerified(
                file, samples, prompt, suppress, VerifyMode.RESIDENT, ticket.token,
                onLoaded, { parsed -> metrics = parsed; session.noteToken(ticket, parsed.residentToken) },
            )
            // Boundary guard: a result produced under a now-cancelled job
            // must never be delivered for commit. (OpenClRouting re-checks at
            // its own boundary; AudioRecognizer yields before committing.)
            coroutineContext.ensureActive()
            val m = metrics
            return guard.finalText.trim() to FastTelemetry(
                guard = guard.rationale.name,
                retry = guard.retry,
                verification = m?.verification ?: "missing",
                verificationMs = m?.verificationMs ?: -1L,
                bytesHashed = m?.bytesHashed ?: -1L,
                residentHit = m?.residentHit ?: -1,
                residentGeneration = m?.residentGeneration ?: -1L,
                residentToken = m?.residentToken ?: -1L,
                fastMs = guard.fastMs,
                retryMs = guard.retryMs,
                totalMs = guard.totalMs,
            )
        } catch (e: CancellationException) {
            session.invalidate()
            throw e
        } catch (e: InferenceCancelledException) {
            session.invalidate()
            throw e
        } catch (e: Exception) {
            // Wrong model, language bail surfacing as failure, dead driver:
            // drop the token so the next request explicitly reverifies.
            session.invalidate()
            throw e
        }
    }

    fun invalidate() = session.invalidate()
}
