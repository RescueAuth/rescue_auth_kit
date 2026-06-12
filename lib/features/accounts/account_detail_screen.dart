import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:otp/otp.dart';
import 'package:provider/provider.dart';

import '../../core/vault/vault_models.dart';
import '../../core/vault/vault_session.dart';
import '../../l10n/app_localizations.dart';
import 'add_credential_sheet.dart';
import 'edit_recovery_codes_screen.dart';
import 'merge_account_dialog.dart';
import 'move_account_dialog.dart';
import 'move_credential_dialog.dart';

/// The account detail screen — the ONLY place in the app where TOTP digits
/// and recovery codes are rendered.
///
/// Uses a 1Hz timer to refresh TOTP codes. The timer lives exclusively on
/// this screen and is disposed when the screen is removed from the tree.
class AccountDetailScreen extends StatefulWidget {
  const AccountDetailScreen({super.key, required this.accountId});

  final String accountId;

  @override
  State<AccountDetailScreen> createState() => _AccountDetailScreenState();
}

class _AccountDetailScreenState extends State<AccountDetailScreen> {
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Account? _findAccount(VaultSession session) {
    try {
      return session.data.accounts.firstWhere((a) => a.id == widget.accountId);
    } catch (_) {
      return null;
    }
  }

  Future<void> _showRenameDialog(BuildContext context, Account account) async {
    final l10n = AppLocalizations.of(context);
    final controller = TextEditingController(text: account.displayName);

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l10n.accountDetailRename),
        content: TextField(
          controller: controller,
          decoration: InputDecoration(labelText: l10n.accountLabel),
          autofocus: true,
        ),
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

