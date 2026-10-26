# OpenCL engine build tools

Build support for the optional, experimental OpenCL backend of this fork. The
default keyboard build uses the stock FUTO CPU runtime and does not run
anything in this directory.

## What this builds

`build-native.sh` produces:

- `build/opencl-jni/arm64-v8a/libfuto_opencl.so`
- `build/opencl-assets/WHISPER-OPENCL-LICENSE.txt`
- `build/opencl-assets/OPENCL-HEADERS-LICENSE.txt`

Gradle includes those paths only when `-PexperimentalOpenClNative=true` is set.
Without that property, the OpenCL library is not packaged and the runtime
capability gate reports `not_packaged`; the keyboard stays on CPU.

## Dependencies

Required on the build host:

- Android SDK with **NDK 28.0.13004108** (override with `ANDROID_NDK_VERSION`)
- CMake 3.22.1 or newer (the SDK copy is used when present)
- Python 3.12+, `patch`, `make`, `curl`, `sha256sum`
- `ANDROID_HOME` or `ANDROID_SDK_ROOT`

Fetched dependencies:

- whisper.cpp `231638a0717b7822784297e5980965ffcec05810`, SHA-256
  `f69100f71ce6c815624994362cd8a8eaaa26d678e5ce518ed35900d4b679e721`.
  `fetch-deps.sh` downloads it into `.deps/` (ignored) and verifies the hash.
- Khronos OpenCL-Headers `4ea6df132107e3b4b9407f903204b5522fdffcd6`
  (`v2024.10.24`), vendored under `third_party/CL` with its Apache-2.0
  license. See `third_party/README.md`.

The FUTO English-244 model is **not** downloaded or bundled. The app accepts a
user-supplied model file and verifies its pinned SHA-256 at runtime.

## Usage

Run these commands from the repository root. The build script currently uses
the Linux x86_64 NDK host tools.

```sh
export ANDROID_HOME=/path/to/android-sdk
bash tools/opencl/fetch-deps.sh      # optional; build-native.sh also calls it
bash tools/opencl/build-native.sh

./gradlew assembleUnstableDebug -PexperimentalOpenClNative=true \
  -x prepareExperimentalOpenCl
```

The `-x prepareExperimentalOpenCl` form packages the library already produced
above. Omitting it lets Gradle run `build-native.sh` through the
`prepareExperimentalOpenCl` task.

`build-native.sh --help` and `fetch-deps.sh --help` list environment
overrides (`OPENCL_BUILD_ROOT`, `OPENCL_DEPS_DIR`, `WHISPER_ARCHIVE`,
`OPENCL_JOBS`, `CMAKE`).

## How the pinned source is produced

`prepare.py` extracts the pinned archive into the build directory (never into
the source tree), applies `patches/opencl-buffer-metadata.patch`, then applies
the FUTO product patches and the narrow debug instrumentation in `readback/`.
The result is a generated tree under `$OPENCL_BUILD_ROOT/generated/whisper`.
The script refuses to reuse a generated tree from a different product revision;
delete the build root or set a fresh `OPENCL_BUILD_ROOT` when changing the
patch set.

The generated tree contains the FUTO token callback, session timings,
embedding mirror, encoder-only flash-attention split, persistent kernel cache
diagnostics, and nonfatal cached lazy-FA builds. These are the same code paths
used by the `0.0.46` capability-gate source, not a separate experiment.

## License and provenance

The build tools are additions to an unofficial, non-commercial fork of FUTO
Keyboard. They are distributed under the same FUTO Source First License 1.1 as
the rest of the modified source; no relicensing is intended. whisper.cpp is
MIT and OpenCL-Headers is Apache-2.0; their notices are retained and copied
into the packaged assets. This directory contains no compiled binaries,
models, APKs, keystores, or downloaded build trees.
