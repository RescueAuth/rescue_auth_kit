// Fixture builder for the legacy .rakvault compatibility suite.
//
// Mirrors the exact envelope/payload shapes emitted by RescueAuthKit v1.2.0
// (frozen at tag v1.2.0). See docs/LEGACY_IMPORT.md for the mapping rules.
//
// WARNING: fixtures intentionally contain non-secret placeholder secrets
// (RFC 4226 test vectors / fake recovery codes). They are test data, never
// real user credentials.
library;

import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:collection/collection.dart';
import 'package:cryptography/cryptography.dart';

const _magic = 'RescueAuthKitVault';
const _version = 1;
const _argon2id = 'argon2id';
const _cipher = 'xchacha20poly1305';
const _defaultMemKiB = 19456; // 19 MiB
const _defaultIter = 2;
const _defaultParallel = 1;
const _defaultHashLen = 32;
const _defaultSaltLen = 16;

String _b64e(List<int> b) => base64UrlEncode(b);
Uint8List _b64d(String s) => Uint8List.fromList(base64Url.decode(s));

class VaultFile {
  final int memoryKiB;
  final int iterations;
  final int parallelism;
  final int hashLengthBytes;
  final Uint8List salt;
  final Uint8List nonce;
  final Uint8List mac;
  final Uint8List ciphertext;

  const VaultFile({
    required this.memoryKiB,
    required this.iterations,
    required this.parallelism,
    required this.hashLengthBytes,
    required this.salt,
    required this.nonce,
    required this.mac,
    required this.ciphertext,
  });

  Map<String, dynamic> toJson() => {
        'magic': _magic,
        'version': _version,
        'kdf': {
          'name': _argon2id,
          'memoryKiB': memoryKiB,
          'iterations': iterations,
          'parallelism': parallelism,
          'hashLengthBytes': hashLengthBytes,
          'saltB64': _b64e(salt),
        },
        'cipher': _cipher,
        'nonceB64': _b64e(nonce),
        'macB64': _b64e(mac),
        'ciphertextB64': _b64e(ciphertext),
      };

  Uint8List encode() => Uint8List.fromList(utf8.encode(jsonEncode(toJson())));

  static VaultFile decode(Uint8List bytes) {
    final decoded = jsonDecode(utf8.decode(bytes));
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Vault file is not a JSON object');
    }
    final kdf = decoded['kdf'] as Map<String, dynamic>;
    return VaultFile(
      memoryKiB: kdf['memoryKiB'] as int,
      iterations: kdf['iterations'] as int,
      parallelism: kdf['parallelism'] as int,
      hashLengthBytes: kdf['hashLengthBytes'] as int,
      salt: _b64d(kdf['saltB64'] as String),
      nonce: _b64d(decoded['nonceB64'] as String),
      mac: _b64d(decoded['macB64'] as String),
      ciphertext: _b64d(decoded['ciphertextB64'] as String),
    );
  }
}

String _randomId() {
  final r = Random.secure();
  final bytes = List<int>.generate(16, (_) => r.nextInt(256));
  final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-'
      '${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

String _base32(int seed) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  final r = Random(seed);
  final sb = StringBuffer();
  for (var i = 0; i < 16; i++) {
    sb.write(alphabet[r.nextInt(32)]);
  }
  return sb.toString();
}

Map<String, dynamic> _totp(String id, String issuer, String account,
    String secret, String algo, int digits, int period, String created) {
  return {
    'id': id,
    'issuer': issuer,
    'accountName': account,
    'secretBase32': secret,
    'algorithm': algo,
    'digits': digits,
    'period': period,
    'createdAt': created,
  };
}

Map<String, dynamic> _recoverySet(String id, String title, List<String> codes,
    String created) {
  return {
    'id': id,
    'title': title,
    'codes': codes,
    'createdAt': created,
  };
}

