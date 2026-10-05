#!/usr/bin/env python3
"""Sign a manifest for a previously verified CI APK; never upload or publish it.

Requires OpenSSL 3. The private PEM is supplied only via UPDATE_SIGNING_KEY_PEM.
No secrets are printed. Callers must finish APK/device acceptance before publish.
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

SPKI = bytes.fromhex('302a300506032b6570032100')
RELEASES = 'https://github.com/RescueAuth/rescue_auth_kit/releases'

def run(args):
    result = subprocess.run(args, capture_output=True)
    if result.returncode:
        raise ValueError('OpenSSL operation failed')
    return result.stdout

def public_der(pin):
    try: raw = base64.b64decode(pin, validate=True)
    except Exception as exc: raise ValueError('Invalid public pin') from exc
    if len(raw) != 32: raise ValueError('Invalid public pin length')
    return SPKI + raw

def verify(manifest, signature, pin, openssl):
    if manifest.stat().st_size > 65536 or signature.stat().st_size > 4096:
        raise ValueError('Update size limit exceeded')
    try: sig = base64.b64decode(signature.read_bytes().strip(), validate=True)
    except Exception as exc: raise ValueError('Invalid signature encoding') from exc
    if len(sig) != 64: raise ValueError('Invalid signature length')
    with tempfile.TemporaryDirectory() as d:
        p = Path(d)
        (p/'public.der').write_bytes(public_der(pin))
        (p/'signature').write_bytes(sig)
        run([openssl, 'pkeyutl', '-verify', '-pubin', '-keyform', 'DER', '-inkey', str(p/'public.der'),
             '-rawin', '-in', str(manifest), '-sigfile', str(p/'signature')])

def create(*, apk, tag, version_code, key, pin, output, openssl):
    if not re.fullmatch(r'rescueauth-v[0-9]+\.[0-9]+\.[0-9]+', tag):
        raise ValueError('Invalid production tag')
    if type(version_code) is not int or version_code <= 0:
        raise ValueError('Invalid versionCode')
    size = apk.stat().st_size
    if not 0 < size <= 5_000_000_000: raise ValueError('Invalid APK size')
    if run([openssl, 'pkey', '-in', str(key), '-pubout', '-outform', 'DER']) != public_der(pin):
        raise ValueError('Signing key does not match reviewed pin')
    version = tag.removeprefix('rescueauth-v')
    digest = hashlib.sha256()
    with apk.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024*1024), b''): digest.update(chunk)
    value = dict(schemaVersion=1, channel='stable', versionName=version,
                 versionCode=version_code, minSupportedVersionCode=10000,
                 publishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                 apkUrl=f'{RELEASES}/download/{tag}/RescueAuth-{version}.apk',
                 apkSizeBytes=size, apkSha256=digest.hexdigest(),
                 releaseNotesUrl=f'{RELEASES}/tag/{tag}', severity='NORMAL')
    if version_code < value['minSupportedVersionCode']: raise ValueError('Version below native baseline')
    # A dedicated new directory prevents accidental replacement of old artifacts.
    output.mkdir(parents=True, exist_ok=False)
    manifest = output/'latest.json'
    manifest.write_bytes((json.dumps(value, ensure_ascii=False, indent=2)+'\n').encode())
    signature = run([openssl, 'pkeyutl', '-sign', '-rawin', '-inkey', str(key), '-in', str(manifest)])
    if len(signature) != 64: raise ValueError('Unexpected signature size')
    (output/'latest.json.sig').write_bytes(base64.b64encode(signature)+b'\n')
    verify(manifest, output/'latest.json.sig', pin, openssl)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--version-code', required=True, type=int)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    pin = (Path(__file__).resolve().parents[1]/'release/update-public-key.txt').read_text().strip()
    pem = os.environ.pop('UPDATE_SIGNING_KEY_PEM', '')
    if not pem: parser.error('UPDATE_SIGNING_KEY_PEM is required')
    with tempfile.TemporaryDirectory(prefix='rescueauth-update-signing-') as d:
        key = Path(d)/'key.pem'
        key.write_text(pem); key.chmod(0o600)
        create(apk=args.apk, tag=args.tag, version_code=args.version_code, key=key,
               pin=pin, output=args.output, openssl=os.environ.get('OPENSSL_BIN', 'openssl'))
    print('Manifest generated and signature verified; publication is a separate acceptance-gated step.')
