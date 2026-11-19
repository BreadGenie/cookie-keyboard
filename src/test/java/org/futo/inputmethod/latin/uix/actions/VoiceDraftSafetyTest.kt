package org.futo.inputmethod.latin.uix.actions

import org.futo.inputmethod.engine.general.ActionTextTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceDraftSafetyTest {
    private class Editor(sanitize: (String) -> String = { it }) {
        var text = "Keep selected draft."
        private var selectionStart = 5
        private var selectionEnd = 13
        var writes = 0
        var finishes = 0
        val events = mutableListOf<String>()
        val queue = ArrayDeque<() -> Unit>()
        private fun replaceSelection(value: String) {
            text = text.take(selectionStart) + value + text.drop(selectionEnd)
            selectionEnd = selectionStart + value.length
            writes++
        }
        val transaction = ActionTextTransaction(
            prepare = {}, writePartial = { replaceSelection(it) },
            writeFinal = { replaceSelection(it) }, finish = { finishes++ }
        )
        val voice = VoiceResultDelivery(
            dispatch = { queue.addLast(it) }, sanitize = sanitize,
            commit = { transaction.commit(it) }, cancelInput = { transaction.cancel() },
            completed = { if (it.isNotBlank()) events.add("announce:$it"); events.add("close") }
        )
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
    }

    @Test fun recognitionPartialsLeaveDraftUntouchedUntilFinalResult() {
        val editor = Editor()
        editor.voice.partialResult("unfinished")
        editor.voice.partialResult("unfinished sentence")
        editor.drain()
        assertEquals("Keep selected draft.", editor.text)
        assertEquals(0, editor.writes)
        editor.voice.deliver("replacement")
        editor.drain()
        assertEquals("Keep replacement draft.", editor.text)
        assertEquals(1, editor.writes)
    }

    @Test fun cancellationPreservesSelectedDraftAndRejectsLateText() {
        val editor = Editor()
        editor.transaction.cancel()
        editor.transaction.commit("late")
        editor.transaction.cancel()
        assertEquals("Keep selected draft.", editor.text)
        assertEquals(0, editor.writes)
        assertEquals(1, editor.finishes)
    }

    @Test fun emptyRecognitionPreservesSelectionAndClosesWithoutAnnouncement() {
        val editor = Editor()
        editor.voice.deliver("")
        editor.drain()
        assertEquals("Keep selected draft.", editor.text)
        assertEquals(0, editor.writes)
        assertEquals(listOf("close"), editor.events)
        assertTrue(editor.voice.isCompleted)
    }

    @Test fun whitespaceRecognitionPreservesSelection() {
        val editor = Editor()
        editor.voice.deliver(" \n\t ")
        editor.drain()
        assertEquals("Keep selected draft.", editor.text)
        assertEquals(0, editor.writes)
        assertEquals(listOf("close"), editor.events)
    }

    @Test fun sanitizerCanStripResultToEmptyWithoutDeletingSelection() {
        val editor = Editor(sanitize = { "" })
        editor.voice.deliver(".")
        editor.drain()
        assertEquals("Keep selected draft.", editor.text)
        assertEquals(0, editor.writes)
        assertEquals(listOf("close"), editor.events)
    }

    @Test fun cancellationWhileFinalResultIsQueuedSuppressesAllDeliveryEffects() {
        val editor = Editor()
        editor.voice.deliver("replacement")
        editor.voice.invalidate()
        editor.transaction.cancel()
        editor.drain()
        assertEquals("Keep selected draft.", editor.text)
        assertTrue(editor.events.isEmpty())
        assertFalse(editor.voice.isCompleted)
    }

    @Test fun callbacksAfterCloseCannotReviveWindow() {
        val editor = Editor()
        editor.voice.invalidate()
        repeat(2) { editor.voice.deliver("late") }
        editor.drain()
        assertEquals("Keep selected draft.", editor.text)
        assertTrue(editor.events.isEmpty())
    }

    @Test fun duplicateResultsReplaceSelectionAnnounceAndCloseExactlyOnce() {
        val editor = Editor()
        repeat(3) { editor.voice.deliver("replacement") }
        assertFalse(editor.voice.isCompleted)
        editor.drain()
        assertEquals("Keep replacement draft.", editor.text)
        assertEquals(1, editor.writes)
        assertEquals(listOf("announce:replacement", "close"), editor.events)
        assertTrue(editor.voice.isCompleted)
        editor.voice.invalidate()
        assertTrue(editor.voice.isCompleted)
    }

    @Test fun composingClientsKeepTheirLastPartialOnCancel() {
        val editor = Editor()
        editor.transaction.updatePartial("partial")
        editor.transaction.cancel()
        assertEquals("Keep partial draft.", editor.text)
        assertEquals(2, editor.writes)
        assertEquals(1, editor.finishes)
    }

    @Test fun emptyComposingUpdateStillGetsFinalized() {
        val editor = Editor()
        editor.transaction.updatePartial("")
        editor.transaction.cancel()
        assertEquals(2, editor.writes)
        assertEquals(1, editor.finishes)
    }

    @Test fun fieldChangeInvalidationRejectsPartialFinalAndCancelWrites() {
        val editor = Editor()
        editor.transaction.invalidate()
        editor.transaction.updatePartial("stale")
        editor.transaction.commit("stale")
        editor.transaction.cancel()
        assertEquals("Keep selected draft.", editor.text)
        assertEquals(0, editor.writes)
        assertEquals(0, editor.finishes)
    }

    @Test fun cursorRefreshCanInvalidateBeforeEditorWrite() {
        var writes = 0
        lateinit var transaction: ActionTextTransaction
        transaction = ActionTextTransaction(
            prepare = { transaction.invalidate() }, writePartial = { writes++ },
            writeFinal = { writes++ }, finish = {}
        )
        transaction.commit("stale")
        assertEquals(0, writes)
    }

    @Test fun closingCanJoinWorkerInvalidationWithoutHoldingDeliveryMonitor() {
        val workerFinished = CountDownLatch(1)
        lateinit var voice: VoiceResultDelivery
        voice = VoiceResultDelivery(
            dispatch = { it() }, sanitize = { it }, commit = {}, cancelInput = {},
            completed = {
                Thread { voice.invalidate(); workerFinished.countDown() }
                    .apply { isDaemon = true }.start()
                assertTrue(workerFinished.await(2, TimeUnit.SECONDS))
            }
        )
        voice.deliver("result")
    }
}
