import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:rescue_auth_kit/core/vault/vault_models.dart';
import 'package:rescue_auth_kit/core/vault/vault_repository.dart';
import 'package:rescue_auth_kit/core/vault/vault_session.dart';
import 'package:rescue_auth_kit/features/accounts/add_credential_sheet.dart';
import 'package:rescue_auth_kit/features/scan/scan_screen.dart';
import 'package:rescue_auth_kit/l10n/app_localizations.dart';

void main() {
  testWidgets('paste import continues after the add sheet closes', (
    WidgetTester tester,
  ) async {
    final repo = VaultRepository.forPath(vaultFilePath: 'unused.rakvault');
    final session = _FakeVaultSession(repo);

    await tester.pumpWidget(
      MultiProvider(
        providers: [
          Provider<VaultRepository>.value(value: repo),
          ChangeNotifierProvider<VaultSession>.value(value: session),
        ],
        child: MaterialApp(
          localizationsDelegates: const [
            AppLocalizations.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          supportedLocales: const [Locale('en'), Locale('zh')],
          home: Builder(
            builder: (context) => Scaffold(
              body: Center(
                child: FilledButton(
                  onPressed: () => AddCredentialSheet.show(context),
                  child: const Text('Open add sheet'),
                ),
              ),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('Open add sheet'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Paste otpauth URI'));
    await tester.pumpAndSettle();

    const uri =
        'otpauth://totp/GitHub:xincy?secret=JBSWY3DPEHPK3PXP&issuer=GitHub';
    await tester.enterText(find.byType(TextField).last, uri);
    await tester.tap(find.text('Continue'));
    await tester.pumpAndSettle();

    expect(find.text('Save to Vault'), findsOneWidget);

    await tester.tap(find.text('Save to Vault'));
    await tester.pumpAndSettle();

    expect(session.data.providers.single.name, 'GitHub');
    expect(session.data.accounts.single.displayName, 'xincy');
    final credential =
        session.data.accounts.single.credentials.single as TotpCredential;
    expect(credential.secretBase32, 'JBSWY3DPEHPK3PXP');
  });

  testWidgets('scan import can be verified with a fake scanner', (
    WidgetTester tester,
  ) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    try {
      final repo = VaultRepository.forPath(vaultFilePath: 'unused.rakvault');
      final session = _FakeVaultSession(repo);
      late ScanResultHandler scan;

      await tester.pumpWidget(
        MultiProvider(
          providers: [
            Provider<VaultRepository>.value(value: repo),
            ChangeNotifierProvider<VaultSession>.value(value: session),
          ],
          child: MaterialApp(
            localizationsDelegates: const [
              AppLocalizations.delegate,
              GlobalMaterialLocalizations.delegate,
              GlobalWidgetsLocalizations.delegate,
              GlobalCupertinoLocalizations.delegate,
            ],
            supportedLocales: const [Locale('en'), Locale('zh')],
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: FilledButton(
                    onPressed: () => AddCredentialSheet.show(
                      context,
                      scanScreenBuilder: (_) => ScanScreen(
                        scanViewBuilder: (_, onScan) {
                          scan = onScan;
                          return const Text('Fake scanner');
                        },
                      ),
                    ),
                    child: const Text('Open add sheet'),
                  ),
                ),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('Open add sheet'));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Scan QR'));
      await tester.pumpAndSettle();

      scan('otpauth://totp/GitHub:xincy?secret=JBSWY3DPEHPK3PXP&issuer=GitHub');
      await tester.pumpAndSettle();

      expect(find.text('Save to Vault'), findsOneWidget);

      await tester.tap(find.text('Save to Vault'));
      await tester.pumpAndSettle();

      expect(session.data.providers.single.name, 'GitHub');
      expect(session.data.accounts.single.displayName, 'xincy');
      final credential =
          session.data.accounts.single.credentials.single as TotpCredential;
      expect(credential.secretBase32, 'JBSWY3DPEHPK3PXP');
    } finally {
      debugDefaultTargetPlatformOverride = null;
    }
  });
}

class _FakeVaultSession extends VaultSession {
  _FakeVaultSession(super.repo);

  VaultData _data = VaultData.empty();
  var _idCounter = 0;

  @override
  VaultData get data => _data;

  @override
  bool get isUnlocked => true;

  String _nextId(String prefix) {
    _idCounter += 1;
    return '$prefix-$_idCounter';
  }

  @override
  Future<({ServiceProvider provider, Account account})>
  addCredentialAsNewProviderAndAccount({
    required String providerName,
    required String accountDisplayName,
    required Credential draft,
  }) async {
    final now = DateTime.utc(2026, 6, 11);
    final provider = ServiceProvider(
      id: _nextId('provider'),
      name: providerName.trim(),
      createdAt: now,
      updatedAt: now,
    );
    final account = Account(
      id: _nextId('account'),
      providerId: provider.id,
      displayName: accountDisplayName.trim(),
      createdAt: now,
      updatedAt: now,
      credentials: [draft],
    );

    _data = _data.withNewProvider(provider).withNewAccount(account);
    notifyListeners();
    return (provider: provider, account: account);
  }
}
