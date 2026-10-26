// v44 OpenCL capability probe. Metadata only: no context, queue, program,
// kernel, image, model, audio, or ggml backend initialization. This code lives
// in the optional :experimental_opencl library and is called only from that
// isolated service process.
#define CL_TARGET_OPENCL_VERSION 300
#define CL_USE_DEPRECATED_OPENCL_1_2_APIS
#include <CL/cl.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <sstream>
#include <string>
#include <vector>

#ifndef FUTO_CAPABILITY_HOST_TEST
#include <jni.h>
#endif

#if DIAG_GPU
// Defined by the generated optional-driver shim. It performs dlopen + dlsym
// checks only and returns false instead of aborting when the vendor library or
// a production symbol is unavailable.
extern "C" bool futo_opencl_available();
#endif

namespace {

constexpr const char *PROBE_VERSION = "1";

struct Api {
    void *lib = nullptr;
    decltype(&clGetPlatformIDs) getPlatformIDs = nullptr;
    decltype(&clGetPlatformInfo) getPlatformInfo = nullptr;
    decltype(&clGetDeviceIDs) getDeviceIDs = nullptr;
    decltype(&clGetDeviceInfo) getDeviceInfo = nullptr;

    bool open() {
        lib = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) return false;
        getPlatformIDs = reinterpret_cast<decltype(getPlatformIDs)>(dlsym(lib, "clGetPlatformIDs"));
        getPlatformInfo = reinterpret_cast<decltype(getPlatformInfo)>(dlsym(lib, "clGetPlatformInfo"));
        getDeviceIDs = reinterpret_cast<decltype(getDeviceIDs)>(dlsym(lib, "clGetDeviceIDs"));
        getDeviceInfo = reinterpret_cast<decltype(getDeviceInfo)>(dlsym(lib, "clGetDeviceInfo"));
        return getPlatformIDs && getPlatformInfo && getDeviceIDs && getDeviceInfo;
    }

    ~Api() { if (lib) dlclose(lib); }
};

std::string clean(const std::string &value) {
    std::string out;
    out.reserve(std::min<size_t>(value.size(), 512));
    for (unsigned char c : value) {
        if (out.size() >= 512) break;
        if (c >= 32 && c < 127 && c != '=' && c != '\n' && c != '\r') out.push_back(static_cast<char>(c));
        else out.push_back('_');
    }
    return out;
}

std::string platformString(const Api &api, cl_platform_id platform, cl_platform_info key) {
    size_t size = 0;
    if (api.getPlatformInfo(platform, key, 0, nullptr, &size) != CL_SUCCESS ||
        size == 0 || size > 64 * 1024) return {};
    std::vector<char> buffer(size + 1, '\0');
    if (api.getPlatformInfo(platform, key, size, buffer.data(), nullptr) != CL_SUCCESS) return {};
    return std::string(buffer.data());
}

std::string deviceString(const Api &api, cl_device_id device, cl_device_info key) {
    size_t size = 0;
    if (api.getDeviceInfo(device, key, 0, nullptr, &size) != CL_SUCCESS ||
        size == 0 || size > 64 * 1024) return {};
    std::vector<char> buffer(size + 1, '\0');
    if (api.getDeviceInfo(device, key, size, buffer.data(), nullptr) != CL_SUCCESS) return {};
    return std::string(buffer.data());
}

bool parseVersion(const std::string &text, int &major, int &minor) {
    const size_t opencl = text.find("OpenCL");
    if (opencl == std::string::npos) return false;
    size_t pos = text.find_first_of("0123456789", opencl);
    if (pos == std::string::npos) return false;
    char *end = nullptr;
    const long parsedMajor = std::strtol(text.c_str() + pos, &end, 10);
    if (end == text.c_str() + pos || *end != '.') return false;
    const long parsedMinor = std::strtol(end + 1, &end, 10);
    if (parsedMajor < 0 || parsedMinor < 0 || parsedMajor > 99 || parsedMinor > 99) return false;
    major = static_cast<int>(parsedMajor);
    minor = static_cast<int>(parsedMinor);
    return true;
}

