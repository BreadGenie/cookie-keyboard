package org.futo.voiceinput.shared.opencl

/**
 * v44 capability gate: availability of the packaged OpenCL engine on this
 * device/driver, deliberately separate from model/language eligibility and from
 * any inference result. Android-free so the decision is testable off-device.
 */
enum class OpenClSupport { SUPPORTED, UNAVAILABLE, UNSUPPORTED, UNKNOWN }

enum class OpenClCapabilityReason {
    OK,
    LIBRARY_MISSING,
    NOT_PACKAGED,
    NO_PLATFORM,
    NO_GPU_DEVICE,
    QUERY_FAILED,
    SERVICE_DEAD,
    TIMEOUT,
    INTERNAL,
    BACKEND_REQUIRES_ADRENO,
    OPENCL_C_TOO_OLD,
    FP16_MISSING,
    SUBGROUPS_MISSING,
    IMAGES_UNSUPPORTED,
    WORKGROUP_TOO_SMALL,
    COMPILER_UNAVAILABLE,
}

enum class OpenClDeviceFamily { ADRENO, INTEL, OTHER, UNKNOWN }

enum class OpenClProbeStatus {
    OK,
    LIBRARY_MISSING,
    NOT_PACKAGED,
    NO_PLATFORM,
    NO_GPU_DEVICE,
    QUERY_FAILED,
}

/**
 * Raw, content-free metadata from the isolated service. No path, audio,
 * transcript, model name, preference, or account data crosses this seam.
 */
data class OpenClProbe(
    val status: OpenClProbeStatus,
    val family: OpenClDeviceFamily = OpenClDeviceFamily.UNKNOWN,
    val openClCMajor: Int = 0,
    val openClCMinor: Int = 0,
    val flags: Set<String> = emptySet(),
    val imageSupport: Boolean = false,
    val imageMaxBuffer: Long = 0L,
    val image2dWidth: Int = 0,
    val image2dHeight: Int = 0,
    val maxWorkgroup: Int = 0,
    val maxComputeUnits: Int = 0,
    val globalMemoryMb: Long = 0L,
    val maxAllocMb: Long = 0L,
    val compilerAvailable: Boolean = false,
    val platform: String = "",
    val device: String = "",
    val vendor: String = "",
    val driver: String = "",
    val deviceVersion: String = "",
) {
    val openClCText: String get() = "$openClCMajor.$openClCMinor"
    fun hasFlag(name: String): Boolean = flags.contains(name)
}

data class OpenClCapabilities(
    val support: OpenClSupport,
    val reason: OpenClCapabilityReason,
    val probe: OpenClProbe? = null,
) {
    val supported: Boolean get() = support == OpenClSupport.SUPPORTED
    /** Settings must not show an OpenCL toggle unless the backend is actually supported. */
    val controlsVisible: Boolean get() = supported

    /**
     * v45: only transport/probe failures are worth a bounded retry. A device
     * that answered with UNSUPPORTED/UNAVAILABLE will answer the same way; a
     * cold service start or a recycled request can produce UNKNOWN once.
     */
    val retryable: Boolean
        get() = support == OpenClSupport.UNKNOWN &&
            (reason == OpenClCapabilityReason.TIMEOUT ||
                reason == OpenClCapabilityReason.SERVICE_DEAD ||
                reason == OpenClCapabilityReason.QUERY_FAILED ||
                reason == OpenClCapabilityReason.INTERNAL)

    /** Fixed labels only: safe for the existing diagnostics copy/download path. */
    fun diagnostic(): String = buildString {
        append("opencl_capability status=").append(support.name.lowercase())
        append(" reason=").append(reason.name.lowercase())
        if (probe != null) {
            append(" family=").append(probe.family.name.lowercase())
            append(" clc=").append(probe.openClCText)
            append(" max_wg=").append(probe.maxWorkgroup)
        }
    }

    companion object {
        fun transportFailure(reason: OpenClCapabilityReason): OpenClCapabilities =
            OpenClCapabilities(OpenClSupport.UNKNOWN, reason, null)

        fun unavailable(reason: OpenClCapabilityReason): OpenClCapabilities =
            OpenClCapabilities(OpenClSupport.UNAVAILABLE, reason, null)

        fun unsupported(reason: OpenClCapabilityReason): OpenClCapabilities =
            OpenClCapabilities(OpenClSupport.UNSUPPORTED, reason, null)
    }
}

