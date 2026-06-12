enum AutoBackupFrequency {
  onEveryChange,
  daily,
  weekly,
  monthly;

  static AutoBackupFrequency fromJson(String? value) {
    return AutoBackupFrequency.values.firstWhere(
      (frequency) => frequency.name == value,
      orElse: () => AutoBackupFrequency.onEveryChange,
    );
  }
}

class BackupStatus {
  final DateTime? at;
  final bool success;
  final String message;
  final String? detail;
  final String? path;

  const BackupStatus({
    required this.at,
    required this.success,
    required this.message,
    this.detail,
    this.path,
  });

  const BackupStatus.none()
    : at = null,
      success = true,
      message = '',
      detail = null,
      path = null;

  factory BackupStatus.fromJson(Map<String, dynamic> json) {
    final atRaw = json['at'] as String?;
    return BackupStatus(
      at: atRaw == null ? null : DateTime.tryParse(atRaw),
      success: json['success'] as bool? ?? true,
      message: json['message'] as String? ?? '',
      detail: json['detail'] as String?,
      path: json['path'] as String?,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'at': at?.toIso8601String(),
      'success': success,
      'message': message,
      'detail': detail,
      'path': path,
    };
  }
}

class BackupSettings {
  final bool autoBackupEnabled;
  final AutoBackupFrequency autoBackupFrequency;
  final DateTime? lastAutoBackupAt;
  final BackupStatus lastBackupStatus;

  const BackupSettings({
    required this.autoBackupEnabled,
    required this.autoBackupFrequency,
    required this.lastAutoBackupAt,
    required this.lastBackupStatus,
  });

  factory BackupSettings.defaults() {
    return const BackupSettings(
      autoBackupEnabled: true,
      autoBackupFrequency: AutoBackupFrequency.onEveryChange,
      lastAutoBackupAt: null,
      lastBackupStatus: BackupStatus.none(),
    );
  }

  factory BackupSettings.fromJson(Map<String, dynamic> json) {
    final lastAutoBackupAtRaw = json['lastAutoBackupAt'] as String?;
    final statusAny = json['lastBackupStatus'];
    return BackupSettings(
      autoBackupEnabled: json['autoBackupEnabled'] as bool? ?? true,
      autoBackupFrequency: AutoBackupFrequency.fromJson(
        json['autoBackupFrequency'] as String?,
      ),
      lastAutoBackupAt: lastAutoBackupAtRaw == null
          ? null
          : DateTime.tryParse(lastAutoBackupAtRaw),
      lastBackupStatus: statusAny is Map
          ? BackupStatus.fromJson(Map<String, dynamic>.from(statusAny))
          : const BackupStatus.none(),
    );
  }

  BackupSettings copyWith({
    bool? autoBackupEnabled,
    AutoBackupFrequency? autoBackupFrequency,
    DateTime? lastAutoBackupAt,
    bool clearLastAutoBackupAt = false,
    BackupStatus? lastBackupStatus,
  }) {
    return BackupSettings(
      autoBackupEnabled: autoBackupEnabled ?? this.autoBackupEnabled,
      autoBackupFrequency: autoBackupFrequency ?? this.autoBackupFrequency,
      lastAutoBackupAt: clearLastAutoBackupAt
          ? null
          : (lastAutoBackupAt ?? this.lastAutoBackupAt),
      lastBackupStatus: lastBackupStatus ?? this.lastBackupStatus,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'autoBackupEnabled': autoBackupEnabled,
      'autoBackupFrequency': autoBackupFrequency.name,
      'lastAutoBackupAt': lastAutoBackupAt?.toIso8601String(),
      'lastBackupStatus': lastBackupStatus.toJson(),
    };
  }
}
