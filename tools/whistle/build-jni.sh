#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
whistle_sdk="${ANDROID_HOME:?Set ANDROID_HOME to installed SDK}"
whistle_cmake="$whistle_sdk/cmake/3.22.1/bin/cmake"
whistle_ndk="$whistle_sdk/ndk/28.0.13004108"
"$whistle_cmake" -S tools/whistle -B tools/whistle/build-android -DCMAKE_TOOLCHAIN_FILE="$whistle_ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static
"$whistle_cmake" --build tools/whistle/build-android --target cookie_whistle
python3 tools/whistle/check_abi.py "$whistle_ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" tools/whistle/build-android/libcookie_whistle.so
mkdir -p build/whistle-jni/arm64-v8a build/whistle-assets/licenses
cp tools/whistle/build-android/libcookie_whistle.so build/whistle-jni/arm64-v8a/
cp tools/whistle/LICENSE.cactus build/whistle-assets/licenses/Cactus-Whistle-Apache-2.0.txt
cp tools/whistle/provenance.json build/whistle-assets/licenses/Cactus-Whistle-provenance.json
