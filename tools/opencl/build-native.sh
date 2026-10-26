#!/usr/bin/env bash
# Build the optional OpenCL native engine from the pinned upstream sources.
#
# Outputs (both ignored by git):
#   build/opencl-jni/arm64-v8a/libfuto_opencl.so
#   build/opencl-assets/{WHISPER-OPENCL-LICENSE.txt,OPENCL-HEADERS-LICENSE.txt}
#
# The normal CPU keyboard build does not use this script. The OpenCL engine is
# opt-in and experimental; see README.md and docs/benchmark.md.
set -euo pipefail
BASE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$BASE/../.." && pwd)
BUILD="${OPENCL_BUILD_ROOT:-$ROOT/build/opencl-native}"
NDK_VERSION="${ANDROID_NDK_VERSION:-28.0.13004108}"
JOBS="${OPENCL_JOBS:-1}"

usage() {
  cat <<'EOF'
Usage: tools/opencl/build-native.sh

Builds build/opencl-jni/arm64-v8a/libfuto_opencl.so from the pinned
whisper.cpp source and the FUTO OpenCL bridge. Requires an Android SDK with
NDK 28.0.13004108 (override with ANDROID_NDK_VERSION), CMake 3.22.1+, Python 3.12+,
patch, make, curl and sha256sum.

Environment:
  ANDROID_HOME / ANDROID_SDK_ROOT   Android SDK root (required)
  ANDROID_NDK_VERSION               NDK version under $ANDROID_HOME/ndk (default: 28.0.13004108)
  OPENCL_BUILD_ROOT                 Native build directory (default: build/opencl-native)
  OPENCL_DEPS_DIR                   Directory for the pinned whisper archive (default: tools/opencl/.deps)
  WHISPER_ARCHIVE                   Pre-downloaded pinned whisper archive
  OPENCL_JOBS                       Parallel build jobs (default: 1; keeps memory bounded)
  CMAKE                             CMake executable (default: $ANDROID_HOME/cmake/3.22.1/bin/cmake)
EOF
}

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
  usage
  exit 0
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  echo "build-native.sh: set ANDROID_HOME (or ANDROID_SDK_ROOT) to the Android SDK" >&2
  exit 1
fi
NDK="$SDK/ndk/$NDK_VERSION"
if [ ! -d "$NDK" ]; then
  echo "build-native.sh: NDK not found: $NDK" >&2
  echo "Install NDK $NDK_VERSION with sdkmanager, or set ANDROID_NDK_VERSION." >&2
  exit 1
fi

if [ -n "${CMAKE:-}" ]; then
  :
elif [ -x "$SDK/cmake/3.22.1/bin/cmake" ]; then
  CMAKE="$SDK/cmake/3.22.1/bin/cmake"
else
  CMAKE=$(command -v cmake || true)
fi
[ -n "${CMAKE:-}" ] || { echo "build-native.sh: cmake not found" >&2; exit 1; }
command -v python3 >/dev/null || { echo "build-native.sh: python3 is required" >&2; exit 1; }
command -v patch >/dev/null || { echo "build-native.sh: patch is required" >&2; exit 1; }
command -v make >/dev/null || { echo "build-native.sh: make is required" >&2; exit 1; }

if [ -z "${WHISPER_ARCHIVE:-}" ]; then
  WHISPER_ARCHIVE=$(bash "$BASE/fetch-deps.sh")
fi
export WHISPER_ARCHIVE

mkdir -p "$BUILD/tmp" "$BUILD/generated"
export TMPDIR="$BUILD/tmp"

echo "Preparing pinned whisper source in $BUILD/generated" >&2
python3 "$BASE/prepare.py" "$BUILD/generated"

echo "Configuring OpenCL engine (NDK $NDK_VERSION)" >&2
"$CMAKE" -S "$ROOT/native/opencl" -B "$BUILD/android" -G 'Unix Makefiles' \
  -DCMAKE_MAKE_PROGRAM="$(command -v make)" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DDIAG_GPU=ON -DDIAG_NAME=futo_opencl \
  -DWHISPER_SOURCE="$BUILD/generated/whisper" -DGENERATED="$BUILD/generated" \
  -DHEADERS="$BASE/third_party" -DPython3_EXECUTABLE="$(command -v python3)"

echo "Building futo_opencl (-j $JOBS)" >&2
"$CMAKE" --build "$BUILD/android" --target futo_opencl -j "$JOBS"

DEST="$ROOT/build/opencl-jni/arm64-v8a"
mkdir -p "$DEST" "$ROOT/build/opencl-assets"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
"$STRIP" --strip-unneeded "$BUILD/android/libfuto_opencl.so" -o "$DEST/libfuto_opencl.so"
cp "$BUILD/generated/whisper/LICENSE" "$ROOT/build/opencl-assets/WHISPER-OPENCL-LICENSE.txt"
cp "$BASE/third_party/OpenCL-Headers-LICENSE" "$ROOT/build/opencl-assets/OPENCL-HEADERS-LICENSE.txt"

echo "Built $DEST/libfuto_opencl.so" >&2
