import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;

import 'package:rescue_auth_kit/core/backup/vault_backup_models.dart';
import 'package:rescue_auth_kit/core/backup/vault_backup_service.dart';
import 'package:rescue_auth_kit/core/vault/vault_models.dart';
import 'package:rescue_auth_kit/core/vault/vault_repository.dart';
import 'package:rescue_auth_kit/core/vault/vault_session.dart';

void main() {
  test('createNew -> lock -> unlock works', () async {
    final dir = await Directory.systemTemp.createTemp('rak_vault_test_');

    try {
      final vaultPath = p.join(dir.path, 'vault.rakvault');
      final repo = VaultRepository.forPath(vaultFilePath: vaultPath);
      final session = VaultSession(repo);

      expect(await session.vaultExists(), isFalse);
      expect(session.isUnlocked, isFalse);

      await session.createNew(password: 'testpassword');
      expect(await session.vaultExists(), isTrue);
      expect(session.isUnlocked, isTrue);
      expect(session.data.providers, isEmpty);
      expect(session.data.accounts, isEmpty);

      session.lock();
      expect(session.isUnlocked, isFalse);

      await session.unlock(password: 'testpassword');
      expect(session.isUnlocked, isTrue);
    } finally {
      await dir.delete(recursive: true);
    }
  });

  test('unlock with wrong password throws VaultAuthException', () async {
    final dir = await Directory.systemTemp.createTemp('rak_vault_test_');
    try {
      final vaultPath = p.join(dir.path, 'vault.rakvault');
      final repo = VaultRepository.forPath(vaultFilePath: vaultPath);
      final session = VaultSession(repo);

      await session.createNew(password: 'correctpassword');
      session.lock();

      await expectLater(
        session.unlock(password: 'wrongpassword'),
        throwsA(isA<VaultAuthException>()),
      );
    } finally {
      await dir.delete(recursive: true);
    }
  });

  test('setData/save persists changes', () async {
    final dir = await Directory.systemTemp.createTemp('rak_vault_test_');

    try {
      final vaultPath = p.join(dir.path, 'vault.rakvault');
      final repo = VaultRepository.forPath(vaultFilePath: vaultPath);
      final session = VaultSession(repo);

      await session.createNew(password: 'testpassword');

      final createdAt = DateTime.parse('2026-01-01T12:00:00Z');
      final provider = ServiceProvider(
        id: 'p-1',
        name: 'Example',
        createdAt: createdAt,
        updatedAt: createdAt,
      );
      final account = Account(
        id: 'a-1',
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

      session.setData(
        session.data.copyWith(providers: [provider], accounts: [account]),
      );
      await session.save();

      session.lock();
      await session.unlock(password: 'testpassword');

      expect(session.data.providers, hasLength(1));
      expect(session.data.accounts, hasLength(1));
      expect(session.data.accounts.single.displayName, 'user@example.com');
    } finally {
      await dir.delete(recursive: true);
    }
  });

  test(
    'developer backups are enabled by default and entries persist',
    () async {
      final dir = await Directory.systemTemp.createTemp('rak_vault_test_');

      try {
        final vaultPath = p.join(dir.path, 'vault.rakvault');
        final repo = VaultRepository.forPath(vaultFilePath: vaultPath);
        final session = VaultSession(repo);

        await session.createNew(password: 'testpassword');
        expect(session.data.developerSettings.enabled, isTrue);

        await session.addDeveloperEntry(
          type: DeveloperEntryType.androidSigningKey,
          title: 'RescueAuthKit Android',
          payload: const {
            'projectName': 'RescueAuthKit',
            'packageName': 'com.xincy.rescue_auth_kit',
            'keystoreFileName': 'upload-keystore.jks',
            'keystoreBytesBase64': 'AQIDBA==',
            'storePassword': 'store-pass',
            'keyAlias': 'upload',
            'keyPassword': 'key-pass',
          },
        );
        expect(session.data.developerEntries.length, 1);

        final id = session.data.developerEntries.single.id;
        await session.updateDeveloperEntry(
          id: id,
          title: 'Updated Android',
          notes: 'release',
          payload: const {
            'projectName': 'Updated',
            'packageName': 'com.example.updated',
            'keystoreFileName': 'upload-keystore.jks',
            'keystoreBytesBase64': 'AQIDBA==',
            'storePassword': 'store-pass',
            'keyAlias': 'upload',
            'keyPassword': 'key-pass',
          },
        );
        expect(session.data.developerEntries.single.title, 'Updated Android');

        await session.removeDeveloperEntry(id);
        expect(session.data.developerEntries, isEmpty);
      } finally {
        await dir.delete(recursive: true);
      }
    },
  );

  // -------------------------------------------------------------------------
  // Account-centric API tests covering provider + account + credential ops.
  // -------------------------------------------------------------------------

  group('account-centric API', () {
    Future<({VaultSession session, Directory dir})> setupVault() async {
      final dir = await Directory.systemTemp.createTemp('rak_session_api_');
      final vaultPath = p.join(dir.path, 'vault.rakvault');
      final repo = VaultRepository.forPath(vaultFilePath: vaultPath);
      final session = VaultSession(repo);
      await session.createNew(password: 'testpassword');
      return (session: session, dir: dir);
    }

    Future<void> tearDownVault(Directory dir) async {
      for (var attempt = 0; attempt < 5; attempt++) {
        try {
          await dir.delete(recursive: true);
          return;
        } on FileSystemException {
          if (attempt == 4) rethrow;
          await Future<void>.delayed(const Duration(milliseconds: 100));
        }
      }
    }

    test('addProvider persists and notifies once', () async {
      final s = await setupVault();
      try {
        var notifyCount = 0;
        s.session.addListener(() => notifyCount++);

        final p = await s.session.addProvider(name: 'GitHub');
        expect(s.session.data.providers, hasLength(1));
        expect(s.session.data.providers.single.id, p.id);
        expect(notifyCount, 1);

        // Persistence.
        s.session.lock();
        await s.session.unlock(password: 'testpassword');
        expect(s.session.data.providers.single.name, 'GitHub');
      } finally {
        await tearDownVault(s.dir);
      }
    });

    test('renameProvider updates and notifies once', () async {
      final s = await setupVault();
      try {
        final p = await s.session.addProvider(name: 'Initial');
        var notifyCount = 0;
        s.session.addListener(() => notifyCount++);

        await s.session.renameProvider(providerId: p.id, name: 'Renamed');
        expect(s.session.data.providers.single.name, 'Renamed');
        expect(notifyCount, 1);
      } finally {
        await tearDownVault(s.dir);
      }
    });

    test('removeProvider cascades to accounts and notifies once', () async {
      final s = await setupVault();
      try {
        final p = await s.session.addProvider(name: 'P');
        await s.session.addAccount(providerId: p.id, displayName: 'a1');
        await s.session.addAccount(providerId: p.id, displayName: 'a2');

        var notifyCount = 0;
        s.session.addListener(() => notifyCount++);

        await s.session.removeProvider(p.id);
        expect(s.session.data.providers, isEmpty);
        expect(s.session.data.accounts, isEmpty);
        expect(notifyCount, 1);
      } finally {
        await tearDownVault(s.dir);
      }
    });

    test('addAccount under existing provider', () async {
      final s = await setupVault();
      try {
        final p = await s.session.addProvider(name: 'GitHub');
        final account = await s.session.addAccount(
          providerId: p.id,
          displayName: 'me@example.com',
        );
        expect(s.session.data.accounts, hasLength(1));
        expect(s.session.data.accounts.single.id, account.id);
        expect(s.session.data.accounts.single.providerId, p.id);
      } finally {
        await tearDownVault(s.dir);
      }
    });

    test('addAccount under unknown provider throws', () async {
      final s = await setupVault();
      try {
        await expectLater(
          s.session.addAccount(providerId: 'unknown', displayName: 'X'),
          throwsA(isA<ProviderNotFoundError>()),
        );
      } finally {
        await tearDownVault(s.dir);
      }
    });

    test('renameAccount updates display name', () async {
      final s = await setupVault();
      try {
        final p = await s.session.addProvider(name: 'P');
        final a = await s.session.addAccount(
          providerId: p.id,
          displayName: 'Initial',
        );
        await s.session.renameAccount(accountId: a.id, displayName: 'Renamed');
        expect(s.session.data.accounts.single.displayName, 'Renamed');
      } finally {
        await tearDownVault(s.dir);
      }
    });

    test('moveAccountToProvider switches providerId', () async {
      final s = await setupVault();
      try {
        final p1 = await s.session.addProvider(name: 'P1');
        final p2 = await s.session.addProvider(name: 'P2');
        final a = await s.session.addAccount(
          providerId: p1.id,
          displayName: 'me',
        );
        await s.session.moveAccountToProvider(
          accountId: a.id,
          newProviderId: p2.id,
        );
        expect(s.session.data.accounts.single.providerId, p2.id);
      } finally {
        await tearDownVault(s.dir);
      }
    });

    test('addCredentialToAccount appends and notifies once', () async {
      final s = await setupVault();
      try {
        final p = await s.session.addProvider(name: 'GitHub');
        final a = await s.session.addAccount(
          providerId: p.id,
          displayName: 'me',
        );

        var notifyCount = 0;
        s.session.addListener(() => notifyCount++);

        final draft = TotpCredential(
          id: 't1',
          createdAt: DateTime.utc(2026, 5, 26),
          secretBase32: 'JBSWY3DPEHPK3PXP',
          algorithm: TotpHashAlgorithm.sha1,
          digits: 6,
          period: 30,
        );
        await s.session.addCredentialToAccount(accountId: a.id, draft: draft);

        expect(s.session.data.accounts.single.credentials, hasLength(1));
        expect(
          s.session.data.accounts.single.credentials.single,
          equals(draft),
        );
        expect(notifyCount, 1);
      } finally {
        await tearDownVault(s.dir);
      }
    });

    test('addCredentialAsNewAccount creates account holding draft', () async {
      final s = await setupVault();
      try {
        final p = await s.session.addProvider(name: 'GitHub');
        final draft = RecoveryCodesCredential(
          id: 'r1',
          createdAt: DateTime.utc(2026, 5, 26),
          codes: const ['c1', 'c2'],
        );
        final a = await s.session.addCredentialAsNewAccount(
          providerId: p.id,
          displayName: 'codes',
          draft: draft,
        );
        expect(s.session.data.accounts.single.id, a.id);
        expect(
          s.session.data.accounts.single.credentials.single,
          equals(draft),
        );
      } finally {
        await tearDownVault(s.dir);
      }
    });

    test('addCredentialAsNewProviderAndAccount creates both', () async {
      final s = await setupVault();
      try {
        final draft = TotpCredential(
          id: 't1',
          createdAt: DateTime.utc(2026, 5, 26),
          secretBase32: 'JBSWY3DPEHPK3PXP',
          algorithm: TotpHashAlgorithm.sha1,
          digits: 6,
          period: 30,
        );
        final result = await s.session.addCredentialAsNewProviderAndAccount(
          providerName: 'GitHub',
          accountDisplayName: 'me',
          draft: draft,
        );
        expect(s.session.data.providers.single.id, result.provider.id);
        expect(s.session.data.providers.single.name, 'GitHub');
        expect(s.session.data.accounts.single.id, result.account.id);
        expect(
          s.session.data.accounts.single.credentials.single,
          equals(draft),
        );
      } finally {
        await tearDownVault(s.dir);
      }
    });

    test(
      'removeCredentialFromAccount keeps emptied account in place',
      () async {
        final s = await setupVault();
        try {
          final p = await s.session.addProvider(name: 'P');
          final draft = TotpCredential(
            id: 't1',
            createdAt: DateTime.utc(2026, 5, 26),
            secretBase32: 'JBSWY3DPEHPK3PXP',
            algorithm: TotpHashAlgorithm.sha1,
            digits: 6,
            period: 30,
          );
          final a = await s.session.addCredentialAsNewAccount(
            providerId: p.id,
            displayName: 'me',
            draft: draft,
          );

          await s.session.removeCredentialFromAccount(
            accountId: a.id,
            credentialId: 't1',
          );

          expect(s.session.data.accounts, hasLength(1));
          expect(s.session.data.accounts.single.id, a.id);
          expect(s.session.data.accounts.single.credentials, isEmpty);
        } finally {
          await tearDownVault(s.dir);
        }
      },
    );

    test('mutators throw VaultLockedException when locked', () async {
      final s = await setupVault();
      try {
        s.session.lock();

        await expectLater(
          s.session.addProvider(name: 'X'),
          throwsA(isA<VaultLockedException>()),
        );
        await expectLater(
          s.session.renameProvider(providerId: 'x', name: 'X'),
          throwsA(isA<VaultLockedException>()),
        );
        await expectLater(
          s.session.removeProvider('x'),
          throwsA(isA<VaultLockedException>()),
        );
        await expectLater(
          s.session.addAccount(providerId: 'x', displayName: 'X'),
          throwsA(isA<VaultLockedException>()),
        );
        await expectLater(
          s.session.renameAccount(accountId: 'x', displayName: 'X'),
          throwsA(isA<VaultLockedException>()),
        );
        await expectLater(
          s.session.removeAccount('x'),
          throwsA(isA<VaultLockedException>()),
        );
        await expectLater(
          s.session.moveAccountToProvider(accountId: 'x', newProviderId: 'y'),
          throwsA(isA<VaultLockedException>()),
        );

        final draft = TotpCredential(
          id: 't1',
          createdAt: DateTime.utc(2026, 5, 26),
          secretBase32: 'JBSWY3DPEHPK3PXP',
          algorithm: TotpHashAlgorithm.sha1,
          digits: 6,
          period: 30,
        );
        await expectLater(
          s.session.addCredentialToAccount(accountId: 'x', draft: draft),
          throwsA(isA<VaultLockedException>()),
        );
        await expectLater(
          s.session.addCredentialAsNewAccount(
            providerId: 'x',
            displayName: 'X',
            draft: draft,
          ),
          throwsA(isA<VaultLockedException>()),
        );
        await expectLater(
          s.session.addCredentialAsNewProviderAndAccount(
            providerName: 'X',
            accountDisplayName: 'X',
            draft: draft,
          ),
          throwsA(isA<VaultLockedException>()),
        );
        await expectLater(
          s.session.removeCredentialFromAccount(
            accountId: 'x',
            credentialId: 'y',
          ),
          throwsA(isA<VaultLockedException>()),
        );
      } finally {
        await tearDownVault(s.dir);
      }
    });

    test('unknown id surfaces appropriate error', () async {
      final s = await setupVault();
      try {
        expect(
          () => s.session.removeProvider('does-not-exist'),
          throwsA(isA<ProviderNotFoundError>()),
        );
        expect(
          () => s.session.renameAccount(
            accountId: 'does-not-exist',
            displayName: 'X',
          ),
          throwsA(isA<AccountNotFoundError>()),
        );
        expect(
          () => s.session.removeAccount('does-not-exist'),
          throwsA(isA<AccountNotFoundError>()),
        );

        final p = await s.session.addProvider(name: 'P');
        final a = await s.session.addAccount(
          providerId: p.id,
          displayName: 'A',
        );
        expect(
          () => s.session.removeCredentialFromAccount(
            accountId: a.id,
            credentialId: 'missing',
          ),
          throwsA(isA<CredentialNotFoundError>()),
        );
      } finally {
        await tearDownVault(s.dir);
      }
    });

    test('replaceCredentialInAccount preserves id and persists', () async {
      final s = await setupVault();
      try {
        final p = await s.session.addProvider(name: 'P');
        final draft = RecoveryCodesCredential(
          id: 'r1',
          createdAt: DateTime.utc(2026, 5, 26),
          codes: const ['a'],
        );
        final account = await s.session.addCredentialAsNewAccount(
          providerId: p.id,
          displayName: 'me',
          draft: draft,
        );

        var notifyCount = 0;
        s.session.addListener(() => notifyCount++);

        final replacement = draft.copyWith(codes: const ['a', 'b', 'c']);
        await s.session.replaceCredentialInAccount(
          accountId: account.id,
          replacement: replacement,
        );

        final stored =
            s.session.data.accounts.single.credentials.single
                as RecoveryCodesCredential;
        expect(stored.id, 'r1');
        expect(stored.codes, const ['a', 'b', 'c']);
        expect(notifyCount, 1);

        // Persistence
        s.session.lock();
        await s.session.unlock(password: 'testpassword');
        final stored2 =
            s.session.data.accounts.single.credentials.single
                as RecoveryCodesCredential;
        expect(stored2.codes, const ['a', 'b', 'c']);
      } finally {
        await tearDownVault(s.dir);
      }
    });

    test(
      'moveCredentialToAccount transfers credential between accounts',
      () async {
        final s = await setupVault();
        try {
          final p = await s.session.addProvider(name: 'P');
          final from = await s.session.addAccount(
            providerId: p.id,
            displayName: 'from',
          );
          final to = await s.session.addAccount(
            providerId: p.id,
            displayName: 'to',
          );

          final draft = RecoveryCodesCredential(
            id: 'r1',
            createdAt: DateTime.utc(2026, 5, 26),
            codes: const ['a', 'b'],
          );
          await s.session.addCredentialToAccount(
            accountId: from.id,
            draft: draft,
          );

          var notifyCount = 0;
          s.session.addListener(() => notifyCount++);

          await s.session.moveCredentialToAccount(
            fromAccountId: from.id,
            credentialId: 'r1',
            toAccountId: to.id,
          );
          expect(notifyCount, 1);
          expect(s.session.data.requireAccount(from.id).credentials, isEmpty);
          expect(
            s.session.data.requireAccount(to.id).credentials.single.id,
            'r1',
          );
        } finally {
          await tearDownVault(s.dir);
        }
      },
    );

    test('mergeAccountInto merges source into target and persists', () async {
      final s = await setupVault();
      try {
        final p = await s.session.addProvider(name: 'Google');
        final source = await s.session.addAccount(
          providerId: p.id,
          displayName: 'me@gmail.com',
        );
        final target = await s.session.addAccount(
          providerId: p.id,
          displayName: 'me@gmail.com',
        );

        await s.session.addCredentialToAccount(
          accountId: source.id,
          draft: RecoveryCodesCredential(
            id: 'r1',
            createdAt: DateTime.utc(2026, 5, 26),
            codes: const ['a'],
          ),
        );
        await s.session.addCredentialToAccount(
          accountId: target.id,
          draft: TotpCredential(
            id: 't1',
            createdAt: DateTime.utc(2026, 5, 26),
            secretBase32: 'JBSWY3DPEHPK3PXP',
            algorithm: TotpHashAlgorithm.sha1,
            digits: 6,
            period: 30,
          ),
        );

        var notifyCount = 0;
        s.session.addListener(() => notifyCount++);

        await s.session.mergeAccountInto(
          sourceAccountId: source.id,
          targetAccountId: target.id,
        );

        expect(notifyCount, 1);
        expect(s.session.data.accounts, hasLength(1));
        final merged = s.session.data.accounts.single;
        expect(merged.id, target.id);
        expect(merged.credentials.map((c) => c.id).toList(), ['t1', 'r1']);

        // Persistence
        s.session.lock();
        await s.session.unlock(password: 'testpassword');
        expect(s.session.data.accounts, hasLength(1));
        expect(
          s.session.data.accounts.single.credentials.map((c) => c.id).toList(),
          ['t1', 'r1'],
        );
      } finally {
        await tearDownVault(s.dir);
      }
    });
  });

  test('destructive operations create checkpoint backups', () async {
    final dir = await Directory.systemTemp.createTemp('rak_vault_test_');
    try {
      final backupService = _RecordingBackupService();
      final repo = VaultRepository.forPath(
        vaultFilePath: p.join(dir.path, 'vault.rakvault'),
        backupService: backupService,
      );
      final session = VaultSession(repo);
      await session.createNew(password: 'testpassword');

      final provider = await session.addProvider(name: 'Example');
      final account = await session.addAccount(
        providerId: provider.id,
        displayName: 'user@example.com',
      );
      await session.addCredentialToAccount(
        accountId: account.id,
        draft: TotpCredential(
          id: 'totp-1',
          createdAt: DateTime.utc(2026),
          secretBase32: 'JBSWY3DPEHPK3PXP',
          algorithm: TotpHashAlgorithm.sha1,
          digits: 6,
          period: 30,
        ),
      );

      await session.removeCredentialFromAccount(
        accountId: account.id,
        credentialId: 'totp-1',
      );
      await session.removeAccount(account.id);
      await session.removeProvider(provider.id);

      expect(backupService.reasons, contains('delete-credential'));
      expect(backupService.reasons, contains('delete-account'));
      expect(backupService.reasons, contains('delete-provider'));
    } finally {
      await dir.delete(recursive: true);
    }
  });

  test('import creates a checkpoint backup before replacing vault', () async {
    final sourceDir = await Directory.systemTemp.createTemp(
      'rak_source_vault_test_',
    );
    final targetDir = await Directory.systemTemp.createTemp(
      'rak_target_vault_test_',
    );
    try {
      final sourceRepo = VaultRepository.forPath(
        vaultFilePath: p.join(sourceDir.path, 'vault.rakvault'),
      );
      final sourceSession = VaultSession(sourceRepo);
      await sourceSession.createNew(password: 'testpassword');
      await sourceSession.addProvider(name: 'Imported');
      final exportedBytes = await sourceRepo.exportBytes();

      final backupService = _RecordingBackupService();
      final targetRepo = VaultRepository.forPath(
        vaultFilePath: p.join(targetDir.path, 'vault.rakvault'),
        backupService: backupService,
      );
      final targetSession = VaultSession(targetRepo);
      await targetSession.createNew(password: 'testpassword');

      await targetSession.importVault(
        vaultBytes: exportedBytes,
        password: 'testpassword',
      );

      expect(backupService.reasons, contains('import'));
      expect(targetSession.data.providers.single.name, 'Imported');
    } finally {
      await sourceDir.delete(recursive: true);
      await targetDir.delete(recursive: true);
    }
  });

  test('merge and developer delete create checkpoint backups', () async {
    final dir = await Directory.systemTemp.createTemp('rak_vault_test_');
    try {
      final backupService = _RecordingBackupService();
      final repo = VaultRepository.forPath(
        vaultFilePath: p.join(dir.path, 'vault.rakvault'),
        backupService: backupService,
      );
      final session = VaultSession(repo);
      await session.createNew(password: 'testpassword');

      final provider = await session.addProvider(name: 'Example');
      final source = await session.addAccount(
        providerId: provider.id,
        displayName: 'source',
      );
      final target = await session.addAccount(
        providerId: provider.id,
        displayName: 'target',
      );
      await session.mergeAccountInto(
        sourceAccountId: source.id,
        targetAccountId: target.id,
      );

      await session.addDeveloperEntry(
        type: DeveloperEntryType.genericSecret,
        title: 'Secret',
        payload: const {'value': 'hidden'},
      );
      final developerEntryId = session.data.developerEntries.single.id;
      await session.removeDeveloperEntry(developerEntryId);

      expect(backupService.reasons, contains('merge-account'));
      expect(backupService.reasons, contains('delete-developer-entry'));
    } finally {
      await dir.delete(recursive: true);
    }
  });
}

class _RecordingBackupService implements VaultBackupService {
  final List<String> reasons = [];

  @override
  String get locationDescription => 'recording';

  @override
  Future<void> createCheckpoint({
    required Uint8List bytes,
    required String reason,
  }) async {
    reasons.add(reason);
  }

  @override
  Future<BackupSettings> loadSettings() async => BackupSettings.defaults();

  @override
  Future<void> maybeAutoBackup(Uint8List bytes) async {}

  @override
  Future<void> setAutoBackupEnabled(bool enabled) async {}

  @override
  Future<void> setAutoBackupFrequency(AutoBackupFrequency frequency) async {}

  @override
  Future<void> updateSettings(BackupSettings settings) async {}
}
