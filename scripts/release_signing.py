"""Strict data-only .env reader. Never evaluate shell code or print credentials."""
import base64
from dataclasses import dataclass
from pathlib import Path
import re

FIELDS = {'RELEASE_KEYSTORE_BASE64', 'RELEASE_STORE_PASSWORD',
          'RELEASE_KEY_ALIAS', 'RELEASE_KEY_PASSWORD'}


@dataclass(repr=False)
class SigningConfig:
    keystore: bytes
    store_password: str
    alias: str
    key_password: str

    def private_references(self):
        return [base64.b64encode(self.keystore).decode('ascii'),
                self.store_password, self.key_password]


def load_config(path: Path) -> SigningConfig:
    values = {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, separator, value = line.partition('=')
        if not separator or key not in FIELDS or key in values or not value:
            raise ValueError('Invalid signing .env: unknown, duplicate or empty field')
        values[key] = value
    if values.keys() != FIELDS:
        raise ValueError('Signing .env requires all four RELEASE_* fields')
    try:
        keystore = base64.b64decode(values['RELEASE_KEYSTORE_BASE64'], validate=True)
    except ValueError:
        raise ValueError('Invalid signing keystore Base64') from None
    if not keystore:
        raise ValueError('Empty signing keystore')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+', values['RELEASE_KEY_ALIAS']):
        raise ValueError('Invalid signing alias')
    for name in ['RELEASE_STORE_PASSWORD', 'RELEASE_KEY_PASSWORD']:
        if not re.fullmatch(r'[A-Za-z0-9_-]{16,}', values[name]):
            raise ValueError('Signing passwords require at least 16 URL-safe characters')
    return SigningConfig(keystore, values['RELEASE_STORE_PASSWORD'],
                         values['RELEASE_KEY_ALIAS'], values['RELEASE_KEY_PASSWORD'])
