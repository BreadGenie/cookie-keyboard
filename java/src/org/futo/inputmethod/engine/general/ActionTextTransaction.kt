package org.futo.inputmethod.engine.general

/** Editor-write lifecycle, shared by dictation and composing action clients. */
internal class ActionTextTransaction(
    private val prepare: () -> Unit,
    private val writePartial: (String) -> Unit,
    private val writeFinal: (String) -> Unit,
    private val finish: () -> Unit
) {
    private var isFinished = false
    private var partialText: String? = null

    fun updatePartial(text: String) {
        if (isFinished) return
        prepare()
        if (isFinished) return
        partialText = text
        writePartial(text)
    }

    fun commit(text: String) {
        if (isFinished) return
        prepare()
        if (isFinished) return
        isFinished = true
        writeFinal(text)
        finish()
    }

    fun cancel() {
        if (isFinished) return
        val partial = partialText
        if (partial != null) commit(partial)
        else {
            // Committing "" would replace selected text even though this action
            // has never written anything. End the transaction without an edit.
            isFinished = true
            finish()
        }
    }

    fun invalidate() { isFinished = true }
}