object OpenClCapabilityProtocol {
    const val VERSION = 1
    const val MAX_PROBE_CHARS = 4096
    const val NOT_PACKAGED_PROBE = "ocv=1\nprobe=not_packaged\n"
    const val QUERY_FAILED_PROBE = "ocv=1\nprobe=query_failed\n"
}

/**
 * Parses the native key=value contract. Unknown/missing required fields fail to
 * [OpenClProbeStatus.QUERY_FAILED] rather than guessing support.
 */
object OpenClProbeParser {
    fun parse(raw: String?): OpenClProbe? {
        if (raw == null || raw.isEmpty() || raw.length > OpenClCapabilityProtocol.MAX_PROBE_CHARS) {
            return null
        }
        val fields = HashMap<String, String>()
        raw.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val split = line.indexOf('=')
            if (split <= 0) return null
            fields[line.substring(0, split).trim()] = line.substring(split + 1).trim()
        }
        if (fields["ocv"] != OpenClCapabilityProtocol.VERSION.toString()) return null

        val status = when (fields["probe"]) {
            "ok" -> OpenClProbeStatus.OK
            "library_missing" -> OpenClProbeStatus.LIBRARY_MISSING
            "not_packaged" -> OpenClProbeStatus.NOT_PACKAGED
            "no_platform" -> OpenClProbeStatus.NO_PLATFORM
            "no_gpu_device", "no_device" -> OpenClProbeStatus.NO_GPU_DEVICE
            "query_failed" -> OpenClProbeStatus.QUERY_FAILED
            else -> return null
        }
        if (status != OpenClProbeStatus.OK) return OpenClProbe(status)

        val clc = parseVersion(fields["clc"]) ?: return null
        val imageSupport = parseBoolean(fields["image_support"]) ?: return null
        val imageMaxBuffer = parseLong(fields["image_max_buffer"]) ?: 0L
        val image2dWidth = parseLong(fields["image2d_w"])?.toInt() ?: return null
        val image2dHeight = parseLong(fields["image2d_h"])?.toInt() ?: return null
        val maxWorkgroup = parseLong(fields["max_wg"])?.toInt() ?: return null
        val compilerAvailable = parseBoolean(fields["compiler"]) ?: return null
        val flags = fields["flags"].orEmpty()
            .split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        return OpenClProbe(
            status = status,
            family = when (fields["family"]) {
                "adreno" -> OpenClDeviceFamily.ADRENO
                "intel" -> OpenClDeviceFamily.INTEL
                "other" -> OpenClDeviceFamily.OTHER
                else -> OpenClDeviceFamily.UNKNOWN
            },
            openClCMajor = clc.first,
            openClCMinor = clc.second,
            flags = flags,
            imageSupport = imageSupport,
            imageMaxBuffer = imageMaxBuffer,
            image2dWidth = image2dWidth,
            image2dHeight = image2dHeight,
            maxWorkgroup = maxWorkgroup,
            maxComputeUnits = parseLong(fields["max_cu"])?.toInt() ?: 0,
            globalMemoryMb = parseLong(fields["global_mb"]) ?: 0L,
            maxAllocMb = parseLong(fields["alloc_mb"]) ?: 0L,
            compilerAvailable = compilerAvailable,
            platform = fields["platform"].orEmpty(),
            device = fields["device"].orEmpty(),
            vendor = fields["vendor"].orEmpty(),
            driver = fields["driver"].orEmpty(),
            deviceVersion = fields["dversion"].orEmpty(),
        )
    }

    private fun parseVersion(value: String?): Pair<Int, Int>? {
        if (value == null) return null
        val major = value.substringBefore('.', "").toIntOrNull() ?: return null
        val minor = value.substringAfter('.', "").substringBefore('.').toIntOrNull() ?: return null
        if (major < 0 || minor < 0) return null
        return major to minor
    }

    private fun parseBoolean(value: String?): Boolean? = when (value) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }

    private fun parseLong(value: String?): Long? = value?.toLongOrNull()
}

