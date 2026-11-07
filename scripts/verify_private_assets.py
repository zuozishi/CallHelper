"""Ensure local personnel fixtures do not enter Git or the release APK."""
import json
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main():
    for name in ['data/export_261108004228.csv', 'app/src/debug/assets/private/members.csv',
                 'app/src/androidTest/assets/private/members.csv', '.env']:
        result = subprocess.run(['git', 'check-ignore', '-q', name], cwd=ROOT)
        if result.returncode:
            raise RuntimeError('Private fixture is not excluded from Git')
        tracked = subprocess.check_output(['git', 'ls-files', '--', name], cwd=ROOT)
        if tracked.strip():
            raise RuntimeError('Private fixture is tracked by Git')
    summary = {}
    for variant, filename in [('debug', 'app-debug.apk'), ('release', 'app-release-unsigned.apk')]:
        path = ROOT / 'app/build/outputs/apk' / variant / filename
        with zipfile.ZipFile(path) as apk:
            has_private = any(n.startswith('assets/private/') for n in apk.namelist())
        if has_private != (variant == 'debug'):
            raise RuntimeError('Unexpected private fixture packaging')
        summary[variant] = {'private_fixture': has_private, 'bytes': path.stat().st_size}
    print(json.dumps(summary))

if __name__ == '__main__':
    main()
