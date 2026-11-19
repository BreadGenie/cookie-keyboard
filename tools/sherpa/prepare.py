#!/usr/bin/env python3
"""Verify the release before extracting only the JNI API and ARM64 runtime.

Every integrity check is an explicit failure, never an `assert`, so `python3 -O`
cannot disable it. With `--offline` (passed by Gradle when the build runs offline)
a missing cache is a clear error instead of a silent network fetch, and a failed
download is always reported with the path a manual copy must land at.
"""
import hashlib
import pathlib
import sys
import urllib.error
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / 'voiceinput-shared/build/sherpa'
CACHE = ROOT / '.cache/sherpa-onnx-2.0.1.aar'
URL = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar'
ARCHIVE_SIZE = 49441341
ARCHIVE_SHA256 = 'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7'
PINS = {
    'classes.jar': '0ee4991c56c19ef5a8a573b01891193d7298ffe7ebcc68f42b944361d8869d93',
    'jni/arm64-v8a/libsherpa-onnx-jni.so': '5db8f89f12b2c900a98c9e01e00c475c59db35e9437a62e36e27839ec48b5575',
    'jni/arm64-v8a/libonnxruntime.so': '33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1',
}
OFFLINE = '--offline' in sys.argv[1:]


def fail(message):
    """SystemExit survives `python -O`; asserts do not."""
    raise SystemExit('prepare.py: ' + message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def verify(label, actual, expected):
    if actual != expected:
        fail('%s sha256 mismatch: expected %s, got %s' % (label, expected, actual))


CACHE.parent.mkdir(exist_ok=True)
if not CACHE.exists():
    if OFFLINE:
        fail('offline build, but %s is missing; run once without --offline to fetch it'
             % CACHE)
    temporary = CACHE.with_suffix('.part')
    try:
        urllib.request.urlretrieve(URL, temporary)
    except (OSError, urllib.error.URLError) as error:
        try:
            temporary.unlink()
        except OSError:
            pass
        fail('could not download %s (%s); place the AAR manually at %s'
             % (URL, error, CACHE))
    temporary.replace(CACHE)

size = CACHE.stat().st_size
if size != ARCHIVE_SIZE:
    fail('archive size mismatch: expected %d, got %d' % (ARCHIVE_SIZE, size))
verify('archive', sha256(CACHE.read_bytes()), ARCHIVE_SHA256)

with zipfile.ZipFile(CACHE) as archive:
    members = set(archive.namelist())
    for name, digest in PINS.items():
        if name not in members:
            fail('pinned member %s is missing from the archive' % name)
        content = archive.read(name)
        verify(name, sha256(content), digest)
        dest = OUT / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        if not dest.exists() or dest.read_bytes() != content:
            dest.write_bytes(content)
        # Verify what the build will actually load, not only the archive member.
        verify('extracted ' + name, sha256(dest.read_bytes()), digest)
print('Verified sherpa-onnx 2.0.1; selected classes and two ARM64 libraries only')
