#!/usr/bin/env python3
"""Inspect packaged names, approved sherpa bytes, compression and ELF alignment."""
import hashlib
import json
import pathlib
import os
import subprocess
import sys
import tempfile
import zipfile

apk = pathlib.Path(sys.argv[1])
expected = {
    'libsherpa-onnx-jni.so': '5db8f89f12b2c900a98c9e01e00c475c59db35e9437a62e36e27839ec48b5575',
    'libonnxruntime.so': '33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1',
}
report = {'apk': str(apk.resolve()), 'bytes': apk.stat().st_size,
          'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'native': {}}
with zipfile.ZipFile(apk) as z, tempfile.TemporaryDirectory(prefix='cookie-apk-audit-') as temp:
    members = [i for i in z.infolist() if i.filename.endswith('.so')]
    names = [pathlib.PurePosixPath(i.filename).name for i in members]
    assert len(names) == len(set(names)), 'Duplicate native names'
    assert set(expected) <= set(names)
    assert not any('sherpa-onnx-c-api' in n or 'sherpa-onnx-cxx-api' in n for n in names)
    for info in members:
        assert info.filename.startswith('lib/arm64-v8a/')
        data = z.read(info)
        digest = hashlib.sha256(data).hexdigest()
        name = pathlib.PurePosixPath(info.filename).name
        if name in expected:
            source = pathlib.Path(__file__).resolve().parents[2] / 'voiceinput-shared/build/sherpa/jni/arm64-v8a' / name
            assert hashlib.sha256(source.read_bytes()).hexdigest() == expected[name], name
            stripped = pathlib.Path(temp) / ('stripped-' + name)
            strip = pathlib.Path(os.environ['ANDROID_HOME']) / 'ndk/28.0.13004108/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'
            subprocess.run([strip, '--strip-unneeded', '-o', stripped, source], check=True)
            assert stripped.read_bytes() == data, 'Packaged bytes differ from verified, NDK-stripped input: ' + name
        path = pathlib.Path(temp) / name
        path.write_bytes(data)
        elf = subprocess.check_output(['readelf', '-lW', path], text=True)
        loads = [line.split() for line in elf.splitlines() if line.strip().startswith('LOAD ')]
        assert loads
        for load in loads:
            offset, address, alignment = int(load[1], 16), int(load[2], 16), int(load[-1], 16)
            assert alignment >= 16384 and (offset - address) % 16384 == 0, name
        # Legacy JNI packaging extracts compressed libraries; ZIP page alignment is
        # relevant only for stored members. zipalign -P 16 also checks the whole APK.
        if info.compress_type == zipfile.ZIP_STORED:
            with apk.open('rb') as stream:
                stream.seek(info.header_offset + 26)
                import struct
                name_size, extra_size = struct.unpack('<HH', stream.read(4))
            assert (info.header_offset + 30 + name_size + extra_size) % 16384 == 0
        report['native'][name] = {'sha256': digest, 'bytes': len(data),
            'compressed': info.compress_type != zipfile.ZIP_STORED, 'elf_16k': True}
    report['no_duplicate_so_names'] = True
    report['wrappers_absent'] = True
print(json.dumps(report, indent=2))
