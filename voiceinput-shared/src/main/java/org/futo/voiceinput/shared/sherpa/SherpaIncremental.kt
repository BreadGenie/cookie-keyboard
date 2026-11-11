package org.futo.voiceinput.shared.sherpa

/** Recurrent VAD and exact batch-planner prefix. PCM is a fixed ring; scores and
 * ranges are bounded by the protocol's total-session resource limit. */
internal class SherpaIncremental(
    private val probability: (FloatArray) -> Float,
    private val decode: (FloatArray, Boolean) -> SherpaTokens
) {
    // 20 s owned audio + both contexts + VAD/frame lookahead; never the full recording.
    private val pcm = FloatArray(SHERPA_WINDOW_SAMPLES + 2 * SHERPA_CONTEXT_SAMPLES + 16000)
    private val frame = FloatArray(512)
    private var frameSize = 0
    private var total = 0
    private var processed = 0
    private var retainedStart = 0
    private val scores = ArrayList<Float>()
    private val speech = ArrayList<SherpaWindow>()
    private var start = -1
    private var lastSpeechEnd = 0
    private var voiced = 0
    private var decodedEnd = 0
    private val text = StringBuilder()
    private var finished = false
    val windows = ArrayList<SherpaWindow>()
    fun feed(samples: FloatArray) {
        check(!finished)
        try {
            check(total <= SherpaSession.MAX_SAMPLES - samples.size)
            for(sample in samples) {
                check(sample.isFinite() && sample in -1f..1f)
                check(total - retainedStart < pcm.size) { "Recognition PCM backlog exceeded" }
                pcm[total % pcm.size] = sample
                total++
                frame[frameSize++] = sample
                if(frameSize == 512) process(false)
            }
        } catch (failure: Throwable) { clear(); throw failure }
    }
    private fun closeSpeech() {
        if(start >= 0 && voiced >= 4000) speech.add(SherpaWindow(start, lastSpeechEnd))
        start = -1; voiced = 0
    }
    private fun process(tail: Boolean) {
        val score = probability(frame)
        check(score.isFinite() && score in 0f..1f)
        scores.add(score)
        val end = processed + frameSize
        if(score >= 0.5f) {
            if(start < 0) start = processed
            lastSpeechEnd = end; voiced += frameSize
        } else if(start >= 0 && end - lastSpeechEnd >= 8000) closeSpeech()
        processed = end
        frame.fill(0f); frameSize = 0
        emit(tail)
    }
    private fun emit(tail: Boolean) {
        val ranges = if(start >= 0 && voiced >= 4000) speech + SherpaWindow(start, lastSpeechEnd) else speech
        val plan = planSherpaWindows(ranges, processed, probabilities = scores.toFloatArray())
        for((index, window) in plan.withIndex()) {
            if(window.end <= decodedEnd) continue
            // A closed region's padding is final after the 500 ms silence. An
            // open region's final window can still grow or merge with its tail.
            if(!tail && index == plan.lastIndex && start >= 0) break
            val left = plan.getOrNull(index - 1)?.end == window.start
            val right = plan.getOrNull(index + 1)?.start == window.end
            if (!tail && right && total.toLong() < window.end.toLong() + SHERPA_CONTEXT_SAMPLES) break
            val range = sherpaDecodeRange(window, total, forcedLeft = left, forcedRight = right)
            check(range.start >= retainedStart)
            val audio = FloatArray(range.end - range.start) { pcm[(range.start + it) % pcm.size] }
            val part = try { stitchSherpaWords(window, range.start, decode(audio, !tail), left, right) } finally { audio.fill(0f) }
            if(part.isNotEmpty()) {
                if(text.isNotEmpty()) text.append(' ')
                text.append(part); check(text.length <= SherpaSession.MAX_TEXT)
            }
            windows.add(window); decodedEnd = window.end
        }
        val pendingStart = plan.firstOrNull { it.end > decodedEnd }?.start
        val futureStart = if (start >= 0) (start - 1600).coerceAtLeast(0) else (processed - 1600).coerceAtLeast(0)
        val needed = (maxOf(decodedEnd, minOf(pendingStart ?: futureStart, futureStart)) - SHERPA_CONTEXT_SAMPLES).coerceAtLeast(0)
        while(retainedStart < needed) { pcm[retainedStart % pcm.size] = 0f; retainedStart++ }
    }
    fun finish(): String {
        check(!finished); finished = true
        try {
            if(frameSize > 0) process(true)
            closeSpeech(); emit(true)
            return text.toString()
        } finally { clear() }
    }
    fun clear() { finished = true; pcm.fill(0f); frame.fill(0f); text.clear() }
}
