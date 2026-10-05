import base64
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('manifest', ROOT / 'scripts/update-manifest.py')
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)

class ManifestTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.ssl = os.environ.get('OPENSSL_BIN', shutil.which('openssl'))
        self.key = self.root / 'test.pem'
        subprocess.run([self.ssl, 'genpkey', '-algorithm', 'ED25519', '-out', str(self.key)], check=True, capture_output=True)
        der = subprocess.check_output([self.ssl, 'pkey', '-in', str(self.key), '-pubout', '-outform', 'DER'])
        self.pin = base64.b64encode(der[-32:]).decode()
        self.apk = self.root / 'test.apk'
        self.apk.write_bytes(b'test artifact bytes only')
        self.out = self.root / 'output'
    def create(self, **overrides):
        args = dict(apk=self.apk, tag='rescueauth-v1.0.0', version_code=10000,
                    key=self.key, pin=self.pin, output=self.out, openssl=self.ssl)
        args.update(overrides)
        return module.create(**args)
    def test_exact_bytes_signature_and_metadata(self):
        self.create()
        manifest = self.out / 'latest.json'
        data = json.loads(manifest.read_bytes())
        self.assertEqual(data['apkSizeBytes'], self.apk.stat().st_size)
        self.assertEqual(data['versionName'], '1.0.0')
        self.assertTrue(data['apkUrl'].endswith('/rescueauth-v1.0.0/RescueAuth-1.0.0.apk'))
        self.assertEqual(len(base64.b64decode((self.out / 'latest.json.sig').read_bytes())), 64)
        module.verify(manifest, self.out / 'latest.json.sig', self.pin, self.ssl)
        manifest.write_bytes(manifest.read_bytes() + b' ')
        with self.assertRaises(ValueError):
            module.verify(manifest, self.out / 'latest.json.sig', self.pin, self.ssl)
    def test_wrong_key_rejected_without_output(self):
        with self.assertRaises(ValueError): self.create(pin=base64.b64encode(b'x'*32).decode())
        self.assertFalse(self.out.exists())
    def test_legacy_and_unsafe_tags_rejected(self):
        for tag in ['v1.0.0', 'legacy-v1.0.0', 'main', '../latest', 'rescueauth-v1.0.0\n']:
            with self.subTest(tag=tag), self.assertRaises(ValueError): self.create(tag=tag)
    def test_empty_apk_rejected(self):
        self.apk.write_bytes(b'')
        with self.assertRaises(ValueError): self.create()
    def test_invalid_version_codes(self):
        for code in [0, -1, True]:
            with self.subTest(code=code), self.assertRaises(ValueError): self.create(version_code=code)
    def test_existing_output_is_never_overwritten(self):
        self.create()
        before=(self.out/'latest.json').read_bytes()
        with self.assertRaises(FileExistsError): self.create()
        self.assertEqual(before,(self.out/'latest.json').read_bytes())

if __name__ == '__main__': unittest.main()
