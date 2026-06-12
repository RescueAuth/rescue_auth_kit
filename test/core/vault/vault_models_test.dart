import 'dart:convert';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:rescue_auth_kit/core/vault/vault_models.dart';

// ---------------------------------------------------------------------------
// Seeded-Random generators for property-based testing.
// ---------------------------------------------------------------------------

const _alphanumeric =
    'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
const _base32Chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

String _randomString(
  Random rng,
  int minLen,
  int maxLen, {
  String chars = _alphanumeric,
}) {
  final len = minLen + rng.nextInt(maxLen - minLen + 1);
  return String.fromCharCodes(
    List.generate(len, (_) => chars.codeUnitAt(rng.nextInt(chars.length))),
  );
}

String _randomUuid(Random rng) {
  const hex = '0123456789abcdef';
  String segment(int len) => String.fromCharCodes(
    List.generate(len, (_) => hex.codeUnitAt(rng.nextInt(16))),
  );
  return '${segment(8)}-${segment(4)}-${segment(4)}-${segment(4)}-${segment(12)}';
}

DateTime _randomDateTime(Random rng) {
  final startSec = DateTime.utc(2020).millisecondsSinceEpoch ~/ 1000;
  final endSec = DateTime.utc(2030).millisecondsSinceEpoch ~/ 1000;
  final sec = startSec + rng.nextInt(endSec - startSec);
  return DateTime.fromMillisecondsSinceEpoch(sec * 1000, isUtc: true);
}

TotpHashAlgorithm _randomAlgorithm(Random rng) {
  return TotpHashAlgorithm.values[rng.nextInt(TotpHashAlgorithm.values.length)];
}

TotpCredential genTotpCredential(Random rng) {
  return TotpCredential(
    id: _randomUuid(rng),
    createdAt: _randomDateTime(rng),
    secretBase32: _randomString(rng, 8, 32, chars: _base32Chars),
    algorithm: _randomAlgorithm(rng),
    digits: 6 + rng.nextInt(5),
    period: 15 + rng.nextInt(106),
  );
}

RecoveryCodesCredential genRecoveryCodesCredential(Random rng) {
  final codeCount = rng.nextInt(17);
  final codes = List.generate(codeCount, (_) => _randomString(rng, 4, 16));
  return RecoveryCodesCredential(
    id: _randomUuid(rng),
    createdAt: _randomDateTime(rng),
    codes: codes,
  );
}

Credential _genCredential(Random rng) {
  return rng.nextBool()
      ? genTotpCredential(rng)
      : genRecoveryCodesCredential(rng);
}

ServiceProvider genProvider(Random rng) {
  final createdAt = _randomDateTime(rng);
  return ServiceProvider(
    id: _randomUuid(rng),
    name: _randomString(rng, 1, 30),
    createdAt: createdAt,
    updatedAt: _randomDateTime(rng),
  );
}

Account genAccount(Random rng, String providerId) {
  final credCount = rng.nextInt(5);
  final credentials = List.generate(credCount, (_) => _genCredential(rng));
  final createdAt = _randomDateTime(rng);
  return Account(
    id: _randomUuid(rng),
    providerId: providerId,
    displayName: _randomString(rng, 1, 30),
    createdAt: createdAt,
    updatedAt: _randomDateTime(rng),
    credentials: credentials,
  );
}

DeveloperEntry _genDeveloperEntry(Random rng) {
  return DeveloperEntry(
    id: _randomUuid(rng),
    type: DeveloperEntryType
        .values[rng.nextInt(DeveloperEntryType.values.length)],
    title: _randomString(rng, 1, 20),
    notes: _randomString(rng, 0, 50),
    createdAt: _randomDateTime(rng),
    updatedAt: _randomDateTime(rng),
    payload: <String, dynamic>{'key': _randomString(rng, 1, 10)},
  );
}

VaultData genVaultDataV3(Random rng) {
  final providerCount = rng.nextInt(5);
  final providers = List.generate(providerCount, (_) => genProvider(rng));

  final accounts = <Account>[];
  if (providers.isNotEmpty) {
    final accountCount = rng.nextInt(9);
    for (int i = 0; i < accountCount; i++) {
      final p = providers[rng.nextInt(providers.length)];
      accounts.add(genAccount(rng, p.id));
    }
  }

  final devEntryCount = rng.nextInt(4);
  final developerEntries = List.generate(
    devEntryCount,
    (_) => _genDeveloperEntry(rng),
  );

  return VaultData(
    schemaVersion: vaultDataSchemaVersion,
    providers: providers,
    accounts: accounts,
    developerSettings: DeveloperSettings(enabled: rng.nextBool()),
    developerEntries: developerEntries,
  );
}

