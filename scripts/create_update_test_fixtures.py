"""Create public, disposable APK verification fixtures. Never install these dummy apps."""
from pathlib import Path
import os
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'artifacts/update-fixtures'
SDK = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk'
TOOLS = SDK / 'build-tools/36.1.0'
JAVA = Path(os.environ['JAVA_HOME']) / 'bin'


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, stdout=subprocess.DEVNULL)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    build = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
    current_code = int(re.search(r'versionCode\s*=\s*(\d+)', build).group(1))
    current_version = re.search(r'versionName\s*=\s*"([^"]+)"', build).group(1)
    parts = list(map(int, current_version.split('.')))
    parts[-1] += 1
    next_version = '.'.join(map(str, parts))
    wrong_key = OUT / 'disposable-test.jks'
    if not wrong_key.exists():
        run(JAVA/'keytool.exe', '-genkeypair', '-keystore', wrong_key, '-storepass', 'android',
            '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-validity', '30',
            '-dname', 'CN=Disposable Update Test', '-noprompt')
    debug_key = Path.home() / '.android/debug.keystore'
    for name, package, code, version, key in [
        ('next', 'com.zuozishi.callhelper', current_code + 1, next_version, debug_key),
        ('old', 'com.zuozishi.callhelper', current_code - 1, '0.1.9', debug_key),
        ('wrong-package', 'com.example.other', current_code + 1, next_version, debug_key),
        ('wrong-signer', 'com.zuozishi.callhelper', current_code + 1, next_version, wrong_key),
    ]:
        manifest = OUT / 'AndroidManifest.xml'
        manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="{code}" android:versionName="{version}"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36"/><application android:label="Update verification fixture"/></manifest>''', encoding='utf-8')
        unsigned, signed = OUT/f'{name}-unsigned.apk', OUT/f'{name}.apk'
        run(TOOLS/'aapt2.exe', 'link', '--manifest', manifest, '-I', SDK/'platforms/android-36/android.jar', '-o', unsigned)
        run(JAVA/'java.exe', '-jar', TOOLS/'lib/apksigner.jar', 'sign', '--ks', key,
            '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', signed, unsigned)
    print(OUT)


if __name__ == '__main__': main()
