import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:rescue_auth_kit/core/backup/vault_backup_models.dart';
import 'package:rescue_auth_kit/core/backup/vault_backup_service.dart';
import 'package:rescue_auth_kit/core/crypto/vault_crypto.dart';
import 'package:rescue_auth_kit/core/vault/vault_models.dart';
import 'package:rescue_auth_kit/core/vault/vault_repository.dart';

void main() {
  test('createNewVault -> open works with correct password', () async {
    final dir = await Directory.systemTemp.createTemp('rak_vault_test_');

    try {
      final vaultPath = p.join(dir.path, 'vault.rakvault');
      final repo = VaultRepository.forPath(vaultFilePath: vaultPath);

      final handle = await repo.createNewVault(password: 'testpassword');
      expect(await repo.vaultFileExists(), isTrue);
      expect(handle.data.providers, isEmpty);
      expect(handle.data.accounts, isEmpty);

      final opened = await repo.open(password: 'testpassword');
      expect(opened.data.providers, isEmpty);
      expect(opened.data.accounts, isEmpty);
    } finally {
      await dir.delete(recursive: true);
    }
  });

  test('open throws VaultAuthException on wrong password', () async {
    final dir = await Directory.systemTemp.createTemp('rak_vault_test_');

    try {
      final vaultPath = p.join(dir.path, 'vault.rakvault');
      final repo = VaultRepository.forPath(vaultFilePath: vaultPath);

      await repo.createNewVault(password: 'correctpassword');

      await expectLater(
        repo.open(password: 'wrongpassword'),
        throwsA(isA<VaultAuthException>()),
      );
    } finally {
      await dir.delete(recursive: true);
    }
  });

  test('save persists data changes', () async {
    final dir = await Directory.systemTemp.createTemp('rak_vault_test_');

    try {
      final vaultPath = p.join(dir.path, 'vault.rakvault');
      final repo = VaultRepository.forPath(vaultFilePath: vaultPath);

      var handle = await repo.createNewVault(password: 'testpassword');

      final createdAt = DateTime.parse('2026-01-01T12:00:00Z');
      final provider = ServiceProvider(
        id: 'p-1',
        name: 'Example',
        createdAt: createdAt,
        updatedAt: createdAt,
      );
      final account = Account(
        id: 'acc-1',
        providerId: provider.id,
        displayName: 'user@example.com',
        createdAt: createdAt,
        updatedAt: createdAt,
        credentials: [
          TotpCredential(
            id: 't1',
            createdAt: createdAt,
            secretBase32: 'JBSWY3DPEHPK3PXP',
            algorithm: TotpHashAlgorithm.sha1,
            digits: 6,
            period: 30,
          ),
        ],
      );

      final updated = handle.data.copyWith(
        providers: [provider],
        accounts: [account],
      );
      handle = handle.copyWith(data: updated);
      await repo.save(handle);

      final reopened = await repo.open(password: 'testpassword');
      expect(reopened.data.providers, hasLength(1));
      expect(reopened.data.providers.single.name, 'Example');
      expect(reopened.data.accounts, hasLength(1));
      expect(reopened.data.accounts.single.displayName, 'user@example.com');
    } finally {
      await dir.delete(recursive: true);
    }
  });

  test('exportBytes/importBytes roundtrip', () async {
    final dir1 = await Directory.systemTemp.createTemp('rak_vault_test_src_');
    final dir2 = await Directory.systemTemp.createTemp('rak_vault_test_dst_');

    try {
      final srcPath = p.join(dir1.path, 'vault.rakvault');
      final dstPath = p.join(dir2.path, 'vault.rakvault');

      final repo1 = VaultRepository.forPath(vaultFilePath: srcPath);
      final repo2 = VaultRepository.forPath(vaultFilePath: dstPath);

      await repo1.createNewVault(password: 'testpassword');
      final bytes = await repo1.exportBytes();
      expect(bytes, isA<Uint8List>());

      final imported = await repo2.importBytes(
        bytes: bytes,
        password: 'testpassword',
      );
      expect(await repo2.vaultFileExists(), isTrue);
      expect(imported.data.schemaVersion, vaultDataSchemaVersion);

      final opened = await repo2.open(password: 'testpassword');
      expect(opened.data.accounts, isEmpty);
    } finally {
      await dir1.delete(recursive: true);
      await dir2.delete(recursive: true);
    }
  });

  test('save triggers automatic backup without blocking persistence', () async {
    final dir = await Directory.systemTemp.createTemp('rak_vault_test_');

    try {
      final vaultPath = p.join(dir.path, 'vault.rakvault');
      final backupService = _RecordingBackupService();
      final repo = VaultRepository.forPath(
        vaultFilePath: vaultPath,
        backupService: backupService,
      );

      var handle = await repo.createNewVault(password: 'testpassword');
      handle = handle.copyWith(
        data: handle.data.copyWith(
          providers: [
            ServiceProvider(
              id: 'p1',
              name: 'Example',
              createdAt: DateTime.utc(2026),
              updatedAt: DateTime.utc(2026),
            ),
          ],
        ),
      );
      await repo.save(handle);

      expect(backupService.autoBackupCount, 2);
      expect(await repo.open(password: 'testpassword'), isA<VaultHandle>());
    } finally {
      await dir.delete(recursive: true);
    }
  });

  test('checkpoint backup failure does not block import', () async {
    final dir1 = await Directory.systemTemp.createTemp('rak_vault_test_src_');
    final dir2 = await Directory.systemTemp.createTemp('rak_vault_test_dst_');

    try {
      final source = VaultRepository.forPath(
        vaultFilePath: p.join(dir1.path, 'vault.rakvault'),
      );
      await source.createNewVault(password: 'testpassword');
      final bytes = await source.exportBytes();

      final backupService = _RecordingBackupService(throwOnCheckpoint: true);
      final repo = VaultRepository.forPath(
        vaultFilePath: p.join(dir2.path, 'vault.rakvault'),
        backupService: backupService,
      );
      await repo.createNewVault(password: 'oldpassword');
      await repo.createCheckpointBackup(reason: 'import');
      await repo.importBytes(bytes: bytes, password: 'testpassword');

      expect(backupService.checkpointCount, 1);
      expect(await repo.open(password: 'testpassword'), isA<VaultHandle>());
    } finally {
      await dir1.delete(recursive: true);
      await dir2.delete(recursive: true);
    }
  });

  test(
    'open silently upgrades schema v1 vault file to current schema',
    () async {
      final dir = await Directory.systemTemp.createTemp(
        'rak_vault_test_legacy_',
      );

      try {
        final vaultPath = p.join(dir.path, 'vault.rakvault');
        const password = 'testpassword';
        await _writeLegacySchema1Vault(
          vaultPath: vaultPath,
          password: password,
        );
        final beforeBytes = await File(vaultPath).readAsBytes();

        final repo = VaultRepository.forPath(vaultFilePath: vaultPath);
        final opened = await repo.open(password: password);
        final afterBytes = await File(vaultPath).readAsBytes();

        expect(opened.data.schemaVersion, vaultDataSchemaVersion);
        expect(opened.data.developerSettings.enabled, isFalse);
        expect(opened.data.developerEntries, isEmpty);
        expect(afterBytes, isNot(beforeBytes));

        final rawPayload = await _decryptVaultPayload(
          bytes: Uint8List.fromList(afterBytes),
          password: password,
        );
        expect(rawPayload['schemaVersion'], vaultDataSchemaVersion);
        expect(rawPayload['developerSettings'], {'enabled': false});
        expect(rawPayload['developerEntries'], isEmpty);
      } finally {
        await dir.delete(recursive: true);
      }
    },
  );

  // v2 → v3 migration integration tests
  _v2MigrationIntegrationTests();

  // Future-schema vault rejection and wrong-password regression tests
  _futureSchemaAndAuthTests();
}

