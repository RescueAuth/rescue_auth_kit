import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:provider/provider.dart';
import 'package:rescue_auth_kit/core/backup/vault_backup_models.dart';
import 'package:rescue_auth_kit/core/backup/vault_backup_service.dart';
import 'package:rescue_auth_kit/core/update/update_checker.dart';
import 'package:rescue_auth_kit/core/vault/vault_models.dart';
import 'package:rescue_auth_kit/core/vault/vault_repository.dart';
import 'package:rescue_auth_kit/core/vault/vault_session.dart';
import 'package:rescue_auth_kit/features/home/home_shell.dart';
import 'package:rescue_auth_kit/features/settings/settings_screen.dart';
import 'package:rescue_auth_kit/l10n/app_localizations.dart';

void main() {
  testWidgets('material smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(
      const MaterialApp(home: Scaffold(body: Text('smoke'))),
    );

    expect(find.text('smoke'), findsOneWidget);
  });

  testWidgets('settings page has no developer backup switch', (
    WidgetTester tester,
  ) async {
    final repo = VaultRepository.forPath(vaultFilePath: 'test-vault.rakvault');
    final session = _FakeVaultSession(repo);

    await tester.pumpWidget(
      MultiProvider(
        providers: [
          Provider<VaultRepository>.value(value: repo),
          ChangeNotifierProvider<VaultSession>.value(value: session),
        ],
        child: const MaterialApp(
          localizationsDelegates: [
            AppLocalizations.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          supportedLocales: [Locale('en'), Locale('zh')],
          home: Scaffold(body: SettingsScreen()),
        ),
      ),
    );

    expect(find.byKey(const ValueKey('developer-backup-switch')), findsNothing);
    expect(find.text('Developer Backup'), findsNothing);
  });

  testWidgets('settings page uses entry cards for backup details', (
    WidgetTester tester,
  ) async {
    final repo = VaultRepository.forPath(
      vaultFilePath: 'test-vault.rakvault',
      backupService: _FakeBackupService(),
    );
    final session = _FakeVaultSession(repo);

    await tester.pumpWidget(
      MultiProvider(
        providers: [
          Provider<VaultRepository>.value(value: repo),
          ChangeNotifierProvider<VaultSession>.value(value: session),
        ],
        child: const MaterialApp(
          localizationsDelegates: [
            AppLocalizations.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          supportedLocales: [Locale('en'), Locale('zh')],
          home: Scaffold(body: SettingsScreen()),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Manual backup'), findsOneWidget);
    expect(find.text('Automatic backups'), findsOneWidget);
    expect(find.text('Automatic backup frequency'), findsNothing);

    await tester.tap(find.text('Automatic backups'));
    await tester.pumpAndSettle();

    expect(find.text('Automatic backup frequency'), findsOneWidget);
    expect(find.text('Backup location'), findsOneWidget);
  });

  testWidgets('about page shows local and latest release notes', (
    WidgetTester tester,
  ) async {
    final checker = UpdateChecker(
      releaseFetcher: () async => const GitHubRelease(
        tagName: 'v1.3.0',
        htmlUrl: 'https://example.com/release',
        name: 'RescueAuthKit 1.3.0',
        body: 'Latest release body',
      ),
    );
    final packageInfo = PackageInfo(
      appName: 'RescueAuthKit',
      packageName: 'com.xincy.rescue_auth_kit',
      version: '1.2.0',
      buildNumber: '5',
    );

    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: const [
          AppLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        supportedLocales: const [Locale('en'), Locale('zh')],
        home: AboutVersionScreen(
          updateChecker: checker,
          packageInfoFuture: Future.value(packageInfo),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('About RescueAuthKit'), findsWidgets);
    expect(find.text('Current version: 1.2.0+5'), findsOneWidget);
    expect(find.text("What's new in 1.2.0"), findsOneWidget);

    await tester.tap(find.text('Check for updates'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    await tester.scrollUntilVisible(
      find.text('Latest release notes'),
      160,
      scrollable: find.byType(Scrollable).first,
    );

    expect(find.text('Latest release notes'), findsOneWidget);
    expect(find.text('Latest release body'), findsOneWidget);
  });

  testWidgets('developer tab is available by default', (
    WidgetTester tester,
  ) async {
    final repo = VaultRepository.forPath(vaultFilePath: 'test-vault.rakvault');
    final session = _FakeVaultSession(repo);

    await tester.pumpWidget(
      MultiProvider(
        providers: [
          Provider<VaultRepository>.value(value: repo),
          ChangeNotifierProvider<VaultSession>.value(value: session),
        ],
        child: const MaterialApp(
          localizationsDelegates: [
            AppLocalizations.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          supportedLocales: [Locale('en'), Locale('zh')],
          home: HomeShell(),
        ),
      ),
    );

    expect(find.text('Developer'), findsWidgets);
  });
}

class _FakeVaultSession extends VaultSession {
  _FakeVaultSession(super.repo);

  final VaultData _data = VaultData.empty();

  @override
  VaultData get data => _data;

  @override
  bool get isUnlocked => true;
}

class _FakeBackupService implements VaultBackupService {
  BackupSettings _settings = BackupSettings.defaults();

  @override
  String get locationDescription => 'Downloads/RescueAuthKit/Backups';

  @override
  Future<void> createCheckpoint({
    required Uint8List bytes,
    required String reason,
  }) async {}

  @override
  Future<BackupSettings> loadSettings() async => _settings;

  @override
  Future<void> maybeAutoBackup(Uint8List bytes) async {}

  @override
  Future<void> setAutoBackupEnabled(bool enabled) async {
    _settings = _settings.copyWith(autoBackupEnabled: enabled);
  }

  @override
  Future<void> setAutoBackupFrequency(AutoBackupFrequency frequency) async {
    _settings = _settings.copyWith(autoBackupFrequency: frequency);
  }

  @override
  Future<void> updateSettings(BackupSettings settings) async {
    _settings = settings;
  }
}
