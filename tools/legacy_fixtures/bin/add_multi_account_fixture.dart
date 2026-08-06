// Additive fixture builder for the legacy .rakvault compatibility suite.
//
// Phase 1 fix: schema 1/2 mapping corrections require new fixtures that were
// not part of the original frozen set:
//   1. schema1_same_issuer_multi_account — several TOTP entries sharing the
//      same issuer but with DIFFERENT account names (must each map to their
//      own AuthAccount; service name may only group in the UI).
//   2. schema1_invalid_totp_params — entries with unknown algorithm / invalid
//      digits / invalid period / missing algorithm / invalid base32 (must be
//      preserved verbatim and listed in the "not imported" report, NEVER
//      silently defaulted to SHA1/6/30 and imported as a normal credential).
//
// This tool is STRICTLY ADDITIVE: it only writes the new fixture files and
// appends their manifest entries. Existing frozen fixtures and their manifest
// entries are read but never rewritten, so their SHA-256 baseline is intact.
//
// WARNING: fixtures intentionally contain non-secret placeholder secrets
// (RFC 4226 test vectors / fake recovery codes). Test data only.
library;

import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

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

/// Several TOTP entries with the SAME issuer but DIFFERENT account names.
/// The corrected mapper must produce one AuthAccount per entry.
Map<String, dynamic> sameIssuerMultiAccountPayload() {
  return {
    'schemaVersion': 1,
    'totpEntries': [
      _totp('totp-s1-ga-1', 'GitHub', 'alice@example.com',
          'JBSWY3DPEHPK3PXP', 'SHA1', 6, 30, '2024-01-15T10:30:00.000Z'),
      _totp('totp-s1-ga-2', 'GitHub', 'bob@example.com', _base32(7), 'SHA256',
          6, 30, '2024-02-01T08:00:00.000Z'),
      _totp('totp-s1-ga-3', 'GitHub', 'carol@example.com', _base32(11),
          'SHA512', 8, 30, '2024-03-01T09:00:00.000Z'),
    ],
    'recoveryCodeSets': [
      _recoverySet('rcs-s1-ga', 'GitHub backup codes',
          ['AAAA-BBBB-CCCC', 'DDDD-EEEE-FFFF'], '2024-01-15T10:31:00.000Z'),
    ],
  };
}

/// Entries with unknown / invalid TOTP parameters. Each must be preserved
/// verbatim (original algorithm/digits/period) and reported as NOT imported,
/// never silently defaulted to SHA1/6/30.
Map<String, dynamic> invalidTotpParamsPayload() {
  final payload = <String, dynamic>{
    'schemaVersion': 1,
    'totpEntries': [
      // One fully valid entry — should import normally.
      _totp('totp-s1-ok', 'GitHub', 'alice@example.com',
          'JBSWY3DPEHPK3PXP', 'SHA1', 6, 30, '2024-01-15T10:30:00.000Z'),
      // Unknown algorithm — not silently defaulted to SHA1.
      _totp('totp-s1-badalgo', 'Google', 'bob@example.com', _base32(7), 'MD5',
          6, 30, '2024-02-01T08:00:00.000Z'),
      // digits == 0 — invalid.
      _totp('totp-s1-baddigits', 'AWS', 'admin@example.com', _base32(13),
          'SHA1', 0, 30, '2024-04-01T10:00:00.000Z'),
      // period <= 0 — invalid.
      _totp('totp-s1-badperiod', 'Dropbox', 'dave@example.com', _base32(17),
          'SHA1', 6, -30, '2024-05-01T11:00:00.000Z'),
      // Missing algorithm field entirely — invalid, not defaulted.
      {
        'id': 'totp-s1-noalgo',
        'issuer': 'GitLab',
        'accountName': 'carol@example.com',
        'secretBase32': _base32(19),
        'digits': 6,
        'period': 30,
        'createdAt': '2024-03-01T09:00:00.000Z',
      },
      // Malformed base32 — cannot produce a correct TOTP.
      {
        'id': 'totp-s1-badsecret',
        'issuer': 'Notion',
        'accountName': 'eve@example.com',
        'secretBase32': 'INVALID!!!',
        'algorithm': 'SHA1',
        'digits': 6,
        'period': 30,
        'createdAt': '2024-06-01T09:00:00.000Z',
      },
    ],
    'recoveryCodeSets': [],
  };
  return payload;
}

