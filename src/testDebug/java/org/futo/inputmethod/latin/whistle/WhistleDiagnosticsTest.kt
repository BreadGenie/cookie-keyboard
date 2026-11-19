package org.futo.inputmethod.latin.whistle

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.ExecutionException

class WhistleDiagnosticsTest {
    @Test fun comparisonExportIncludesBuildAndTimingsBeforeAnyWhistleTest() {
        val timings = "phase=model_load engine=parakeet cold_model_load_ms=123\n" +
            "phase=recording_decode engine=parakeet windows=2 decode_ms=456 asr_sampled_peak_pss_kb=789\n" +
            "phase=stop_to_result engine=parakeet elapsed_ms=500 audio_ms=12000"
        val report = WhistleDiagnostics.comparisonReport(63, "0.0.63-parakeet-unstable", "No test run.", timings)
        assertTrue(report.startsWith("Cookie debug v63 (0.0.63-parakeet-unstable)"))
        assertTrue(report.endsWith(timings))
        assertTrue(report.contains("No test run."))
    }

    @Test fun sanitizesOriginalAndWrappedExceptions() {
        val e = IllegalArgumentException("secret transcript content://private /home/secret")
        e.stackTrace = Array(20) { StackTraceElement("Worker", "finish", "/secret/path", 42) }
        val report = WhistleDiagnostics.report(WhistleStage.SCHEMA, WhistleCode.EXCEPTION, ExecutionException(e))
        assertTrue(report.contains("stage=SCHEMA code=EXCEPTION exception=java.lang.IllegalArgumentException"))
        assertEquals(6, Regex("Worker#finish").findAll(report).count())
        assertFalse(report.contains("secret")); assertFalse(report.contains("42"))
        assertTrue(report.length <= 2048)
    }
    @Test fun workerStageSurvivesExecutorAndClientWrapping() {
        val worker = WhistleDiagnostics.attemptForTest()
        assertEquals("stage=LOAD code=NATIVE_REJECTED", WhistleDiagnostics.report(WhistleStage.ACK, WhistleCode.EXCEPTION, ExecutionException(worker)))
    }
    private fun WhistleDiagnostics.attemptForTest(): WhistleFailure = try {
        at(WhistleStage.LOAD) { throw failure(WhistleStage.LOAD, WhistleCode.NATIVE_REJECTED) }
    } catch(e: WhistleFailure) { e }
    @Test fun linkageErrorsBecomeContentFreeFailures() {
        val e = try { WhistleDiagnostics.at(WhistleStage.LOAD) { throw UnsatisfiedLinkError("/secret/lib.so") } } catch(e: WhistleFailure) { e }
        assertTrue((e as WhistleFailure).report.contains("java.lang.UnsatisfiedLinkError")); assertFalse(e.report.contains("/secret"))
    }
    @Test fun remoteReportBoundsAndAlphabet() {
        for(s in listOf("x".repeat(2049), "stage=LOAD\nsecret", "content://private")) {
            try { WhistleDiagnostics.checkedRemote(s); fail("unsafe report accepted") } catch(_: IllegalStateException) { }
        }
    }
    @Test fun absentEmptyAndWrongSizeModelNeverVerify() {
        val f = File.createTempFile("whistle-test", ".cact")
        try { assertFalse(WhistleModel.verify(f)); f.writeBytes(ByteArray(32)); assertFalse(WhistleModel.verify(f)); f.delete(); assertFalse(WhistleModel.verify(f)) } finally { f.delete() }
    }
}