/// Deterministic payload: one account with a known secret (JBSWY3DPEHPK3PXP),
/// a recovery code set, plus deterministic IDs.
Map<String, dynamic> schema1Payload() {
  return {
    'schemaVersion': 1,
    'totpEntries': [
      _totp('totp-s1-rfc', 'GitHub', 'alice@example.com',
          'JBSWY3DPEHPK3PXP', 'SHA1', 6, 30, '2024-01-15T10:30:00.000Z'),
      _totp('totp-s1-256', 'Google', 'bob@example.com', _base32(7), 'SHA256',
          6, 30, '2024-02-01T08:00:00.000Z'),
      _totp('totp-s1-512', 'GitLab', 'carol@example.com', _base32(11), 'SHA512',
          6, 30, '2024-03-01T09:00:00.000Z'),
      _totp('totp-s1-d8', 'AWS', 'admin@example.com', _base32(13), 'SHA1', 8,
          30, '2024-04-01T10:00:00.000Z'),
      _totp('totp-s1-p60', 'Dropbox', 'dave@example.com', _base32(17), 'SHA1',
          6, 60, '2024-05-01T11:00:00.000Z'),
    ],
    'recoveryCodeSets': [
      _recoverySet('rcs-s1', 'GitHub backup codes',
          ['AAAA-BBBB-CCCC', 'DDDD-EEEE-FFFF', '1111-2222-3333'],
          '2024-01-15T10:31:00.000Z'),
    ],
  };
}

/// Deterministic payload: schema 2 adds developer settings + entries.
Map<String, dynamic> schema2Payload() {
  final p = schema1Payload();
  p['schemaVersion'] = 2;
  p['developerSettings'] = {'enabled': true};
  p['developerEntries'] = [
    {
      'id': 'dev-1',
      'type': 'apiCredential',
      'title': 'Example API',
      'notes': 'placeholder',
      'createdAt': '2024-06-01T00:00:00.000Z',
      'updatedAt': '2024-06-01T00:00:00.000Z',
      'payload': {'apiKey': 'sk-test-placeholder', 'baseUrl': 'https://example.com'},
    },
    {
      'id': 'dev-2',
      'type': 'sshKey',
      'title': 'Deploy key',
      'notes': '',
      'createdAt': '2024-06-02T00:00:00.000Z',
      'updatedAt': '2024-06-02T00:00:00.000Z',
      'payload': {'privateKey': '-----BEGIN TEST KEY-----', 'fingerprint': 'aa:bb'},
    },
  ];
  return p;
}

/// Deterministic payload: schema 3 (account-centric model).
Map<String, dynamic> schema3Payload() {
  return {
    'schemaVersion': 3,
    'providers': [
      {'id': 'prov-1', 'name': 'GitHub', 'createdAt': '2024-01-15T10:30:00.000Z', 'updatedAt': '2024-01-15T10:30:00.000Z'},
      {'id': 'prov-2', 'name': 'Google', 'createdAt': '2024-02-01T08:00:00.000Z', 'updatedAt': '2024-02-01T08:00:00.000Z'},
    ],
    'accounts': [
      {
        'id': 'acc-1',
        'providerId': 'prov-1',
        'displayName': 'alice@example.com',
        'createdAt': '2024-01-15T10:30:00.000Z',
        'updatedAt': '2024-01-15T10:30:00.000Z',
        'credentials': [
          {
            'kind': 'totp',
            'id': 'cred-1',
            'createdAt': '2024-01-15T10:30:00.000Z',
            'secretBase32': 'JBSWY3DPEHPK3PXP',
            'algorithm': 'SHA1',
            'digits': 6,
            'period': 30,
          },
          {
            'kind': 'recoveryCodes',
            'id': 'cred-2',
            'createdAt': '2024-01-15T10:31:00.000Z',
            'codes': ['AAAA-BBBB-CCCC', 'DDDD-EEEE-FFFF', '1111-2222-3333'],
          },
        ],
      },
      {
        'id': 'acc-2',
        'providerId': 'prov-2',
        'displayName': 'bob@example.com',
        'createdAt': '2024-02-01T08:00:00.000Z',
        'updatedAt': '2024-02-01T08:00:00.000Z',
        'credentials': [
          {
            'kind': 'totp',
            'id': 'cred-3',
            'createdAt': '2024-02-01T08:00:00.000Z',
            'secretBase32': _base32(7),
            'algorithm': 'SHA256',
            'digits': 6,
            'period': 30,
          },
        ],
      },
    ],
    'developerSettings': {'enabled': true},
    'developerEntries': [
      {
        'id': 'dev-1',
        'type': 'genericSecret',
        'title': 'Test secret',
        'notes': 'placeholder',
        'createdAt': '2024-06-01T00:00:00.000Z',
        'updatedAt': '2024-06-01T00:00:00.000Z',
        'payload': {'value': 'test-placeholder'},
      },
    ],
  };
}

