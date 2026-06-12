import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:uuid/uuid.dart';

import '../../core/import/otpauth_parser.dart';
import '../../core/vault/vault_models.dart';
import '../../core/vault/vault_session.dart';
import '../../l10n/app_localizations.dart';
import '../accounts/destination_selector.dart';

/// Confirm-import screen for an otpauth URI scanned or pasted by the user.
///
/// Layout (top to bottom):
///   1. Inline [DestinationSelector] — pick provider+account up-front. The
///      issuer/account parsed from the URI become the prefilled hints.
///   2. Read-only summary of the parsed algorithm/digits/period
///   3. Save button (disabled until destination is complete)
class ConfirmImportScreen extends StatefulWidget {
  const ConfirmImportScreen({
    super.key,
    required this.otpauthUri,
    this.targetAccountId,
    this.targetProviderId,
  });

  final String otpauthUri;
  final String? targetAccountId;
  final String? targetProviderId;

  @override
  State<ConfirmImportScreen> createState() => _ConfirmImportScreenState();
}

class _ConfirmImportScreenState extends State<ConfirmImportScreen> {
  static const _uuid = Uuid();

  ParsedTotp? _parsed;
  String? _parseError;

  CredentialDestination? _destination;
  bool _saving = false;
  String? _saveError;

  @override
  void initState() {
    super.initState();
    try {
      _parsed = parseOtpauthTotpUri(widget.otpauthUri);
    } catch (e) {
      _parseError = e.toString();
    }
  }

  bool get _canSave =>
      !_saving && _parsed != null && _destination?.isComplete == true;

  Future<void> _save() async {
    final l10n = AppLocalizations.of(context);
    final parsed = _parsed;
    final destination = _destination;
    if (parsed == null || destination == null || !destination.isComplete) {
      return;
    }

    setState(() {
      _saving = true;
      _saveError = null;
    });

    try {
      final draft = TotpCredential(
        id: _uuid.v4(),
        createdAt: DateTime.now(),
        secretBase32: parsed.secretBase32,
        algorithm: parsed.algorithm,
        digits: parsed.digits,
        period: parsed.period,
      );

      await persistDestination(
        session: context.read<VaultSession>(),
        draft: draft,
        destination: destination,
      );

      if (!mounted) return;
      final navigator = Navigator.of(context);
      final messenger = ScaffoldMessenger.of(context);
      navigator.pop();
      messenger.showSnackBar(SnackBar(content: Text(l10n.importSaved)));
    } catch (e) {
      setState(() => _saveError = l10n.importSaveFailed(e.toString()));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final parsed = _parsed;

    return Scaffold(
      appBar: AppBar(title: Text(l10n.confirmImportTitle)),
      body: SafeArea(
        child: parsed == null
            ? Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(l10n.confirmImportParseError(_parseError ?? '')),
                    const SizedBox(height: 12),
                    Text(l10n.confirmImportHint),
                  ],
                ),
              )
            : SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    DestinationSelector(
                      providerNameHint: parsed.issuer.isNotEmpty
                          ? parsed.issuer
                          : null,
                      accountNameHint: parsed.accountName.isNotEmpty
                          ? parsed.accountName
                          : null,
                      lockedAccountId: widget.targetAccountId,
                      lockedProviderId: widget.targetProviderId,
                      onChanged: (d) => setState(() => _destination = d),
                    ),
                    const SizedBox(height: 16),
                    Card(
                      margin: EdgeInsets.zero,
                      child: ListTile(
                        title: Text(l10n.algoDigitsPeriod),
                        subtitle: Text(
                          '${parsed.algorithm.otpauthName} / '
                          '${parsed.digits} / ${parsed.period}s',
                        ),
                      ),
                    ),
                    if (_saveError != null) ...[
                      const SizedBox(height: 12),
                      Text(
                        _saveError!,
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    ],
                    const SizedBox(height: 24),
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton(
                        onPressed: _canSave ? _save : null,
                        child: _saving
                            ? const SizedBox(
                                width: 18,
                                height: 18,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : Text(l10n.saveToVault),
                      ),
                    ),
                  ],
                ),
              ),
      ),
    );
  }
}