bool openClCVersion(const Api &api, cl_device_id device, int platformMajor, int platformMinor,
                    int deviceMajor, int deviceMinor, int &major, int &minor) {
    if (platformMajor >= 3 && deviceMajor >= 3) {
        size_t size = 0;
        if (api.getDeviceInfo(device, CL_DEVICE_OPENCL_C_ALL_VERSIONS, 0, nullptr, &size) == CL_SUCCESS &&
            size >= sizeof(cl_name_version)) {
            std::vector<cl_name_version> versions(size / sizeof(cl_name_version));
            if (api.getDeviceInfo(device, CL_DEVICE_OPENCL_C_ALL_VERSIONS, size, versions.data(), nullptr) == CL_SUCCESS) {
                cl_version best = 0;
                for (const auto &entry : versions) best = std::max(best, entry.version);
                if (best != 0) {
                    major = static_cast<int>(CL_VERSION_MAJOR(best));
                    minor = static_cast<int>(CL_VERSION_MINOR(best));
                    return true;
                }
            }
        }
    }
    const std::string legacy = deviceString(api, device, CL_DEVICE_OPENCL_C_VERSION);
    if (legacy.empty()) return false;
    return parseVersion(legacy, major, minor);
}

enum class Family { ADRENO, INTEL, OTHER, UNKNOWN };

Family familyOf(const std::string &name, const std::string &version) {
    // Match ggml_opencl_is_device_supported: the backend inspects the device
    // name and device version, not the platform vendor or a product allowlist.
    const std::string haystack = name + "\n" + version;
    if (haystack.find("Adreno") != std::string::npos ||
        haystack.find("ADRENO") != std::string::npos ||
        haystack.find("Qualcomm") != std::string::npos ||
        haystack.find("QUALCOMM") != std::string::npos) return Family::ADRENO;
    if (haystack.find("Intel") != std::string::npos ||
        haystack.find("INTEL") != std::string::npos) return Family::INTEL;
    return Family::OTHER;
}

const char *familyName(Family family) {
    switch (family) {
        case Family::ADRENO: return "adreno";
        case Family::INTEL: return "intel";
        case Family::OTHER: return "other";
        default: return "unknown";
    }
}

bool hasToken(const std::string &list, const char *token) {
    std::istringstream input(list);
    std::string item;
    while (input >> item) if (item == token) return true;
    return false;
}

struct Candidate {
    cl_platform_id platform = nullptr;
    cl_device_id device = nullptr;
    std::string platformName;
    std::string platformVendor;
    std::string platformVersion;
    std::string name;
    std::string vendor;
    std::string version;
    Family family = Family::UNKNOWN;
};

std::string unsupportedProbe(const char *status) {
    std::ostringstream out;
    out << "ocv=" << PROBE_VERSION << "\nprobe=" << status << "\n";
    return out.str();
}