    if (!context.mounted || confirmed != true) return;
    final displayName = controller.text.trim();
    if (displayName.isEmpty) return;
    await context.read<VaultSession>().renameAccount(
      accountId: widget.accountId,
      displayName: displayName,
    );
  }

  Future<void> _confirmDeleteAccount(BuildContext context) async {
    final l10n = AppLocalizations.of(context);

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l10n.accountDetailDeleteAccount),
        content: Text(l10n.accountDetailDeleteAccountConfirm),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l10n.dialogCancel),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(ctx).colorScheme.error,
            ),
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(l10n.deleteButton),
          ),
        ],
      ),
    );

    if (!context.mounted || confirmed != true) return;
    await context.read<VaultSession>().removeAccount(widget.accountId);
    if (!context.mounted) return;
    Navigator.of(context).pop();
  }

  Future<void> _confirmDeleteCredential(
    BuildContext context,
    String credentialId,
  ) async {
    final l10n = AppLocalizations.of(context);

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l10n.accountDetailDeleteCredential),
        content: Text(l10n.accountDetailDeleteCredentialConfirm),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l10n.dialogCancel),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(ctx).colorScheme.error,
            ),
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(l10n.deleteButton),
          ),
        ],
      ),
    );

    if (!context.mounted || confirmed != true) return;
    await context.read<VaultSession>().removeCredentialFromAccount(
      accountId: widget.accountId,
      credentialId: credentialId,
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final session = context.watch<VaultSession>();
    final account = _findAccount(session);

    if (account == null) {
      // Account was deleted externally; show nothing and pop.
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) Navigator.of(context).pop();
      });
      return const Scaffold(body: SizedBox.shrink());
    }

    // Look up the owning provider for the header subtitle.
    ServiceProvider? owningProvider;
    try {
      owningProvider = session.data.requireProvider(account.providerId);
    } catch (_) {
      owningProvider = null;
    }

    return Scaffold(
      appBar: AppBar(
        title: Text(account.displayName),
        actions: [
          PopupMenuButton<String>(
            onSelected: (value) {
              switch (value) {
                case 'rename':
                  _showRenameDialog(context, account);
                case 'move':
                  showMoveAccountDialog(context, account: account);
                case 'merge':
                  () async {
                    final navigator = Navigator.of(context);
                    final merged = await showMergeAccountDialog(
                      context,
                      source: account,
                    );
                    // Source account is gone after a successful merge; pop
                    // back to the previous screen.
                    if (merged != null && mounted) {
                      navigator.pop();
                    }
                  }();
                case 'delete':
                  _confirmDeleteAccount(context);
              }
            },
            itemBuilder: (ctx) => [
              PopupMenuItem(
                value: 'rename',
                child: Text(l10n.accountDetailRename),
              ),
              PopupMenuItem(value: 'move', child: Text(l10n.accountMoveAction)),
              PopupMenuItem(
                value: 'merge',
                child: Text(l10n.accountMergeAction),
              ),
              PopupMenuItem(
                value: 'delete',
                child: Text(l10n.accountDetailDeleteAccount),
              ),
            ],
          ),
        ],
      ),
      body: _buildBody(context, account, owningProvider, l10n),
    );
  }

  Widget _buildBody(
    BuildContext context,
    Account account,
    ServiceProvider? provider,
    AppLocalizations l10n,
  ) {
    if (account.credentials.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (provider != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(
                    provider.name,
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                ),
              Text(l10n.accountDetailEmptyState),
              const SizedBox(height: 16),
              FilledButton.icon(
                onPressed: () => _addCredential(context),
                icon: const Icon(Icons.add),
                label: Text(l10n.accountDetailAddCredential),
              ),
            ],
          ),
        ),
      );
    }

    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        if (provider != null) _ProviderHeader(provider: provider),
        const SizedBox(height: 12),

        // Credential sections — exhaustive switch on sealed type
        for (final credential in account.credentials)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: switch (credential) {
              TotpCredential totp => _TotpCredentialCard(
                credential: totp,
                account: account,
                onDelete: () =>
                    _confirmDeleteCredential(context, credential.id),
              ),
              RecoveryCodesCredential recovery => _RecoveryCodesCredentialCard(
                credential: recovery,
                accountId: widget.accountId,
                onDelete: () =>
                    _confirmDeleteCredential(context, credential.id),
                onEdit: () => _editRecoveryCredential(context, recovery),
                onMove: () => _moveCredentialToAccount(context, credential.id),
              ),
            },
          ),

        const SizedBox(height: 8),
        Center(
          child: OutlinedButton.icon(
            onPressed: () => _addCredential(context),
            icon: const Icon(Icons.add),
            label: Text(l10n.accountDetailAddCredential),
          ),
        ),
        const SizedBox(height: 16),
      ],
    );
  }

  void _addCredential(BuildContext context) {
    AddCredentialSheet.show(context, targetAccountId: widget.accountId);
  }

  void _editRecoveryCredential(
    BuildContext context,
    RecoveryCodesCredential cred,
  ) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => EditRecoveryCodesScreen(
          accountId: widget.accountId,
          credentialId: cred.id,
        ),
      ),
    );
  }

  void _moveCredentialToAccount(BuildContext context, String credentialId) {
    showMoveCredentialDialog(
      context,
      fromAccountId: widget.accountId,
      credentialId: credentialId,
    );
  }
}

class _ProviderHeader extends StatelessWidget {
  const _ProviderHeader({required this.provider});

  final ServiceProvider provider;

