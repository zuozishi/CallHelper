"""Generate local signing material or build and verify a signed release APK."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile

from release_signing import load_config
from verify_private_assets import audit

ROOT = Path(__file__).resolve().parents[1]
ENV_FILE = ROOT / '.env'


def run(args, **kwargs):
    return subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True, **kwargs)


def ensure_env_private():
    ignored = subprocess.run(['git', 'check-ignore', '--quiet', '.env'], cwd=ROOT)
    tracked = subprocess.run(['git', 'ls-files', '--error-unmatch', '.env'], cwd=ROOT,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if ignored.returncode != 0 or tracked.returncode == 0:
        raise RuntimeError('.env must be ignored by Git and must not be tracked')


def jdk_tools():
    home = os.environ.get('JAVA_HOME')
    suffix = '.exe' if os.name == 'nt' else ''
    if not home:
        raise RuntimeError('Set JAVA_HOME to JDK 21 before running this script')
    java = Path(home) / 'bin' / ('java' + suffix)
    keytool = Path(home) / 'bin' / ('keytool' + suffix)
    if not java.is_file() or not keytool.is_file():
        raise RuntimeError('JAVA_HOME must contain java and keytool')
    return java, keytool


def initialize(keytool):
    ensure_env_private()
    if ENV_FILE.exists():
        raise RuntimeError('.env already exists; refusing to replace signing identity')
    password = secrets.token_urlsafe(48)
    env = dict(os.environ, CALLHELPER_SIGN_STORE_PASS=password)
    with tempfile.TemporaryDirectory(prefix='callhelper-keygen-') as directory:
        path = Path(directory) / 'release.p12'
        run([keytool, '-genkeypair', '-keystore', path, '-storetype', 'PKCS12',
             '-storepass:env', 'CALLHELPER_SIGN_STORE_PASS',
             '-keypass:env', 'CALLHELPER_SIGN_STORE_PASS',
             '-alias', 'callhelper-release', '-keyalg', 'RSA', '-keysize', '4096',
             '-sigalg', 'SHA256withRSA', '-validity', '10000',
             '-dname', 'CN=CallHelper Release,O=Zuozishi', '-noprompt'], env=env)
        encoded = base64.b64encode(path.read_bytes()).decode('ascii')
        content = ('# Local release signing identity. Back up securely; never commit.\n'
                   f'RELEASE_KEYSTORE_BASE64={encoded}\n'
                   f'RELEASE_STORE_PASSWORD={password}\n'
                   'RELEASE_KEY_ALIAS=callhelper-release\n'
                   f'RELEASE_KEY_PASSWORD={password}\n')
        # O_EXCL also protects against a concurrent initializer.
        fd = os.open(ENV_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as output:
            output.write(content)
    print('Created local .env (RSA-4096 / PKCS12); credentials were not printed.')


def android_build_tools():
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    local = ROOT / 'local.properties'
    if not sdk and local.exists():
        for line in local.read_text(encoding='utf-8').splitlines():
            if line.strip().startswith('sdk.dir='):
                sdk = line.split('=', 1)[1].strip().replace('\\:', ':').replace('\\\\', '\\')
                break
    if not sdk:
        raise RuntimeError('Set Android SDK in local.properties or ANDROID_HOME')
    directory = Path(sdk) / 'build-tools'
    candidates = sorted((p for p in directory.iterdir()
                         if p.is_dir() and re.fullmatch(r'\d+\.\d+\.\d+', p.name)),
                        key=lambda p: tuple(map(int, p.name.split('.'))), reverse=True)
    for candidate in candidates:
        align = candidate / ('zipalign.exe' if os.name == 'nt' else 'zipalign')
        signer = candidate / 'lib' / 'apksigner.jar'
        if align.is_file() and signer.is_file():
            return align, signer
    raise RuntimeError('Install a stable Android SDK Build Tools with zipalign and apksigner')


def validate_release_tag(tag, version):
    if not re.fullmatch(r'v\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?', tag):
        raise ValueError('Release tag must use vX.Y.Z or vX.Y.Z-prerelease format')
    if tag != f'v{version}':
        raise ValueError('Release tag must match versionName in app/build.gradle.kts')


def build(java, keytool, tag=None):
    if tag:
        source = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
        versions = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"', source, re.MULTILINE)
        if len(versions) != 1:
            raise ValueError('Expected one literal versionName in app/build.gradle.kts')
        validate_release_tag(tag, versions[0])
    ensure_env_private()
    config = load_config(ENV_FILE)
    zipalign, apksigner = android_build_tools()
    # Signing passwords are never passed to Gradle or its persistent daemon.
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    wrapper_command = [wrapper] if os.name == 'nt' else ['bash', wrapper]
    run([*wrapper_command, ':app:assembleRelease', ':app:testDebugUnitTest', ':app:lintDebug'])
    apk_dir = ROOT / 'app/build/outputs/apk/release'
    metadata = json.loads((apk_dir / 'output-metadata.json').read_text(encoding='utf-8'))
    elements = metadata['elements']
    if len(elements) != 1:
        raise RuntimeError('Expected one universal release APK')
    element = elements[0]
    unsigned = (apk_dir / element['outputFile']).resolve()
    if unsigned.parent != apk_dir.resolve() or not unsigned.is_file():
        raise RuntimeError('Unexpected release APK location')
    version = element['versionName']
    if tag:
        validate_release_tag(tag, version)
    if not re.fullmatch(r'[A-Za-z0-9_.-]+', version):
        raise RuntimeError('Invalid versionName for output filename')
    references = config.private_references()
    audit(unsigned, references)
    destination = ROOT / 'artifacts/releases'
    destination.mkdir(parents=True, exist_ok=True)
    # Temporary files remain on the output filesystem and are removed on failure.
    with tempfile.TemporaryDirectory(prefix='signing-', dir=destination) as directory:
        temporary = Path(directory)
        store = temporary / 'release.p12'
        fd = os.open(store, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as output:
            output.write(config.keystore)
        aligned, signed = temporary / 'aligned.apk', temporary / 'signed.apk'
        run([zipalign, '-f', '-P', '16', '4', unsigned, aligned])
        env = dict(os.environ, CALLHELPER_SIGN_STORE_PASS=config.store_password,
                   CALLHELPER_SIGN_KEY_PASS=config.key_password)
        run([java, '-jar', apksigner, 'sign', '--ks', store, '--ks-type', 'PKCS12',
             '--ks-key-alias', config.alias, '--ks-pass', 'env:CALLHELPER_SIGN_STORE_PASS',
             '--key-pass', 'env:CALLHELPER_SIGN_KEY_PASS',
             '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
             '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false',
             '--out', signed, aligned], env=env)
        verified = run([java, '-jar', apksigner, 'verify', '--verbose', '--print-certs', signed],
                       capture_output=True, text=True)
        certificate = run([keytool, '-exportcert', '-keystore', store, '-storetype', 'PKCS12',
                           '-storepass:env', 'CALLHELPER_SIGN_STORE_PASS', '-alias', config.alias],
                          env=env, capture_output=True).stdout
        fingerprint = hashlib.sha256(certificate).hexdigest()
        if fingerprint not in verified.stdout.lower():
            raise RuntimeError('APK signer does not match the local release certificate')
        run([zipalign, '-c', '-P', '16', '4', signed])
        report = audit(signed, references)
        target = destination / f'callhelper-{version}-release.apk'
        commit = run(['git', 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
        dirty = bool(run(['git', 'status', '--porcelain'], capture_output=True, text=True).stdout)
        report.update(version_name=version, version_code=element['versionCode'],
                      certificate_sha256=fingerprint, git_commit=commit, working_tree_dirty=dirty)
        os.replace(signed, target)
        target.with_suffix('.apk.sha256').write_text(
            f"{report['sha256']}  {target.name}\n", encoding='utf-8')
        target.with_suffix('.apk.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(f'Release APK: {target}')
    print(f'Certificate SHA-256: {fingerprint}')
    print(f"APK SHA-256: {report['sha256']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--init-signing', action='store_true',
                        help='Create .env once; refuses to overwrite an existing file')
    parser.add_argument('--tag', help='Require v<versionName> before building and signing')
    args = parser.parse_args()
    if args.init_signing and args.tag:
        parser.error('--tag cannot be combined with --init-signing')
    try:
        java, keytool = jdk_tools()
        if args.init_signing:
            initialize(keytool)
        else:
            build(java, keytool, args.tag)
    except (OSError, ValueError, RuntimeError, AssertionError, subprocess.CalledProcessError) as error:
        # No tracebacks or subprocess argument dumps containing local config.
        if isinstance(error, subprocess.CalledProcessError):
            print(f'Release tool failed (exit {error.returncode}); no release was published.', file=sys.stderr)
        elif isinstance(error, OSError):
            print('Release file/tool unavailable; check .env, SDK/JDK and directory permissions.', file=sys.stderr)
        else:
            print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
