package org.futo.voiceinput.shared.sherpa

/** No Android or JNI dependencies: shared protocol invariants and cancellation boundary. */
internal class SherpaSession(val generation: Long) {
    enum class State { NEW, LOADING, READY, FEEDING, DECODING, DONE, INVALID }
    var state = State.NEW; private set
    private var sequence = 0L
    private var pending = -1L
    private var count = 0
    @Synchronized fun begin(op: Int, seq: Long, samples: Int = 0) {
        check(seq == sequence && pending == -1L)
        check(when (op) { LOAD -> state == State.NEW; FEED, STOP -> state == State.READY; else -> false })
        if(op == FEED) { check(samples in 1..CHUNK && count <= MAX_SAMPLES - samples); count += samples }
        pending = seq
        state = when(op) { LOAD -> State.LOADING; FEED -> State.FEEDING; else -> State.DECODING }
    }
    @Synchronized fun accept(g: Long, seq: Long, text: String?): String? {
        check(state != State.INVALID && g == generation && seq == pending && seq == sequence)
        check(state in listOf(State.LOADING, State.FEEDING, State.DECODING))
        val final = state == State.DECODING
        check(if(final) text != null && text.length <= MAX_TEXT else text == null)
        sequence++; pending = -1
        state = if(final) State.DONE else State.READY
        return if(final) text else null
    }
    @Synchronized fun invalidate() { state = State.INVALID; pending = -1 }
    companion object {
        const val TOKEN = "cookie.sherpa.v1"
        const val LOAD = 1; const val FEED = 2; const val STOP = 3; const val CANCEL = 4
        const val CHUNK = 16000
        // Resource failure, never a successful truncation or automatic stop.
        const val MAX_SAMPLES = 16000 * 60 * 20
        const val MAX_TEXT = 65536
    }
}

/** A process lease is released only by its registered Binder DeathRecipient. */
internal class SherpaLease {
    private var owner: Long? = null
    @Synchronized fun acquire(g: Long): Boolean { if(owner != null) return false; owner = g; return true }
    @Synchronized fun died(g: Long) { check(owner == g); owner = null }
    @Synchronized fun notStarted(g: Long) { check(owner == g); owner = null }
}
