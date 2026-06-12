import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:uuid/uuid.dart';

import '../../core/vault/vault_models.dart';
import '../../core/vault/vault_session.dart';
import '../../l10n/app_localizations.dart';
import 'destination_selector.dart';

/// Screen for adding recovery codes as a new [RecoveryCodesCredential].
///
/// Layout (top to bottom):
///   1. Inline [DestinationSelector] — pick where to save up-front.
///   2. Codes field (one per line).
///   3. Save button (disabled until destination is complete and at least one
///      non-empty code has been entered).
class AddRecoveryCodesScreen extends StatefulWidget {
  const AddRecoveryCodesScreen({
    super.key,
    this.targetAccountId,
    this.targetProviderId,
  });

  final String? targetAccountId;
  final String? targetProviderId;

  @override
  State<AddRecoveryCodesScreen> createState() => _AddRecoveryCodesScreenState();
}

class _AddRecoveryCodesScreenState extends State<AddRecoveryCodesScreen> {
  static const _uuid = Uuid();

  final _codesCtrl = TextEditingController();

  CredentialDestination? _destination;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _codesCtrl.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _codesCtrl.dispose();
    super.dispose();
  }

  bool get _canSave {
    if (_saving) return false;
    if (_destination?.isComplete != true) return false;
    return _codesCtrl.text
        .split(RegExp(r'[\r\n]+'))
        .any((line) => line.trim().isNotEmpty);
  }

  Future<void> _save() async {
    final l10n = AppLocalizations.of(context);
    final destination = _destination;
    if (destination == null || !destination.isComplete) return;

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
      final draft = RecoveryCodesCredential(
        id: _uuid.v4(),
        createdAt: DateTime.now(),
        codes: codes,
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
    return Scaffold(
      appBar: AppBar(title: Text(l10n.addRecoveryTitle)),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              DestinationSelector(
                lockedAccountId: widget.targetAccountId,
                lockedProviderId: widget.targetProviderId,
                onChanged: (d) => setState(() => _destination = d),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: _codesCtrl,
                minLines: 6,
                maxLines: 12,
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
                  onPressed: _canSave ? _save : null,
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
