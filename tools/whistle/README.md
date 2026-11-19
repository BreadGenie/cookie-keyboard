# Whistle native foundation (developer-only)

Independent native build; debug Cookie/unstable builds now wire JNI and a private `:whistle`
service. There is no product model selection or editor integration.
Cactus Compute Whistle weights 2.0.0, runtime 4.0.0. `provision.py` contains
immutable HF revisions, sizes and SHA-256 pins, including manifest and licenses.
The official header is copied byte-for-byte in `native/cactus.h` and the Apache
license in `LICENSE.cactus`. No NOTICE is listed by the pinned runtime manifest.
Model LICENSE is independently fetched and hash-checked. Preserve licenses in
any future distribution; engine source rebuild provenance is still unverified.

From repository root (set SDK path to the installed SDK):

```bash
python3 tools/whistle/provision.py --host
export WHISTLE_SDK=/home/t3code/.local/share/cookie-android/sdk
export WHISTLE_CMAKE="$WHISTLE_SDK/cmake/3.22.1/bin/cmake"
export WHISTLE_NDK="$WHISTLE_SDK/ndk/28.0.13004108"
"$WHISTLE_CMAKE" -S tools/whistle -B tools/whistle/build-android \
  -DCMAKE_TOOLCHAIN_FILE="$WHISTLE_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static
"$WHISTLE_CMAKE" --build tools/whistle/build-android --target cookie_whistle real_smoke
python3 tools/whistle/check_abi.py \
  "$WHISTLE_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" \
  tools/whistle/build-android/libcookie_whistle.so
"$WHISTLE_CMAKE" -S tools/whistle -B tools/whistle/build-tests \
  -DCMAKE_CXX_COMPILER=/usr/bin/g++ -DWHISTLE_TESTS=ON
"$WHISTLE_CMAKE" --build tools/whistle/build-tests --target adapter_test
"$WHISTLE_SDK/cmake/3.22.1/bin/ctest" --test-dir tools/whistle/build-tests --output-on-failure
```

Omit `--host` to download only Android artifacts/model. Downloads are explicit
local developer operations; no provisioning is triggered by the build/product.
`.cache/`, build trees and weights are ignored. Configuring checks the archive,
header and license hashes. Native load checks size and SHA-256 again on a private
retained copy before the runtime sees the model.

`real_smoke MODEL FLOAT32_PCM` is an Android executable link target: little-endian
float32 mono 16 kHz input, bounded 16000-sample calls, private JSON lines on stdout,
one final Stop. It has NOT been run on Android. The official Linux CLI was run
against the repository's 11-second WAV fixture. The available host C++ runtimes
cannot link the official Linux static archive (`std::__1::__hash_memory` missing
under Zig; GNU libstdc++ cannot resolve libc++ symbols). Do not use the custom host
shared/smoke targets as validated runtime proof. Fake-runtime tests use GCC and
exercise the same adapter source. No C++ toolchain dependencies were installed.

## Ownership contract

Exactly one generation/model/session per OS process, one serial worker thread.
The adapter uses try-lock serialization and has zero queued PCM; the future
producer must wait for acknowledgment or fail explicitly on backlog, never drop.
Each call is at most 16000 finite samples in [-1,1]. Output buffer is 256 KiB;
negative status, reported overflow, missing terminator, malformed JSON, invalid
UTF-8, insufficient capacity or invalid state poisons the session. No retry of a
mutating process/Stop call. It returns checked opaque JSON, not transcript text;
the IPC consumer MUST validate schema and accumulated text limits before use.
No accumulated transcript or editor API exists here. A successful finish may
follow zero feed calls; phone silence/empty tests remain required.

Pointers remain caller-owned through return; output must not alias input. The
retained model copy lives until process death because the header does not specify
whether load borrows bytes. The adapter has a 64 KiB PCM caller bound and fixed
256 KiB native output, but the opaque engine's internal memory bound is unproven.
No duration cap is added. Resource exhaustion must fail the session explicitly.

