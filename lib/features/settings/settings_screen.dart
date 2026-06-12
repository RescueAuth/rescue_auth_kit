import 'dart:io';

import 'package:file_selector/file_selector.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:provider/provider.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../core/backup/vault_backup_models.dart';
import '../../core/backup/vault_backup_service.dart';
import '../../core/update/update_checker.dart';
import '../../core/vault/vault_repository.dart';
import '../../core/vault/vault_session.dart';
import '../../l10n/app_localizations.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late final Future<PackageInfo?> _packageInfoFuture;
  Future<BackupSettings>? _backupSettingsFuture;

  @override
  void initState() {
    super.initState();
    _packageInfoFuture = _loadPackageInfo();
  }

  XTypeGroup _vaultTypeGroup() => const XTypeGroup(
    label: 'RescueAuthKit Vault',
    extensions: <String>['rakvault'],
  );

  String _suggestedFileName() {
    final now = DateTime.now();
    final stamp =
        '${now.year}${now.month.toString().padLeft(2, '0')}'
        '${now.day.toString().padLeft(2, '0')}-'
        '${now.hour.toString().padLeft(2, '0')}'
        '${now.minute.toString().padLeft(2, '0')}';
    return 'RescueAuthKit-$stamp.rakvault';
  }

  Future<String?> _askPassword(
    BuildContext context, {
    required String title,
  }) async {
    final l10n = AppLocalizations.of(context);
    final ctrl = TextEditingController();
    final result = await showDialog<String?>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: ctrl,
          obscureText: true,
          decoration: InputDecoration(
            labelText: l10n.masterPasswordLabel,
            border: const OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: Text(l10n.dialogCancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, ctrl.text),
            child: Text(l10n.dialogContinue),
          ),
        ],
      ),
    );
    ctrl.dispose();
    return result;
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final repo = context.read<VaultRepository>();
    _backupSettingsFuture ??= repo.backupService.loadSettings();

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _SectionHeader(
          title: l10n.settingsVaultSection,
          subtitle: l10n.settingsVaultSubtitle,
        ),
        const SizedBox(height: 12),
        _buildManualBackupEntry(context, repo),
        const SizedBox(height: 12),
        _buildAutoBackupEntry(context, repo),
        const SizedBox(height: 20),
        _buildVersionSection(context),
      ],
    );
  }

  Widget _buildManualBackupEntry(BuildContext context, VaultRepository repo) {
    final l10n = AppLocalizations.of(context);

    return _SettingsEntryCard(
      icon: Icons.folder_copy_outlined,
      title: l10n.backupManualTitle,
      subtitle: l10n.backupManualEntrySubtitle,
      onTap: () => Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => ManualBackupScreen(
            vaultFilePath: repo.vaultFilePath,
            onExport: (ctx) => _exportVault(ctx, repo),
            onImport: (ctx) => _importVault(ctx),
          ),
        ),
      ),
    );
  }

  Widget _buildAutoBackupEntry(BuildContext context, VaultRepository repo) {
    final l10n = AppLocalizations.of(context);

    return FutureBuilder<BackupSettings>(
      future: _backupSettingsFuture,
      builder: (context, snapshot) {
        final settings = snapshot.data ?? BackupSettings.defaults();
        final status = settings.lastBackupStatus;
        final statusText = _backupStatusText(l10n, status);

        return _SettingsEntryCard(
          icon: Icons.history,
          title: l10n.backupAutoTitle,
          subtitle: settings.autoBackupEnabled
              ? l10n.backupAutoEntrySubtitle(
                  _frequencyLabel(l10n, settings.autoBackupFrequency),
                  statusText,
                )
              : l10n.backupAutoDisabledSubtitle,
          onTap: () async {
            await Navigator.of(context).push<void>(
              MaterialPageRoute(
                builder: (_) => AutoBackupSettingsScreen(
                  backupService: repo.backupService,
                  initialSettings: settings,
                  statusTextBuilder: (l10n, status) =>
                      _backupStatusText(l10n, status),
                  frequencyLabelBuilder: _frequencyLabel,
                ),
              ),
            );
            if (!context.mounted) return;
            setState(() {
              _backupSettingsFuture = repo.backupService.loadSettings();
            });
          },
        );
      },
    );
  }

  String _frequencyLabel(AppLocalizations l10n, AutoBackupFrequency frequency) {
    return switch (frequency) {
      AutoBackupFrequency.onEveryChange => l10n.backupAutoFrequencyEveryChange,
      AutoBackupFrequency.daily => l10n.backupAutoFrequencyDaily,
      AutoBackupFrequency.weekly => l10n.backupAutoFrequencyWeekly,
      AutoBackupFrequency.monthly => l10n.backupAutoFrequencyMonthly,
    };
  }

  String _backupStatusText(AppLocalizations l10n, BackupStatus status) {
    if (status.at == null) return l10n.backupAutoStatusNone;

    final message = _localizedBackupStatusMessage(l10n, status);
    final summary = status.success
        ? l10n.backupAutoStatusSuccess(message)
        : l10n.backupAutoStatusIssue(message);
    final path = status.path;
    if (path == null || path.isEmpty) return summary;
    return '$summary\n$path';
  }

  String _localizedBackupStatusMessage(
    AppLocalizations l10n,
    BackupStatus status,
  ) {
    final message = status.message;
    final detail = status.detail ?? '';
    return switch (message) {
      'auto.created' || 'Automatic backup created.' => l10n.backupAutoCreated,
      'checkpoint.created' ||
      'Checkpoint backup created.' => l10n.backupCheckpointCreated,
      'backup.cleanupFailed' => l10n.backupCleanupFailed(detail),
      'auto.failed' => l10n.backupAutoFailed(detail),
      'checkpoint.failed' => l10n.backupCheckpointFailed(detail),
      _ when message.startsWith('Failed to clean up ') => message,
      _ when message.startsWith('Automatic backup failed: ') =>
        l10n.backupAutoFailed(
          message.substring('Automatic backup failed: '.length),
        ),
      _ when message.startsWith('Checkpoint backup failed: ') =>
        l10n.backupCheckpointFailed(
          message.substring('Checkpoint backup failed: '.length),
        ),
      _ => message,
    };
  }

  Future<PackageInfo?> _loadPackageInfo() async {
    try {
      return await PackageInfo.fromPlatform();
    } catch (_) {
      return null;
    }
  }

  Widget _buildVersionSection(BuildContext context) {
    final l10n = AppLocalizations.of(context);

    return FutureBuilder<PackageInfo?>(
      future: _packageInfoFuture,
      builder: (context, snapshot) {
        final packageInfo = snapshot.data;
        final version = snapshot.connectionState == ConnectionState.waiting
            ? l10n.settingsLoadingVersion
            : packageInfo == null
            ? l10n.settingsUnknownVersion
            : '${packageInfo.version}+${packageInfo.buildNumber}';

        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _SectionHeader(
              title: l10n.settingsVersionSection,
              subtitle: l10n.settingsVersionSubtitle,
            ),
            const SizedBox(height: 12),
            Card(
              child: InkWell(
                borderRadius: BorderRadius.circular(12),
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const AboutVersionScreen()),
                ),
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Row(
                    children: [
                      _IconBadge(icon: Icons.verified_outlined),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              l10n.aboutTitle,
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                            const SizedBox(height: 4),
                            Text(l10n.settingsAppVersion(version)),
                          ],
                        ),
                      ),
                      const Icon(Icons.chevron_right),
                    ],
                  ),
                ),
              ),
            ),
          ],
        );
      },
    );
  }

  Future<void> _exportVault(BuildContext context, VaultRepository repo) async {
    final l10n = AppLocalizations.of(context);

    try {
      final bytes = await repo.exportBytes();
      final fileName = _suggestedFileName();

      final isAndroid =
          !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

      if (isAndroid) {
        final tmp = await getTemporaryDirectory();
        final outPath = p.join(tmp.path, fileName);
        await File(outPath).writeAsBytes(bytes);

        await SharePlus.instance.share(
          ShareParams(files: [XFile(outPath)], text: 'RescueAuthKit Backup'),
        );

        if (context.mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(SnackBar(content: Text(l10n.backupExportShared)));
        }
      } else {
        final loc = await getSaveLocation(suggestedName: fileName);
        if (loc == null) return;

        final xf = XFile.fromData(
          bytes,
          mimeType: 'application/octet-stream',
          name: fileName,
        );
        await xf.saveTo(loc.path);

        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(l10n.backupExportedTo(loc.path))),
          );
        }
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(l10n.backupExportFailed(e.toString()))),
        );
      }
    }
  }

  Future<void> _importVault(BuildContext context) async {
    final l10n = AppLocalizations.of(context);

    try {
      final file = await openFile(
        acceptedTypeGroups: <XTypeGroup>[_vaultTypeGroup()],
      );
      if (file == null) return;

      if (!context.mounted) return;

      final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: Text(l10n.backupImportReplaceTitle),
          content: Text(l10n.backupImportReplaceBody),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: Text(l10n.dialogCancel),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: Text(l10n.dialogContinue),
            ),
          ],
        ),
      );
      if (ok != true) return;

      if (!context.mounted) return;

      final pw = await _askPassword(context, title: l10n.backupPasswordTitle);
      if (pw == null || pw.isEmpty) return;

      final bytes = Uint8List.fromList(await file.readAsBytes());

      if (!context.mounted) return;

      await context.read<VaultSession>().importVault(
        vaultBytes: bytes,
        password: pw,
      );

      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(l10n.backupImportedFrom(file.name))),
        );
      }
    } on VaultAuthException {
      if (context.mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l10n.backupWrongPassword)));
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(l10n.backupImportFailed(e.toString()))),
        );
      }
    }
  }
}

