package org.futo.voiceinput.shared.sherpa

/** Half-open sample range; never a timestamp rounded through floating point. */
internal data class SherpaWindow(val start: Int, val end: Int)
internal const val SHERPA_WINDOW_SAMPLES = 20 * 16000

/** VAD ranges must be ordered. Merge padding overlaps before cutting, so no sample repeats. */
internal fun planSherpaWindows(
    speech: List<SherpaWindow>, total: Int, padding: Int = 1600,
    probabilities: FloatArray = floatArrayOf()
): List<SherpaWindow> {
    require(total >= 0 && padding >= 0)
    require(probabilities.isEmpty() || probabilities.size.toLong() == (total.toLong() + 511) / 512)
    require(probabilities.all { it.isFinite() && it in 0f..1f })
    val regions = ArrayList<SherpaWindow>()
    var previousEnd = 0
    for (segment in speech) {
        require(segment.start >= previousEnd && segment.end >= segment.start && segment.end <= total)
        previousEnd = segment.end
        if (segment.start == segment.end) continue
        val start = (segment.start.toLong() - padding).coerceAtLeast(0).toInt()
        val end = (segment.end.toLong() + padding).coerceAtMost(total.toLong()).toInt()
        val last = regions.lastOrNull()
        if (last != null && start <= last.end) {
            regions[regions.lastIndex] = SherpaWindow(last.start, maxOf(last.end, end))
        } else regions.add(SherpaWindow(start, end))
    }
    return buildList {
        for (region in regions) {
            var start = region.start
            while (start < region.end) {
                val ceiling = minOf(start.toLong() + SHERPA_WINDOW_SAMPLES, region.end.toLong()).toInt()
                var end = ceiling
                if (ceiling < region.end && probabilities.isNotEmpty()) {
                    // A frame's midpoint owns the cut. Search only the final five seconds;
                    // latest wins ties, keeping flat-score windows close to the ceiling.
                    val earliest = ceiling - 5 * 16000
                    var lowest = Float.POSITIVE_INFINITY
                    for (index in earliest / 512..minOf(ceiling / 512, probabilities.lastIndex)) {
                        val midpoint = index * 512 + 256
                        if (midpoint in earliest..ceiling && probabilities[index] <= lowest) {
                            lowest = probabilities[index]
                            end = midpoint
                        }
                    }
                }
                add(SherpaWindow(start, end))
                start = end
            }
        }
    }
}

/** Nothing escapes until every window succeeds. Only one temporary PCM window is retained. */
internal fun decodeSherpaWindows(
    samples: FloatArray, windows: List<SherpaWindow>, decode: (FloatArray) -> String
): String {
    val text = StringBuilder()
    for (window in windows) {
        require(window.start >= 0 && window.end <= samples.size && window.end > window.start)
        require(window.end - window.start <= SHERPA_WINDOW_SAMPLES)
        val pcm = samples.copyOfRange(window.start, window.end)
        val part = try { decode(pcm).trim() } finally { pcm.fill(0f) }
        if (part.isNotEmpty()) {
            if (text.isNotEmpty()) text.append(' ')
            text.append(part)
            check(text.length <= SherpaSession.MAX_TEXT)
        }
    }
    return text.toString()
}

/** Use the recurrent Silero probability API, avoiding the native segment ring buffer.
 * A 250 ms speech candidate opens a region; 500 ms silence closes it. Short pauses
 * remain inside the region. Only the final inference frame is zero padded; ranges
 * always refer to original samples. No reset at forced decode boundaries.
 */
internal fun detectSherpaSpeech(
    samples: FloatArray, probability: (FloatArray) -> Float
): List<SherpaWindow> {
    val speech = ArrayList<SherpaWindow>()
    val frame = FloatArray(512)
    var start = -1
    var lastSpeechEnd = 0
    var voicedSamples = 0
    fun finish() {
        if (start >= 0 && voicedSamples >= 4000) speech.add(SherpaWindow(start, lastSpeechEnd))
        start = -1
        voicedSamples = 0
    }
    try {
        var offset = 0
        while (offset < samples.size) {
            frame.fill(0f)
            val end = minOf(offset + frame.size, samples.size)
            samples.copyInto(frame, 0, offset, end)
            val score = probability(frame)
            check(score.isFinite() && score in 0f..1f)
            if (score >= 0.5f) {
                if (start < 0) start = offset
                lastSpeechEnd = end
                voicedSamples += end - offset
            } else if (start >= 0 && end - lastSpeechEnd >= 8000) finish()
            offset = end
        }
        finish()
        return speech
    } finally { frame.fill(0f) }
}