  @override
  Widget build(BuildContext context) {
    return Card(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Row(
          children: [
            const Icon(Icons.business, size: 18),
            const SizedBox(width: 8),
            Text(provider.name, style: Theme.of(context).textTheme.titleSmall),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// TOTP credential card
// ---------------------------------------------------------------------------

class _TotpCredentialCard extends StatelessWidget {
  const _TotpCredentialCard({
    required this.credential,
    required this.account,
    required this.onDelete,
  });

  final TotpCredential credential;
  final Account account;
  final VoidCallback onDelete;

  Algorithm _mapAlgorithm(TotpHashAlgorithm algo) {
    return switch (algo) {
      TotpHashAlgorithm.sha1 => Algorithm.SHA1,
      TotpHashAlgorithm.sha256 => Algorithm.SHA256,
      TotpHashAlgorithm.sha512 => Algorithm.SHA512,
    };
  }

  int _remainingSeconds(int period, int epochSeconds) {
    final passed = epochSeconds % period;
    return period - passed;
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final now = DateTime.now();
    final epochSeconds = now.millisecondsSinceEpoch ~/ 1000;

    final remaining = _remainingSeconds(credential.period, epochSeconds);
    final progress = remaining / credential.period;

    String code;
    bool hasError = false;
    try {
      code = OTP.generateTOTPCodeString(
        credential.secretBase32,
        now.millisecondsSinceEpoch,
        length: credential.digits,
        interval: credential.period,
        algorithm: _mapAlgorithm(credential.algorithm),
        isGoogle: true,
      );
    } catch (_) {
      code = '';
      hasError = true;
    }

    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    l10n.accountsBadgeTotp,
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                ),
                IconButton(
                  tooltip: l10n.accountDetailDeleteCredential,
                  icon: const Icon(Icons.delete_outline, size: 20),
                  onPressed: onDelete,
                ),
              ],
            ),
            if (hasError) ...[
              const SizedBox(height: 8),
              Row(
                children: [
                  Icon(
                    Icons.error_outline,
                    color: Theme.of(context).colorScheme.error,
                  ),
                  const SizedBox(width: 8),
                  Text(
                    l10n.totpInvalidSecret,
                    style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ],
              ),
            ] else ...[
              Row(
                children: [
                  Expanded(
                    child: Text(
                      code,
                      style: Theme.of(context).textTheme.headlineMedium,
                    ),
                  ),
                  IconButton(
                    tooltip: l10n.copied,
                    onPressed: () async {
                      await Clipboard.setData(ClipboardData(text: code));
                      if (!context.mounted) return;
                      ScaffoldMessenger.of(
                        context,
                      ).showSnackBar(SnackBar(content: Text(l10n.totpCopied)));
                    },
                    icon: const Icon(Icons.copy),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              LinearProgressIndicator(value: progress),
              const SizedBox(height: 6),
              Text(l10n.totpExpiresIn(remaining)),
            ],
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Recovery codes credential card
// ---------------------------------------------------------------------------

class _RecoveryCodesCredentialCard extends StatefulWidget {
  const _RecoveryCodesCredentialCard({
    required this.credential,
    required this.accountId,
    required this.onDelete,
    required this.onEdit,
    required this.onMove,
  });

  final RecoveryCodesCredential credential;
  final String accountId;
  final VoidCallback onDelete;
  final VoidCallback onEdit;
  final VoidCallback onMove;

  @override
  State<_RecoveryCodesCredentialCard> createState() =>
      _RecoveryCodesCredentialCardState();
}

class _RecoveryCodesCredentialCardState
    extends State<_RecoveryCodesCredentialCard> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final codes = widget.credential.codes;

    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    l10n.accountsBadgeRecovery,
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                ),
                IconButton(
                  tooltip: l10n.copyAllTooltip,
                  icon: const Icon(Icons.copy_all, size: 20),
                  onPressed: () async {
                    final allCodes = codes.join('\n');
                    await Clipboard.setData(ClipboardData(text: allCodes));
                    if (!context.mounted) return;
                    ScaffoldMessenger.of(
                      context,
                    ).showSnackBar(SnackBar(content: Text(l10n.copied)));
                  },
                ),
                PopupMenuButton<String>(
                  icon: const Icon(Icons.more_vert, size: 20),
                  onSelected: (value) {
                    switch (value) {
                      case 'edit':
                        widget.onEdit();
                      case 'move':
                        widget.onMove();
                      case 'delete':
                        widget.onDelete();
                    }
                  },
                  itemBuilder: (ctx) => [
                    PopupMenuItem(
                      value: 'edit',
                      child: Text(l10n.credentialEditAction),
                    ),
                    PopupMenuItem(
                      value: 'move',
                      child: Text(l10n.credentialMoveAction),
                    ),
                    PopupMenuItem(
                      value: 'delete',
                      child: Text(l10n.accountDetailDeleteCredential),
                    ),
                  ],
                ),
              ],
            ),
            InkWell(
              onTap: () => setState(() => _expanded = !_expanded),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Row(
                  children: [
                    Icon(
                      _expanded ? Icons.expand_less : Icons.expand_more,
                      size: 20,
                    ),
                    const SizedBox(width: 4),
                    Text(
                      l10n.recoveryCodesCount(codes.length),
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
            ),
            if (_expanded)
              Padding(
                padding: const EdgeInsets.only(left: 8, top: 4),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    for (final code in codes)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 2),
                        child: Text(
                          code,
                          style: Theme.of(context).textTheme.bodyMedium
                              ?.copyWith(fontFamily: 'monospace'),
                        ),
                      ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }
}