typedef BackupAction = Future<void> Function(BuildContext context);

class ManualBackupScreen extends StatelessWidget {
  const ManualBackupScreen({
    super.key,
    required this.vaultFilePath,
    required this.onExport,
    required this.onImport,
  });

  final String vaultFilePath;
  final BackupAction onExport;
  final BackupAction onImport;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.backupManualTitle)),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _CardTitle(
                    icon: Icons.folder_copy_outlined,
                    title: l10n.backupManualTitle,
                    subtitle: l10n.backupManualSubtitle,
                  ),
                  const SizedBox(height: 16),
                  _InfoTile(
                    icon: Icons.lock_outline,
                    label: l10n.backupCurrentPath,
                    value: vaultFilePath,
                  ),
                  const SizedBox(height: 16),
                  Wrap(
                    spacing: 12,
                    runSpacing: 12,
                    children: [
                      FilledButton.icon(
                        icon: const Icon(Icons.upload),
                        label: Text(l10n.backupExport),
                        onPressed: () => onExport(context),
                      ),
                      OutlinedButton.icon(
                        icon: const Icon(Icons.download),
                        label: Text(l10n.backupImport),
                        onPressed: () => onImport(context),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

typedef BackupStatusTextBuilder =
    String Function(AppLocalizations l10n, BackupStatus status);
typedef FrequencyLabelBuilder =
    String Function(AppLocalizations l10n, AutoBackupFrequency frequency);

class AutoBackupSettingsScreen extends StatefulWidget {
  const AutoBackupSettingsScreen({
    super.key,
    required this.backupService,
    required this.initialSettings,
    required this.statusTextBuilder,
    required this.frequencyLabelBuilder,
  });

  final VaultBackupService backupService;
  final BackupSettings initialSettings;
  final BackupStatusTextBuilder statusTextBuilder;
  final FrequencyLabelBuilder frequencyLabelBuilder;

  @override
  State<AutoBackupSettingsScreen> createState() =>
      _AutoBackupSettingsScreenState();
}

class _AutoBackupSettingsScreenState extends State<AutoBackupSettingsScreen> {
  late Future<BackupSettings> _settingsFuture;

  @override
  void initState() {
    super.initState();
    _settingsFuture = Future.value(widget.initialSettings);
  }

  Future<void> _reload() async {
    setState(() {
      _settingsFuture = widget.backupService.loadSettings();
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.backupAutoTitle)),
      body: FutureBuilder<BackupSettings>(
        future: _settingsFuture,
        builder: (context, snapshot) {
          final settings = snapshot.data ?? widget.initialSettings;
          final statusText = widget.statusTextBuilder(
            l10n,
            settings.lastBackupStatus,
          );
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _CardTitle(
                        icon: Icons.history,
                        title: l10n.backupAutoTitle,
                        subtitle: l10n.backupAutoSubtitle,
                        trailing: Switch(
                          value: settings.autoBackupEnabled,
                          onChanged: (value) async {
                            await widget.backupService.setAutoBackupEnabled(
                              value,
                            );
                            await _reload();
                          },
                        ),
                      ),
                      const SizedBox(height: 16),
                      DropdownButtonFormField<AutoBackupFrequency>(
                        initialValue: settings.autoBackupFrequency,
                        decoration: InputDecoration(
                          labelText: l10n.backupAutoFrequency,
                          border: OutlineInputBorder(),
                        ),
                        items: [
                          for (final frequency in AutoBackupFrequency.values)
                            DropdownMenuItem(
                              value: frequency,
                              child: Text(
                                widget.frequencyLabelBuilder(l10n, frequency),
                              ),
                            ),
                        ],
                        onChanged: settings.autoBackupEnabled
                            ? (value) async {
                                if (value == null) return;
                                await widget.backupService
                                    .setAutoBackupFrequency(value);
                                await _reload();
                              }
                            : null,
                      ),
                      const SizedBox(height: 12),
                      _InfoTile(
                        icon: Icons.download_done_outlined,
                        label: l10n.backupAutoLocationLabel,
                        value: widget.backupService.locationDescription,
                      ),
                      const SizedBox(height: 8),
                      _InfoTile(
                        icon: settings.lastBackupStatus.success
                            ? Icons.check_circle_outline
                            : Icons.error_outline,
                        label: l10n.backupAutoLastResult,
                        value: statusText,
                        isIssue: !settings.lastBackupStatus.success,
                      ),
                      const SizedBox(height: 12),
                      Text(
                        l10n.backupAutoRetentionDescription,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({required this.title, required this.subtitle});

  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 4),
        Text(
          subtitle,
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
      ],
    );
  }
}

class _CardTitle extends StatelessWidget {
  const _CardTitle({
    required this.icon,
    required this.title,
    required this.subtitle,
    this.trailing,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _IconBadge(icon: icon),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 4),
              Text(
                subtitle,
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
        if (trailing != null) ...[const SizedBox(width: 12), trailing!],
      ],
    );
  }
}

class _InfoTile extends StatelessWidget {
  const _InfoTile({
    required this.icon,
    required this.label,
    required this.value,
    this.isIssue = false,
  });

  final IconData icon;
  final String label;
  final String value;
  final bool isIssue;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final iconColor = isIssue ? colorScheme.error : colorScheme.primary;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainerHighest.withValues(alpha: 0.45),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 20, color: iconColor),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: Theme.of(context).textTheme.labelMedium?.copyWith(
                    color: colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 4),
                SelectableText(value),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _IconBadge extends StatelessWidget {
  const _IconBadge({required this.icon});

  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Container(
      width: 40,
      height: 40,
      decoration: BoxDecoration(
        color: colorScheme.primaryContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Icon(icon, color: colorScheme.onPrimaryContainer),
    );
  }
}

class _SettingsEntryCard extends StatelessWidget {
  const _SettingsEntryCard({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              _IconBadge(icon: icon),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 4),
                    Text(
                      subtitle,
                      maxLines: 3,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              const Icon(Icons.chevron_right),
            ],
          ),
        ),
      ),
    );
  }
}

