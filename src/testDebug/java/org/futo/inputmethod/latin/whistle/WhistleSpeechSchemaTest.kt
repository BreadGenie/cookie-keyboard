package org.futo.inputmethod.latin.whistle

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Unmodified cached official CLI suppresses empty feed responses; these are its emitted
 * committed pieces and final tail, not a full Android IPC trace or quality acceptance. */
class WhistleSpeechSchemaTest {
    @Test fun actualOfficialSpeechPiecesAndFinishTailAccumulateExactly() {
        val lines=javaClass.getResourceAsStream("/whistle/jfk-runtime4-speech.jsonl")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(7,lines.size)
        val s=WhistleSession(19);s.begin(1,0);s.accept(19,0,null)
        lines.forEachIndexed { i,line ->
            val final=i==lines.lastIndex;val seq=i.toLong()+1
            s.begin(if(final) 3 else 2,seq)
            val result=s.accept(19,seq,line)
            if(final) assertEquals("And so my fellow Americans ask not what your country can do for you, ask what you can do for your country.",result)
            else assertNull(result)
            val words=Json.parseToJsonElement(line).jsonObject["words"]!!.jsonArray
            assertTrue(words.isNotEmpty())
        }
        assertEquals(WhistleSession.State.DONE,s.state)
        try { s.begin(3,8);fail() } catch(_: IllegalStateException) {}
    }
}
