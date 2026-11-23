#!/usr/bin/env python3
"""Fetch only the dependency pinned by this checkout. Never redistribute."""
import hashlib
import os
from pathlib import Path
import re
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        while chunk := handle.read(65536):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    props_text = (ROOT / 'gradle.properties').read_text(encoding='utf-8')
    props = {k: v.strip() for k, v in re.findall(r'^([a-z_][a-z0-9_]*)=(.*)$', props_text, re.M)}
    version = props.get('millenaire_version', '')
    checksum = props.get('millenaire_sha256', '')
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version) or not re.fullmatch(r'[0-9a-f]{64}', checksum):
        return 2

    target = ROOT / f'millenaire-{version}.jar'
    if target.is_file():
        actual = sha256_file(target)
        if actual != checksum:
            raise ValueError(f'{target.name} SHA-256 mismatch: expected {checksum}, got {actual}')
        print(f'{target.name}: OK ({actual})')
        return 0

    url = f'https://www.millenaire.org/api/downloads/{version}~millenaire-{version}.jar'
    temp = ROOT / f'.millenaire-{version}.{os.getpid()}.tmp'
    try:
        request = urllib.request.Request(url, headers={'User-Agent': 'curl/8.5.0'})
        with urllib.request.urlopen(request, timeout=180) as response, temp.open('wb') as out:
            while chunk := response.read(65536):
                out.write(chunk)
        actual = sha256_file(temp)
        if actual != checksum:
            raise ValueError(f'Downloaded {target.name} SHA-256 mismatch: expected {checksum}, got {actual}')
        os.replace(temp, target)
        print(f'{target.name}: OK ({actual})')
        return 0
    finally:
        if temp.exists():
            temp.unlink()


if __name__ == '__main__':
    sys.exit(main())
