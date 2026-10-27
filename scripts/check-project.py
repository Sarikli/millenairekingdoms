#!/usr/bin/env python3
"""Lightweight source/package invariants; no third-party tools or graphical claims."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jar', type=Path)
args = parser.parse_args()
properties = dict(re.findall(r'^([a-z_][a-z0-9_]*)=(.*)$', (ROOT / 'gradle.properties').read_text(), re.M))
files = list((ROOT / 'src/main/java').rglob('*.java'))
for source in files:
    text = source.read_text(encoding='utf-8')
    package = re.search(r'^package\s+([\w.]+);', text, re.M)
    if not package or source.parent.relative_to(ROOT / 'src/main/java').as_posix() != package[1].replace('.', '/'):
        raise ValueError('Java package/path mismatch: ' + str(source))
native = ROOT / ('millenaire-' + properties['millenaire_version'] + '.jar')
if native.exists() and hashlib.sha256(native.read_bytes()).hexdigest() != properties['millenaire_sha256']:
    raise ValueError('Original native dependency checksum mismatch')
report = {'production_java_files': len(files), 'java_release': 21,
          'native_checksum_verified': native.exists(), 'version': properties['mod_version']}
if args.jar:
    with zipfile.ZipFile(args.jar) as archive:
        names = archive.namelist()
        classes = [name for name in names if name.endswith('.class')]
        if not classes or any(not name.startswith('com/millenairekingdoms/') or '/test/' in name for name in classes):
            raise ValueError('Production artifact contains foreign/test classes or no addon classes')
        if any(name.endswith('.jar') or name.startswith('fixtures/') for name in names):
            raise ValueError('Production artifact embeds a dependency or test fixture')
        for name in classes:
            if struct.unpack('>IHH', archive.read(name)[:8]) != (0xCAFEBABE, 0, 65):
                raise ValueError('Production class is not Java21: ' + name)
        if archive.read('META-INF/LICENSE') != (ROOT / 'LICENSE').read_bytes():
            raise ValueError('Packaged addon license differs from its preserved attribution')
        metadata = archive.read('META-INF/neoforge.mods.toml').decode()
        if 'version="' + properties['mod_version'] + '"' not in metadata.replace(' ', ''):
            raise ValueError('Packaged mod version differs from checkout')
        report.update(jar_classes=len(classes), license_verified=True,
                      jar_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest())
print(json.dumps(report, indent=2))
