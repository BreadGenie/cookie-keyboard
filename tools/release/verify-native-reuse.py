#!/usr/bin/env python3
"""Prove the v55 native baseline before staging its stock JNI for Cookie release.

This never copies the debug DEX, manifest, resources, or benchmark fixtures.
The final APK must also be audited: AAR natives are merged/stripped by Gradle.
"""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile

repo = Path(__file__).resolve().parents[2]
baseline = repo.parent / '.scratch/futo-opencl/settings-cleanup/v55'
apk = baseline / 'futo-settings-cleanup-v55.apk'
sha = lambda b: hashlib.sha256(b).hexdigest()
assert sha(apk.read_bytes()) == 'd06aef00132118c49d200df551678e81d5acccb97e5b90484e2f8afe7bbb7056'
expected = json.loads((baseline / 'native-reuse-proof.json').read_text())['all_nine_stripped_native_bytes_identical_v54']
assert len(expected) == 9
stripped = repo / 'build/intermediates/stripped_native_libs/unstableDebug/stripUnstableDebugDebugSymbols/out'
with zipfile.ZipFile(apk) as z:
    assert set(expected) == {n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')}
    for name, digest in expected.items():
        assert sha(z.read(name)) == digest, name
        assert sha((stripped / name).read_bytes()) == digest, name
    merged_assets = repo / 'build/intermediates/assets/unstableDebug/mergeUnstableDebugAssets'
    baseline_assets = {n for n in z.namelist() if n.startswith('assets/') and not n.endswith('/')}
    assert len(baseline_assets) == 219
    for name in baseline_assets:
        assert (merged_assets / name.removeprefix('assets/')).read_bytes() == z.read(name), name
    opencl = repo / 'build/opencl-jni/arm64-v8a/libfuto_opencl.so'
    assert sha(opencl.read_bytes()) == expected['lib/arm64-v8a/libfuto_opencl.so']
    # Verify all staged experimental assets, including third-party notices.
    asset_hashes = {}
    for p in sorted((repo / 'build/opencl-assets').rglob('*')):
        if p.is_file():
            name = 'assets/' + p.relative_to(repo / 'build/opencl-assets').as_posix()
            assert p.read_bytes() == z.read(name), name
            asset_hashes[name] = sha(p.read_bytes())
    assert len(asset_hashes) == 2
    dest = repo / 'build/cookie-release-native/arm64-v8a'
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(stripped / 'lib/arm64-v8a/libjni_latinime.so', dest / 'libjni_latinime.so')
    assert sha((dest / 'libjni_latinime.so').read_bytes()) == expected['lib/arm64-v8a/libjni_latinime.so']
proof = {'v55_apk_sha256': sha(apk.read_bytes()), 'all_nine_native_hashes': expected,
         'all_v55_merged_assets_identical': len(baseline_assets),
         'opencl_asset_hashes': asset_hashes, 'staged_stock_jni': str(dest / 'libjni_latinime.so'),
         'native_builds': 0, 'scope': 'Reuse v55 native bytes; release DEX/resources are rebuilt and minified.'}
(repo / 'build/cookie-release-native-proof.json').write_text(json.dumps(proof, indent=2) + '\n')
print('PASS: all nine v55 native hashes and both OpenCL license assets; stock JNI staged.')
