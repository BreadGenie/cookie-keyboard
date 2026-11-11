package org.futo.voiceinput.shared.provisioning

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ModelArtifact(val name: String, val url: String, val bytes: Long, val sha256: String)

/** Only a fully verified private staging directory is published. */
class ParakeetStore(private val filesDir: File, val artifacts: List<ModelArtifact> = PINNED,
                    private val vocabulary: ModelArtifact? = if(artifacts == PINNED) BPE else null) {
    val directory = File(filesDir, "sherpa/parakeet")
    private val staging = File(filesDir, "sherpa/parakeet-download")
    val totalBytes: Long get() = artifacts.sumOf { it.bytes }
    private val identity get() = artifacts.joinToString("\n") { "${it.name}:${it.bytes}:${it.sha256}" }
    fun isReady(): Boolean {
        // Settings may check readiness on every import progress update. Never wait
        // on the model-copy owner from the UI thread.
        if(!owner.tryLock()) return false
        return try {
            val marker = File(directory, "ready")
            if(!marker.isFile || marker.readText() != identity ||
                !artifacts.all { a -> File(directory, a.name).length() == a.bytes }) return false
            // v64/v65 migration. Only this provisioning owner writes derived files.
            // Verify source tokens again before deriving; never trust just their size.
            ensureVocabulary(directory)
            true
        } catch(_: Exception) { false }
        finally { owner.unlock() }
    }
    private fun ensureVocabulary(target: File) {
        val pin = vocabulary ?: return
        if(verify(File(target, pin.name), pin)) return
        val tokens = artifacts.single { it.name == "tokens.txt" }
        if(!verify(File(target, tokens.name), tokens)) throw IOException("Corrupt tokens.txt")
        val temporary = File(target, "bpe.vocab.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(deriveVocabulary(File(target, tokens.name).readText(Charsets.UTF_8)))
                output.fd.sync()
            }
            if(!verify(temporary, pin)) throw IOException("Invalid bpe.vocab")
            Files.move(temporary.toPath(), File(target, pin.name).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    fun delete() = owner.withLock {
        File(directory, "ready").delete()
        check(directory.deleteRecursively() && staging.deleteRecursively()) { "Could not delete Parakeet files" }
    }
    /** Each opener is consumed once; names from providers are deliberately not trusted. */
    fun importFiles(sources: List<() -> InputStream>,
                                  progress: (Long, Long) -> Unit = { _, _ -> },
                                  checkCancelled: () -> Unit = {}) = owner.withLock {
        if(isReady()) return@withLock
        staging.deleteRecursively()
        check(staging.mkdirs())
        val found = mutableSetOf<String>()
        var totalRead = 0L
        // Allows duplicates and small unknown entries, but bounds decompressed ZIP bombs.
        val budget = totalBytes * 2 + 1024 * 1024
        val buffer = ByteArray(65536)
        fun consume(input: InputStream, unknownAllowed: Boolean) {
            val temporary = File(staging, "incoming")
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            temporary.outputStream().use { output ->
                while(true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if(n < 0) break
                    totalRead += n
                    if(totalRead > budget) throw IOException("Import exceeds size limit")
                    count += n
                    digest.update(buffer, 0, n)
                    // Unknown entries are drained with bounded disk usage.
                    if(count <= artifacts.maxOf { it.bytes }) output.write(buffer, 0, n)
                    progress(totalRead.coerceAtMost(totalBytes), totalBytes)
                }
                output.fd.sync()
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val artifact = artifacts.find { it.bytes == count && it.sha256 == hash }
            if(artifact == null) {
                temporary.delete()
                if(!unknownAllowed) throw IOException("Wrong or corrupt model file")
            } else if(found.add(artifact.name)) {
                check(temporary.renameTo(File(staging, artifact.name)))
            } else temporary.delete()
        }
        try {
            for(source in sources) source().use { raw ->
                val input = BufferedInputStream(raw)
                input.mark(4)
                val signature = ByteArray(4)
                var length = 0
                while(length < 4) {
                    val value = input.read()
                    if(value < 0) break
                    signature[length++] = value.toByte()
                }
                input.reset()
                if(length == 4 && signature.contentEquals(byteArrayOf(80, 75, 3, 4))) {
                    ZipInputStream(input).use { zip ->
                        var entries = 0
                        while(true) {
                            checkCancelled()
                            val entry = zip.nextEntry ?: break
                            if(++entries > 1024) throw IOException("Too many ZIP entries")
                            val name = entry.name.replace('\\', '/')
                            if(name.startsWith("/") || name.split('/').any { it == ".." } || ':' in name)
                                throw IOException("Unsafe ZIP entry")
                            consume(zip, true)
                            zip.closeEntry()
                        }
                    }
                } else consume(input, false)
            }
            val missing = artifacts.filter { it.name !in found }
            if(missing.isNotEmpty()) throw IOException("Missing or corrupt files: " + missing.joinToString { it.name })
            checkCancelled()
            ensureVocabulary(staging)
            checkCancelled()
            File(staging, "ready").writeText(identity)
            check(!directory.exists() || directory.deleteRecursively())
            Files.move(staging.toPath(), directory.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            staging.deleteRecursively()
        }
    }
    companion object {
        private val owner = ReentrantLock()
        val BPE = ModelArtifact("bpe.vocab", "derived:tokens.txt", 10494L,
            "2fe7119efddd0052e9ffe4b077553f77c834e429dda3af47686cbaa72ed08a80")
        fun deriveVocabulary(tokens: String): ByteArray = tokens.lineSequence()
            .filter { it.isNotBlank() }
            .joinToString("") { it.trim().split(Regex("\\s+"), limit = 2)[0] + "\t-1.0\n" }
            .toByteArray(Charsets.UTF_8)
        private const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/84b478d541cdbf0c296a7ab5f1e20a6f8f998114/"
        val PINNED = listOf(
            ModelArtifact("encoder.int8.onnx", BASE + "encoder.int8.onnx", 652282309L, "92a10bd9c23936ea0566c29f963bc57c2a5df4261b6a8921c39abea66557ca8b"),
            ModelArtifact("decoder.int8.onnx", BASE + "decoder.int8.onnx", 7257753L, "4d097bae3bac5c96018d5293ba39f1b657296102b142affda40378f38b020f41"),
            ModelArtifact("joiner.int8.onnx", BASE + "joiner.int8.onnx", 1739080L, "77a29f3e5ac7d37074e0af465391b80f314015d5b97f0f90c6c7767f1d56f428"),
            ModelArtifact("tokens.txt", BASE + "tokens.txt", 9384L, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
            ModelArtifact("silero_vad.onnx", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", 643854L, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"))
        fun verify(file: File, a: ModelArtifact): Boolean {
            if(!file.isFile || file.length() != a.bytes) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(65536)
                while(true) { val n = input.read(buffer); if(n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } == a.sha256
        }
    }
}