Future<Uint8List> buildEncrypted({
  required String password,
  required Map<String, dynamic> payload,
}) async {
  final rand = Random.secure();
  final salt = Uint8List.fromList(
      List<int>.generate(_defaultSaltLen, (_) => rand.nextInt(256)));
  final nonce = Uint8List.fromList(
      List<int>.generate(24, (_) => rand.nextInt(256)));

  final kdf = Argon2id(
    memory: _defaultMemKiB,
    iterations: _defaultIter,
    parallelism: _defaultParallel,
    hashLength: _defaultHashLen,
  );
  final key = await kdf.deriveKeyFromPassword(password: password, nonce: salt);

  final cleartext = Uint8List.fromList(utf8.encode(jsonEncode(payload)));
  final cipher = Cryptography.instance.xchacha20Poly1305Aead();
  final box = await cipher.encrypt(cleartext, secretKey: key, nonce: nonce);

  final file = {
    'magic': _magic,
    'version': _version,
    'kdf': {
      'name': _argon2id,
      'memoryKiB': _defaultMemKiB,
      'iterations': _defaultIter,
      'parallelism': _defaultParallel,
      'hashLengthBytes': _defaultHashLen,
      'saltB64': _b64e(salt),
    },
    'cipher': _cipher,
    'nonceB64': _b64e(nonce),
    'macB64': _b64e(box.mac.bytes),
    'ciphertextB64': _b64e(box.cipherText),
  };
  return Uint8List.fromList(utf8.encode(jsonEncode(file)));
}

Future<void> main(List<String> args) async {
  final fixturesDir = Directory(args.isNotEmpty ? args[0] : 'legacy-fixtures');
  if (!fixturesDir.existsSync()) {
    stderr.writeln('Run from tools/legacy_fixtures (fixturesDir = legacy-fixtures).');
    exit(1);
  }

  // Load the existing manifest WITHOUT touching its current entries.
  final manifestPath = '${fixturesDir.path}/manifest.json';
  final manifest = jsonDecode(File(manifestPath).readAsStringSync())
      as Map<String, dynamic>;

  Future<void> add({
    required String name,
    required String password,
    required Map<String, dynamic> payload,
    required String subdir,
  }) async {
    final bytes = await buildEncrypted(password: password, payload: payload);
    final sha = await Sha256().hash(bytes);
    final hex =
        sha.bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
    final dir = Directory('${fixturesDir.path}/$subdir')
      ..createSync(recursive: true);
    File('${dir.path}/$name.rakvault').writeAsBytesSync(bytes);
    // Expected plaintext for positive fixtures.
    final expDir = Directory('${fixturesDir.path}/expected')
      ..createSync(recursive: true);
    File('${expDir.path}/$name.json').writeAsStringSync(
        const JsonEncoder.withIndent('  ').convert(payload));
    manifest[name] = {
      'sha256': hex,
      'sizeBytes': bytes.length,
      'path': '$subdir/$name.rakvault',
    };
    stdout.writeln('added $name: $hex (${bytes.length} bytes)');
  }

  await add(
    name: 'schema1_same_issuer_multi_account',
    password: 'test-password-1',
    payload: sameIssuerMultiAccountPayload(),
    subdir: 'schema1',
  );
  await add(
    name: 'schema1_invalid_totp_params',
    password: 'test-password-1',
    payload: invalidTotpParamsPayload(),
    subdir: 'schema1',
  );

  // Re-write the manifest preserving all pre-existing entries verbatim.
  File(manifestPath).writeAsStringSync(
      const JsonEncoder.withIndent('  ').convert(manifest));
  stdout.writeln('manifest updated: ${manifest.keys.length - 2} fixtures');
}
