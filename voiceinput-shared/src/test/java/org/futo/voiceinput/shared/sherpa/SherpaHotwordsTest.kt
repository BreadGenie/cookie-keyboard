package org.futo.voiceinput.shared.sherpa

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SherpaHotwordsTest {
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch(_: IllegalArgumentException) { }
    }
    @Test fun normalizeCaseWhitespaceApostrophesAndDeduplicate() {
        assertEquals("Priya Shah/Sean O'Connor/SQLite", SherpaHotwords.snapshot(
            listOf("  Priya\t Shah  ", "Priya Shah", "Sean O’Connor", "SQLite", " ")))
        assertEquals("", SherpaHotwords.snapshot(emptyList()))
    }
    @Test fun skipControlSyntaxAndUnsupportedCharactersKeepingValidEntries() {
        val unusable = listOf("a/b", "a\nb", "a\rb", ":4", "a :4", "a:b", "a\u0000b",
            "René", "Rene\u0301", "😀", "<unk>", "---", "a\u2028b", "C++", "e.g.")
        for(value in unusable) assertEquals("", SherpaHotwords.snapshot(listOf(value)))
        assertEquals("Priya Shah/Siobhan Murphy",
            SherpaHotwords.snapshot(listOf("Priya Shah") + unusable + "Siobhan Murphy"))
    }
    @Test fun phraseCountAndCodePointBoundsKeepEarliestEntries() {
        assertEquals(128, SherpaHotwords.snapshot((1..128).map { "Name$it" }).split('/').size)
        val capped = SherpaHotwords.snapshot((1..129).map { "Name$it" }).split('/')
        assertEquals(128, capped.size)
        assertEquals("Name128", capped.last())
        assertEquals(80, SherpaHotwords.snapshot(listOf("x".repeat(80))).length)
        assertEquals("ok", SherpaHotwords.snapshot(listOf("x".repeat(81), "ok")))
    }
    @Test fun exactPayloadByteLimitAndIndependentProtocolValidation() {
        // 100*81 + 92 = 8192 bytes, including the 101 delimiters.
        val phrases = (0..99).map { "x".repeat(77) + "%03d".format(it) } +
            listOf("y".repeat(80), "z".repeat(11))
        val payload = SherpaHotwords.snapshot(phrases)
        assertEquals(8192, payload.toByteArray().size)
        assertEquals(payload, SherpaHotwords.validate(payload))
        assertEquals(payload.substringBeforeLast('/'),
            SherpaHotwords.snapshot(phrases.dropLast(1) + "z".repeat(12)))
        for(value in listOf("x".repeat(8193), "a//b", "/a", "a/", " a", "a/a", "a/:4"))
            rejects { SherpaHotwords.validate(value) }
    }
    @Test fun recordingStartSnapshotIsImmutableAndRefreshesOnlyAtNextBegin() {
        val source = mutableListOf("Priya Shah")
        var reads = 0
        val session = SherpaHotwordSnapshot { reads++; source }
        session.begin(); source[0] = "Sean O'Connor"
        repeat(5) { assertEquals("Priya Shah", session.value()) }
        assertEquals(1, reads)
        session.begin(); assertEquals("Sean O'Connor", session.value()); assertEquals(2, reads)
        source.clear(); session.begin(); assertEquals("", session.value())
    }
    @Test fun unusableDictionaryEntriesAreSkippedNotFatal() {
        val session = SherpaHotwordSnapshot { listOf("private/name", "Priya Shah") }
        session.begin()
        assertEquals("Priya Shah", session.value())
    }
    @Test fun dictionarySourceFailureFailsAtPreloadWithoutLeakingText() {
        val session = SherpaHotwordSnapshot { throw IllegalStateException("closed") }
        session.begin()
        try { session.value(); fail() } catch(e: IllegalStateException) {
            assertFalse(e.message.orEmpty().contains("private"))
        }
    }
    @Test fun beamConfigurationHasPinnedBpeAndNativeLoggingOffEvenWithoutHints() {
        val dir = Files.createTempDirectory("beam-config").toFile()
        try {
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "silero_vad.onnx", "bpe.vocab")
                .forEach { File(dir, it).writeText("fixture") }
            val config = SherpaModels.config(dir)
            assertEquals("modified_beam_search", config.decodingMethod)
            assertEquals(4, config.maxActivePaths); assertEquals(2.0f, config.hotwordsScore)
            assertEquals("", config.hotwordsFile); assertEquals("bpe", config.modelConfig.modelingUnit)
            assertEquals(File(dir, "bpe.vocab").path, config.modelConfig.bpeVocab)
            assertFalse(config.modelConfig.debug)
        } finally { dir.deleteRecursively() }
    }
    @Test fun loadUpdatesHintsWithoutReloadAndAllStreamingWindowsUseSessionSnapshot() {
        var active = ""; var loads = 0
        val observed = mutableListOf<String>()
        val engine = object : SherpaStreamingEngine {
            override fun setHotwords(hotwords: String) { active = hotwords }
            override fun begin() = Unit
            override fun feed(samples: FloatArray) { observed.add(active) }
            override fun finish(): String { observed.add(active); return "final" }
            override fun decode(samples: FloatArray): String = error("batch route")
            override fun close() = Unit
        }
        val worker = SherpaWorker { _, _ -> loads++; engine }
        val transport = object : SherpaTransport {
            override fun request(op: Int, generation: Long, sequence: Long, kind: String?, path: String?, samples: FloatArray?, hotwords: String) =
                worker.execute(op, generation, sequence, kind, path, samples, hotwords)
            override fun cancel() = Unit
        }
        val entries = mutableListOf("Priya Shah")
        val snapshot = SherpaHotwords.snapshot(entries)
        val work = SherpaWork(1, "parakeet", "/model", { transport }, {}, snapshot)
        work.load(); entries[0] = "SQLite"
        work.feed(FloatArray(1)); work.feed(FloatArray(1)); work.finish()
        assertEquals(listOf(snapshot, snapshot, snapshot), observed)
        val next = SherpaWork(2, "parakeet", "/model", { transport }, {}, "")
        next.load(); next.feed(FloatArray(1)); next.finish()
        assertEquals(listOf("", ""), observed.takeLast(2)); assertEquals(1, loads)
    }
}
