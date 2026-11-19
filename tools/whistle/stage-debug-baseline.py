#!/usr/bin/env python3
"""Reuse verified existing Whisper bytes; no engine rebuild or signing access."""
from pathlib import Path
import hashlib
import json
import zipfile
repo = Path(__file__).resolve().parents[2]
baseline = repo.parent / '.scratch/futo-opencl/settings-cleanup/v55'
apk = baseline / 'futo-settings-cleanup-v55.apk'
sha = lambda b: hashlib.sha256(b).hexdigest()
assert sha(apk.read_bytes()) == 'd06aef00132118c49d200df551678e81d5acccb97e5b90484e2f8afe7bbb7056'
pins = json.loads((baseline / 'native-reuse-proof.json').read_text())['all_nine_stripped_native_bytes_identical_v54']
with zipfile.ZipFile(apk) as z:
    assert len(pins) == 9
    for name, digest in pins.items():
        assert sha(z.read(name)) == digest, name
    for name in ('libjni_latinime.so', 'libfuto_opencl.so'):
        dest = repo / 'build/cookie-release-native/arm64-v8a' / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(z.read('lib/arm64-v8a/' + name))
    for name in ('OPENCL-HEADERS-LICENSE.txt', 'WHISPER-OPENCL-LICENSE.txt'):
        dest = repo / 'build/whistle-assets' / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(z.read('assets/' + name))
print('PASS: pinned v55 nine-library baseline; stock JNI/OpenCL and notices staged')