class Result {
  final String name;
  final Uint8List bytes;
  final String sha256Hex;
  final Map<String, dynamic>? expectedJson;
  Result(this.name, this.bytes, this.sha256Hex, [this.expectedJson]);
}

Future<Result> buildFixture({
  required String name,
  required String password,
  required Map<String, dynamic> payload,
  int memoryKiB = _defaultMemKiB,
  int iterations = _defaultIter,
  int parallelism = _defaultParallel,
  int hashLengthBytes = _defaultHashLen,
  bool tamper = false,
  bool truncated = false,
  String? macReplace,
}) async {
  final rand = Random.secure();
  final salt = Uint8List.fromList(
      List<int>.generate(_defaultSaltLen, (_) => rand.nextInt(256)));
  final nonce = Uint8List.fromList(
      List<int>.generate(24, (_) => rand.nextInt(256)));

  final kdf = Argon2id(
    memory: memoryKiB,
    iterations: iterations,
    parallelism: parallelism,
    hashLength: hashLengthBytes,
  );
  final key = await kdf.deriveKeyFromPassword(password: password, nonce: salt);

  final cleartext = Uint8List.fromList(utf8.encode(jsonEncode(payload)));
  final cipher = Cryptography.instance.xchacha20Poly1305Aead();
  final box = await cipher.encrypt(cleartext, secretKey: key, nonce: nonce);

  var file = VaultFile(
    memoryKiB: memoryKiB,
    iterations: iterations,
    parallelism: parallelism,
    hashLengthBytes: hashLengthBytes,
    salt: salt,
    nonce: nonce,
    mac: Uint8List.fromList(box.mac.bytes),
    ciphertext: Uint8List.fromList(box.cipherText),
  );

  var bytes = file.encode();

  if (tamper) {
    // Corrupt a byte inside ciphertext (JSON is ASCII; flip a base64 char
    // that decodes to non-padding is messy, so just flip the last byte of
    // the encoded JSON — guaranteed to corrupt parsing or MAC).
    final last = bytes[bytes.length - 1];
    bytes[bytes.length - 1] = last == 0x30 ? 0x31 : 0x30;
  } else if (truncated) {
    bytes = Uint8List.fromList(bytes.sublist(0, bytes.length - 40));
  } else if (macReplace != null) {
    // Replace macB64 with a valid-format but wrong MAC.
    final decoded = jsonDecode(utf8.decode(bytes)) as Map<String, dynamic>;
    decoded['macB64'] = macReplace;
    bytes = Uint8List.fromList(utf8.encode(jsonEncode(decoded)));
  }

  final sha256 = await Sha256().hash(bytes);
  final hex = sha256.bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return Result(name, bytes, hex,
      tamper || truncated || macReplace != null ? null : payload);
}

