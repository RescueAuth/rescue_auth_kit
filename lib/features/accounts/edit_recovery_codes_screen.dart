import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../core/vault/vault_models.dart';
import '../../core/vault/vault_session.dart';
import '../../l10n/app_localizations.dart';

/// Edits the [codes] list of an existing [RecoveryCodesCredential].
///
/// Preserves the credential's id and createdAt — only the codes list is
/// replaced via [VaultSession.replaceCredentialInAccount].
class EditRecoveryCodesScreen extends StatefulWidget {
  const EditRecoveryCodesScreen({
    super.key,
    required this.accountId,
    required this.credentialId,
  });

  final String accountId;
  final String credentialId;

  @override
  State<EditRecoveryCodesScreen> createState() =>
      _EditRecoveryCodesScreenState();
}

class _EditRecoveryCodesScreenState extends State<EditRecoveryCodesScreen> {
  late final TextEditingController _codesCtrl;
  bool _initialized = false;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _codesCtrl = TextEditingController();
    _codesCtrl.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _codesCtrl.dispose();
    super.dispose();
  }

  RecoveryCodesCredential? _findCredential(VaultSession session) {
    Account? account;
    try {
      account = session.data.requireAccount(widget.accountId);
    } catch (_) {
      return null;
    }
    for (final c in account.credentials) {
      if (c.id == widget.credentialId && c is RecoveryCodesCredential) {
        return c;
      }
    }
    return null;
  }

  bool get _canSave {
    if (_saving) return false;
    return _codesCtrl.text
        .split(RegExp(r'[\r\n]+'))
        .any((line) => line.trim().isNotEmpty);
  }

  Future<void> _save(RecoveryCodesCredential original) async {
    final l10n = AppLocalizations.of(context);
    setState(() {
      _saving = true;
      _error = null;
    });

    final codes = _codesCtrl.text
        .split(RegExp(r'[\r\n]+'))
        .map((e) => e.trim())
        .where((e) => e.isNotEmpty)
        .toList(growable: false);

    if (codes.isEmpty) {
      setState(() {
        _saving = false;
        _error = l10n.recoveryNeedOne;
      });
      return;
    }

    try {
      final replacement = original.copyWith(codes: codes);
      await context.read<VaultSession>().replaceCredentialInAccount(
        accountId: widget.accountId,
        replacement: replacement,
      );

      if (!mounted) return;
      final navigator = Navigator.of(context);
      final messenger = ScaffoldMessenger.of(context);
      navigator.pop();
      messenger.showSnackBar(SnackBar(content: Text(l10n.recoverySaved)));
    } catch (e) {
      setState(() => _error = l10n.recoverySaveFailed(e.toString()));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final session = context.watch<VaultSession>();
    final cred = _findCredential(session);

    if (cred == null) {
      // Credential was deleted externally; pop after frame.
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) Navigator.of(context).pop();
      });
      return const Scaffold(body: SizedBox.shrink());
    }

    if (!_initialized) {
      _codesCtrl.text = cred.codes.join('\n');
      _initialized = true;
    }

    return Scaffold(
      appBar: AppBar(title: Text(l10n.editRecoveryTitle)),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                controller: _codesCtrl,
                minLines: 6,
                maxLines: 16,
                decoration: InputDecoration(
                  labelText: l10n.recoveryCodesLabel,
                  border: const OutlineInputBorder(),
                ),
              ),
              if (_error != null) ...[
                const SizedBox(height: 12),
                Text(
                  _error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ],
              const SizedBox(height: 24),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: _canSave ? () => _save(cred) : null,
                  child: _saving
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
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
