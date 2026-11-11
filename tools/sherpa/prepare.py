#!/usr/bin/env python3
"""Verify the release before extracting only the JNI API and ARM64 runtime."""
import hashlib
import pathlib
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / 'voiceinput-shared/build/sherpa'
CACHE = ROOT / '.cache/sherpa-onnx-2.0.1.aar'
PINS = {
    'classes.jar': '0ee4991c56c19ef5a8a573b01891193d7298ffe7ebcc68f42b944361d8869d93',
    'jni/arm64-v8a/libsherpa-onnx-jni.so': '5db8f89f12b2c900a98c9e01e00c475c59db35e9437a62e36e27839ec48b5575',
    'jni/arm64-v8a/libonnxruntime.so': '33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1',
}
CACHE.parent.mkdir(exist_ok=True)
if not CACHE.exists():
    temporary = CACHE.with_suffix('.part')
    urllib.request.urlretrieve('https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar', temporary)
    temporary.replace(CACHE)
assert CACHE.stat().st_size == 49441341
assert hashlib.sha256(CACHE.read_bytes()).hexdigest() == 'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7'
with zipfile.ZipFile(CACHE) as archive:
    for name, digest in PINS.items():
        content = archive.read(name)
        assert hashlib.sha256(content).hexdigest() == digest
        dest = OUT / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        if not dest.exists() or dest.read_bytes() != content:
            dest.write_bytes(content)
print('Verified sherpa-onnx 2.0.1; selected classes and two ARM64 libraries only')
