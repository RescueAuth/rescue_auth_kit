import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import 'vault_backup_models.dart';
import 'vault_backup_writer.dart';

abstract class VaultBackupService {
  String get locationDescription;

  Future<BackupSettings> loadSettings();

  Future<void> updateSettings(BackupSettings settings);

  Future<void> setAutoBackupEnabled(bool enabled);

  Future<void> setAutoBackupFrequency(AutoBackupFrequency frequency);

  Future<void> maybeAutoBackup(Uint8List bytes);

  Future<void> createCheckpoint({
    required Uint8List bytes,
    required String reason,
  });
}

class NoopVaultBackupService implements VaultBackupService {
  const NoopVaultBackupService();

  @override
  String get locationDescription => 'Disabled';

  @override
  Future<BackupSettings> loadSettings() async => BackupSettings.defaults();

  @override
  Future<void> updateSettings(BackupSettings settings) async {}

  @override
  Future<void> setAutoBackupEnabled(bool enabled) async {}

  @override
  Future<void> setAutoBackupFrequency(AutoBackupFrequency frequency) async {}

  @override
  Future<void> maybeAutoBackup(Uint8List bytes) async {}

  @override
  Future<void> createCheckpoint({
    required Uint8List bytes,
    required String reason,
  }) async {}
}

class DefaultVaultBackupService implements VaultBackupService {
  static const int autoRetentionCount = 5;
  static const int checkpointRetentionCount = 5;
  static const String autoPrefix = 'RescueAuthKit-auto-';
  static const String checkpointPrefix = 'RescueAuthKit-checkpoint-';
  static const String autoCreatedStatus = 'auto.created';
  static const String checkpointCreatedStatus = 'checkpoint.created';
  static const String cleanupFailedStatus = 'backup.cleanupFailed';
  static const String autoFailedStatus = 'auto.failed';
  static const String checkpointFailedStatus = 'checkpoint.failed';

  final File settingsFile;
  final VaultBackupWriter writer;
  final DateTime Function() now;

  const DefaultVaultBackupService({
    required this.settingsFile,
    required this.writer,
    DateTime Function()? now,
  }) : now = now ?? DateTime.now;

  static Future<DefaultVaultBackupService> create() async {
    final base = await getApplicationSupportDirectory();
    final settingsFile = File(
      p.join(base.path, 'RescueAuthKit', 'backup_settings.json'),
    );
    return DefaultVaultBackupService(
      settingsFile: settingsFile,
      writer: await createDefaultVaultBackupWriter(),
    );
  }

  @override
  String get locationDescription => writer.locationDescription;

  @override
  Future<BackupSettings> loadSettings() async {
    if (!await settingsFile.exists()) {
      return BackupSettings.defaults();
    }
    try {
      final obj = jsonDecode(await settingsFile.readAsString()) as Object?;
      if (obj is! Map<String, dynamic>) return BackupSettings.defaults();
      return BackupSettings.fromJson(obj);
    } catch (_) {
      return BackupSettings.defaults();
    }
  }

  @override
  Future<void> updateSettings(BackupSettings settings) async {
    await settingsFile.parent.create(recursive: true);
    await settingsFile.writeAsString(
      jsonEncode(settings.toJson()),
      flush: true,
    );
  }

  @override
  Future<void> setAutoBackupEnabled(bool enabled) async {
    final settings = await loadSettings();
    await updateSettings(settings.copyWith(autoBackupEnabled: enabled));
  }

  @override
  Future<void> setAutoBackupFrequency(AutoBackupFrequency frequency) async {
    final settings = await loadSettings();
    await updateSettings(settings.copyWith(autoBackupFrequency: frequency));
  }

  @override
  Future<void> maybeAutoBackup(Uint8List bytes) async {
    final settings = await loadSettings();
    if (!settings.autoBackupEnabled) return;

    final currentTime = now();
    if (!_shouldAutoBackup(settings, currentTime)) return;

    try {
      final result = await writer.writeBackup(
        fileName: '$autoPrefix${_stamp(currentTime)}.rakvault',
        bytes: bytes,
        retentionPrefix: autoPrefix,
        keep: autoRetentionCount,
      );
      await updateSettings(
        settings.copyWith(
          lastAutoBackupAt: currentTime,
          lastBackupStatus: BackupStatus(
            at: currentTime,
            success: result.cleanupFailureCount == 0,
            message: result.cleanupFailureCount == 0
                ? autoCreatedStatus
                : cleanupFailedStatus,
            detail: result.cleanupFailureCount == 0
                ? null
                : result.cleanupFailureCount.toString(),
            path: result.path,
          ),
        ),
      );
    } catch (e) {
      await updateSettings(
        settings.copyWith(
          lastBackupStatus: BackupStatus(
            at: currentTime,
            success: false,
            message: autoFailedStatus,
            detail: e.toString(),
          ),
        ),
      );
    }
  }

  @override
  Future<void> createCheckpoint({
    required Uint8List bytes,
    required String reason,
  }) async {
    final currentTime = now();
    final settings = await loadSettings();
    try {
      final result = await writer.writeBackup(
        fileName:
            '$checkpointPrefix${_sanitizeReason(reason)}-${_stamp(currentTime)}.rakvault',
        bytes: bytes,
        retentionPrefix: checkpointPrefix,
        keep: checkpointRetentionCount,
      );
      await updateSettings(
        settings.copyWith(
          lastBackupStatus: BackupStatus(
            at: currentTime,
            success: result.cleanupFailureCount == 0,
            message: result.cleanupFailureCount == 0
                ? checkpointCreatedStatus
                : cleanupFailedStatus,
            detail: result.cleanupFailureCount == 0
                ? null
                : result.cleanupFailureCount.toString(),
            path: result.path,
          ),
        ),
      );
    } catch (e) {
      await updateSettings(
        settings.copyWith(
          lastBackupStatus: BackupStatus(
            at: currentTime,
            success: false,
            message: checkpointFailedStatus,
            detail: e.toString(),
          ),
        ),
      );
    }
  }

  bool _shouldAutoBackup(BackupSettings settings, DateTime currentTime) {
    final last = settings.lastAutoBackupAt;
    if (last == null) return true;

    switch (settings.autoBackupFrequency) {
      case AutoBackupFrequency.onEveryChange:
        return true;
      case AutoBackupFrequency.daily:
        return !_sameDate(last, currentTime);
      case AutoBackupFrequency.weekly:
        return currentTime.difference(last) >= const Duration(days: 7);
      case AutoBackupFrequency.monthly:
        return last.year != currentTime.year || last.month != currentTime.month;
    }
  }

  bool _sameDate(DateTime a, DateTime b) {
    return a.year == b.year && a.month == b.month && a.day == b.day;
  }

  String _stamp(DateTime value) {
    String two(int n) => n.toString().padLeft(2, '0');
    String three(int n) => n.toString().padLeft(3, '0');
    return '${value.year}${two(value.month)}${two(value.day)}-'
        '${two(value.hour)}${two(value.minute)}${two(value.second)}-'
        '${three(value.millisecond)}';
  }

  String _sanitizeReason(String reason) {
    final sanitized = reason
        .toLowerCase()
        .replaceAll(RegExp(r'[^a-z0-9]+'), '-')
        .replaceAll(RegExp(r'^-+|-+$'), '');
    return sanitized.isEmpty ? 'operation' : sanitized;
  }
}
