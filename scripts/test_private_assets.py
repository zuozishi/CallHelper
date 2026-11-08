"""Synthetic regression cases for the package privacy gate."""
import tempfile
import unittest
import zipfile
from pathlib import Path
from verify_private_assets import audit


class PackagePrivacyTest(unittest.TestCase):
    def package(self, extra=None, hook=b'com.zuozishi.callhelper.rear.NativeRearHook'):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / 'test.apk'
        with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('assets/xposed_init', hook)
            for name, data in (extra or {}).items():
                archive.writestr(name, data)
        return path

    def test_clean_archive_passes(self):
        self.assertFalse(audit(self.package())['private_assets'])

    def test_private_or_unreviewed_asset_rejected(self):
        for name in ['assets/people.csv', 'assets/export-key.json', 'assets/directory.bin', 'res/raw/people.db', 'secrets/key.txt', '.env']:
            with self.subTest(name=name), self.assertRaises(AssertionError):
                audit(self.package({name: b'synthetic fixture'}))

    def test_reference_checked_in_every_member_and_encoding(self):
        value = 'synthetic-secret-for-test'
        for name in ['classes.dex', 'resources.arsc', 'lib/arm64-v8a/test.so', 'META-INF/test']:
            for encoding in ['utf-8', 'utf-16le']:
                with self.subTest(name=name, encoding=encoding), self.assertRaises(AssertionError):
                    audit(self.package({name: value.encode(encoding)}), [value])

    def test_hook_content_is_allowlisted(self):
        with self.assertRaises(AssertionError):
            audit(self.package(hook=b'synthetic private content'))

    def test_base64_key_constant_is_rejected_without_reference_file(self):
        import base64
        key = base64.b64encode(bytes(range(32)))
        with self.assertRaises(AssertionError):
            audit(self.package({'classes.dex': b'\x00' + key + b'\x00'}))


if __name__ == '__main__':
    unittest.main()