Cancel is the only concurrent method: atomically invalidate the generation.
It never calls Stop/reset, takes the inference lock, or promises native interruption.
In-flight results are discarded. Caller must independently invalidate/recheck
its generation at consumption to cover cancellation after native return. Finish
flushes once; double finish, feed after finish and reload fail closed. Reset is
never exposed/called: the header gives no reset/unload ownership guarantee.

## Required service integration (next phase)

Add a non-exported same-UID service with `android:process=":whistle"`; do not use
Android `isolatedProcess=true` without solving verified private-model FD access.
Load this library only there, never in the IME. One executor handles load/feed/
finish in sequence. Pass a verified read-only model FD; native re-verifies bytes.
Binder messages carry session generation plus monotonic PCM sequence; one bounded
chunk can be outstanding. Stop drains already accepted sequence numbers then
calls finish once. Validate all JSON schema/types/limits before accumulating.
Bound transcript/backlog resources and report exhaustion, never silently truncate.

Parent cancellation first invalidates delivery generation. A separate Binder
control thread in the worker invalidates native generation and kills **its own**
process with `Process.killProcess(Process.myPid())`, without waiting on inference.
Use `IBinder.DeathRecipient` to confirm death before starting a replacement; do
not treat unbind/stopService/coroutine cancellation as proof of process exit.
Apply the same reclamation after success/error. Unexpected death fails the pending
session; no automatic retry/fallback. If death cannot be confirmed, keep Whistle
unavailable and surface failure. No reuse/reset of an opaque dirty runtime.

Before opt-in wiring, prove this lifecycle on Android, including cancellation
while load/process/Stop blocks, worker death, editor changes, long audio, offline
operation and memory/backlog. Only final text can later pass through the existing
VoiceResultDelivery and editor transaction checks. No results here authorize any
editor write. Existing Whisper behavior/defaults remain owned by their code.

## Debug service validation (2026-11-07)

Read `.scratch/whistle-model/service-review.md` in the workspace root for lifecycle
limits and required phone gates. `src/debug/.../whistle` owns the Android boundary;
no Whistle classes or service are present in release. Internal settings Diagnostics
opens the debug validation screen. It imports a verified private model explicitly,
feeds one second of zero PCM and flushes, or cancels/reclaims/restarts. It displays
content-free status/counts, never text and never editor/history insertion.

Reproduce from workspace root with installed SDK/JDK/Gradle cache paths:

```bash
python3 futo-keyboard/tools/whistle/stage-debug-baseline.py
export ANDROID_HOME=/home/t3code/.local/share/cookie-android/sdk
export JAVA_HOME=/home/t3code/.local/share/cookie-android/jdk-17.0.20.1+1
export VERSION_CODE=57 VERSION_NAME=0.0.57-whistle-validation
export GRADLE_USER_HOME=/home/t3code/.local/share/cookie-android/gradle-cache
/home/t3code/.local/share/cookie-android/gradle-8.11.1/bin/gradle -p futo-keyboard \
  --offline --no-daemon --max-workers=1 \
  -PcookieSigningProperties=/tmp/whistle-no-signing-properties \
  -PcookieVerifiedNativeDir=build/cookie-release-native \
  -Pkotlin.compiler.execution.strategy=in-process \
  compileUnstableDebugKotlin testUnstableDebugUnitTest :voiceinput-shared:testDebugUnitTest
# Only after tests pass, and rechecking locally unused version:
# same Gradle flags and version environment + assembleUnstableDebug
```

The signing-properties path above must not exist. This avoids reading release
properties during debug configuration. It does not change release signing setup.
`stage-debug-baseline.py` requires the existing pinned v55 APK/evidence in .scratch;
no native Whisper rebuild or download occurs. `build-jni.sh` checks cached pinned
Cactus runtime/header/license before staging JNI. Provisioning remains an explicit
separate developer action; ordinary debug builds fail if the archive is missing.
Only debug JNI/assets source sets receive Whistle; weights are never staged.
