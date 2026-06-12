import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:rescue_auth_kit/core/backup/vault_backup_models.dart';
import 'package:rescue_auth_kit/core/backup/vault_backup_service.dart';
import 'package:rescue_auth_kit/core/backup/vault_backup_writer.dart';

void main() {
  test(
    'auto backup on every change writes every time and keeps latest 5',
    () async {
      final dir = await Directory.systemTemp.createTemp('rak_backup_test_');
      var now = DateTime.parse('2026-06-11T10:00:00Z');

      try {
        final service = DefaultVaultBackupService(
          settingsFile: File(p.join(dir.path, 'settings.json')),
          writer: DartIoVaultBackupWriter(directory: Directory(dir.path)),
          now: () => now,
        );

        for (var i = 0; i < 6; i++) {
          now = now.add(const Duration(milliseconds: 1));
          await service.maybeAutoBackup(Uint8List.fromList([i]));
        }

        final names = await _backupNames(dir);
        expect(
          names.where((name) => name.startsWith('RescueAuthKit-auto-')),
          hasLength(5),
        );
      } finally {
        await dir.delete(recursive: true);
      }
    },
  );

  test(
    'daily, weekly, and monthly frequencies skip within the same period',
    () async {
      final dir = await Directory.systemTemp.createTemp('rak_backup_test_');
      var now = DateTime.parse('2026-06-11T10:00:00Z');

      try {
        final service = DefaultVaultBackupService(
          settingsFile: File(p.join(dir.path, 'settings.json')),
          writer: DartIoVaultBackupWriter(directory: Directory(dir.path)),
          now: () => now,
        );

        await service.setAutoBackupFrequency(AutoBackupFrequency.daily);
        await service.maybeAutoBackup(Uint8List.fromList([1]));
        now = DateTime.parse('2026-06-11T23:00:00Z');
        await service.maybeAutoBackup(Uint8List.fromList([2]));
        now = DateTime.parse('2026-06-12T00:01:00Z');
        await service.maybeAutoBackup(Uint8List.fromList([3]));
        expect(await _countBackups(dir, 'RescueAuthKit-auto-'), 2);

        await service.updateSettings(
          BackupSettings.defaults().copyWith(
            autoBackupFrequency: AutoBackupFrequency.weekly,
            clearLastAutoBackupAt: true,
          ),
        );
        now = DateTime.parse('2026-06-13T00:00:00Z');
        await service.maybeAutoBackup(Uint8List.fromList([4]));
        now = DateTime.parse('2026-06-19T23:59:00Z');
        await service.maybeAutoBackup(Uint8List.fromList([5]));
        now = DateTime.parse('2026-06-20T00:00:01Z');
        await service.maybeAutoBackup(Uint8List.fromList([6]));
        expect(await _countBackups(dir, 'RescueAuthKit-auto-'), 4);

        await service.updateSettings(
          BackupSettings.defaults().copyWith(
            autoBackupFrequency: AutoBackupFrequency.monthly,
            clearLastAutoBackupAt: true,
          ),
        );
        now = DateTime.parse('2026-06-21T00:00:00Z');
        await service.maybeAutoBackup(Uint8List.fromList([7]));
        now = DateTime.parse('2026-06-30T23:59:00Z');
        await service.maybeAutoBackup(Uint8List.fromList([8]));
        now = DateTime.parse('2026-07-01T00:00:00Z');
        await service.maybeAutoBackup(Uint8List.fromList([9]));
        expect(await _countBackups(dir, 'RescueAuthKit-auto-'), 5);
      } finally {
        await dir.delete(recursive: true);
      }
    },
  );

  test('checkpoint backups keep a separate latest 5', () async {
    final dir = await Directory.systemTemp.createTemp('rak_backup_test_');
    var now = DateTime.parse('2026-06-11T10:00:00Z');

    try {
      final service = DefaultVaultBackupService(
        settingsFile: File(p.join(dir.path, 'settings.json')),
        writer: DartIoVaultBackupWriter(directory: Directory(dir.path)),
        now: () => now,
      );

      for (var i = 0; i < 6; i++) {
        now = now.add(const Duration(milliseconds: 1));
        await service.maybeAutoBackup(Uint8List.fromList([i]));
        await service.createCheckpoint(
          bytes: Uint8List.fromList([i]),
          reason: 'delete-account',
        );
      }

      expect(await _countBackups(dir, 'RescueAuthKit-auto-'), 5);
      expect(await _countBackups(dir, 'RescueAuthKit-checkpoint-'), 5);
    } finally {
      await dir.delete(recursive: true);
    }
  });

  test('backup failures are recorded by the service', () async {
    final dir = await Directory.systemTemp.createTemp('rak_backup_test_');

    try {
      final service = DefaultVaultBackupService(
        settingsFile: File(p.join(dir.path, 'settings.json')),
        writer: const _ThrowingWriter(),
        now: () => DateTime.parse('2026-06-11T10:00:00Z'),
      );

      await service.maybeAutoBackup(Uint8List.fromList([1]));

      final settings = await service.loadSettings();
      expect(settings.lastBackupStatus.success, isFalse);
      expect(settings.lastBackupStatus.message, 'auto.failed');
      expect(settings.lastBackupStatus.detail, contains('nope'));
    } finally {
      await dir.delete(recursive: true);
    }
  });
}

Future<List<String>> _backupNames(Directory dir) async {
  final names = await dir
      .list()
      .where((entity) => entity is File)
      .map((entity) => p.basename(entity.path))
      .where((name) => name.endsWith('.rakvault'))
      .toList();
  names.sort();
  return names;
}

Future<int> _countBackups(Directory dir, String prefix) async {
  final names = await _backupNames(dir);
  return names.where((name) => name.startsWith(prefix)).length;
}

class _ThrowingWriter implements VaultBackupWriter {
  const _ThrowingWriter();

  @override
  String get locationDescription => 'throwing';

  @override
  Future<BackupWriteResult> writeBackup({
    required String fileName,
    required Uint8List bytes,
    required String retentionPrefix,
    required int keep,
  }) async {
    throw const FileSystemException('nope');
  }
}
