"""Synthetic signing-config validation; never reads the real .env."""
import base64
from pathlib import Path
import tempfile
import unittest

from release_signing import load_config
from build_release import validate_release_tag


class SigningConfigTest(unittest.TestCase):
    def config(self, content):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / '.env'
            path.write_text(content, encoding='utf-8')
            return load_config(path)

    def fixture(self):
        return ('RELEASE_KEYSTORE_BASE64=' + base64.b64encode(b'synthetic-store').decode() + '\n'
                'RELEASE_STORE_PASSWORD=synthetic-password-123\n'
                'RELEASE_KEY_ALIAS=callhelper-release\n'
                'RELEASE_KEY_PASSWORD=synthetic-password-123\n')

    def test_decodes_without_exposing_values_in_repr(self):
        config = self.config(self.fixture())
        self.assertEqual(config.keystore, b'synthetic-store')
        self.assertNotIn(config.store_password, repr(config))

    def test_missing_duplicate_unknown_or_empty_field_rejected(self):
        for content in [self.fixture().replace('RELEASE_KEY_ALIAS=callhelper-release\n', ''),
                        self.fixture() + 'RELEASE_KEY_ALIAS=duplicate\n',
                        self.fixture() + 'DATABASE_PASSWORD=forbidden\n',
                        self.fixture().replace('RELEASE_KEY_ALIAS=callhelper-release', 'RELEASE_KEY_ALIAS=')]:
            with self.subTest(content=content), self.assertRaises(ValueError):
                self.config(content)

    def test_shell_syntax_is_data_and_rejected(self):
        with self.assertRaises(ValueError):
            self.config(self.fixture().replace('callhelper-release', '$(echo unsafe)'))

    def test_invalid_store_and_weak_password_rejected(self):
        for old, new in [('c3ludGhldGljLXN0b3Jl', '!invalid-base64!'),
                         ('synthetic-password-123', 'short')]:
            with self.subTest(field=old), self.assertRaises(ValueError):
                self.config(self.fixture().replace(old, new))

    def test_release_tag_accepts_matching_stable_and_prerelease(self):
        for version in ['0.1.9', '1.0.0-rc.1']:
            validate_release_tag('v' + version, version)

    def test_release_tag_rejects_mismatch_and_unsafe_names(self):
        for tag in ['v0.1.8', '0.1.9', 'v0.1', 'v0.1.9/other', 'v0.1.9;echo unsafe']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                validate_release_tag(tag, '0.1.9')


if __name__ == '__main__':
    unittest.main()