/** Decoder context never changes half-open output ownership. Times are stream-relative seconds. */
internal const val SHERPA_CONTEXT_SAMPLES = 24000
internal data class SherpaTokens(val tokens: Array<String>, val timestamps: FloatArray, val text: String? = null)
internal fun sherpaDecodeRange(owned: SherpaWindow, total: Int, context: Int = SHERPA_CONTEXT_SAMPLES,
    forcedLeft: Boolean = true, forcedRight: Boolean = true): SherpaWindow {
    require(context >= 0 && owned.start >= 0 && owned.end > owned.start && owned.end <= total)
    require(owned.end - owned.start <= SHERPA_WINDOW_SAMPLES)
    return SherpaWindow((owned.start - if (forcedLeft) context else 0).coerceAtLeast(0),
        minOf(total.toLong(), owned.end.toLong() + if (forcedRight) context else 0).toInt())
}

internal fun stitchSherpaWords(owned: SherpaWindow, decodeOffset: Int, result: SherpaTokens,
    forcedLeft: Boolean = true, forcedRight: Boolean = true): String {
    require(decodeOffset >= 0 && decodeOffset <= owned.start && owned.end > owned.start)
    require(result.tokens.size == result.timestamps.size)
    require(result.timestamps.all { it.isFinite() && it >= 0f })
    require(result.timestamps.asList().zipWithNext().all { (a, b) -> a <= b })
    val words = ArrayList<Pair<StringBuilder, Double>>()
    var boundary: Double? = null
    for (i in result.tokens.indices) {
        val piece = result.tokens[i]
        // SymbolTable normalizes SentencePiece ▁ to ASCII space in this runtime.
        val beginsWord = piece.startsWith("▁") || piece.startsWith(" ")
        val value = piece.removePrefix("▁").trimStart(' ')
        if (beginsWord && value.isEmpty()) { boundary = decodeOffset + result.timestamps[i].toDouble() * 16000; continue }
        if (value.isEmpty()) continue
        val punctuation = value.all { !it.isLetterOrDigit() && it != '\'' }
        if (words.isEmpty() || ((beginsWord || boundary != null) && !punctuation)) {
            words.add(StringBuilder(value) to (boundary ?: (decodeOffset + result.timestamps[i].toDouble() * 16000)))
        } else words.last().first.append(value)
        boundary = null
    }
    if (!forcedLeft && !forcedRight && result.text != null) return result.text.trim()
    return words.filter { (!forcedLeft || it.second >= owned.start) && (!forcedRight || it.second < owned.end) }
        .joinToString(" ") { it.first.toString() }
}

internal fun decodeSherpaStitchedWindows(
    samples: FloatArray, windows: List<SherpaWindow>, context: Int = SHERPA_CONTEXT_SAMPLES,
    decode: (FloatArray) -> SherpaTokens
): String {
    val text = StringBuilder()
    for ((index, owned) in windows.withIndex()) {
        val left = windows.getOrNull(index - 1)?.end == owned.start
        val right = windows.getOrNull(index + 1)?.start == owned.end
        val range = sherpaDecodeRange(owned, samples.size, context, left, right)
        val pcm = samples.copyOfRange(range.start, range.end)
        val part = try { stitchSherpaWords(owned, range.start, decode(pcm), left, right) } finally { pcm.fill(0f) }
        if (part.isNotEmpty()) {
            if (text.isNotEmpty()) text.append(' ')
            text.append(part)
            check(text.length <= SherpaSession.MAX_TEXT)
        }
    }
    return text.toString()
}

/** A fresh stream receives the session snapshot on every private decode. */
internal fun <S> decodeSherpaTokenStream(
    hotwords: String, create: (String) -> S, decode: (S) -> SherpaTokens, release: (S) -> Unit
): SherpaTokens {
    val stream = create(hotwords)
    return try { decode(stream) } finally { release(stream) }
}