class _RecordingBackupService implements VaultBackupService {
  _RecordingBackupService({this.throwOnCheckpoint = false});

  final bool throwOnCheckpoint;
  var autoBackupCount = 0;
  var checkpointCount = 0;

  @override
  String get locationDescription => 'recording';

  @override
  Future<void> createCheckpoint({
    required Uint8List bytes,
    required String reason,
  }) async {
    checkpointCount++;
    if (throwOnCheckpoint) throw const FileSystemException('checkpoint failed');
  }

  @override
  Future<BackupSettings> loadSettings() async => BackupSettings.defaults();

  @override
  Future<void> maybeAutoBackup(Uint8List bytes) async {
    autoBackupCount++;
  }

  @override
  Future<void> setAutoBackupEnabled(bool enabled) async {}

  @override
  Future<void> setAutoBackupFrequency(AutoBackupFrequency frequency) async {}

  @override
  Future<void> updateSettings(BackupSettings settings) async {}
}

Future<void> _writeLegacySchema1Vault({
  required String vaultPath,
  required String password,
}) async {
  final crypto = VaultCrypto();
  final salt = crypto.newSalt();
  final kdf = VaultKdfParams.forTesting(salt: salt);
  final key = await crypto.deriveKeyFromPassword(
    password: password,
    params: kdf,
  );
  final legacyJson = jsonEncode({
    'schemaVersion': 1,
    'totpEntries': const [],
    'recoveryCodeSets': const [],
  });
  final vaultFile = await crypto.encryptToFile(
    cleartextJsonBytes: Uint8List.fromList(utf8.encode(legacyJson)),
    key: key,
    kdfParams: kdf,
  );

  final file = File(vaultPath);
  await file.parent.create(recursive: true);
  await file.writeAsBytes(vaultFile.encode(), flush: true);
}

