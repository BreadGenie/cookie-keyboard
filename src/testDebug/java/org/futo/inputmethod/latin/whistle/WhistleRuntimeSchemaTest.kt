package org.futo.inputmethod.latin.whistle

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WhistleRuntimeSchemaTest {
    private val fixture = javaClass.getResourceAsStream("/whistle/silence-runtime4-feed-finish.jsonl")!!.bufferedReader().readLines()
    private fun ready() = WhistleSession(7).apply { begin(1, 0); accept(7, 0, null) }
    private fun changed(key: String, value: JsonElement?): String {
        val o = Json.parseToJsonElement(fixture[0]).jsonObject.toMutableMap()
        if(value == null) o.remove(key) else o[key] = value
        return JsonObject(o).toString()
    }
    private fun rejects(raw: String, final: Boolean = false): WhistleSchemaException {
        val s = ready(); s.begin(if(final) 3 else 2, 1)
        try { s.accept(7, 1, raw); fail("Accepted malformed response") } catch(e: WhistleSchemaException) {
            assertEquals(WhistleSession.State.INVALID, s.state); return e
        }
        error("unreachable")
    }
    @Test fun actualRuntimeSilenceFeedAndFinish() {
        assertEquals(2, fixture.size)
        val s = ready(); s.begin(2, 1); assertNull(s.accept(7, 1, fixture[0])); assertEquals(WhistleSession.State.READY, s.state)
        s.begin(3, 2); assertEquals("", s.accept(7, 2, fixture[1])); assertEquals(WhistleSession.State.DONE, s.state)
    }
    @Test fun onlyEmptyLanguageSilenceVariationIsAllowed() {
        for(k in listOf("language", "text", "pending", "words", "received", "pass_ms")) {
            rejects(changed(k, null)); rejects(changed(k, JsonNull))
        }
        for(v in listOf(JsonPrimitive("de"), JsonPrimitive(3), JsonPrimitive(false))) rejects(changed("language", v))
        rejects(changed("text", JsonPrimitive("recognized")))
        rejects(changed("pending", JsonPrimitive("provisional")))
        rejects(changed("words", buildJsonArray { add(buildJsonObject { put("word", "x"); put("start",0); put("end",1); put("probability",1) }) }))
    }
    @Test fun malformedWordsUnicodeAndUnknownKeysStillFail() {
        fun withWord(word: JsonElement): String = Json.parseToJsonElement(fixture[0]).jsonObject.toMutableMap().apply {
            put("language", JsonPrimitive("en")); put("words", JsonArray(listOf(word)))
        }.let { JsonObject(it).toString() }
        val good = buildJsonObject { put("word", "hello"); put("start",0); put("end",1); put("probability",1) }
        ready().apply { begin(2,1); assertNull(accept(7,1,withWord(good))) }
        for(k in listOf("word", "start", "end", "probability")) {
            rejects(withWord(JsonObject(good.toMutableMap().apply { remove(k) })))
            rejects(withWord(JsonObject(good.toMutableMap().apply { put(k,JsonNull) })))
        }
        for((k,v) in listOf("start" to JsonPrimitive(-1), "end" to JsonPrimitive(3), "probability" to JsonPrimitive(1.1), "word" to JsonPrimitive("\uD800")))
            rejects(withWord(JsonObject(good.toMutableMap().apply { put(k,v) })))
        rejects(withWord(JsonPrimitive(1)))
        rejects(changed("unexpected_secret_key", JsonPrimitive("secret_value")))
        rejects(changed("text",JsonPrimitive("\uD800")))
        rejects(changed("pending",JsonPrimitive("\uDC00")))
        rejects(changed("words",JsonArray(List(8193) { good })))
        val report = WhistleDiagnostics.report(WhistleStage.SCHEMA,WhistleCode.EXCEPTION,
            rejects(changed("unexpected_secret_key",JsonPrimitive("secret_value"))))
        assertFalse(report.contains("secret"))
    }
    @Test fun strictBoundsAndDiagnostics() {
        for(k in listOf("received", "pass_ms")) for(v in listOf(JsonPrimitive(-1), JsonPrimitive("1"), JsonPrimitive(false))) rejects(changed(k,v))
        val failure = rejects(changed("words", JsonObject(emptyMap())))
        val report = WhistleDiagnostics.report(WhistleStage.SCHEMA, WhistleCode.EXCEPTION, failure)
        assertTrue(report.contains("invariant=array key=words type=object"))
        assertEquals(report, WhistleDiagnostics.checkedRemote(report).report)
        val text = Json.parseToJsonElement(fixture[1]).jsonObject.toMutableMap().apply { put("language", JsonPrimitive("en")); put("pending", JsonPrimitive("tail")) }
        rejects(JsonObject(text).toString(), true)
        val s=ready();s.begin(2,1);s.accept(7,1,fixture[0]);s.begin(3,2)
        try { s.accept(7,2,changed("received",JsonPrimitive(0)));fail() } catch(e: WhistleSchemaException) { assertEquals("monotonic",e.invariant) }
    }
}
