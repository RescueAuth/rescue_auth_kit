import 'package:flutter/foundation.dart';
import 'package:uuid/uuid.dart';

import 'vault_models.dart';
import 'vault_repository.dart';

enum VaultSessionStatus { locked, unlocked }

class VaultLockedException implements Exception {
  const VaultLockedException();
  @override
  String toString() => 'VaultLockedException: vault is locked';
}

/// In-memory session state. UI talks to this instead of using the repository
/// directly. Every successful mutator persists atomically through the
/// repository BEFORE the new handle is committed and listeners are notified
/// exactly once.
class VaultSession extends ChangeNotifier {
  final VaultRepository _repo;
  final Uuid _uuid = const Uuid();
  VaultSessionStatus _status = VaultSessionStatus.locked;
  VaultHandle? _handle;

  VaultSession(this._repo);

  VaultSessionStatus get status => _status;
  bool get isUnlocked => _status == VaultSessionStatus.unlocked;

  VaultData get data {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    return h.data;
  }

  Future<bool> vaultExists() => _repo.vaultFileExists();

  Future<void> createNew({required String password}) async {
    final handle = await _repo.createNewVault(password: password);
    _handle = handle;
    _status = VaultSessionStatus.unlocked;
    notifyListeners();
  }

  Future<void> unlock({required String password}) async {
    final handle = await _repo.open(password: password);
    _handle = handle;
    _status = VaultSessionStatus.unlocked;
    notifyListeners();
  }

  void lock() {
    _handle = null;
    _status = VaultSessionStatus.locked;
    notifyListeners();
  }

  void setData(VaultData newData) {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    _handle = h.copyWith(data: newData);
    notifyListeners();
  }