Future<Map<String, dynamic>> _decryptVaultPayload({
  required Uint8List bytes,
  required String password,
}) async {
  final crypto = VaultCrypto();
  final vaultFile = VaultFile.decode(bytes);
  final key = await crypto.deriveKeyFromPassword(
    password: password,
    params: vaultFile.kdf,
  );
  final clearBytes = await crypto.decryptFile(file: vaultFile, key: key);
  return jsonDecode(utf8.decode(clearBytes)) as Map<String, dynamic>;
}

// ---------------------------------------------------------------------------
// Integration test: open a v2 fixture vault produces v3 with no on-disk v2
// cleartext.
// ---------------------------------------------------------------------------

Future<void> _writeV2FixtureVault({
  required String vaultPath,
  required String password,
}) async {
  final crypto = VaultCrypto();
  final salt = crypto.newSalt();
  final kdf = VaultKdfParams.forTesting(salt: salt);
  final key = await crypto.deriveKeyFromPassword(
    password: password,
    params: kdf,
  );

  final v2Json = jsonEncode({
    'schemaVersion': 2,
    'totpEntries': [
      {
        'id': 'totp-001',
        'issuer': 'GitHub',
        'accountName': 'user@github.com',
        'secretBase32': 'JBSWY3DPEHPK3PXP',
        'algorithm': 'SHA1',
        'digits': 6,
        'period': 30,
        'createdAt': '2024-06-15T10:30:00.000Z',
      },
    ],
    'recoveryCodeSets': [
      {
        'id': 'rc-001',
        'title': 'GitHub Recovery',
        'codes': ['abc-123', 'def-456', 'ghi-789'],
        'createdAt': '2024-07-01T08:00:00.000Z',
      },
    ],
    'developerSettings': {'enabled': false},
    'developerEntries': <dynamic>[],
  });

  final vaultFile = await crypto.encryptToFile(
    cleartextJsonBytes: Uint8List.fromList(utf8.encode(v2Json)),
    key: key,
    kdfParams: kdf,
  );

  final file = File(vaultPath);
  await file.parent.create(recursive: true);
  await file.writeAsBytes(vaultFile.encode(), flush: true);
}