Future<void> verify(String fixturesDir) async {
  // Mirrors lib/core/crypto/vault_crypto.dart from tag v1.2.0 exactly
  // (same envelope fields, same Argon2id/XChaCha20-Poly1305 primitives).
  final manifestRaw = File('$fixturesDir/manifest.json').readAsStringSync();
  final manifest = jsonDecode(manifestRaw) as Map<String, dynamic>;
  final cipher = Cryptography.instance.xchacha20Poly1305Aead();
  int passed = 0;

  Future<bool> decryptPositive(String name, String password) async {
    final path = manifest[name]!['path'] as String;
    final bytes = File('$fixturesDir/$path').readAsBytesSync();
    final file = VaultFile.decode(bytes);
    final kdf = Argon2id(
      memory: file.memoryKiB,
      iterations: file.iterations,
      parallelism: file.parallelism,
      hashLength: file.hashLengthBytes,
    );
    final key = await kdf.deriveKeyFromPassword(password: password, nonce: file.salt);
    final box = SecretBox(
      file.ciphertext,
      nonce: file.nonce,
      mac: Mac(file.mac),
    );
    final clear = await cipher.decrypt(box, secretKey: key);
    final expected = jsonDecode(
        File('$fixturesDir/expected/$name.json').readAsStringSync());
    return const DeepCollectionEquality().equals(
        jsonDecode(utf8.decode(clear)), expected);
  }

  Future<bool> decryptShouldFail(String name, String password) async {
    final path = manifest[name]!['path'] as String;
    final bytes = File('$fixturesDir/$path').readAsBytesSync();
    try {
      final file = VaultFile.decode(bytes);
      // A real importer MUST validate KDF parameters BEFORE running Argon2
      // (an 8 GiB request aborts the process on allocation, as observed).
      if (file.memoryKiB <= 0 ||
          file.memoryKiB > 4 * 1024 * 1024 || // cap 4 GiB
          file.iterations < 1 ||
          file.iterations > 16 ||
          file.parallelism < 1 ||
          file.parallelism > 16 ||
          file.hashLengthBytes < 16 ||
          file.hashLengthBytes > 64) {
        return true; // rejected by header validation, as required
      }
      final kdf = Argon2id(
        memory: file.memoryKiB,
        iterations: file.iterations,
        parallelism: file.parallelism,
        hashLength: file.hashLengthBytes,
      );
      final key = await kdf.deriveKeyFromPassword(password: password, nonce: file.salt);
      final box = SecretBox(file.ciphertext, nonce: file.nonce, mac: Mac(file.mac));
      await cipher.decrypt(box, secretKey: key);
      return false;
    } catch (_) {
      return true;
    }
  }

  // Positive: must decrypt to the exact expected JSON.
  for (final (name, pw) in [
    ('schema1_normal', 'test-password-1'),
    ('schema2_normal', 'test-password-2'),
    ('schema3_normal', 'test-password-3'),
    ('rfc4226_sha1_secret', 'rfc-test'),
  ]) {
    final ok = await decryptPositive(name, pw);
    stdout.writeln('  verify $name: ${ok ? "PASS" : "FAIL"}');
    if (!ok) throw StateError('Fixture $name verification failed');
    passed++;
  }

  // Negative: must fail at AEAD/JSON layer.
  for (final (name, pw) in [
    ('wrong_password', 'wrong-password'),
    ('tampered_ciphertext', 'test-password-1'),
    ('truncated_ciphertext', 'test-password-1'),
    ('wrong_mac', 'test-password-1'),
    ('extreme_kdf_params', 'test-password-1'),
  ]) {
    final ok = await decryptShouldFail(name, pw);
    stdout.writeln('  verify $name (expect FAIL): ${ok ? "PASS" : "FAIL"}');
    if (!ok) throw StateError('Fixture $name should have failed decryption');
    passed++;
  }

  stdout.writeln('verify: $passed/9 checks passed');
}

