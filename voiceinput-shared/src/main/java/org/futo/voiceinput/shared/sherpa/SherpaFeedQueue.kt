package org.futo.voiceinput.shared.sherpa

import kotlinx.coroutines.channels.Channel

/** Includes the in-flight message. A slow/dead worker never blocks microphone capture. */
internal class SherpaFeedQueue(private val limit: Int = 30 * 16000) {
    private val channel = Channel<FloatArray>(Channel.UNLIMITED)
    private var retained = 0
    private var closed = false
    @Synchronized fun offer(samples: FloatArray) {
        check(!closed && samples.size in 1..SherpaSession.CHUNK && retained <= limit - samples.size) {
            "Recognition backlog exceeded"
        }
        val copy = samples.copyOf()
        retained += copy.size
        check(channel.trySend(copy).isSuccess)
    }
    @Synchronized fun close() { closed = true; channel.close() }
    @Synchronized fun cancel() {
        closed = true
        channel.close()
        while(true) {
            val pcm = channel.tryReceive().getOrNull() ?: break
            retained -= pcm.size; pcm.fill(0f)
        }
    }
    suspend fun consume(feed: (FloatArray) -> Unit) {
        try {
            for(pcm in channel) try { feed(pcm) } finally {
                synchronized(this) { retained -= pcm.size }
                pcm.fill(0f)
            }
        } finally { cancel() }
    }
}
