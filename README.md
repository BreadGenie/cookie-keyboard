# Unofficial, non-commercial FUTO Keyboard fork

> **This is not an official FUTO release.** It is a modified, unofficial,
> non-commercial fork of [FUTO Keyboard](https://github.com/futo-org/android-keyboard)
> at `70a5d390c505a6bbcc4e14966e5628e43ca3f1fc`. It is not affiliated with,
> endorsed by, or supported by FUTO. Do not report issues from this fork to
> FUTO. No APK from this fork is signed with FUTO's official certificate.

## License and payment functionality

The upstream source is licensed under the **FUTO Source First License 1.1**
(`LICENSE.md`). This fork keeps that license: modifications and distribution
are for **non-commercial purposes only**, free of charge. The original
copyright, license, NOTICE, and attribution files are retained. Payment-related
functionality is **not removed or obscured**, and this fork does not relicense
any FUTO code. The vendored OpenCL headers are Apache-2.0 and whisper.cpp is
MIT; their notices ship with the app.

## AI provenance

The changes in this fork were produced with **AI coding assistance**, including
Sol, Muse, and GO DeepSeek Flash. Preparation and review included AI agents;
this repository does not claim a completed independent human code review.
Upstream explicitly asks contributors not to submit AI-generated pull requests,
so these changes are published here as a separate fork instead of being sent
upstream. Treat the code, tests, and measurements as an independent,
unofficial contribution.

## What this fork adds

- **CPU remains the default.** The stock FUTO CPU speech path is used unless
  OpenCL is explicitly enabled; diagnostic timing changes do not alter decode
  results.
- **Experimental OpenCL backend** (opt-in). It is built as a separate
  `libfuto_opencl.so`, loaded only when packaged and supported, and falls back
  to the stock CPU path on failure. The settings toggle is hidden unless the
  capability probe reports support.
- **Experimental "Fast" dictation** (opt-in, requires OpenCL). It uses
  encoder-only flash attention, guarded beam-1 with at most one beam-5 retry at
  threshold `-1`, and generation-verified resident model weights. It is not a
  general accuracy guarantee.
- The OpenCL library is **arm64-v8a only**; the build filters packaging to that
  ABI. This is a packaging choice, not a runtime rewrite.

### Runtime capability gate (what "supported" means)

The OpenCL controls appear only when the probe finds the optional library and
the pinned backend's requirements hold: Adreno family, OpenCL C >= 2.0,
`cl_khr_fp16`, OpenCL 3.0 subgroups when applicable, usable image limits,
max workgroup >= 64, and an available compiler. The QCOM required-subgroup
*advertisement* is diagnostic only; the backend does not require it. Transport
failures get a bounded retry. A device that passes metadata but fails kernel
compilation takes the existing managed CPU fallback; it is not silently
presented as working OpenCL.

## Measured performance (selected public results)

On a OnePlus CPH2649 with the user-supplied FUTO English-244 model, three
counterbalanced rounds of public JFK speech gave these **warm adapter API
walls** (including request overhead, excluding preparation and the separate
full-shape warmup):

| Workload | CPU beam5 | OpenCL beam5 | Fast | CPU / OpenCL | CPU / Fast |
|---|---:|---:|---:|---:|---:|
| 12 s | 3.284 s | 1.391 s | 0.407 s | 2.36× | 8.07× |
| 60 s | 36.147 s | 13.175 s | 3.431 s | 2.74× | 10.54× |

These are **not** stop-to-editor commit measurements, p95 latency, energy
measurements, or held-out WER. The 60-second fixture repeats the public JFK
clip with silence; it is not 60 seconds of distinct speech. Fast is a
whole-configuration comparison (lighter decoding policy and warm residency),
not a pure GPU speedup. Digital-silence skipping is a policy shortcut, not GPU
inference speed. See [`docs/benchmark.md`](docs/benchmark.md) for provenance,
fixtures, limitations, and the full source notes.

## Building

A recursive clone is required for upstream submodules:

```sh
git clone --recursive https://github.com/BreadGenie/cookie-keyboard.git
cd cookie-keyboard
# or, if already cloned:
git submodule update --init --recursive
```

### Debug build (CPU default)

```sh
./gradlew assembleUnstableDebug
```

Open the project in Android Studio, or use the Gradle command above. The
default build does **not** run the experimental native build.

### Debug build with the experimental OpenCL library

Requirements: Android SDK with NDK `28.0.13004108`, CMake 3.22.1+, Python
3.12+, `patch`, `make`, `curl`, `sha256sum`, and `ANDROID_HOME` (or
`ANDROID_SDK_ROOT`) set.

```sh
bash tools/opencl/build-native.sh
./gradlew assembleUnstableDebug -PexperimentalOpenClNative=true \
  -x prepareExperimentalOpenCl
```

The script downloads the pinned whisper.cpp source into
`tools/opencl/.deps/` (ignored) and verifies its SHA-256. It writes
`build/opencl-jni/arm64-v8a/libfuto_opencl.so` and the third-party license
assets. `tools/opencl/README.md` documents the dependency closure, pins, and
environment overrides. A first run needs network access; models and driver
binaries are never downloaded or bundled.

### Release signing — use your own key

Builds from this fork must **not** use FUTO's official signing key. Create your
own keystore and a local (untracked) `keystore.properties`:

```properties
storeFile=/absolute/path/to/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then run `./gradlew assembleStableRelease`. Without `keystore.properties`,
the current Gradle configuration falls back to the shared debug signing key;
provide your own release keystore before distributing a release APK. Keep the keystore and properties
out of version control. The official FUTO fingerprints printed later in this
file do not apply to builds from this repository, and a build from this fork
must not be presented as an official FUTO APK.

## Upstream project

The original project description, contribution rules, and official signing
fingerprints follow below. They describe upstream FUTO, not this fork.

# FUTO Keyboard

The goal is to make a good modern keyboard that stays offline and doesn't spy on you. This keyboard is a fork of [LatinIME, The Android Open-Source Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME), with significant changes made to it.

Check out the [FUTO Keyboard website](https://keyboard.futo.tech/) for downloads and more information.

The code is licensed under the [FUTO Source First License 1.1](LICENSE.md).

## Issue tracking and contributing

Please check the GitHub repository to report issues: [https://github.com/futo-org/android-keyboard/](https://github.com/futo-org/android-keyboard/)

The source code is hosted on our [internal GitLab](https://gitlab.futo.org/keyboard/latinime) and mirrored to [GitHub](https://github.com/futo-org/android-keyboard/). As registration is closed on our internal GitLab, we use GitHub instead for issues and pull requests.

If you want to help translate the app, please do so via our Pontoon instance: https://i18n-keyboard.futo.org/

Due to custom license, pull requests to this repository require signing a [CLA](https://cla.futo.org/) which you can do after opening a PR. Contributions to the [layouts repo](https://github.com/futo-org/futo-keyboard-layouts) don't require CLA as they're Apache-2.0

Please do not submit AI-generated pull requests, as these tend to make the codebase more difficult to understand. Pull requests made with clear heavy use of AI may be closed without comment. Please also avoid using AI to write issues, and try to explain it in your own words instead.

## Layouts

If you want to contribute layouts, check out the [layouts repo](https://github.com/futo-org/futo-keyboard-layouts).

## Building

When cloning the repository, you must perform a recursive clone to fetch all dependencies:
```
git clone --recursive https://gitlab.futo.org/keyboard/latinime.git
```

If you forgot to specify recursive clone, use this to fetch submodules:
```
git submodule update --init --recursive
```

You can then open the project in Android Studio and build it that way, or use gradle commands:
```
./gradlew assembleUnstableDebug
./gradlew assembleStableRelease
```

## APK signing

For official FUTO Keyboard versions, you can verify the APK's signing key fingerprint for integrity.

```
Signing key fingerprint for all versions except Google Play:

MD5: 3A:BB:71:C6:BB:E4:92:27:B1:E3:5D:81:01:48:6A:B0
SHA1: 5D:15:B3:6E:C9:6A:96:28:41:09:DD:62:93:0D:9C:39:9F:5F:06:43
SHA-256: 74:3F:AD:58:64:AB:C4:26:50:0B:2D:C2:C4:7C:8A:D3:24:CB:CD:16:03:3F:80:16:99:48:41:35:63:74:F9:95

```
# Cookie signed release candidate (v56)

The active Cookie candidate is `cookieRelease`, version `0.1.0-rc1` (code 56),
package `io.github.breadgenie.cookie`. It includes the current v55 branding and
settings work, with R8 minification and `debuggable=false`. It is a candidate
awaiting OnePlus installation, IME binding, typing, dictation, and navigation
acceptance. It installs beside the debug and official FUTO apps; settings and
downloaded models do not migrate automatically. Enable and select Cookie in
Android's keyboard settings, grant microphone access, and provision its models.

Use the existing local toolchain from the parent workspace:

```sh
source ../scripts/env.sh
export COOKIE_SIGNING_PROPERTIES="$HOME/.local/share/cookie/signing/keystore.properties"
./gradlew --offline --max-workers=2 assembleCookieRelease -PexperimentalOpenClNative=true
```

This ordinary command builds native code. To reproduce v56 with the proven v55
native bytes already present in this workspace, first verify all nine libraries
and both OpenCL license assets, then stage just the stock JNI library:

```sh
python3 tools/release/verify-native-reuse.py
./gradlew --offline --max-workers=2 assembleCookieRelease \
  -PexperimentalOpenClNative=true -x prepareExperimentalOpenCl \
  -PcookieVerifiedNativeDir=build/cookie-release-native
```

Do not skip native work without running the verifier successfully. The final
APK must match all nine baseline native hashes. The output is
`build/outputs/apk/cookie/release/futo-keyboard-cookie-release.apk`.
Release builds require private signing and reject the upstream shared debug
certificate. Missing configuration fails the release prebuild; debug builds
continue to use the upstream debug key without needing private configuration.
`-PcookieSigningProperties=/absolute/private/path/keystore.properties` also
selects the config. A local ignored `keystore.properties` is the final fallback.
The properties are `storeFile` (absolute key path), `storePassword`, `keyAlias`,
and `keyPassword`. Never put their values in shell arguments, logs, or Git.

The owner's RSA-4096 key is stored privately at
`~/.local/share/cookie/signing/cookie-release.p12`, alias `cookie`; its passwords
are in `~/.local/share/cookie/signing/keystore.properties`. The directory is mode
0700 and both files are mode 0600. **Back up both files together to private,
secure storage.** Losing the key/password prevents compatible future updates;
keep the same key for every later Cookie release. Do not regenerate it on a
failed build, put it in an artifact directory, or upload it with an APK.

Cookie release certificate SHA-256 (public):
`8244d1c4361f4458d038cb4662dd72572172e85a1c7b2d1e8828908f3936b661`.

Cookie keeps FUTO's licensing, payment notices, and attribution. Its payment
deep links use `cookie-keyboard` / `cookie-voice-input` to avoid claiming FUTO's
callbacks; the upstream checkout and manual license entry remain available.
Cookie does not offer upstream APK updates. ACRA is disabled in release builds
and no crash-report credentials are embedded. CPU remains the default;
experimental OpenCL and guarded Fast dictation retain their v55 behavior.
Benchmark activities, comparison fixtures, and debug agents are excluded from
the release source sets. Phone/performance acceptance remains pending.