Future<void> main(List<String> args) async {
  if (args.isNotEmpty && args.first == 'verify') {
    final dir = args.length > 1 ? args[1] : 'legacy-fixtures';
    await verify(dir);
    return;
  }

  final outDir = Directory('legacy-fixtures');
  final manifest = <String, dynamic>{};
  final generated = <Result>[];

  Future<void> write(Result r, String subdir) async {
    final dir = Directory('${outDir.path}/$subdir')..createSync(recursive: true);
    File('${dir.path}/${r.name}.rakvault').writeAsBytesSync(r.bytes);
    generated.add(r);
    manifest[r.name] = {
      'sha256': r.sha256Hex,
      'sizeBytes': r.bytes.length,
      'path': '$subdir/${r.name}.rakvault',
    };
    if (r.expectedJson != null) {
      final expDir = Directory('${outDir.path}/expected')
        ..createSync(recursive: true);
      File('${expDir.path}/${r.name}.json')
          .writeAsStringSync(const JsonEncoder.withIndent('  ').convert(r.expectedJson!));
    }
  }

  // --- Positive fixtures (schema 1/2/3, normal params) ---
  await write(
    await buildFixture(
        name: 'schema1_normal',
        password: 'test-password-1',
        payload: schema1Payload()),
    'schema1',
  );
  await write(
    await buildFixture(
        name: 'schema2_normal',
        password: 'test-password-2',
        payload: schema2Payload()),
    'schema2',
  );
  await write(
    await buildFixture(
        name: 'schema3_normal',
        password: 'test-password-3',
        payload: schema3Payload()),
    'schema3',
  );

  // --- RFC 4226 test vector (SHA1, 6 digits, 30s) ---
  final rfcPayload = {
    'schemaVersion': 1,
    'totpEntries': [
      _totp('totp-rfc4226', 'RFC4226', 'vector',
          'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 'SHA1', 6, 30,
          '2024-01-01T00:00:00.000Z'),
    ],
    'recoveryCodeSets': [],
  };
  await write(
    await buildFixture(
        name: 'rfc4226_sha1_secret',
        password: 'rfc-test',
        payload: rfcPayload),
    'schema1',
  );

  // --- Negative fixtures ---
  await write(
    await buildFixture(
        name: 'wrong_password',
        password: 'right-password',
        payload: schema2Payload()),
    'negative',
  );
  await write(
    await buildFixture(
        name: 'tampered_ciphertext',
        password: 'test-password-1',
        payload: schema1Payload(),
        tamper: true),
    'negative',
  );
  await write(
    await buildFixture(
        name: 'truncated_ciphertext',
        password: 'test-password-1',
        payload: schema1Payload(),
        truncated: true),
    'negative',
  );
  await write(
    await buildFixture(
        name: 'wrong_mac',
        password: 'test-password-1',
        payload: schema1Payload(),
        macReplace:
            'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'),
    'negative',
  );
  // Extreme KDF parameters: build a structurally-valid envelope whose header
  // claims an absurd memory cost (8 GiB). A compliant importer MUST reject
  // this from the header alone — before ever running Argon2 (this mirrors the
  // OOM-abort we observed when actually running 8 GiB Argon2id).
  final extreme = await buildFixture(
      name: 'extreme_kdf_params',
      password: 'test-password-1',
      payload: schema1Payload());
  {
    final decoded = jsonDecode(utf8.decode(extreme.bytes)) as Map<String, dynamic>;
    (decoded['kdf'] as Map<String, dynamic>)['memoryKiB'] = 8 * 1024 * 1024;
    final patched =
        Uint8List.fromList(utf8.encode(jsonEncode(decoded)));
    final sha = await Sha256().hash(patched);
    final hex = sha.bytes
        .map((b) => b.toRadixString(16).padLeft(2, '0'))
        .join();
    final dir = Directory('${outDir.path}/negative')..createSync(recursive: true);
    File('${dir.path}/extreme_kdf_params.rakvault').writeAsBytesSync(patched);
    generated.add(Result('extreme_kdf_params', patched, hex));
    manifest['extreme_kdf_params'] = {
      'sha256': hex,
      'sizeBytes': patched.length,
      'path': 'negative/extreme_kdf_params.rakvault',
    };
  }

  // --- Manifest ---
  manifest['_comment'] =
      'Generated by tools/legacy_fixtures (tag v1.2.0 frozen format). '
      'Passwords listed in docs/LEGACY_IMPORT.md. SHA-256 is final; '
      'do not regenerate casually.';
  manifest['_generatedAt'] = DateTime.now().toUtc().toIso8601String();
  File('${outDir.path}/manifest.json').writeAsStringSync(
      const JsonEncoder.withIndent('  ').convert(manifest));

  stdout.writeln('Generated ${generated.length} fixtures:');
  for (final r in generated) {
    stdout.writeln('  ${r.name}: ${r.sha256Hex} (${r.bytes.length} bytes)');
  }
}