void main() {
  group('Property 1: Credential JSON round-trip', () {
    test('TotpCredential round-trips for 200 instances', () {
      final rng = Random(42);
      for (int i = 0; i < 200; i++) {
        final c = genTotpCredential(rng);
        expect(Credential.fromJson(c.toJson()), equals(c), reason: 'iter $i');
      }
    });

    test('RecoveryCodesCredential round-trips for 200 instances', () {
      final rng = Random(42);
      for (int i = 0; i < 200; i++) {
        final c = genRecoveryCodesCredential(rng);
        expect(Credential.fromJson(c.toJson()), equals(c), reason: 'iter $i');
      }
    });

    test('Mixed credentials round-trip for 200 instances', () {
      final rng = Random(99);
      for (int i = 0; i < 200; i++) {
        final c = _genCredential(rng);
        expect(Credential.fromJson(c.toJson()), equals(c), reason: 'iter $i');
      }
    });
  });

  group('Property 2: ServiceProvider and Account JSON round-trip', () {
    test('ServiceProvider round-trips for 100 instances', () {
      final rng = Random(123);
      for (int i = 0; i < 100; i++) {
        final p = genProvider(rng);
        expect(
          ServiceProvider.fromJson(
            jsonDecode(jsonEncode(p.toJson())) as Map<String, dynamic>,
          ),
          equals(p),
          reason: 'iter $i',
        );
      }
    });

    test('Account round-trips for 200 instances', () {
      final rng = Random(123);
      for (int i = 0; i < 200; i++) {
        final a = genAccount(rng, _randomUuid(rng));
        expect(
          Account.fromJson(
            jsonDecode(jsonEncode(a.toJson())) as Map<String, dynamic>,
          ),
          equals(a),
          reason: 'iter $i',
        );
      }
    });
  });

  group('Property 3: VaultData v3 JSON round-trip', () {
    test('VaultData round-trips for 100 instances', () {
      final rng = Random(456);
      for (int i = 0; i < 100; i++) {
        final v = genVaultDataV3(rng);
        expect(
          VaultData.fromJson(
            jsonDecode(jsonEncode(v.toJson())) as Map<String, dynamic>,
          ),
          equals(v),
          reason: 'iter $i',
        );
      }
    });

    test('DeveloperEntry equality includes nested payload contents', () {
      final createdAt = DateTime.utc(2026, 6, 12);
      final base = DeveloperEntry(
        id: 'dev-1',
        type: DeveloperEntryType.genericSecret,
        title: 'Secret',
        notes: 'notes',
        createdAt: createdAt,
        updatedAt: createdAt,
        payload: const <String, dynamic>{
          'fields': [
            {'name': 'token', 'value': 'old'},
          ],
        },
      );
      final changed = base.copyWith(
        payload: const <String, dynamic>{
          'fields': [
            {'name': 'token', 'value': 'new'},
          ],
        },
      );

      expect(changed, isNot(equals(base)));
      expect(changed.hashCode, isNot(base.hashCode));
    });
  });

  group(
    'Property 12: ordering invariants and FormatException for invalid kind',
    () {
      test('RecoveryCodesCredential.codes ordering survives round-trip', () {
        final rng = Random(789);
        for (int i = 0; i < 200; i++) {
          final c = genRecoveryCodesCredential(rng);
          final restored = RecoveryCodesCredential.fromJson(c.toJson());
          expect(restored.codes, orderedEquals(c.codes), reason: 'iter $i');
        }
      });

      test('Credential.fromJson throws when kind is missing', () {
        expect(
          () => Credential.fromJson({
            'id': 'x',
            'createdAt': '2025-01-01T00:00:00.000Z',
          }),
          throwsA(
            isA<FormatException>().having(
              (e) => e.message,
              'message',
              contains('missing or not a string'),
            ),
          ),
        );
      });

      test('Credential.fromJson throws when kind is not a string', () {
        expect(
          () => Credential.fromJson({
            'kind': 42,
            'id': 'x',
            'createdAt': '2025-01-01T00:00:00.000Z',
          }),
          throwsA(isA<FormatException>()),
        );
      });

      test('Credential.fromJson throws when kind is unknown', () {
        expect(
          () => Credential.fromJson({
            'kind': 'unknownKind',
            'id': 'x',
            'createdAt': '2025-01-01T00:00:00.000Z',
          }),
          throwsA(
            isA<FormatException>().having(
              (e) => e.message,
              'message',
              contains('Unknown credential kind: unknownKind'),
            ),
          ),
        );
      });
    },
  );
}
