package org.futo.voiceinput.shared.opencl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.futo.voiceinput.shared.ggml.InferenceCancelledException
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FastDictationTest {
    private val header = "guard=RETRY_LOW_CONFIDENCE fast_beam=1 fast_chars=22 fast_segments=1 " +
        "fast_tokens=2 fast_avg_logprob=-3.828 fast_missing_lp=0 " +
        "fast_nospeech_min=0.91 fast_nospeech_max=0.91 fast_nospeech_valid=1 fast_nospeech_unknown=0 fast_nospeech=0.91 " +
        "retry=1 retry_beam=5 " +
        "retry_chars=10 retry_segments=1 retry_tokens=1 retry_avg_logprob=-0.5 retry_missing_lp=0 " +
        "retry_nospeech_min=0.02 retry_nospeech_max=0.02 retry_nospeech_valid=1 retry_nospeech_unknown=0 retry_nospeech=0.02 " +
        "fast_ms=500.0 retry_ms=1300.0 total_ms=1805.0 thold_logprob=-1.0 " +
        "fast_fa_executed=1 fast_enc_built=12 fast_dec_built=0 " +
        "retry_fa_executed=1 retry_enc_built=12 retry_dec_built=0"

    /** Fake service honoring the v18 token contract: a live presented token
     * skips all model-byte reads; anything else sealed-reloads exactly once
     * and issues a fresh generation. Records every presented mode/token. */
    private class FakeFastEngine(
        var packed: String,
        var failPrepareWith: Exception? = null,
        var failRunWith: Exception? = null,
        /** When set, run suspends here before metrics/result (cancellation seam). */
        var runGate: CompletableDeferred<Unit>? = null,
        /** Invoked synchronously at run entry, before the gate. */
        var onRunEntry: (() -> Unit)? = null,
    ) : FastEngine {
        var liveToken = 0L
        val seenModes = mutableListOf<String>()
        val seenTokens = mutableListOf<Long>()
        var loads = 0
        /** Adversarial service recreation: fresh instance re-issues the SAME
         * numeric generation for (by contract) the same allowlisted bytes. */
        fun recreateServiceReissuingSameNumber() {
            liveToken = 0L
            loads++
            liveToken++
        }
        private fun metrics(hit: Boolean): ServiceMetrics {
            return if (hit) {
                ServiceMetrics("resident_verified", 0L, 0L, liveToken, 1, liveToken, true)
            } else {
                loads++
                liveToken++
                ServiceMetrics("resident_reverified", 140L, 264_477_561L, liveToken, 0, liveToken, false)
            }
        }
        override suspend fun prepareVerified(file: File, verifyMode: String, residentToken: Long, onMetrics: (ServiceMetrics) -> Unit) {
            seenModes.add(verifyMode); seenTokens.add(residentToken)
            failPrepareWith?.let { throw it }
            onMetrics(metrics(hit = verifyMode == VerifyMode.RESIDENT && residentToken != 0L && residentToken == liveToken))
        }
        override suspend fun runGuardedBeamVerified(file: File, samples: FloatArray, prompt: String, suppress: Boolean, verifyMode: String, residentToken: Long, onLoaded: () -> Unit, onMetrics: (ServiceMetrics) -> Unit): GuardPolicy.GuardResult {
            seenModes.add(verifyMode); seenTokens.add(residentToken)
            failRunWith?.let { throw it }
            onRunEntry?.invoke()
            runGate?.await()
            onLoaded()
            onMetrics(metrics(hit = verifyMode == VerifyMode.RESIDENT && residentToken != 0L && residentToken == liveToken))
            return GuardPolicy.parse(packed)
        }
    }

    /** Controllable stand-in for OpenClLeases.fastEpoch (Android-only). */
    private class FakeLease(var epoch: Long = 0L) {
        fun current() = epoch
    }

    private fun session(lease: FakeLease = FakeLease()) =
        OpenClFastSession(leaseEpoch = { lease.current() })

    private fun runner(engine: FakeFastEngine, s: OpenClFastSession) =
        FastDictationRunner(engine, s)

    private fun packed(fast: String, final: String): String {
        val fastBytes = fast.toByteArray(Charsets.UTF_8).size
        return header.replace("fast_chars=22", "fast_chars=$fastBytes") + "\n$fast\n$final"
    }

    @Test fun optInFalseLeavesRouteUnchanged() {
        // Fast off behaves exactly like today even with OpenCL on: only the
        // ordinary OpenCL/stock eligibility applies, never the fast branch.
        assertFalse(FastDictation.eligible(true, false, setOf("en"), false, true))
        assertFalse(FastDictation.eligible(false, true, setOf("en"), false, true))
        assertTrue(FastDictation.eligible(true, true, setOf("en"), false, true))
    }

    @Test fun requiresOpenClAndSupportedModel() {
        // Same allowlist as normal GPU: English-only, no language-specific
        // models, provisioned file. Fast adds no new model surface.
        assertFalse(FastDictation.eligible(true, true, emptySet(), false, true))
        assertFalse(FastDictation.eligible(true, true, setOf("en", "de"), false, true))
        assertFalse(FastDictation.eligible(true, true, setOf("en"), true, true))
        assertFalse(FastDictation.eligible(true, true, setOf("en"), false, false))
        assertFalse(FastDictation.eligible(false, true, setOf("en"), false, true))
    }

    @Test fun fastRidesOnlyTheAuditedGuardedVerifiedSeam() {
        // Cache OFF (no reuse entry), forced retry OFF (no force entry),
        // global-flash and ordinary engines untouched on the fast path.
        val source = File("src/main/java/org/futo/voiceinput/shared/opencl/FastDictation.kt").readText()
        assertTrue(source.contains("runGuardedBeamVerified"))
        assertFalse(source.contains("runGuardedBeamReuse"))
        assertFalse(source.contains("runGuardedBeamWithEncoderReuse"))
        assertFalse(source.contains("OpenClClient("))
        assertFalse(source.contains("OpenClFlashClient("))
        assertFalse(source.contains("runBeam("))
        assertFalse(source.contains("forceRetry"))
        assertFalse(source.contains("encoder_reuse\", true"))
        // Resident mode only: stale/missing tokens reload+reverify, never a
        // metadata shortcut; full-hash callers keep today's behavior.
        assertTrue(source.contains("VerifyMode.RESIDENT"))
        // v21: scoped tickets + coroutine boundary guard.
        assertTrue(source.contains("Ticket"))
        assertTrue(source.contains("ensureActive"))
        // Partial-suppression by construction: the fast engine seam exposes
        // no partial callback, so speculative attempt text cannot leak.
        val iface = source.substringAfter("interface FastEngine").substringBefore("}")
        assertFalse("fast seam forwards partials: $iface", iface.contains("artial"))
        // Same discipline in the runner source: guarded parse only, final
        // text only, header never forwarded.
        val runner = File("src/main/java/org/futo/voiceinput/shared/whisper/MultiModelRunner.kt").readText()
        assertTrue(runner.contains("FastDictationRunner"))
        assertTrue(runner.contains("VerifyMode.RESIDENT") || runner.contains("fastSession"))
    }

    @Test fun warmLeaseOwnerIsSharedAcrossWindows() {
        // v21 scope proof: the token owner lives in ModelManager (persistent
        // keyboard state), and per-window runners share it. A private
        // per-runner session (v20 shape) is gone.
        val manager = File("src/main/java/org/futo/voiceinput/shared/whisper/ModelManager.kt").readText()
        assertTrue(manager.contains("val fastResident"))
        assertTrue(manager.contains("OpenClFastSession()"))
        val runner = File("src/main/java/org/futo/voiceinput/shared/whisper/MultiModelRunner.kt").readText()
        assertTrue(runner.contains("modelManager.fastResident"))
        assertFalse("per-window private session reintroduced", runner.contains("= OpenClFastSession()"))
        // Lease epoch machinery exists and Fast-off retires residency only.
        val leases = File("src/main/java/org/futo/voiceinput/shared/opencl/OpenClLeases.kt").readText()
        assertTrue(leases.contains("fastEpoch"))
        assertTrue(leases.contains("fun releaseFast()"))
        val settings = File("../java/src/org/futo/inputmethod/latin/uix/settings/pages/VoiceInput.kt").readText()
        assertTrue(settings.contains("releaseFast()"))
    }

    @Test fun coldPrepareThenWarmRunReadsZeroBytes() = runBlocking {
        val engine = FakeFastEngine(packed("superseded fast speculation", "kept words"))
        val r = runner(engine, session())
        val file = File("model-a.bin")
        r.prepare(file, "key-a")
        assertEquals(1, engine.loads)
        assertEquals(listOf(VerifyMode.RESIDENT), engine.seenModes)
        assertEquals(listOf(0L), engine.seenTokens)
        val (text, telemetry) = r.run(file, "key-a", FloatArray(160), "(Glossary: )", true) {}
        assertEquals("kept words", text)
        // Warm run presented the live token and hashed zero bytes.
        assertEquals(listOf(0L, 1L), engine.seenTokens)
        assertEquals(1, engine.loads)
        assertEquals("resident_verified", telemetry.verification)
        assertEquals(0L, telemetry.bytesHashed)
        assertEquals(1, telemetry.residentHit)
        assertEquals("RETRY_LOW_CONFIDENCE", telemetry.guard)
        assertEquals(1, telemetry.retry)
    }

    @Test fun prewarmTokenReusedAcrossRuns() = runBlocking {
        val engine = FakeFastEngine(packed("fast", "final text"))
        val r = runner(engine, session())
        val file = File("model-a.bin")
        r.prepare(file, "key-a")
        repeat(3) {
            val (text, telemetry) = r.run(file, "key-a", FloatArray(160), "", true) {}
            assertEquals("final text", text)
            assertEquals("resident_verified", telemetry.verification)
        }
        // One cold verify total; every later call rode the resident token.
        assertEquals(1, engine.loads)
        assertEquals(listOf(0L, 1L, 1L, 1L), engine.seenTokens)
    }

    @Test fun newRunnerSameOwnerStaysWarmAcrossWindows() = runBlocking {
        // v21 fix proof at the real object seam: two DISTINCT
        // FastDictationRunner instances (two voice windows, two
        // MultiModelRunners) sharing one ModelManager-hosted owner. First
        // window cold-verifies; the NEW runner's prewarm AND run present the
        // live token and hash zero bytes (no second cold load).
        val engine = FakeFastEngine(packed("fast", "final text"))
        val shared = session()
        val file = File("model-a.bin")
        val first = runner(engine, shared)
        first.prepare(file, "key-a")
        first.run(file, "key-a", FloatArray(160), "", true) {}
        assertEquals(1, engine.loads)
        // New window: brand-new runner object, same owner.
        val second = runner(engine, shared)
        second.prepare(file, "key-a")
        val (text, telemetry) = second.run(file, "key-a", FloatArray(160), "", true) {}
        assertEquals("final text", text)
        assertEquals("resident_verified", telemetry.verification)
        assertEquals(0L, telemetry.bytesHashed)
        // Tokens presented: 0 (cold), 1, 1 (warm prewarm), 1 (warm run).
        assertEquals(listOf(0L, 1L, 1L, 1L), engine.seenTokens)
        assertEquals("second window must not cold-reload", 1, engine.loads)
    }

    @Test fun separateOwnersColdReloadPerWindow() = runBlocking {
        // Honesty control for the v20 shape: two independent owners (one per
        // window, no sharing) each cold-verify. This is what v21 fixes by
        // hosting the owner in ModelManager.
        val engine = FakeFastEngine(packed("fast", "final text"))
        val file = File("model-a.bin")
        runner(engine, session()).run {
            prepare(file, "key-a")
            run(file, "key-a", FloatArray(160), "", true) {}
        }
        runner(engine, session()).run {
            prepare(file, "key-a")
            run(file, "key-a", FloatArray(160), "", true) {}
        }
        assertEquals(2, engine.loads)
        assertEquals(listOf(0L, 1L, 0L, 2L), engine.seenTokens)
    }

    @Test fun leaseEpochBumpForcesExactlyOneColdReverify() = runBlocking {
        // Lease death/expiry/release/trim/Fast-off retires the token; the
        // next request cold-verifies exactly once, then goes warm again.
        val lease = FakeLease()
        val engine = FakeFastEngine(packed("fast", "final text"))
        val shared = session(lease)
        val file = File("model-a.bin")
        runner(engine, shared).run {
            prepare(file, "key-a")
            run(file, "key-a", FloatArray(160), "", true) {}
        }
        assertEquals(1, engine.loads)
        lease.epoch = 7L // e.g. idle-lease expiry, trim, releaseFast().
        val second = runner(engine, shared)
        second.prepare(file, "key-a")
        // The prewarm presented 0 and cold-verified exactly once...
        assertEquals(listOf(0L, 1L, 0L), engine.seenTokens)
        assertEquals(2, engine.loads)
        // ...so the run that follows is warm again (zero bytes), and it
        // stays warm afterwards. One cold reverify total for the bump.
        val (_, telemetry) = second.run(file, "key-a", FloatArray(160), "", true) {}
        assertEquals("resident_verified", telemetry.verification)
        assertEquals(0L, telemetry.bytesHashed)
        val (_, telemetry2) = second.run(file, "key-a", FloatArray(160), "", true) {}
        assertEquals("resident_verified", telemetry2.verification)
        assertEquals(2, engine.loads)
        assertEquals(listOf(0L, 1L, 0L, 2L, 2L), engine.seenTokens)
    }

    @Test fun sameNumberedFreshGenerationCannotBeReused() = runBlocking {
        // Numeric collision guard: even if a fresh service instance re-issues
        // the SAME numeric generation, a moved lease epoch forces cold
        // verify; the old number is never presented.
        val lease = FakeLease()
        val engine = FakeFastEngine(packed("fast", "final"))
        val shared = session(lease)
        val file = File("model-a.bin")
        runner(engine, shared).prepare(file, "key-a")
        assertEquals(listOf(0L), engine.seenTokens)
        lease.epoch = 3L
        engine.recreateServiceReissuingSameNumber() // liveToken is 1 again
        runner(engine, shared).prepare(file, "key-a")
        assertEquals("old numeric token must never be presented", listOf(0L, 0L), engine.seenTokens)
    }

    @Test fun staleTicketAfterInvalidateCannotResurrect() {
        // Delayed metrics arriving after cancel/selection-change must not
        // restore a token for a retired generation.
        val shared = session()
        val ticket = shared.begin("key-a")
        shared.invalidate()
        shared.noteToken(ticket, 99L)
        assertEquals(0L, shared.token())
        assertEquals(0L, shared.begin("key-a").token)
    }

    @Test fun staleTicketAfterLeaseBumpCannotResurrect() {
        val lease = FakeLease()
        val shared = session(lease)
        val ticket = shared.begin("key-a")
        shared.noteToken(ticket, 5L)
        assertEquals(5L, shared.token())
        lease.epoch = 1L // service death / expiry / Fast-off observed
        shared.noteToken(ticket, 5L) // late duplicate accept under old lease
        assertEquals(0L, shared.token())
        assertEquals(0L, shared.begin("key-a").token)
    }

    @Test fun packedHeaderAndSupersededFastTextNeverLeak() = runBlocking {
        val engine = FakeFastEngine(packed("line1\nline2 superseded héllo", "final ✓ words"))
        val r = runner(engine, session())
        val file = File("model-a.bin")
        val (text, _) = r.run(file, "key-a", FloatArray(160), "", true) {}
        assertEquals("final ✓ words", text)
        assertFalse(text.contains("guard="))
        assertFalse(text.contains("superseded"))
        assertFalse(text.contains("fast_chars"))
    }

    @Test fun cancelInvalidatesToken() = runBlocking {
        val engine = FakeFastEngine(packed("fast", "final"))
        val r = runner(engine, session())
        val file = File("model-a.bin")
        r.prepare(file, "key-a")
        engine.failRunWith = InferenceCancelledException()
        try {
            r.run(file, "key-a", FloatArray(160), "", true) {}
            fail("expected cancellation")
        } catch (_: InferenceCancelledException) { }
        // Next request must cold-verify once, never trust the old token.
        engine.failRunWith = null
        r.run(file, "key-a", FloatArray(160), "", true) {}
        assertEquals(listOf(0L, 1L, 0L), engine.seenTokens)
        assertEquals(2, engine.loads)
    }

    @Test fun coroutineCancelDeliversNothingAndInvalidates() = runBlocking {
        // Stop/hide/switch/lock racing an in-flight run: the coroutine is
        // cancelled mid-service-call, no text is delivered, and the token
        // is retired so the next request cold-verifies.
        val gate = CompletableDeferred<Unit>()
        val engine = FakeFastEngine(packed("fast", "final"), runGate = gate)
        val shared = session()
        val file = File("model-a.bin")
        val r = runner(engine, shared)
        r.prepare(file, "key-a")
        assertEquals(1, engine.loads)
        var delivered: String? = "unreached"
        val entered = CompletableDeferred<Unit>()
        engine.onRunEntry = { entered.complete(Unit) }
        val job = async {
            val (text, _) = r.run(file, "key-a", FloatArray(160), "", true) {}
            delivered = text
        }
        // Wait until the run is actually suspended inside the service call,
        // then cancel (Stop/hide/switch/lock racing the in-flight run). The
        // runner must surface cancellation, never a result.
        entered.await()
        try {
            job.cancelAndJoin()
        } catch (_: CancellationException) { }
        assertTrue(job.isCancelled)
        assertEquals("unreached", delivered)
        assertEquals(0L, shared.token())
        // Next window cold-verifies exactly once.
        engine.runGate = null
        r.run(file, "key-a", FloatArray(160), "", true) {}
        assertEquals(2, engine.loads)
    }

    @Test fun cancelledPrewarmLeavesNoPartialState() = runBlocking {
        // Prewarm cancelled mid-call (Stop while prewarm runs): no metrics
        // were recorded, no token issued; the run then cold-verifies exactly
        // once. The single extra load vs. uninterrupted warm is the legitimate
        // cancel-retry, not a double load of one request.
        val engine = FakeFastEngine(packed("fast", "final"))
        val shared = session()
        val file = File("model-a.bin")
        val r = runner(engine, shared)
        // Simulate a prewarm whose service call is cancelled before metrics.
        engine.failPrepareWith = InferenceCancelledException()
        try {
            r.prepare(file, "key-a")
            fail("expected cancellation")
        } catch (_: InferenceCancelledException) { }
        assertEquals(0, engine.loads)
        engine.failPrepareWith = null
        val (_, telemetry) = r.run(file, "key-a", FloatArray(160), "", true) {}
        assertEquals("resident_reverified", telemetry.verification)
        assertEquals(1, engine.loads)
    }

    @Test fun failureInvalidatesToken() = runBlocking {
        val engine = FakeFastEngine(packed("fast", "final"))
        val r = runner(engine, session())
        val file = File("model-a.bin")
        r.prepare(file, "key-a")
        engine.failRunWith = OpenClRecognitionException()
        try {
            r.run(file, "key-a", FloatArray(160), "", true) {}
            fail("expected recognition failure")
        } catch (_: OpenClRecognitionException) { }
        engine.failRunWith = null
        r.run(file, "key-a", FloatArray(160), "", true) {}
        assertEquals(listOf(0L, 1L, 0L), engine.seenTokens)
    }

    @Test fun modelSwitchReverifiesFromZero() = runBlocking {
        val engine = FakeFastEngine(packed("fast", "final"))
        val r = runner(engine, session())
        r.run(File("model-a.bin"), "key-a|/a.bin", FloatArray(160), "", true) {}
        assertEquals(listOf(0L), engine.seenTokens)
        // Same path but a different selection key: explicit reverify, never
        // a path-identity shortcut. (Honesty: for path-only keys
        // [ModelFileFile], a same-path reimport keeps the key; content
        // identity there rests on the cold-load SHA gate, and
        // resident_verified asserts nothing about the supplied file.)
        r.run(File("model-a.bin"), "key-b|/a.bin", FloatArray(160), "", true) {}
        assertEquals(listOf(0L, 0L), engine.seenTokens)
        assertEquals(2, engine.loads)
        // Returning to the first selection reverifies again (no retained
        // cross-selection token).
        r.run(File("model-a.bin"), "key-a|/a.bin", FloatArray(160), "", true) {}
        assertEquals(3, engine.loads)
    }

    @Test fun wrongModelPrepareFailsWithoutToken() = runBlocking {
        val engine = FakeFastEngine(packed("fast", "final"), failPrepareWith = OpenClStartupException())
        val r = runner(engine, session())
        try {
            r.prepare(File("wrong.bin"), "key-wrong")
            fail("expected startup failure")
        } catch (_: OpenClStartupException) { }
        // No token was issued; a later valid prepare still cold-verifies.
        engine.failPrepareWith = null
        r.prepare(File("model-a.bin"), "key-a")
        assertEquals(listOf(0L, 0L), engine.seenTokens)
        assertEquals(1, engine.loads)
    }

    @Test fun exactZeroBlankStaysBlank() = runBlocking {
        val skipHeader = header.replace("guard=RETRY_LOW_CONFIDENCE", "guard=NO_SIGNAL_DIGITAL_ZERO")
            .replace("fast_chars=22", "fast_chars=0")
            .replace("retry=1 retry_beam=5", "retry=0 retry_beam=0")
            .replace("fast_avg_logprob=-3.828", "fast_avg_logprob=NaN")
        val engine = FakeFastEngine("$skipHeader\n\n")
        val r = runner(engine, session())
        val (text, telemetry) = r.run(File("model-a.bin"), "key-a", FloatArray(160), "", true) {}
        assertEquals("", text)
        assertEquals("NO_SIGNAL_DIGITAL_ZERO", telemetry.guard)
        assertEquals(0, telemetry.retry)
    }
    @Test fun completedWindowCleanupPreservesSharedResidentAcrossNewRunner() = runBlocking {
        val engine = FakeFastEngine(packed("fast", "final"))
        val owner = session()
        runner(engine, owner).prepare(File("model-a.bin"), "key-a")
        runner(engine, owner).run(File("model-a.bin"), "key-a", FloatArray(160), "", true) {}
        // Both cleanup callers run: recognizer reset and host window close.
        owner.finishWindow(completed = true)
        owner.finishWindow(completed = true)
        runner(engine, owner).prepare(File("model-a.bin"), "key-a")
        assertEquals(1, engine.loads)
        owner.finishWindow(completed = false)
        runner(engine, owner).prepare(File("model-a.bin"), "key-a")
        assertEquals(2, engine.loads)
    }

    @Test fun newWindowInitPreservesSharedResident() = runBlocking {
        // v23 phone regression: the second window's prewarm cold-reloaded 6s
        // after a successful dictation (context_hit=false, generation 1->2)
        // despite v22 preserving residency through successful close. Cause:
        // the NEW window's init reset() ran on a fresh per-window recognizer
        // but defaulted to invalidating the SHARED ModelManager.fastResident
        // token, so the following prewarm presented token 0 and reloaded.
        val engine = FakeFastEngine(packed("fast", "final"))
        val owner = session()
        val file = File("model-a.bin")
        runner(engine, owner).prepare(file, "key-a")
        runner(engine, owner).run(file, "key-a", FloatArray(160), "", true) {}
        assertEquals(1, engine.loads)
        // Successful close preserves (v22).
        owner.finishWindow(completed = true)
        owner.finishWindow(completed = true)
        // New-window init must take the preserving path: prewarm and run stay
        // warm with zero bytes hashed, no second cold load.
        owner.finishWindow(completed = true) // models init reset(preserveFastResidency = true)
        runner(engine, owner).prepare(file, "key-a")
        val (_, telemetry) = runner(engine, owner).run(file, "key-a", FloatArray(160), "", true) {}
        assertEquals("resident_verified", telemetry.verification)
        assertEquals(0L, telemetry.bytesHashed)
        assertEquals("new-window init must not force a cold reload", 1, engine.loads)
        assertEquals(listOf(0L, 1L, 1L, 1L), engine.seenTokens)
        // The fresh AudioRecognizer/RecognizerView init path needs Android
        // Context/LifecycleScope and cannot instantiate on JVM, so the host
        // call site is locked by source assertion (limitation documented, not
        // mocked); the behavior above runs the real shared owner + token
        // contract. True cancellation still invalidates via the defaults
        // (see completedWindowCleanup... test).
        val host = File("../java/src/org/futo/inputmethod/latin/uix/actions/VoiceInputAction.kt").readText()
        assertTrue("new-window init must preserve Fast residency", host.contains("recognizerView.reset(preserveFastResidency = true)"))
    }

    @Test fun hostCompletedCloseUsesDistinctCleanupAndCancelDefaultsToInvalidation() {
        val host = File("../java/src/org/futo/inputmethod/latin/uix/actions/VoiceInputAction.kt").readText()
        val view = File("src/main/java/org/futo/voiceinput/shared/RecognizerView.kt").readText()
        val recorder = File("src/main/java/org/futo/voiceinput/shared/AudioRecognizer.kt").readText()
        assertTrue(host.contains("if (wasFinished) recognizerView.value?.closeCompleted()"))
        assertTrue(host.contains("cancelAll(preserveFastResidency = wasFinished)"))
        assertTrue(view.contains("recognizer.reset(preserveFastResidency = true)"))
        assertTrue(recorder.contains("fun reset(preserveFastResidency: Boolean = false)"))
    }

}
