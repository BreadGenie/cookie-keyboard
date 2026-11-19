package org.futo.voiceinput.shared.sherpa

import java.text.Normalizer

/** Application policy: English letters, digits, spaces, ASCII apostrophes and hyphens.
 * NFC first; curly apostrophes become ASCII. Case is preserved. Reject unsupported
 * characters before JNI: createStream exposes no hotword-encoding success flag and
 * native encoding errors can log the input even with debug=false. No score syntax.
 * Entries are hints, so an unusable entry is skipped rather than failing dictation;
 * once the phrase or byte limit is reached, later entries are skipped.
 */
object SherpaHotwords {
    const val MAX_BYTES = 8192
    const val MAX_PHRASES = 128
    const val MAX_CODE_POINTS = 80
    private fun phrase(entry: String): String? {
        if (entry.any { it == '/' || it == ':' || it == '\n' || it == '\r' ||
                (it.isISOControl() && it != '\t') }) return null
        val phrase = Normalizer.normalize(entry, Normalizer.Form.NFC)
            .replace('’', '\'').trim().replace(Regex("[\\s\\p{Zs}]+"), " ")
        return phrase.takeIf {
            it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX_CODE_POINTS &&
                it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in " '-" } &&
                it.any { c -> c.isLetterOrDigit() }
        }
    }
    fun snapshot(entries: List<String>): String {
        val phrases = linkedSetOf<String>()
        var bytes = 0
        for (entry in entries) {
            if (phrases.size >= MAX_PHRASES) break
            val phrase = phrase(entry) ?: continue
            if (phrase in phrases) continue
            val added = phrase.length + if (phrases.isEmpty()) 0 else 1
            if (bytes + added > MAX_BYTES) break
            phrases.add(phrase)
            bytes += added
        }
        return phrases.joinToString("/")
    }
    /** Independently validate the canonical wire payload, including delimiters. */
    fun validate(payload: String): String {
        require(payload.length <= MAX_BYTES && payload.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        require(payload.isEmpty() || snapshot(payload.split('/')) == payload)
        return payload
    }
}

/** Captures failures without leaking entry text; preload uses the existing explicit error path. */
internal class SherpaHotwordSnapshot(private val source: () -> List<String>) {
    private var captured = Result.success("")
    fun begin() { captured = runCatching { SherpaHotwords.snapshot(source()) } }
    fun value(): String = captured.getOrThrow()
}
