package org.futo.voiceinput.shared.sherpa

/** Serialized worker state. A factory owns all engine/model-specific knowledge. */
internal class SherpaWorker(private val factory: (String, String) -> SherpaEngine) {
    private var engine: SherpaEngine? = null
    private var model: Pair<String, String>? = null
    private var session: SherpaSession? = null
    private val chunks = ArrayList<FloatArray>()
    private var samples = 0
    private var poisoned = false
    fun execute(op: Int, generation: Long, sequence: Long, kind: String?, path: String?, pcm: FloatArray?, hotwords: String = ""): String? {
        check(!poisoned)
        try {
            if(op == SherpaSession.LOAD) {
                check(session == null || session!!.state == SherpaSession.State.DONE)
                session = SherpaSession(generation); chunks.clear(); samples = 0
            }
            val current = checkNotNull(session)
            check(current.generation == generation)
            current.begin(op, sequence, pcm?.size ?: 0)
            val result = when(op) {
                SherpaSession.LOAD -> {
                    SherpaHotwords.validate(hotwords)
                    val identity = checkNotNull(kind) to checkNotNull(path)
                    if(model != identity) {
                        engine?.close(); engine = null; model = null
                        engine = factory(kind, path); model = identity
                    }
                    checkNotNull(engine).setHotwords(hotwords)
                    (engine as? SherpaStreamingEngine)?.begin()
                    null
                }
                SherpaSession.FEED -> {
                    val chunk = checkNotNull(pcm)
                    check(chunk.all { it.isFinite() && it in -1f..1f })
                    val streaming = engine as? SherpaStreamingEngine
                    if(streaming != null) try { streaming.feed(chunk) } finally { chunk.fill(0f) }
                    else { chunks.add(chunk); samples += chunk.size }
                    null
                }
                else -> {
                    val all = FloatArray(samples); var offset = 0
                    chunks.forEach { it.copyInto(all, offset); offset += it.size; it.fill(0f) }
                    chunks.clear(); samples = 0
                    try { (engine as? SherpaStreamingEngine)?.finish() ?: checkNotNull(engine).decode(all) } finally { all.fill(0f) }
                }
            }
            current.accept(generation, sequence, result)
            return result
        } catch(e: Throwable) {
            poisoned = true; session?.invalidate(); chunks.forEach { it.fill(0f) }; chunks.clear()
            throw e // Service catches every failure and terminates the private process.
        }
    }
}
