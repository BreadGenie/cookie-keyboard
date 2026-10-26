package org.futo.voiceinput.shared.opencl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClCapabilityTest {
    private fun adrenoProbe(
        flags: String = "fp16,subgroups,qcom_reqd_subgroup_size",
        clc: String = "3.0",
        family: String = "adreno",
        imageSupport: Int = 1,
        imageMaxBuffer: Long = 16_384,
        image2d: Int = 16_384,
        maxWorkgroup: Int = 1024,
        compiler: Int = 1,
    ): String = """
        ocv=1
        probe=ok
        family=$family
        clc=$clc
        flags=$flags
        image_support=$imageSupport
        image_max_buffer=$imageMaxBuffer
        image2d_w=$image2d
        image2d_h=$image2d
        max_wg=$maxWorkgroup
        max_cu=6
        global_mb=7600
        alloc_mb=2048
        compiler=$compiler
        platform=QUALCOMM Snapdragon OpenCL
        device=Adreno (TM) 830
        vendor=QUALCOMM
        driver=E031.47
        dversion=OpenCL 3.0 Adreno
    """.trimIndent()

    private fun evaluate(raw: String): OpenClCapabilities {
        val probe = OpenClProbeParser.parse(raw)
        assertNotNull("expected parseable probe", probe)
        return OpenClCapabilityEvaluator.evaluate(probe!!)
    }

    @Test fun supportedOnePlusLikeAdrenoCapabilitiesRevealControls() {
        val result = evaluate(adrenoProbe())
        assertEquals(OpenClSupport.SUPPORTED, result.support)
        assertEquals(OpenClCapabilityReason.OK, result.reason)
        assertTrue(result.supported)
        assertTrue(result.controlsVisible)
        assertEquals(OpenClDeviceFamily.ADRENO, result.probe?.family)
        assertEquals(3, result.probe?.openClCMajor)
        assertTrue(result.diagnostic().contains("status=supported"))
        assertFalse(result.diagnostic().contains("Adreno (TM) 830")) // content-free copy
    }

    @Test fun missingLibraryIsUnavailableAndHidesControls() {
        val result = evaluate("ocv=1\nprobe=library_missing\n")
        assertEquals(OpenClSupport.UNAVAILABLE, result.support)
        assertEquals(OpenClCapabilityReason.LIBRARY_MISSING, result.reason)
        assertFalse(result.supported)
        assertFalse(result.controlsVisible)
    }

    @Test fun appWithoutPackagedNativeFeatureIsUnavailable() {
        val result = evaluate(OpenClCapabilityProtocol.NOT_PACKAGED_PROBE)
        assertEquals(OpenClSupport.UNAVAILABLE, result.support)
        assertEquals(OpenClCapabilityReason.NOT_PACKAGED, result.reason)
        assertFalse(result.controlsVisible)
    }

    @Test fun noPlatformAndNoGpuAreDistinctUnavailableReasons() {
        assertEquals(OpenClCapabilityReason.NO_PLATFORM,
            evaluate("ocv=1\nprobe=no_platform\n").reason)
        assertEquals(OpenClCapabilityReason.NO_GPU_DEVICE,
            evaluate("ocv=1\nprobe=no_gpu_device\n").reason)
        assertFalse(evaluate("ocv=1\nprobe=no_gpu_device\n").supported)
    }

    @Test fun nativeQueryFailureIsUnknownAndNeverSupported() {
        val result = evaluate("ocv=1\nprobe=query_failed\n")
        assertEquals(OpenClSupport.UNKNOWN, result.support)
        assertEquals(OpenClCapabilityReason.QUERY_FAILED, result.reason)
        assertFalse(result.controlsVisible)
    }

    @Test fun timeoutDeathAndMalformedRepliesAreUnknownAndHidden() {
        listOf(
            OpenClCapabilities.transportFailure(OpenClCapabilityReason.TIMEOUT),
            OpenClCapabilities.transportFailure(OpenClCapabilityReason.SERVICE_DEAD),
            OpenClCapabilities.transportFailure(OpenClCapabilityReason.QUERY_FAILED),
        ).forEach {
            assertEquals(OpenClSupport.UNKNOWN, it.support)
            assertFalse(it.supported)
            assertFalse(it.controlsVisible)
        }
        assertNull(OpenClProbeParser.parse("ocv=2\nprobe=ok\n"))
        assertNull(OpenClProbeParser.parse("probe=ok\n"))
        assertNull(OpenClProbeParser.parse(""))
        assertNull(OpenClProbeParser.parse("ocv=1\nprobe=ok\nclc=3.0\n"))
    }

    @Test fun missingFp16IsUnsupported() {
        val result = evaluate(adrenoProbe(flags = "subgroups,qcom_reqd_subgroup_size"))
        assertEquals(OpenClCapabilityReason.FP16_MISSING, result.reason)
        assertFalse(result.controlsVisible)
    }

    @Test fun openClThreeWithoutSubgroupsIsUnsupported() {
        val result = evaluate(adrenoProbe(flags = "fp16,qcom_reqd_subgroup_size"))
        assertEquals(OpenClCapabilityReason.SUBGROUPS_MISSING, result.reason)
    }

    @Test fun oldOpenClCIsUnsupported() {
        val result = evaluate(adrenoProbe(clc = "1.2"))
        assertEquals(OpenClCapabilityReason.OPENCL_C_TOO_OLD, result.reason)
    }

    @Test fun missingQcomExtensionAdvertisementStillSupportedWhenBackendRequirementsHold() {
        // v46: the OnePlus 13 advertises family=adreno, clc=3.0, max_wg=1024
        // but its CL_DEVICE_EXTENSIONS list omits cl_qcom_reqd_sub_group_size.
        // The pinned backend does not consult that advertisement: its device
        // predicate checks family, OpenCL C, fp16, and (for 3.0) subgroups.
        // The Q8_0 kernel selects its Adreno branch from the compiler-defined
        // macro, which v43 already proved by compiling/running the flat kernel.
        // This is the exact phone case; it must not hide the controls.
        val result = evaluate(adrenoProbe(flags = "fp16,subgroups"))
        assertEquals(OpenClSupport.SUPPORTED, result.support)
        assertEquals(OpenClCapabilityReason.OK, result.reason)
        assertTrue(result.controlsVisible)
        assertFalse(result.probe!!.hasFlag("qcom_reqd_subgroup_size"))
    }

    @Test fun imageLimitsAreTheBackendGateNotTheOptionalBoolean() {
        // v45: the pinned backend decides whether an image path fits from the
        // integer limits; it never rejects a device on CL_DEVICE_IMAGE_SUPPORT.
        assertTrue(evaluate(adrenoProbe(imageSupport = 0)).supported)
        assertEquals(OpenClCapabilityReason.IMAGES_UNSUPPORTED,
            evaluate(adrenoProbe(imageMaxBuffer = 0)).reason)
        assertEquals(OpenClCapabilityReason.IMAGES_UNSUPPORTED,
            evaluate(adrenoProbe(image2d = 0)).reason)
        assertEquals(OpenClCapabilityReason.WORKGROUP_TOO_SMALL,
            evaluate(adrenoProbe(maxWorkgroup = 32)).reason)
        assertEquals(OpenClCapabilityReason.COMPILER_UNAVAILABLE,
            evaluate(adrenoProbe(compiler = 0)).reason)
    }

    @Test fun genericNonAdrenoOpenClIsNotClaimedAsSupported() {
        val generic = adrenoProbe(family = "other")
        val result = evaluate(generic)
        assertEquals(OpenClCapabilityReason.BACKEND_REQUIRES_ADRENO, result.reason)
        assertFalse(result.controlsVisible)
        assertEquals(OpenClCapabilityReason.BACKEND_REQUIRES_ADRENO,
            evaluate(adrenoProbe(family = "intel")).reason)
        assertEquals(OpenClCapabilityReason.BACKEND_REQUIRES_ADRENO,
            evaluate(adrenoProbe(family = "unknown")).reason)
    }

    @Test fun onlyTransientUnknownResultsAreRetryable() {
        assertTrue(OpenClCapabilities.transportFailure(OpenClCapabilityReason.TIMEOUT).retryable)
        assertTrue(OpenClCapabilities.transportFailure(OpenClCapabilityReason.SERVICE_DEAD).retryable)
        assertTrue(OpenClCapabilities.transportFailure(OpenClCapabilityReason.QUERY_FAILED).retryable)
        assertFalse(OpenClCapabilities.unavailable(OpenClCapabilityReason.LIBRARY_MISSING).retryable)
        assertFalse(OpenClCapabilities.unavailable(OpenClCapabilityReason.NO_GPU_DEVICE).retryable)
        assertFalse(evaluate(adrenoProbe()).retryable)
        assertFalse(evaluate(adrenoProbe(flags = "fp16,subgroups")).retryable)
    }

    @Test fun persistedEnabledPreferenceCannotBypassUnsupportedOrUnknownCapability() {
        val supported = evaluate(adrenoProbe())
        assertTrue(OpenClPolicy.runtimeEligible(true, supported, setOf("en"), false, true))
        assertFalse(OpenClPolicy.runtimeEligible(true, null, setOf("en"), false, true))
        assertFalse(OpenClPolicy.runtimeEligible(true,
            OpenClCapabilities.unsupported(OpenClCapabilityReason.FP16_MISSING),
            setOf("en"), false, true))
        assertFalse(OpenClPolicy.runtimeEligible(true,
            OpenClCapabilities.transportFailure(OpenClCapabilityReason.TIMEOUT),
            setOf("en"), false, true))
    }

    @Test fun capabilityDoesNotReplaceTheEnglish244ModelGate() {
        val supported = evaluate(adrenoProbe())
        assertFalse(OpenClPolicy.runtimeEligible(true, supported, setOf("en", "de"), false, true))
        assertFalse(OpenClPolicy.runtimeEligible(true, supported, setOf("en"), true, true))
        assertFalse(OpenClPolicy.runtimeEligible(true, supported, setOf("en"), false, false))
        assertFalse(OpenClPolicy.runtimeEligible(false, supported, setOf("en"), false, true))
    }

    @Test fun flagsAreWhitespaceTolerantButUnknownFlagsRemainOptional() {
        val probe = OpenClProbeParser.parse(
            adrenoProbe(flags = "fp16, subgroups , qcom_reqd_subgroup_size, qcom_large_buffer"))
        assertNotNull(probe)
        assertTrue(probe!!.hasFlag("fp16"))
        assertTrue(probe.hasFlag("subgroups"))
        assertTrue(probe.hasFlag("qcom_reqd_subgroup_size"))
        assertTrue(probe.hasFlag("qcom_large_buffer"))
        assertEquals(OpenClSupport.SUPPORTED, OpenClCapabilityEvaluator.evaluate(probe).support)
    }
}
