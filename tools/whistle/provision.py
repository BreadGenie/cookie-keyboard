#!/usr/bin/env python3
"""Explicit developer-only provisioning. Never invoked by Gradle or product code."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

RUNTIME = '1a028f5a3972b67dd8d8752f882113d475e7ec4c'
MODEL = '741118687e1f008f06d1351a402a731274789790'
BASE = f'https://huggingface.co/Cactus-Compute/cactus/resolve/{RUNTIME}/releases/4.0.0/'
MANIFEST_HASH = 'b6a39fb21bc77780c029c45d4cc206b60f883146fa2dbc28bfcc9995fe72b871'
PINS = {
 'android-arm64/libcactus.a': (2826406, '4f75e975f6a859979a2715a89fdbc0fc32742cde001f8674598cf89017d39fdf'),
 'android-arm64/cactus.h': (1671, 'facefca17a0d5e715bd92a8b0ea272c1ac34a20a798003b1e691c8fba5ace6e2'),
 'linux-x86_64/libcactus.a': (2847638, '9e5e7abe1b0bf6675b69a00a5c5e875e80fde496d4e79f7ae86faeae5cab8fd6'),
 'linux-x86_64/cactus.h': (1671, 'facefca17a0d5e715bd92a8b0ea272c1ac34a20a798003b1e691c8fba5ace6e2'),
 'linux-x86_64/cactus': (1602448, '1233a27b65f2557ade0c965f17ba9de70a47e9f10c139539daca78814f946045'),
 'LICENSE': (11358, 'cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30'),
}
MODEL_PIN = (16919407, 'b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb')

def fetch(url, path, size, digest):
    data = path.read_bytes() if path.exists() else urllib.request.urlopen(url, timeout=60).read(size + 1)
    if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f'pin mismatch: {path}')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return {'file': str(path), 'url': url, 'size': size, 'sha256': digest}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent / '.cache'
    manifest_data = urllib.request.urlopen(BASE + 'manifest.json', timeout=60).read(32768)
    if hashlib.sha256(manifest_data).hexdigest() != MANIFEST_HASH:
        raise ValueError('manifest hash mismatch')
    manifest = json.loads(manifest_data)
    root.mkdir(exist_ok=True)
    (root / 'manifest.json').write_bytes(manifest_data)
    names = ['LICENSE', 'android-arm64/cactus.h', 'android-arm64/libcactus.a']
    if args.host:
        names += ['linux-x86_64/cactus.h', 'linux-x86_64/libcactus.a', 'linux-x86_64/cactus']
    records = []
    for name in names:
        size, digest = PINS[name]
        assert manifest['files'][name] == {'size': size, 'sha256': digest}
        records.append(fetch(BASE + name, root / name, size, digest))
    model_base = f'https://huggingface.co/Cactus-Compute/whistle/resolve/{MODEL}/'
    # Same Apache license bytes, checked independently at the model revision.
    records.append(fetch(model_base + 'LICENSE', root / 'model-LICENSE', *PINS['LICENSE']))
    records.append(fetch(model_base + 'whistle.cact', root / 'whistle.cact', *MODEL_PIN))
    if args.host:
        (root / 'linux-x86_64/cactus').chmod(0o755)
    (root / 'verified.json').write_text(json.dumps(records, indent=2) + '\n')
    print(json.dumps(records, indent=2))

if __name__ == '__main__':
    main()
