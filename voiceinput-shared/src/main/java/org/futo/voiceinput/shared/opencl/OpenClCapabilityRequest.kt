package org.futo.voiceinput.shared.opencl

/**
 * v45: immutable snapshot of one CAPABILITY request.
 *
 * Android's `Looper.loop()` calls `Message.recycleUnchecked()` immediately
 * after `Handler.dispatchMessage()` returns. That method sets `data = null`,
 * `what = 0` and `replyTo = null` before returning the Message to the pool.
 * Reading `Message.data` inside the service worker therefore observes a null
 * Bundle, so a valid request is rejected as a protocol failure. [capture]
 * performs the read eagerly on the main thread and copies only the values the
 * worker needs.
 */
internal class OpenClCapabilityRequest private constructor(
    val id: String,
    val protocol: Int,
) {
    val protocolAccepted: Boolean get() = protocol == OpenClCapabilityProtocol.VERSION

    companion object {
        /**
         * Evaluates [protocol] exactly once, here, before any worker dispatch.
         * A later Message recycle (or reuse from the Message pool) cannot
         * change the captured request.
         */
        fun capture(id: String?, protocol: () -> Int?): OpenClCapabilityRequest? =
            id?.let { OpenClCapabilityRequest(it, protocol() ?: 0) }
    }
}

/** Pure mapping from a captured request to the bounded native reply. */
internal object OpenClCapabilityWorker {
    fun reply(protocolAccepted: Boolean, query: () -> String): String =
        if (!protocolAccepted) {
            OpenClCapabilityProtocol.QUERY_FAILED_PROBE
        } else try {
            query()
        } catch (_: LinkageError) {
            // Optional library not packaged in this build, or an ABI mismatch.
            OpenClCapabilityProtocol.NOT_PACKAGED_PROBE
        } catch (_: Throwable) {
            OpenClCapabilityProtocol.QUERY_FAILED_PROBE
        }
}
