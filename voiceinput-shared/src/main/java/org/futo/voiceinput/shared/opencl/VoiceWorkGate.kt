package org.futo.voiceinput.shared.opencl

/** Admission spans capture, preload, inference and cancellation drain. */
object VoiceWorkGate {
    private var owner: Any? = null
    @Synchronized fun acquire(): Any? = if (owner == null) Any().also { owner = it } else null
    @Synchronized fun release(token: Any) { if (owner === token) owner = null }
}
