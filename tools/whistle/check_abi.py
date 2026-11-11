#!/usr/bin/env python3
"""Check linked ELF, not a claim of phone execution or APK zip alignment."""
import argparse
from pathlib import Path
import re
import subprocess

p = argparse.ArgumentParser()
p.add_argument('readelf', type=Path)
p.add_argument('library', type=Path)
a = p.parse_args()
text = subprocess.check_output([str(a.readelf), '-h', '-l', '-d', '--dyn-syms', '-V', str(a.library)], text=True)
assert 'AArch64' in text
loads = [line for line in text.splitlines() if line.strip().startswith('LOAD ')]
assert loads and all(int(line.split()[-1], 16) >= 16384 for line in loads)
assert 'TEXTREL' not in text
needed = set(re.findall(r'Shared library: \[(.*?)\]', text))
assert needed == {'libc.so', 'libm.so', 'libdl.so'}, needed
exports = set()
for line in text.splitlines():
    cols = line.split()
    if len(cols) == 8 and cols[4] in ('GLOBAL', 'WEAK') and cols[6] not in ('UND', 'ABS'):
        exports.add(cols[7])
assert exports == {'cookie_whistle_load', 'cookie_whistle_feed', 'cookie_whistle_finish', 'cookie_whistle_cancel', *{'Java_org_futo_inputmethod_latin_whistle_WhistleNative_' + op for op in ('load', 'feed', 'finish')}}, exports
print('PASS: AArch64; 16 KiB PT_LOAD; no TEXTREL; Android system dependencies; four C and three JNI exports')
