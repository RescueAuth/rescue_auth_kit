import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

class BackupWriteResult {
  final String path;
  final int cleanupFailureCount;

  const BackupWriteResult({required this.path, this.cleanupFailureCount = 0});
}

abstract class VaultBackupWriter {
  String get locationDescription;

  Future<BackupWriteResult> writeBackup({
    required String fileName,
    required Uint8List bytes,
    required String retentionPrefix,
    required int keep,
  });
}

class NoopVaultBackupWriter implements VaultBackupWriter {
  const NoopVaultBackupWriter();

  @override
  String get locationDescription => 'Disabled';

  @override
  Future<BackupWriteResult> writeBackup({
    required String fileName,
    required Uint8List bytes,
    required String retentionPrefix,
    required int keep,
  }) async {
    return BackupWriteResult(path: fileName);
  }
}

class DartIoVaultBackupWriter implements VaultBackupWriter {
  final Directory directory;

  const DartIoVaultBackupWriter({required this.directory});

  static Future<DartIoVaultBackupWriter> createDefault() async {
    final base = await getApplicationDocumentsDirectory();
    return DartIoVaultBackupWriter(
      directory: Directory(p.join(base.path, 'RescueAuthKit', 'Backups')),
    );
  }

  @override
  String get locationDescription => directory.path;

  @override
  Future<BackupWriteResult> writeBackup({
    required String fileName,
    required Uint8List bytes,
    required String retentionPrefix,
    required int keep,
  }) async {
    await directory.create(recursive: true);
    final file = File(p.join(directory.path, fileName));
    await file.writeAsBytes(bytes, flush: true);
    final cleanupFailureCount = await _prune(
      retentionPrefix: retentionPrefix,
      keep: keep,
    );
    return BackupWriteResult(
      path: file.path,
      cleanupFailureCount: cleanupFailureCount,
    );
  }

  Future<int> _prune({
    required String retentionPrefix,
    required int keep,
  }) async {
    final files = await directory
        .list()
        .where((entity) {
          final name = p.basename(entity.path);
          return entity is File &&
              name.startsWith(retentionPrefix) &&
              name.endsWith('.rakvault');
        })
        .cast<File>()
        .toList();

    files.sort((a, b) => p.basename(b.path).compareTo(p.basename(a.path)));
    final stale = files.skip(keep);
    var failures = 0;
    for (final file in stale) {
      try {
        await file.delete();
      } catch (_) {
        failures++;
      }
    }
    return failures;
  }
}

class MethodChannelVaultBackupWriter implements VaultBackupWriter {
  static const MethodChannel _channel = MethodChannel(
    'com.xincy.rescue_auth_kit/backups',
  );

  const MethodChannelVaultBackupWriter();

  @override
  String get locationDescription => 'Downloads/RescueAuthKit/Backups';

  @override
  Future<BackupWriteResult> writeBackup({
    required String fileName,
    required Uint8List bytes,
    required String retentionPrefix,
    required int keep,
  }) async {
    final result = await _channel
        .invokeMapMethod<String, Object?>('writeBackupFile', {
          'fileName': fileName,
          'bytes': bytes,
          'retentionPrefix': retentionPrefix,
          'keep': keep,
        });
    return BackupWriteResult(
      path: result?['path'] as String? ?? '$locationDescription/$fileName',
      cleanupFailureCount: result?['cleanupFailureCount'] as int? ?? 0,
    );
  }
}

Future<VaultBackupWriter> createDefaultVaultBackupWriter() async {
  if (!kIsWeb && defaultTargetPlatform == TargetPlatform.android) {
    return const MethodChannelVaultBackupWriter();
  }
  return DartIoVaultBackupWriter.createDefault();
}
