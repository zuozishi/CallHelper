"""Read-only live protocol check. Never persist plaintext, keys or personnel values."""
import base64
import csv
import io
import json
import argparse
import getpass
import urllib.parse
import urllib.request
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--url', help='HTTPS export URL; omit to enter it without echo')
args = parser.parse_args()
url = args.url or getpass.getpass('Sync URL: ')
parsed = urllib.parse.urlsplit(url)
assert parsed.scheme == 'https' and parsed.hostname and not parsed.username and not parsed.password and not parsed.fragment, 'HTTPS URL required'
key = base64.b64decode(getpass.getpass('AES-256 key (Base64): '), validate=True)
assert len(key) == 32, 'AES-256 requires 32 bytes'
request = urllib.request.Request(url, headers={'Accept': 'text/plain', 'Cache-Control': 'no-store'})
with urllib.request.urlopen(request, timeout=45) as response:
    assert response.status == 200
    body = response.read(28 * 1024 * 1024 + 1)
    assert len(body) <= 28 * 1024 * 1024
packet = base64.b64decode(body.strip(), validate=True)
assert len(packet) >= 29 and packet[0] == 1
plain = AESGCM(key).decrypt(packet[1:13], packet[29:] + packet[13:29], packet[:1])
reader = csv.DictReader(io.StringIO(plain.decode('utf-8-sig'), newline=''))
rows = list(reader)
assert rows
report = {'http': 200, 'authenticated_decryption': True, 'records': len(rows),
          'duplicate_ids': len(rows) - len({r['Id'] for r in rows}),
          'units': len({r['TopOrgName'] for r in rows}),
          'fields': reader.fieldnames, 'plaintext_saved': False}
print(json.dumps(report, ensure_ascii=True))