class _ReleaseNotesCard extends StatelessWidget {
  const _ReleaseNotesCard({required this.title, required this.body});

  final String title;
  final String body;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.notes_outlined),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    title,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            SelectableText(body, style: Theme.of(context).textTheme.bodyMedium),
          ],
        ),
      ),
    );
  }
}

class AboutVersionScreen extends StatefulWidget {
  const AboutVersionScreen({
    super.key,
    this.updateChecker = const UpdateChecker(),
    this.packageInfoFuture,
  });

  final UpdateChecker updateChecker;
  final Future<PackageInfo?>? packageInfoFuture;

  @override
  State<AboutVersionScreen> createState() => _AboutVersionScreenState();
}

class _AboutVersionScreenState extends State<AboutVersionScreen> {
  late final Future<PackageInfo?> _packageInfoFuture;
  bool _checkingForUpdates = false;
  UpdateCheckResult? _lastUpdateCheck;

  @override
  void initState() {
    super.initState();
    _packageInfoFuture = widget.packageInfoFuture ?? _loadPackageInfo();
  }

  Future<PackageInfo?> _loadPackageInfo() async {
    try {
      return await PackageInfo.fromPlatform();
    } catch (_) {
      return null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.aboutTitle)),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: ListTile(
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 16,
                vertical: 12,
              ),
              leading: const Icon(Icons.info_outline),
              title: Text(l10n.aboutTitle),
              subtitle: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const SizedBox(height: 4),
                  Text(l10n.aboutDescription),
                  const SizedBox(height: 12),
                  FutureBuilder<PackageInfo?>(
                    future: _packageInfoFuture,
                    builder: (context, snapshot) {
                      if (snapshot.connectionState == ConnectionState.waiting) {
                        return Text(l10n.settingsLoadingVersion);
                      }

                      final packageInfo = snapshot.data;
                      final version = packageInfo == null
                          ? l10n.settingsUnknownVersion
                          : '${packageInfo.version}+${packageInfo.buildNumber}';

                      return Text(l10n.settingsAppVersion(version));
                    },
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          _ReleaseNotesCard(
            title: l10n.aboutCurrentReleaseNotesTitle,
            body: l10n.aboutCurrentReleaseNotes,
          ),
          if (_lastUpdateCheck != null) ...[
            const SizedBox(height: 16),
            _buildReleaseCard(context, _lastUpdateCheck!),
            const SizedBox(height: 16),
            _ReleaseNotesCard(
              title: l10n.aboutLatestReleaseNotesTitle,
              body: _lastUpdateCheck!.releaseNotes?.trim().isNotEmpty == true
                  ? _lastUpdateCheck!.releaseNotes!
                  : l10n.aboutNoReleaseNotes,
            ),
          ],
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
          child: Center(
            widthFactor: 1,
            heightFactor: 1,
            child: FilledButton.icon(
              icon: _checkingForUpdates
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.system_update),
              label: Text(
                _checkingForUpdates
                    ? l10n.settingsCheckingUpdates
                    : l10n.settingsCheckUpdates,
              ),
              onPressed: _checkingForUpdates
                  ? null
                  : () => _checkForUpdates(context),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildReleaseCard(BuildContext context, UpdateCheckResult result) {
    final l10n = AppLocalizations.of(context);
    final releaseUrl = result.releaseUrl;
    final releaseLabel = result.releaseName?.trim().isNotEmpty == true
        ? result.releaseName!
        : result.latestTag;

    return Card(
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 16,
          vertical: 12,
        ),
        leading: Icon(
          result.updateAvailable ? Icons.system_update_alt : Icons.check_circle,
        ),
        title: Text(_updateSummary(l10n, result)),
        subtitle: releaseLabel == null ? null : Text(releaseLabel),
        trailing: releaseUrl == null ? null : const Icon(Icons.open_in_new),
        onTap: releaseUrl == null
            ? null
            : () => _openRelease(context, releaseUrl),
      ),
    );
  }

