package org.futo.voiceinput.shared.opencl

/** Keep selection and delivery rules independent of Android and native loading. */
internal object OpenClPolicy {
    const val MODEL_SHA = "58fbe949992dafed917590d58bc12ca577b08b9957f0b3e0d7ee71b64bed3aa8"
    fun eligible(enabled: Boolean, languages: Set<String>, hasLanguageModels: Boolean, fileAvailable: Boolean) =
        enabled && languages == setOf("en") && !hasLanguageModels && fileAvailable

    /**
     * v44 runtime gate. The persisted user preference is never rewritten: a
     * previously enabled preference still requires a live SUPPORTED capability
     * result, and UNKNOWN/UNAVAILABLE/UNSUPPORTED always routes to stock CPU.
     */
    fun runtimeEligible(
        enabled: Boolean,
        capability: OpenClCapabilities?,
        languages: Set<String>,
        hasLanguageModels: Boolean,
        fileAvailable: Boolean,
    ): Boolean = enabled && capability?.supported == true &&
        eligible(enabled, languages, hasLanguageModels, fileAvailable)
}

/** A request has one terminal outcome. Cancelling also seals it against late IPC replies. */
internal class OpenClDelivery {
    private var terminal = false
    @Synchronized fun finish(): Boolean {
        if (terminal) return false
        terminal = true
        return true
    }
    @Synchronized fun isActive() = !terminal
}

internal class OpenClStartupException : Exception()
internal class OpenClRecognitionException : Exception()

/** Only a managed startup exception authorizes a single stock attempt. */
internal object OpenClRouting {
    suspend fun run(experimental: suspend () -> String, stock: suspend () -> String,
                    unavailable: suspend () -> Unit): String {
        try {
            val result = experimental()
            kotlinx.coroutines.currentCoroutineContext().ensureActiveForOpenCl()
            return result
        } catch (_: OpenClStartupException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActiveForOpenCl()
            unavailable()
            kotlinx.coroutines.currentCoroutineContext().ensureActiveForOpenCl()
            return stock()
        }
    }
}

private fun kotlin.coroutines.CoroutineContext.ensureActiveForOpenCl() {
    val job = this[kotlinx.coroutines.Job]
    if (job != null && !job.isActive) throw kotlinx.coroutines.CancellationException("Recognition cancelled")
}

/** Serial worker admission and cancellation share this lock; reset cannot erase a new cancel. */
internal class OpenClRequests {
    private data class Request(val owner: Any, var cancelled: Boolean = false)
    private val pending = mutableMapOf<String, Request>()
    private var active: String? = null
    @Synchronized fun add(id: String, owner: Any): Boolean {
        if (pending.containsKey(id)) return false
        pending[id] = Request(owner)
        return true
    }
    @Synchronized fun start(id: String, reset: () -> Unit): Boolean {
        val request = pending[id] ?: return false
        check(active == null)
        if (request.cancelled) return false
        active = id
        reset()
        return true
    }
    @Synchronized fun cancel(id: String, owner: Any, abort: () -> Unit) {
        val request = pending[id] ?: return
        if (request.owner != owner) return
        request.cancelled = true
        if (active == id) abort()
    }
    @Synchronized fun idle() = pending.isEmpty() && active == null
    @Synchronized fun cancelled(id: String) = pending[id]?.cancelled != false
    @Synchronized fun finish(id: String) {
        pending.remove(id)
        if (active == id) active = null
    }
}
