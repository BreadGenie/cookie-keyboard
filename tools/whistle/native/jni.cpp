#include "adapter.h"
#include "model_fd.h"
#include <jni.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
#include <array>

// No Java strings/Modified UTF-8: adapter-validated UTF-8 crosses as bounded bytes.
extern "C" JNIEXPORT jboolean JNICALL
Java_org_futo_inputmethod_latin_whistle_WhistleNative_load(JNIEnv*, jobject, jlong g, jint fd) {
    if (g <= 0) return JNI_FALSE;
    try {
        std::vector<unsigned char> bytes;
        if (!cookie_read_model_fd(fd, bytes)) return JNI_FALSE;
        return cookie_whistle_load(static_cast<uint64_t>(g), bytes.data(), bytes.size()) == 0;
    } catch (...) { return JNI_FALSE; }
}
namespace {
jbyteArray result(JNIEnv* env, int n, const char* out) {
    if (n <= 0 || n >= 262144) return nullptr;
    auto bytes = env->NewByteArray(n);
    if (bytes) env->SetByteArrayRegion(bytes, 0, n, reinterpret_cast<const jbyte*>(out));
    return bytes;
}
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_futo_inputmethod_latin_whistle_WhistleNative_feed(JNIEnv* env, jobject, jlong g, jfloatArray pcm) {
    if (!pcm || g <= 0) return nullptr;
    jsize n = env->GetArrayLength(pcm);
    if (n < 1 || n > 16000) return nullptr;
    std::array<float, 16000> input{};
    env->GetFloatArrayRegion(pcm, 0, n, input.data());
    if (env->ExceptionCheck()) return nullptr;
    std::array<char, 262144> out{};
    int status = cookie_whistle_feed(g, input.data(), n, out.data(), out.size());
    return result(env, status, out.data());
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_futo_inputmethod_latin_whistle_WhistleNative_finish(JNIEnv* env, jobject, jlong g) {
    if (g <= 0) return nullptr;
    std::array<char, 262144> out{};
    int status = cookie_whistle_finish(g, out.data(), out.size());
    return result(env, status, out.data());
}
