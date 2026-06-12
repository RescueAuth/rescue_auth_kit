import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../../l10n/app_localizations.dart';
import '../scan/confirm_import_screen.dart';
import '../scan/paste_uri_dialog.dart';
import '../scan/scan_screen.dart';
import 'add_recovery_codes_screen.dart';

enum _AddCredentialAction { scan, paste, recoveryCodes }

typedef ScanScreenBuilder = Widget Function(BuildContext context);

/// The unified add-credential bottom sheet, presented from the providers/
/// accounts FAB or the [AccountDetailScreen]'s "Add credential" action.
///
/// Offers three options:
/// - Scan QR (Android only)
/// - Paste otpauth URI
/// - Add recovery codes
///
/// `show` can pre-bind a target account or provider. When a target account is
/// provided, the credential is attached directly to that account. When only a
/// target provider is provided, the subsequent picker is locked to that
/// provider.
class AddCredentialSheet extends StatelessWidget {
  const AddCredentialSheet({super.key, this.scanScreenBuilder});

  final ScanScreenBuilder? scanScreenBuilder;

  static Future<void> show(
    BuildContext context, {
    String? targetAccountId,
    String? targetProviderId,
    ScanScreenBuilder? scanScreenBuilder,
  }) async {
    final action = await showModalBottomSheet<_AddCredentialAction>(
      context: context,
      builder: (ctx) =>
          AddCredentialSheet(scanScreenBuilder: scanScreenBuilder),
    );

    if (!context.mounted || action == null) return;

    switch (action) {
      case _AddCredentialAction.scan:
        await _openScanImport(
          context,
          targetAccountId: targetAccountId,
          targetProviderId: targetProviderId,
          scanScreenBuilder: scanScreenBuilder,
        );
      case _AddCredentialAction.paste:
        await _openPasteImport(
          context,
          targetAccountId: targetAccountId,
          targetProviderId: targetProviderId,
        );
      case _AddCredentialAction.recoveryCodes:
        await Navigator.of(context).push<void>(
          MaterialPageRoute(
            builder: (_) => AddRecoveryCodesScreen(
              targetAccountId: targetAccountId,
              targetProviderId: targetProviderId,
            ),
          ),
        );
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final isAndroid =
        !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

    return SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Text(
              l10n.addCredentialSheetTitle,
              style: Theme.of(context).textTheme.titleLarge,
            ),
          ),
          if (isAndroid)
            ListTile(
              leading: const Icon(Icons.qr_code_scanner),
              title: Text(l10n.addCredentialScan),
              onTap: () => Navigator.of(context).pop(_AddCredentialAction.scan),
            ),
          ListTile(
            leading: const Icon(Icons.paste),
            title: Text(l10n.addCredentialPaste),
            onTap: () => Navigator.of(context).pop(_AddCredentialAction.paste),
          ),
          ListTile(
            leading: const Icon(Icons.key),
            title: Text(l10n.addCredentialRecoveryCodes),
            onTap: () =>
                Navigator.of(context).pop(_AddCredentialAction.recoveryCodes),
          ),
          const SizedBox(height: 8),
        ],
      ),
    );
  }
}

Future<void> _openScanImport(
  BuildContext context, {
  required String? targetAccountId,
  required String? targetProviderId,
  required ScanScreenBuilder? scanScreenBuilder,
}) async {
  final uriText = await Navigator.of(context).push<String?>(
    MaterialPageRoute(
      builder: (ctx) => scanScreenBuilder?.call(ctx) ?? const ScanScreen(),
    ),
  );

  if (!context.mounted || uriText == null || uriText.trim().isEmpty) return;

  await Navigator.of(context).push<void>(
    MaterialPageRoute(
      builder: (_) => ConfirmImportScreen(
        otpauthUri: uriText,
        targetAccountId: targetAccountId,
        targetProviderId: targetProviderId,
      ),
    ),
  );
}

Future<void> _openPasteImport(
  BuildContext context, {
  required String? targetAccountId,
  required String? targetProviderId,
}) async {
  final uriText = await showPasteOtpauthDialog(context);

  if (!context.mounted || uriText == null || uriText.trim().isEmpty) return;

  await Navigator.of(context).push<void>(
    MaterialPageRoute(
      builder: (_) => ConfirmImportScreen(
        otpauthUri: uriText,
        targetAccountId: targetAccountId,
        targetProviderId: targetProviderId,
      ),
    ),
  );
}
