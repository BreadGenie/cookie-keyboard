package org.futo.inputmethod.latin.uix.actions

/**
 * Final-only voice delivery. Dispatch and host close must run on the UI thread.
 * Worker cancellation competes with queued delivery for one terminal claim.
 */
internal class VoiceResultDelivery(
    private val dispatch: (() -> Unit) -> Unit,
    private val sanitize: (String) -> String,
    private val commit: (String) -> Unit,
    private val cancelInput: () -> Unit,
    private val completed: (String) -> Unit
) {
    private enum class State { Pending, Cancelled, Completed }
    private var state = State.Pending

    val isCompleted: Boolean
        @Synchronized get() = state == State.Completed

    @Synchronized
    fun invalidate() {
        if (state == State.Pending) state = State.Cancelled
    }

    fun deliver(result: String) {
        dispatch {
            val claimed = synchronized(this) {
                if (state == State.Pending) {
                    state = State.Completed
                    true
                } else false
            }
            // Never hold the monitor across host effects: close can join a worker
            // that is itself invalidating this delivery.
            if (claimed) {
                val text = if (result.isBlank()) "" else sanitize(result)
                if (text.isBlank()) cancelInput() else commit(text)
                completed(if (text.isBlank()) "" else result)
            }
        }
    }

    // Internal recognition may stream; editor insertion waits for final delivery.
    @Suppress("UNUSED_PARAMETER")
    fun partialResult(result: String) = Unit
}
