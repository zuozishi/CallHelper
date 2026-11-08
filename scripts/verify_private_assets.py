"""Audit every APK member without printing private contents. Optional ephemeral references via stdin."""
import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ALLOWED_ASSETS = {'assets/xposed_init', 'assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm'}
PRIVATE_SUFFIXES = {'.csv', '.db', '.sqlite', '.sqlite3', '.enc', '.jks', '.keystore', '.pem', '.p12', '.pfx', '.apk', '.aab', '.zip'}


def audit(path, references=()):
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        assert len(names) == len(set(names)), 'Duplicate archive members'
        for name in names:
            lower = name.lower()
            assert Path(lower).suffix not in PRIVATE_SUFFIXES, 'Private data file in APK'
            assert not any(part in {'private', 'secrets', '.git'} for part in lower.split('/')), 'Private directory in APK'
            assert not Path(lower).name.startswith('.env'), 'Environment file in APK'
            if name.startswith('assets/') and not name.endswith('/'):
                assert name in ALLOWED_ASSETS, 'Unexpected asset: review before allowing it'
            data = apk.read(name)
            assert not re.search(rb'(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{43}=(?![A-Za-z0-9+/=])', data), 'Possible hardcoded Base64 AES-256 key: review required'
            if name == 'assets/dexopt/baseline.prof':
                assert data.startswith(b'pro\x00'), 'Invalid generated baseline profile'
            if name == 'assets/dexopt/baseline.profm':
                assert data.startswith(b'prm\x00'), 'Invalid generated baseline profile metadata'
            for reference in references:
                assert reference.encode('utf-8') not in data and reference.encode('utf-16le') not in data, 'Known private value in APK'
            assert not re.search(rb'(?i)"(?:KeyBase64|CALLHELPER_DB_PASSWORD)"\s*:\s*"[^"\r\n]+"', data), 'Embedded credential assignment'
        assert apk.read('assets/xposed_init').decode().strip() == 'com.zuozishi.callhelper.rear.NativeRearHook', 'Unexpected hook asset'
    return {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'bytes': path.stat().st_size,
            'members': len(names), 'private_assets': False, 'private_references_checked': len(references)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--reference-stdin', action='store_true', help='Ephemeral JSON array of known private values; never saved or printed')
    args = parser.parse_args()
    references = []
    if args.reference_stdin:
        import sys
        references = [value for value in json.load(sys.stdin) if value]
    for relative in ['.env', 'secrets', 'data', 'artifacts/private']:
        path = ROOT / relative
        assert not path.is_file() and (not path.exists() or not any(path.rglob('*'))), 'Local private input remains: ' + relative
    summary = {}
    for variant, filename in [('debug', 'app-debug.apk'), ('release', 'app-release-unsigned.apk')]:
        path = ROOT / 'app/build/outputs/apk' / variant / filename
        summary[variant] = audit(path, references)
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