/**
 * Applies the exact requirements the pinned build's kernels expose. This is not
 * a model or speed check: SUPPORTED only says the compiled backend can accept
 * this device. Missing optional extensions remain optional.
 */
object OpenClCapabilityEvaluator {
    fun evaluate(probe: OpenClProbe): OpenClCapabilities {
        when (probe.status) {
            OpenClProbeStatus.LIBRARY_MISSING ->
                return OpenClCapabilities.unavailable(OpenClCapabilityReason.LIBRARY_MISSING)
            OpenClProbeStatus.NOT_PACKAGED ->
                return OpenClCapabilities.unavailable(OpenClCapabilityReason.NOT_PACKAGED)
            OpenClProbeStatus.NO_PLATFORM ->
                return OpenClCapabilities.unavailable(OpenClCapabilityReason.NO_PLATFORM)
            OpenClProbeStatus.NO_GPU_DEVICE ->
                return OpenClCapabilities.unavailable(OpenClCapabilityReason.NO_GPU_DEVICE)
            OpenClProbeStatus.QUERY_FAILED ->
                return OpenClCapabilities.transportFailure(OpenClCapabilityReason.QUERY_FAILED)
            OpenClProbeStatus.OK -> Unit
        }

        fun unsupported(reason: OpenClCapabilityReason) =
            OpenClCapabilities(OpenClSupport.UNSUPPORTED, reason, probe)

        // The build enables GGML_OPENCL_USE_ADRENO_KERNELS (ON by default), and
        // device support drops every non-Adreno family after that compile gate.
        if (probe.family != OpenClDeviceFamily.ADRENO) {
            return unsupported(OpenClCapabilityReason.BACKEND_REQUIRES_ADRENO)
        }
        if (probe.openClCMajor < 2) {
            return unsupported(OpenClCapabilityReason.OPENCL_C_TOO_OLD)
        }
        if (!probe.hasFlag("fp16")) {
            return unsupported(OpenClCapabilityReason.FP16_MISSING)
        }
        if (probe.openClCMajor == 3 && !probe.hasFlag("subgroups")) {
            return unsupported(OpenClCapabilityReason.SUBGROUPS_MISSING)
        }
        // v46: do not gate on the cl_qcom_reqd_sub_group_size advertisement.
        // The pinned backend's device predicate
        // (ggml_opencl_is_device_supported, ggml-opencl.cpp:6760-6831) checks
        // only family, OpenCL C >= 2.0, cl_khr_fp16, and (for 3.0) subgroups.
        // The Q8_0 kernel selects its Adreno branch from the compiler-defined
        // macro (#elif defined(cl_qcom_reqd_sub_group_size),
        // mul_mv_q8_0_f32_flat.cl:14-19), not from CL_DEVICE_EXTENSIONS; some
        // Adreno drivers omit the advertisement while the compiler accepts the
        // attribute. v43 compiled/ran the flat kernel on this same phone, while
        // v45's probe showed the token absent. The flag remains diagnostic.
        // A device without compiler support fails at backend kernel build and
        // takes the existing managed stock-CPU fallback; it is not silently
        // claimed as a supported runtime. See v46 source proof.
        // v45: the pinned backend never rejects a device on CL_DEVICE_IMAGE_SUPPORT;
        // it uses the image limits to decide whether the image kernels fit and
        // falls back to buffer kernels when they do not. Keep the limit check the
        // backend actually consults, but do not turn its optional boolean into a
        // hard gate. v43 ran the Q8_0 model on hardware whose boolean was never
        // part of its acceptance decision.
        if (probe.imageMaxBuffer <= 0L ||
            probe.image2dWidth <= 0 || probe.image2dHeight <= 0) {
            return unsupported(OpenClCapabilityReason.IMAGES_UNSUPPORTED)
        }
        if (probe.maxWorkgroup < 64) {
            return unsupported(OpenClCapabilityReason.WORKGROUP_TOO_SMALL)
        }
        if (!probe.compilerAvailable) {
            return unsupported(OpenClCapabilityReason.COMPILER_UNAVAILABLE)
        }
        return OpenClCapabilities(OpenClSupport.SUPPORTED, OpenClCapabilityReason.OK, probe)
    }
}