std::string probe() {
#if !DIAG_GPU
    return unsupportedProbe("library_missing");
#else
    if (!futo_opencl_available()) return unsupportedProbe("library_missing");

    Api api;
    if (!api.open()) return unsupportedProbe("library_missing");

    cl_uint platformCount = 0;
    if (api.getPlatformIDs(0, nullptr, &platformCount) != CL_SUCCESS ||
        platformCount == 0 || platformCount > 32) return unsupportedProbe("no_platform");
    std::vector<cl_platform_id> platforms(platformCount);
    if (api.getPlatformIDs(platformCount, platforms.data(), nullptr) != CL_SUCCESS)
        return unsupportedProbe("no_platform");

    std::vector<Candidate> candidates;
    for (cl_platform_id platform : platforms) {
        Candidate platformInfo;
        platformInfo.platform = platform;
        platformInfo.platformName = platformString(api, platform, CL_PLATFORM_NAME);
        platformInfo.platformVendor = platformString(api, platform, CL_PLATFORM_VENDOR);
        platformInfo.platformVersion = platformString(api, platform, CL_PLATFORM_VERSION);

        cl_uint deviceCount = 0;
        const cl_int countResult = api.getDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 0, nullptr, &deviceCount);
        if (countResult == CL_DEVICE_NOT_FOUND) continue;
        if (countResult != CL_SUCCESS || deviceCount > 32) return unsupportedProbe("query_failed");
        if (deviceCount == 0) continue;
        std::vector<cl_device_id> devices(deviceCount);
        if (api.getDeviceIDs(platform, CL_DEVICE_TYPE_GPU, deviceCount, devices.data(), nullptr) != CL_SUCCESS)
            return unsupportedProbe("query_failed");

        for (cl_device_id device : devices) {
            Candidate candidate = platformInfo;
            candidate.device = device;
            candidate.name = deviceString(api, device, CL_DEVICE_NAME);
            candidate.vendor = deviceString(api, device, CL_DEVICE_VENDOR);
            candidate.version = deviceString(api, device, CL_DEVICE_VERSION);
            if (candidate.name.empty()) return unsupportedProbe("query_failed");
            candidate.family = familyOf(candidate.name, candidate.version);
            candidates.push_back(std::move(candidate));
        }
    }
    if (candidates.empty()) return unsupportedProbe("no_gpu_device");

    auto selected = std::find_if(candidates.begin(), candidates.end(),
        [](const Candidate &candidate) { return candidate.family == Family::ADRENO; });
    if (selected == candidates.end()) selected = candidates.begin();

    const Candidate &candidate = *selected;
    int platformMajor = 0, platformMinor = 0;
    int deviceMajor = 0, deviceMinor = 0;
    parseVersion(candidate.platformVersion, platformMajor, platformMinor);
    parseVersion(candidate.version, deviceMajor, deviceMinor);
    int cMajor = 0, cMinor = 0;
    if (!openClCVersion(api, candidate.device, platformMajor, platformMinor,
                        deviceMajor, deviceMinor, cMajor, cMinor)) {
        return unsupportedProbe("query_failed");
    }

    const std::string extensions = deviceString(api, candidate.device, CL_DEVICE_EXTENSIONS);
    const std::string driver = deviceString(api, candidate.device, CL_DRIVER_VERSION);
    if (extensions.empty() || driver.empty()) return unsupportedProbe("query_failed");

    cl_bool imageSupport = CL_FALSE;
    size_t imageMaxBuffer = 0, image2dWidth = 0, image2dHeight = 0, maxWorkgroup = 0;
    cl_uint maxComputeUnits = 0;
    cl_ulong globalMemory = 0, maxAlloc = 0;
    cl_bool compilerAvailable = CL_FALSE;
    const auto queryFailed = [&](cl_int result) { return result != CL_SUCCESS; };
    if (queryFailed(api.getDeviceInfo(candidate.device, CL_DEVICE_IMAGE_SUPPORT,
                                      sizeof(imageSupport), &imageSupport, nullptr)) ||
        queryFailed(api.getDeviceInfo(candidate.device, CL_DEVICE_IMAGE_MAX_BUFFER_SIZE,
                                      sizeof(imageMaxBuffer), &imageMaxBuffer, nullptr)) ||
        queryFailed(api.getDeviceInfo(candidate.device, CL_DEVICE_IMAGE2D_MAX_WIDTH,
                                      sizeof(image2dWidth), &image2dWidth, nullptr)) ||
        queryFailed(api.getDeviceInfo(candidate.device, CL_DEVICE_IMAGE2D_MAX_HEIGHT,
                                      sizeof(image2dHeight), &image2dHeight, nullptr)) ||
        queryFailed(api.getDeviceInfo(candidate.device, CL_DEVICE_MAX_WORK_GROUP_SIZE,
                                      sizeof(maxWorkgroup), &maxWorkgroup, nullptr)) ||
        queryFailed(api.getDeviceInfo(candidate.device, CL_DEVICE_MAX_COMPUTE_UNITS,
                                      sizeof(maxComputeUnits), &maxComputeUnits, nullptr)) ||
        queryFailed(api.getDeviceInfo(candidate.device, CL_DEVICE_GLOBAL_MEM_SIZE,
                                      sizeof(globalMemory), &globalMemory, nullptr)) ||
        queryFailed(api.getDeviceInfo(candidate.device, CL_DEVICE_MAX_MEM_ALLOC_SIZE,
                                      sizeof(maxAlloc), &maxAlloc, nullptr)) ||
        queryFailed(api.getDeviceInfo(candidate.device, CL_DEVICE_COMPILER_AVAILABLE,
                                      sizeof(compilerAvailable), &compilerAvailable, nullptr))) {
        return unsupportedProbe("query_failed");
    }

    std::string flags;
    const auto flag = [&](const char *name) {
        if (!flags.empty()) flags.push_back(',');
        flags += name;
    };
    if (hasToken(extensions, "cl_khr_fp16")) flag("fp16");
    if (hasToken(extensions, "cl_khr_subgroups") || hasToken(extensions, "cl_intel_subgroups")) flag("subgroups");
    if (hasToken(extensions, "cl_qcom_reqd_sub_group_size")) flag("qcom_reqd_subgroup_size");
    if (hasToken(extensions, "cl_qcom_subgroup_shuffle")) flag("qcom_subgroup_shuffle");
    if (hasToken(extensions, "cl_khr_subgroup_shuffle")) flag("khr_subgroup_shuffle");
    if (hasToken(extensions, "cl_khr_integer_dot_product")) flag("integer_dot_product");
    if (hasToken(extensions, "cl_qcom_large_buffer")) flag("qcom_large_buffer");

    std::ostringstream out;
    out << "ocv=" << PROBE_VERSION << "\n";
    out << "probe=ok\n";
    out << "family=" << familyName(candidate.family) << "\n";
    out << "clc=" << cMajor << "." << cMinor << "\n";
    out << "flags=" << flags << "\n";
    out << "image_support=" << (imageSupport == CL_TRUE ? 1 : 0) << "\n";
    out << "image_max_buffer=" << static_cast<unsigned long long>(imageMaxBuffer) << "\n";
    out << "image2d_w=" << static_cast<unsigned long long>(image2dWidth) << "\n";
    out << "image2d_h=" << static_cast<unsigned long long>(image2dHeight) << "\n";
    out << "max_wg=" << static_cast<unsigned long long>(maxWorkgroup) << "\n";
    out << "max_cu=" << static_cast<unsigned long long>(maxComputeUnits) << "\n";
    out << "global_mb=" << static_cast<unsigned long long>(globalMemory / (1024ULL * 1024ULL)) << "\n";
    out << "alloc_mb=" << static_cast<unsigned long long>(maxAlloc / (1024ULL * 1024ULL)) << "\n";
    out << "compiler=" << (compilerAvailable == CL_TRUE ? 1 : 0) << "\n";
    out << "platform=" << clean(candidate.platformName) << "\n";
    out << "pversion=" << clean(candidate.platformVersion) << "\n";
    out << "device=" << clean(candidate.name) << "\n";
    out << "dversion=" << clean(candidate.version) << "\n";
    out << "vendor=" << clean(candidate.vendor) << "\n";
    out << "driver=" << clean(driver) << "\n";
    return out.str();
#endif
}

} // namespace

// Host tests call this directly; Android exposes the same string through JNI.
std::string futoOpenClCapabilityProbe() {
    try {
        return probe();
    } catch (...) {
        return unsupportedProbe("query_failed");
    }
}

#ifndef FUTO_CAPABILITY_HOST_TEST
extern "C" JNIEXPORT jstring JNICALL
Java_org_futo_voiceinput_shared_opencl_NativeOpenClCapability_query(JNIEnv *env, jobject) {
    const std::string value = futoOpenClCapabilityProbe();
    return env->NewStringUTF(value.c_str());
}
#endif