  Future<void> save() async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    await _repo.save(h);
  }

  Future<void> importVault({
    required Uint8List vaultBytes,
    required String password,
  }) async {
    await _repo.createCheckpointBackup(reason: 'import');
    _handle = await _repo.importBytes(bytes: vaultBytes, password: password);
    _status = VaultSessionStatus.unlocked;
    notifyListeners();
  }

  // ---------------------------------------------------------------------------
  // Persistence helper: apply a pure transformation to data, save, then notify.
  // ---------------------------------------------------------------------------

  Future<void> _persist(VaultData newData) async {
    final h = _handle!;
    final newHandle = h.copyWith(data: newData);
    await _repo.save(newHandle);
    _handle = newHandle;
    notifyListeners();
  }

  // ---------------------------------------------------------------------------
  // ServiceProvider operations
  // ---------------------------------------------------------------------------

  Future<ServiceProvider> addProvider({required String name}) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    final now = DateTime.now();
    final p = ServiceProvider(
      id: _uuid.v4(),
      name: name.trim(),
      createdAt: now,
      updatedAt: now,
    );
    await _persist(h.data.withNewProvider(p));
    return p;
  }

  Future<void> renameProvider({
    required String providerId,
    required String name,
  }) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    await _persist(
      h.data.withRenamedProvider(
        providerId,
        name: name.trim(),
        now: DateTime.now(),
      ),
    );
  }

  /// Removes a ServiceProvider and cascades to all its accounts.
  Future<void> removeProvider(String providerId) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    await _repo.createCheckpointBackup(reason: 'delete-provider');
    await _persist(h.data.withoutProvider(providerId));
  }

  // ---------------------------------------------------------------------------
  // Account operations
  // ---------------------------------------------------------------------------

  Future<Account> addAccount({
    required String providerId,
    required String displayName,
  }) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    final now = DateTime.now();
    final account = Account(
      id: _uuid.v4(),
      providerId: providerId,
      displayName: displayName.trim(),
      createdAt: now,
      updatedAt: now,
      credentials: const [],
    );
    await _persist(h.data.withNewAccount(account));
    return account;
  }

  Future<void> renameAccount({
    required String accountId,
    required String displayName,
  }) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    await _persist(
      h.data.withRenamedAccount(
        accountId,
        displayName: displayName.trim(),
        now: DateTime.now(),
      ),
    );
  }

  Future<void> moveAccountToProvider({
    required String accountId,
    required String newProviderId,
  }) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    await _persist(
      h.data.withMovedAccount(
        accountId,
        newProviderId: newProviderId,
        now: DateTime.now(),
      ),
    );
  }

  Future<void> removeAccount(String accountId) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    await _repo.createCheckpointBackup(reason: 'delete-account');
    await _persist(h.data.withoutAccount(accountId));
  }

  // ---------------------------------------------------------------------------
  // Credential operations
  // ---------------------------------------------------------------------------

  Future<void> addCredentialToAccount({
    required String accountId,
    required Credential draft,
  }) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    await _persist(
      h.data.withCredential(accountId, draft, now: DateTime.now()),
    );
  }

  /// Convenience: create a new account under [providerId] holding [draft].
  Future<Account> addCredentialAsNewAccount({
    required String providerId,
    required String displayName,
    required Credential draft,
  }) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    final now = DateTime.now();
    final account = Account(
      id: _uuid.v4(),
      providerId: providerId,
      displayName: displayName.trim(),
      createdAt: now,
      updatedAt: now,
      credentials: [draft],
    );
    await _persist(h.data.withNewAccount(account));
    return account;
  }

  /// Convenience: create a new ServiceProvider AND a new account holding [draft].
  Future<({ServiceProvider provider, Account account})>
  addCredentialAsNewProviderAndAccount({
    required String providerName,
    required String accountDisplayName,
    required Credential draft,
  }) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    final now = DateTime.now();
    final provider = ServiceProvider(
      id: _uuid.v4(),
      name: providerName.trim(),
      createdAt: now,
      updatedAt: now,
    );
    final account = Account(
      id: _uuid.v4(),
      providerId: provider.id,
      displayName: accountDisplayName.trim(),
      createdAt: now,
      updatedAt: now,
      credentials: [draft],
    );
    final newData = h.data.withNewProvider(provider).withNewAccount(account);
    await _persist(newData);
    return (provider: provider, account: account);
  }

  Future<void> removeCredentialFromAccount({
    required String accountId,
    required String credentialId,
  }) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    await _repo.createCheckpointBackup(reason: 'delete-credential');
    await _persist(
      h.data.withoutCredential(accountId, credentialId, now: DateTime.now()),
    );
  }

  /// Replaces a credential's content while preserving its id and createdAt.
  /// Used for editing a [RecoveryCodesCredential]'s codes list. The
  /// [replacement] MUST share its id with the existing credential.
  Future<void> replaceCredentialInAccount({
    required String accountId,
    required Credential replacement,
  }) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    await _persist(
      h.data.withReplacedCredential(
        accountId,
        replacement.id,
        replacement,
        now: DateTime.now(),
      ),
    );
  }

  /// Moves a credential from one account to another, preserving id, createdAt,
  /// and contents. No-op when both ids are equal.
  Future<void> moveCredentialToAccount({
    required String fromAccountId,
    required String credentialId,
    required String toAccountId,
  }) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    await _persist(
      h.data.withMovedCredential(
        fromAccountId,
        credentialId,
        toAccountId,
        now: DateTime.now(),
      ),
    );
  }

  /// Merges [sourceAccountId] into [targetAccountId]: appends every credential
  /// from the source to the target (preserving order), then deletes the
  /// source account. The target's provider, id, displayName, and createdAt
  /// are left unchanged; only `updatedAt` is bumped.
  Future<void> mergeAccountInto({
    required String sourceAccountId,
    required String targetAccountId,
  }) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    await _repo.createCheckpointBackup(reason: 'merge-account');
    await _persist(
      h.data.withMergedAccount(
        sourceAccountId,
        targetAccountId,
        now: DateTime.now(),
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Developer entries — unchanged behavior.
  // ---------------------------------------------------------------------------

  Future<void> setDeveloperBackupEnabled(bool enabled) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    await _persist(
      h.data.copyWith(
        developerSettings: h.data.developerSettings.copyWith(enabled: enabled),
      ),
    );
  }

  Future<void> addDeveloperEntry({
    required DeveloperEntryType type,
    required String title,
    String notes = '',
    required Map<String, dynamic> payload,
  }) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    final now = DateTime.now();
    final entry = DeveloperEntry(
      id: _uuid.v4(),
      type: type,
      title: title.trim(),
      notes: notes.trim(),
      createdAt: now,
      updatedAt: now,
      payload: Map.unmodifiable(payload),
    );
    await _persist(
      h.data.copyWith(
        developerEntries: <DeveloperEntry>[...h.data.developerEntries, entry],
      ),
    );
  }

  Future<void> updateDeveloperEntry({
    required String id,
    required String title,
    required String notes,
    required Map<String, dynamic> payload,
  }) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    final now = DateTime.now();
    final updatedEntries = h.data.developerEntries
        .map((entry) {
          if (entry.id != id) return entry;
          return entry.copyWith(
            title: title.trim(),
            notes: notes.trim(),
            updatedAt: now,
            payload: payload,
          );
        })
        .toList(growable: false);
    await _persist(h.data.copyWith(developerEntries: updatedEntries));
  }

  Future<void> removeDeveloperEntry(String id) async {
    final h = _handle;
    if (h == null) throw const VaultLockedException();
    await _repo.createCheckpointBackup(reason: 'delete-developer-entry');
    await _persist(
      h.data.copyWith(
        developerEntries: h.data.developerEntries
            .where((entry) => entry.id != id)
            .toList(growable: false),
      ),
    );
  }
}