  String _updateSummary(AppLocalizations l10n, UpdateCheckResult result) {
    switch (result.status) {
      case UpdateCheckStatus.updateAvailable:
        return l10n.settingsUpdateAvailable(result.latestTag ?? '');
      case UpdateCheckStatus.upToDate:
        return l10n.settingsNoUpdate(result.latestTag ?? result.currentVersion);
      case UpdateCheckStatus.noReleaseFound:
        return l10n.settingsNoReleaseFound;
      case UpdateCheckStatus.cannotCompare:
        return l10n.settingsUpdateCompareFailed(result.latestTag ?? '');
    }
  }

  Future<void> _checkForUpdates(BuildContext context) async {
    setState(() => _checkingForUpdates = true);

    try {
      final packageInfo = await _packageInfoFuture;
      final result = packageInfo == null
          ? await widget.updateChecker.check()
          : await widget.updateChecker.checkForVersion(
              currentVersion: packageInfo.version,
              currentBuildNumber: packageInfo.buildNumber,
            );
      if (!context.mounted) return;

      setState(() => _lastUpdateCheck = result);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(_updateSummary(AppLocalizations.of(context), result)),
          action: result.releaseUrl == null
              ? null
              : SnackBarAction(
                  label: AppLocalizations.of(context).settingsOpenRelease,
                  onPressed: () => _openRelease(context, result.releaseUrl!),
                ),
        ),
      );
    } catch (e) {
      if (!context.mounted) return;

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            AppLocalizations.of(
              context,
            ).settingsUpdateCheckFailed(e.toString()),
          ),
        ),
      );
    } finally {
      if (mounted) {
        setState(() => _checkingForUpdates = false);
      }
    }
  }

  Future<void> _openRelease(BuildContext context, String url) async {
    final ok = await launchUrl(
      Uri.parse(url),
      mode: LaunchMode.externalApplication,
    );
    if (!ok && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            AppLocalizations.of(context).settingsUpdateCheckFailed(url),
          ),
        ),
      );
    }
  }
}
