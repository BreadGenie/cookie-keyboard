package org.futo.inputmethod.latin.whistle

import kotlinx.serialization.json.*

/** Metadata is exclusively fixed tokens; never includes runtime keys or values. */
internal class WhistleSchemaException(val invariant: String, val key: String, val type: String) : IllegalStateException()

/** Shared by the real transport and host tests. No editor or history authority. */
internal class WhistleSession(val generation: Long) {
    enum class State { NEW, LOADING, READY, FEEDING, FINISHING, DONE, INVALID, DEAD }
    var state = State.NEW; private set
    private var next = 0L
    private var pending = -1L
    private val text = StringBuilder()
    private var received = 0.0
    @Synchronized fun begin(op: Int, sequence: Long) {
        check(sequence == next && pending == -1L)
        check(when(op) { LOAD -> state == State.NEW; FEED, FINISH -> state == State.READY; else -> false })
        pending = sequence
        state = when(op) { LOAD -> State.LOADING; FEED -> State.FEEDING; else -> State.FINISHING }
    }
    @Synchronized fun accept(g: Long, sequence: Long, json: String?): String? {
        if (state == State.INVALID || state == State.DEAD) return null
        try {
            requireSchema(g == generation && sequence == pending && pending == next, "identity", "sequence", "integer")
            if (state != State.LOADING) append(json ?: throw WhistleSchemaException("required", "response", "absent"), state == State.FINISHING)
            val final = state == State.FINISHING
            requireSchema(state in listOf(State.LOADING, State.FEEDING, State.FINISHING), "state", "session", "state")
            pending = -1; next++; state = if(final) State.DONE else State.READY
            return if(final) text.toString() else null
        } catch(e: Exception) { invalidate(); throw e }
    }
    private fun requireSchema(ok: Boolean, invariant: String, key: String, type: String) {
        if (!ok) throw WhistleSchemaException(invariant, key, type)
    }
    private fun type(v: JsonElement?): String = when(v) {
        null -> "absent"; JsonNull -> "null"; is JsonObject -> "object"; is JsonArray -> "array"
        is JsonPrimitive -> if(v.isString) "string" else if(v.booleanOrNull != null) "boolean" else "number"
    }
    private fun append(raw: String, final: Boolean) {
        requireSchema(raw.toByteArray(Charsets.UTF_8).size <= 262143, "byte_bound", "response", "string")
        val element = try { Json.parseToJsonElement(raw) } catch(_: Exception) {
            throw WhistleSchemaException("json", "response", "string")
        }
        val o = element as? JsonObject ?: throw WhistleSchemaException("object", "response", type(element))
        fun string(obj: JsonObject, k: String): String {
            val p = obj[k] as? JsonPrimitive
            requireSchema(p?.isString == true, "string", k, type(obj[k]))
            return p!!.content
        }
        fun number(obj: JsonObject, k: String): Double {
            val p = obj[k] as? JsonPrimitive
            val n = p?.takeUnless { it.isString }?.doubleOrNull
            requireSchema(n != null && n.isFinite() && n >= 0, "finite_nonnegative", k, type(obj[k]))
            return n!!
        }
        fun unicode(s: String, k: String) {
            var i = 0
            while(i < s.length) {
                val c = s[i++]
                if(c.isHighSurrogate()) {
                    requireSchema(i < s.length && s[i].isLowSurrogate(), "unicode", k, "string"); i++
                } else requireSchema(!c.isLowSurrogate(), "unicode", k, "string")
            }
        }
        for(k in listOf("text", "pending", "words", "language", "received", "pass_ms"))
            requireSchema(k in o, "required", k, "absent")
        requireSchema(o.keys == setOf("text", "pending", "words", "language", "received", "pass_ms"), "keys", "response", "object")
        val committed = string(o, "text"); val provisional = string(o, "pending")
        unicode(committed, "text"); unicode(provisional, "pending")
        requireSchema(!final || provisional.isEmpty(), "final_empty", "pending", "string")
        val duration = number(o, "received")
        requireSchema(duration >= received, "monotonic", "received", "number")
        number(o, "pass_ms")
        val words = o["words"] as? JsonArray ?: throw WhistleSchemaException("array", "words", type(o["words"]))
        requireSchema(words.size <= 8192, "count_bound", "words", "array")
        val language = string(o, "language")
        // Runtime 4.0.0 emits an unset language for all-empty silence, even with
        // forced English. It is not evidence of a recognized non-English result.
        requireSchema(language == "en" || (language.isEmpty() && committed.isEmpty() && provisional.isEmpty() && words.isEmpty()),
            "english_or_empty_silence", "language", "string")
        words.forEach {
            val w = it as? JsonObject ?: throw WhistleSchemaException("object", "words", type(it))
            unicode(string(w, "word"), "word")
            val probability = number(w, "probability")
            requireSchema(probability <= 1, "probability_bound", "probability", "number")
            val start = number(w, "start"); val end = number(w, "end")
            requireSchema(end >= start && end <= duration + 1, "time_bound", "end", "number")
        }
        val separator = if(text.isNotEmpty() && committed.isNotEmpty()) " " else ""
        requireSchema(text.length + separator.length + committed.length <= 65536, "text_bound", "text", "string")
        received = duration
        text.append(separator).append(committed)
    }
    @Synchronized fun invalidate() { text.clear(); pending = -1; state = State.INVALID }
    @Synchronized fun died() { text.clear(); pending = -1; state = State.DEAD }
    companion object { const val LOAD = 1; const val FEED = 2; const val FINISH = 3; const val CANCEL = 4; const val TOKEN = "cookie.whistle.v2" }
}