void _v2MigrationIntegrationTests() {
  test(
    'open a v2 fixture vault produces v3 with providers/accounts and no leftover files',
    () async {
      final dir = await Directory.systemTemp.createTemp(
        'rak_vault_v2_migration_',
      );

      try {
        final vaultPath = p.join(dir.path, 'vault.rekvault');
        const password = 'test-migration-pw';

        await _writeV2FixtureVault(vaultPath: vaultPath, password: password);

        final repo = VaultRepository.forPath(vaultFilePath: vaultPath);
        final handle = await repo.open(password: password);
        final data = handle.data;

        expect(data.schemaVersion, 3);

        // 1 TOTP-derived provider + 1 recovery-derived provider = 2 providers
        expect(data.providers, hasLength(2));
        expect(data.providers[0].name, 'GitHub');
        expect(data.providers[1].name, 'GitHub Recovery');

        // 2 accounts total
        expect(data.accounts, hasLength(2));

        // First account: TOTP under GitHub provider
        final totpAccount = data.accounts[0];
        expect(totpAccount.providerId, data.providers[0].id);
        expect(totpAccount.displayName, 'user@github.com');
        final totpCred = totpAccount.credentials.single as TotpCredential;
        expect(totpCred.id, 'totp-001');
        expect(totpCred.secretBase32, 'JBSWY3DPEHPK3PXP');

        // Second account: recovery codes under its own provider
        final rcAccount = data.accounts[1];
        expect(rcAccount.providerId, data.providers[1].id);
        expect(rcAccount.displayName, 'GitHub Recovery');
        final rcCred = rcAccount.credentials.single as RecoveryCodesCredential;
        expect(rcCred.codes, ['abc-123', 'def-456', 'ghi-789']);

        // Re-open and assert structural equality (idempotence at file IO).
        final handle2 = await repo.open(password: password);
        expect(handle2.data, equals(data));

        // No .bak or .tmp left.
        final leftover = dir.listSync().where((e) {
          final name = p.basename(e.path);
          return name.endsWith('.bak') || name.endsWith('.tmp');
        });
        expect(leftover, isEmpty);
      } finally {
        await dir.delete(recursive: true);
      }
    },
  );
}

// ---------------------------------------------------------------------------
// Future-schema rejection + wrong-password regression
// ---------------------------------------------------------------------------

Future<void> _writeFutureSchemaVault({
  required String vaultPath,
  required String password,
}) async {
  final crypto = VaultCrypto();
  final salt = crypto.newSalt();
  final kdf = VaultKdfParams.forTesting(salt: salt);
  final key = await crypto.deriveKeyFromPassword(
    password: password,
    params: kdf,
  );

  final futureJson = jsonEncode({
    'schemaVersion': 99,
    'providers': <dynamic>[],
    'accounts': <dynamic>[],
    'developerSettings': {'enabled': false},
    'developerEntries': <dynamic>[],
  });

  final vaultFile = await crypto.encryptToFile(
    cleartextJsonBytes: Uint8List.fromList(utf8.encode(futureJson)),
    key: key,
    kdfParams: kdf,
  );

  final file = File(vaultPath);
  await file.parent.create(recursive: true);
  await file.writeAsBytes(vaultFile.encode(), flush: true);
}

void _futureSchemaAndAuthTests() {
  test(
    'open throws VaultFormatException for future schemaVersion and leaves file unchanged',
    () async {
      final dir = await Directory.systemTemp.createTemp(
        'rak_vault_future_schema_',
      );

      try {
        final vaultPath = p.join(dir.path, 'vault.rekvault');
        const password = 'test-future-pw';
        await _writeFutureSchemaVault(vaultPath: vaultPath, password: password);

        final bytesBefore = await File(vaultPath).readAsBytes();

        final repo = VaultRepository.forPath(vaultFilePath: vaultPath);
        await expectLater(
          repo.open(password: password),
          throwsA(
            allOf(
              isA<VaultFormatException>(),
              predicate<VaultFormatException>(
                (e) => e.message.contains('99'),
                'message identifies schemaVersion 99',
              ),
            ),
          ),
        );

        final bytesAfter = await File(vaultPath).readAsBytes();
        expect(bytesAfter, equals(bytesBefore));
      } finally {
        await dir.delete(recursive: true);
      }
    },
  );

  test(
    'open still throws VaultAuthException on wrong password (regression guard)',
    () async {
      final dir = await Directory.systemTemp.createTemp(
        'rak_vault_auth_regression_',
      );

      try {
        final vaultPath = p.join(dir.path, 'vault.rekvault');
        const correctPassword = 'correct-password';
        const wrongPassword = 'wrong-password';

        await _writeFutureSchemaVault(
          vaultPath: vaultPath,
          password: correctPassword,
        );

        final repo = VaultRepository.forPath(vaultFilePath: vaultPath);
        await expectLater(
          repo.open(password: wrongPassword),
          throwsA(isA<VaultAuthException>()),
        );
      } finally {
        await dir.delete(recursive: true);
      }
    },
  );
}
