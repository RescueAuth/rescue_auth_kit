const int vaultDataSchemaVersion = 3;

enum TotpHashAlgorithm { sha1, sha256, sha512 }

extension TotpHashAlgorithmX on TotpHashAlgorithm {
  String get otpauthName => switch (this) {
    TotpHashAlgorithm.sha1 => 'SHA1',
    TotpHashAlgorithm.sha256 => 'SHA256',
    TotpHashAlgorithm.sha512 => 'SHA512',
  };

  static TotpHashAlgorithm fromOtpauthName(String name) {
    final n = name.trim().toUpperCase();
    return switch (n) {
      'SHA1' => TotpHashAlgorithm.sha1,
      'SHA256' => TotpHashAlgorithm.sha256,
      'SHA512' => TotpHashAlgorithm.sha512,
      _ => throw FormatException('Unsupported TOTP hash algorithm: $name'),
    };
  }
}

// ---------------------------------------------------------------------------
// Legacy v2 models — used by VaultMigrator only.
// ---------------------------------------------------------------------------

/// Legacy v1/v2 TOTP entry shape. Kept solely to feed [VaultMigrator]; never
/// referenced by v3 code paths or persisted under schemaVersion 3.
class TotpEntry {
  final String id;
  final String issuer;
  final String accountName;
  final String secretBase32;
  final TotpHashAlgorithm algorithm;
  final int digits;
  final int period;
  final DateTime createdAt;

  const TotpEntry({
    required this.id,
    required this.issuer,
    required this.accountName,
    required this.secretBase32,
    required this.algorithm,
    required this.digits,
    required this.period,
    required this.createdAt,
  });

  factory TotpEntry.fromJson(Map<String, dynamic> json) {
    final id = json['id'] as String?;
    final issuer = json['issuer'] as String? ?? '';
    final accountName = json['accountName'] as String? ?? '';
    final secretBase32 = json['secretBase32'] as String?;
    final algorithmStr = json['algorithm'] as String? ?? 'SHA1';
    final digits = json['digits'] as int? ?? 6;
    final period = json['period'] as int? ?? 30;
    final createdAtStr = json['createdAt'] as String?;

    if (id == null || secretBase32 == null || createdAtStr == null) {
      throw const FormatException('Invalid TOTP entry');
    }

    return TotpEntry(
      id: id,
      issuer: issuer,
      accountName: accountName,
      secretBase32: secretBase32,
      algorithm: TotpHashAlgorithmX.fromOtpauthName(algorithmStr),
      digits: digits,
      period: period,
      createdAt: DateTime.parse(createdAtStr),
    );
  }
}

/// Legacy v1/v2 recovery code set shape. Kept solely to feed [VaultMigrator].
class RecoveryCodeSet {
  final String id;
  final String title;
  final List<String> codes;
  final DateTime createdAt;

  const RecoveryCodeSet({
    required this.id,
    required this.title,
    required this.codes,
    required this.createdAt,
  });

  factory RecoveryCodeSet.fromJson(Map<String, dynamic> json) {
    final id = json['id'] as String?;
    final title = json['title'] as String? ?? '';
    final codesAny = json['codes'];
    final createdAtStr = json['createdAt'] as String?;

    if (id == null || createdAtStr == null) {
      throw const FormatException('Invalid recovery code set');
    }

    final codes = (codesAny is List)
        ? codesAny.map((e) => e.toString()).toList(growable: false)
        : <String>[];
    return RecoveryCodeSet(
      id: id,
      title: title,
      codes: codes,
      createdAt: DateTime.parse(createdAtStr),
    );
  }
}

// ---------------------------------------------------------------------------
// Developer entries — unchanged across schema versions.
// ---------------------------------------------------------------------------

enum DeveloperEntryType {
  androidSigningKey,
  apiCredential,
  sshKey,
  envVarSet,
  genericSecret,
}

extension DeveloperEntryTypeX on DeveloperEntryType {
  String get jsonName => switch (this) {
    DeveloperEntryType.androidSigningKey => 'androidSigningKey',
    DeveloperEntryType.apiCredential => 'apiCredential',
    DeveloperEntryType.sshKey => 'sshKey',
    DeveloperEntryType.envVarSet => 'envVarSet',
    DeveloperEntryType.genericSecret => 'genericSecret',
  };

  static DeveloperEntryType fromJsonName(String name) {
    return switch (name) {
      'androidSigningKey' => DeveloperEntryType.androidSigningKey,
      'apiCredential' => DeveloperEntryType.apiCredential,
      'sshKey' => DeveloperEntryType.sshKey,
      'envVarSet' => DeveloperEntryType.envVarSet,
      'genericSecret' => DeveloperEntryType.genericSecret,
      _ => DeveloperEntryType.genericSecret,
    };
  }
}

class DeveloperSettings {
  final bool enabled;

  const DeveloperSettings({required this.enabled});

  factory DeveloperSettings.enabled() => const DeveloperSettings(enabled: true);

  factory DeveloperSettings.disabled() =>
      const DeveloperSettings(enabled: false);

  DeveloperSettings copyWith({bool? enabled}) {
    return DeveloperSettings(enabled: enabled ?? this.enabled);
  }

  Map<String, dynamic> toJson() => <String, dynamic>{'enabled': enabled};

  factory DeveloperSettings.fromJson(Map<String, dynamic> json) {
    return DeveloperSettings(enabled: json['enabled'] as bool? ?? true);
  }
}

bool _jsonLikeEquals(Object? a, Object? b) {
  if (identical(a, b)) return true;
  if (a is Map && b is Map) {
    if (a.length != b.length) return false;
    for (final key in a.keys) {
      if (!b.containsKey(key)) return false;
      if (!_jsonLikeEquals(a[key], b[key])) return false;
    }
    return true;
  }
  if (a is List && b is List) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (!_jsonLikeEquals(a[i], b[i])) return false;
    }
    return true;
  }
  return a == b;
}

int _jsonLikeHash(Object? value) {
  if (value is Map) {
    final entries = value.entries.toList()
      ..sort((a, b) => a.key.toString().compareTo(b.key.toString()));
    return Object.hashAll(
      entries.map(
        (entry) => Object.hash(entry.key, _jsonLikeHash(entry.value)),
      ),
    );
  }
  if (value is List) {
    return Object.hashAll(value.map(_jsonLikeHash));
  }
  return value.hashCode;
}

class DeveloperEntry {
  final String id;
  final DeveloperEntryType type;
  final String title;
  final String notes;
  final DateTime createdAt;
  final DateTime updatedAt;
  final Map<String, dynamic> payload;

  const DeveloperEntry({
    required this.id,
    required this.type,
    required this.title,
    required this.notes,
    required this.createdAt,
    required this.updatedAt,
    required this.payload,
  });

  DeveloperEntry copyWith({
    DeveloperEntryType? type,
    String? title,
    String? notes,
    DateTime? updatedAt,
    Map<String, dynamic>? payload,
  }) {
    return DeveloperEntry(
      id: id,
      type: type ?? this.type,
      title: title ?? this.title,
      notes: notes ?? this.notes,
      createdAt: createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
      payload: Map.unmodifiable(payload ?? this.payload),
    );
  }

  Map<String, dynamic> toJson() => <String, dynamic>{
    'id': id,
    'type': type.jsonName,
    'title': title,
    'notes': notes,
    'createdAt': createdAt.toIso8601String(),
    'updatedAt': updatedAt.toIso8601String(),
    'payload': payload,
  };

  factory DeveloperEntry.fromJson(Map<String, dynamic> json) {
    final id = json['id'] as String?;
    final typeName = json['type'] as String?;
    final title = json['title'] as String?;
    final createdAtStr = json['createdAt'] as String?;
    final updatedAtStr = json['updatedAt'] as String?;

    if (id == null ||
        typeName == null ||
        title == null ||
        createdAtStr == null ||
        updatedAtStr == null) {
      throw const FormatException('Invalid developer entry');
    }

    final payloadAny = json['payload'];

    return DeveloperEntry(
      id: id,
      type: DeveloperEntryTypeX.fromJsonName(typeName),
      title: title,
      notes: json['notes'] as String? ?? '',
      createdAt: DateTime.parse(createdAtStr),
      updatedAt: DateTime.parse(updatedAtStr),
      payload: payloadAny is Map
          ? Map<String, dynamic>.unmodifiable(payloadAny)
          : const <String, dynamic>{},
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is DeveloperEntry &&
          id == other.id &&
          type == other.type &&
          title == other.title &&
          notes == other.notes &&
          createdAt == other.createdAt &&
          updatedAt == other.updatedAt &&
          _jsonLikeEquals(payload, other.payload);

  @override
  int get hashCode => Object.hash(
    id,
    type,
    title,
    notes,
    createdAt,
    updatedAt,
    _jsonLikeHash(payload),
  );
}

// ---------------------------------------------------------------------------
// ServiceProvider — top-level grouping (e.g. "GitHub", "Google").
// ---------------------------------------------------------------------------

/// A ServiceProvider groups one or more [Account]s that belong to the same service.
///
/// In typical otpauth terms, the ServiceProvider's [name] corresponds to the
/// `issuer` parameter (e.g. "GitHub"). A ServiceProvider can be empty (no accounts);
/// removing the last account does not auto-delete the ServiceProvider — symmetric
/// to how an account keeps an empty credentials list.
final class ServiceProvider {
  /// Unique id within the vault.
  final String id;

  /// User-facing name (used as the otpauth `issuer` when adding TOTP).
  final String name;

  final DateTime createdAt;
  final DateTime updatedAt;

  const ServiceProvider({
    required this.id,
    required this.name,
    required this.createdAt,
    required this.updatedAt,
  });

  ServiceProvider copyWith({String? name, DateTime? updatedAt}) {
    return ServiceProvider(
      id: id,
      name: name ?? this.name,
      createdAt: createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  Map<String, dynamic> toJson() => <String, dynamic>{
    'id': id,
    'name': name,
    'createdAt': createdAt.toIso8601String(),
    'updatedAt': updatedAt.toIso8601String(),
  };

  factory ServiceProvider.fromJson(Map<String, dynamic> json) {
    final id = json['id'] as String?;
    final name = json['name'] as String?;
    final createdAtStr = json['createdAt'] as String?;
    final updatedAtStr = json['updatedAt'] as String?;
    if (id == null ||
        name == null ||
        createdAtStr == null ||
        updatedAtStr == null) {
      throw const FormatException('Invalid ServiceProvider JSON');
    }
    return ServiceProvider(
      id: id,
      name: name,
      createdAt: DateTime.parse(createdAtStr),
      updatedAt: DateTime.parse(updatedAtStr),
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is ServiceProvider &&
          id == other.id &&
          name == other.name &&
          createdAt == other.createdAt &&
          updatedAt == other.updatedAt;

  @override
  int get hashCode => Object.hash(id, name, createdAt, updatedAt);
}

// ---------------------------------------------------------------------------
// Account — belongs to a ServiceProvider, holds credentials.
// ---------------------------------------------------------------------------

/// An Account belongs to exactly one [ServiceProvider] (via [providerId]) and holds
/// an ordered list of [Credential]s.
final class Account {
  final String id;

  /// Foreign key into [VaultData.providers].
  final String providerId;

  /// User-facing name within the ServiceProvider (e.g. "user@example.com",
  /// "personal", "work"). Distinct from the ServiceProvider name.
  final String displayName;

  final DateTime createdAt;
  final DateTime updatedAt;

  /// Ordered list of credentials. Unmodifiable.
  final List<Credential> credentials;

  Account({
    required this.id,
    required this.providerId,
    required this.displayName,
    required this.createdAt,
    required this.updatedAt,
    required List<Credential> credentials,
  }) : credentials = List<Credential>.unmodifiable(credentials);

  Account copyWith({
    String? providerId,
    String? displayName,
    DateTime? updatedAt,
    List<Credential>? credentials,
  }) {
    return Account(
      id: id,
      providerId: providerId ?? this.providerId,
      displayName: displayName ?? this.displayName,
      createdAt: createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
      credentials: credentials ?? this.credentials,
    );
  }

  Map<String, dynamic> toJson() => <String, dynamic>{
    'id': id,
    'providerId': providerId,
    'displayName': displayName,
    'createdAt': createdAt.toIso8601String(),
    'updatedAt': updatedAt.toIso8601String(),
    'credentials': credentials.map((c) => c.toJson()).toList(growable: false),
  };

  factory Account.fromJson(Map<String, dynamic> json) {
    final id = json['id'] as String?;
    final providerId = json['providerId'] as String?;
    final displayName = json['displayName'] as String?;
    final createdAtStr = json['createdAt'] as String?;
    final updatedAtStr = json['updatedAt'] as String?;
    final credentialsAny = json['credentials'];

    if (id == null ||
        providerId == null ||
        displayName == null ||
        createdAtStr == null ||
        updatedAtStr == null) {
      throw const FormatException('Invalid Account JSON');
    }

    final credentials = (credentialsAny is List)
        ? credentialsAny
              .whereType<Map>()
              .map((e) => Credential.fromJson(Map<String, dynamic>.from(e)))
              .toList()
        : <Credential>[];

    return Account(
      id: id,
      providerId: providerId,
      displayName: displayName,
      createdAt: DateTime.parse(createdAtStr),
      updatedAt: DateTime.parse(updatedAtStr),
      credentials: credentials,
    );
  }

  /// All TOTP credentials in this account.
  Iterable<TotpCredential> get totpCredentials =>
      credentials.whereType<TotpCredential>();

  /// All recovery-codes credentials in this account.
  Iterable<RecoveryCodesCredential> get recoveryCodesCredentials =>
      credentials.whereType<RecoveryCodesCredential>();

  bool get hasTotp => totpCredentials.isNotEmpty;
  bool get hasRecoveryCodes => recoveryCodesCredentials.isNotEmpty;

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    if (other is! Account) return false;
    if (id != other.id ||
        providerId != other.providerId ||
        displayName != other.displayName ||
        createdAt != other.createdAt ||
        updatedAt != other.updatedAt ||
        credentials.length != other.credentials.length) {
      return false;
    }
    for (int i = 0; i < credentials.length; i++) {
      if (credentials[i] != other.credentials[i]) return false;
    }
    return true;
  }

  @override
  int get hashCode => Object.hash(
    id,
    providerId,
    displayName,
    createdAt,
    updatedAt,
    Object.hashAll(credentials),
  );
}

// ---------------------------------------------------------------------------
// VaultData v3 — providers + accounts + developer.
// ---------------------------------------------------------------------------

class VaultData {
  final int schemaVersion;
  final List<ServiceProvider> providers;
  final List<Account> accounts;
  final DeveloperSettings developerSettings;
  final List<DeveloperEntry> developerEntries;

  VaultData({
    required this.schemaVersion,
    required List<ServiceProvider> providers,
    required List<Account> accounts,
    required this.developerSettings,
    required List<DeveloperEntry> developerEntries,
  }) : providers = List<ServiceProvider>.unmodifiable(providers),
       accounts = List<Account>.unmodifiable(accounts),
       developerEntries = List<DeveloperEntry>.unmodifiable(developerEntries);

  factory VaultData.empty() => VaultData(
    schemaVersion: vaultDataSchemaVersion,
    providers: const <ServiceProvider>[],
    accounts: const <Account>[],
    developerSettings: DeveloperSettings.enabled(),
    developerEntries: const <DeveloperEntry>[],
  );

  VaultData copyWith({
    List<ServiceProvider>? providers,
    List<Account>? accounts,
    DeveloperSettings? developerSettings,
    List<DeveloperEntry>? developerEntries,
  }) {
    return VaultData(
      schemaVersion: vaultDataSchemaVersion,
      providers: providers ?? this.providers,
      accounts: accounts ?? this.accounts,
      developerSettings: developerSettings ?? this.developerSettings,
      developerEntries: developerEntries ?? this.developerEntries,
    );
  }

  Map<String, dynamic> toJson() => <String, dynamic>{
    'schemaVersion': schemaVersion,
    'providers': providers.map((p) => p.toJson()).toList(growable: false),
    'accounts': accounts.map((a) => a.toJson()).toList(growable: false),
    'developerSettings': developerSettings.toJson(),
    'developerEntries': developerEntries
        .map((e) => e.toJson())
        .toList(growable: false),
  };

  /// Strict v3 parse. Legacy v1/v2 inputs MUST go through [VaultMigrator].
  factory VaultData.fromJson(Map<String, dynamic> json) {
    final schemaVersion = json['schemaVersion'] as int? ?? 1;

    if (schemaVersion != 3) {
      throw FormatException(
        'VaultData.fromJson only accepts schemaVersion 3, '
        'got $schemaVersion. Use VaultMigrator for legacy data.',
      );
    }

    final providersAny = json['providers'];
    final accountsAny = json['accounts'];
    final developerSettingsAny = json['developerSettings'];
    final developerEntriesAny = json['developerEntries'];

    final providers = (providersAny is List)
        ? providersAny
              .whereType<Map>()
              .map(
                (e) => ServiceProvider.fromJson(Map<String, dynamic>.from(e)),
              )
              .toList()
        : <ServiceProvider>[];

    final accounts = (accountsAny is List)
        ? accountsAny
              .whereType<Map>()
              .map((e) => Account.fromJson(Map<String, dynamic>.from(e)))
              .toList()
        : <Account>[];

    final developerSettings = developerSettingsAny is Map
        ? DeveloperSettings.fromJson(
            Map<String, dynamic>.from(developerSettingsAny),
          )
        : DeveloperSettings.disabled();

    final developerEntries = (developerEntriesAny is List)
        ? developerEntriesAny
              .whereType<Map>()
              .map((e) => DeveloperEntry.fromJson(Map<String, dynamic>.from(e)))
              .toList()
        : <DeveloperEntry>[];

    return VaultData(
      schemaVersion: schemaVersion,
      providers: providers,
      accounts: accounts,
      developerSettings: developerSettings,
      developerEntries: developerEntries,
    );
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    if (other is! VaultData) return false;
    if (schemaVersion != other.schemaVersion ||
        developerSettings.enabled != other.developerSettings.enabled ||
        providers.length != other.providers.length ||
        accounts.length != other.accounts.length ||
        developerEntries.length != other.developerEntries.length) {
      return false;
    }
    for (int i = 0; i < providers.length; i++) {
      if (providers[i] != other.providers[i]) return false;
    }
    for (int i = 0; i < accounts.length; i++) {
      if (accounts[i] != other.accounts[i]) return false;
    }
    for (int i = 0; i < developerEntries.length; i++) {
      if (developerEntries[i] != other.developerEntries[i]) return false;
    }
    return true;
  }

  @override
  int get hashCode => Object.hash(
    schemaVersion,
    Object.hashAll(providers),
    Object.hashAll(accounts),
    developerSettings.enabled,
    Object.hashAll(developerEntries),
  );
}

// ---------------------------------------------------------------------------
// Error types.
// ---------------------------------------------------------------------------

class ProviderNotFoundError extends StateError {
  ProviderNotFoundError(String id) : super('Provider not found: $id');
}

class AccountNotFoundError extends StateError {
  AccountNotFoundError(String id) : super('Account not found: $id');
}

class CredentialNotFoundError extends StateError {
  CredentialNotFoundError(String accountId, String credentialId)
    : super('Credential $credentialId not found in account $accountId');
}

// ---------------------------------------------------------------------------
// Pure transformations on VaultData.
// ---------------------------------------------------------------------------

extension VaultDataOps on VaultData {
  // --- ServiceProvider lookups / mutations ---

  ServiceProvider requireProvider(String providerId) {
    final i = providers.indexWhere((p) => p.id == providerId);
    if (i == -1) throw ProviderNotFoundError(providerId);
    return providers[i];
  }

  /// Accounts belonging to [providerId], in stored order.
  Iterable<Account> accountsOf(String providerId) =>
      accounts.where((a) => a.providerId == providerId);

  VaultData withNewProvider(ServiceProvider p) {
    return copyWith(providers: [...providers, p]);
  }

  VaultData withRenamedProvider(
    String providerId, {
    required String name,
    required DateTime now,
  }) {
    final i = providers.indexWhere((p) => p.id == providerId);
    if (i == -1) throw ProviderNotFoundError(providerId);
    final updated = List<ServiceProvider>.from(providers);
    updated[i] = updated[i].copyWith(name: name, updatedAt: now);
    return copyWith(providers: updated);
  }

  /// Removes a ServiceProvider AND every account that belongs to it (cascade).
  VaultData withoutProvider(String providerId) {
    final i = providers.indexWhere((p) => p.id == providerId);
    if (i == -1) throw ProviderNotFoundError(providerId);
    return copyWith(
      providers: providers.where((p) => p.id != providerId).toList(),
      accounts: accounts.where((a) => a.providerId != providerId).toList(),
    );
  }

  // --- Account lookups / mutations ---

  Account requireAccount(String accountId) {
    final i = accounts.indexWhere((a) => a.id == accountId);
    if (i == -1) throw AccountNotFoundError(accountId);
    return accounts[i];
  }

  VaultData withNewAccount(Account a) {
    // Validate ServiceProvider exists.
    if (providers.indexWhere((p) => p.id == a.providerId) == -1) {
      throw ProviderNotFoundError(a.providerId);
    }
    return copyWith(accounts: [...accounts, a]);
  }

  VaultData withRenamedAccount(
    String accountId, {
    required String displayName,
    required DateTime now,
  }) {
    final i = accounts.indexWhere((a) => a.id == accountId);
    if (i == -1) throw AccountNotFoundError(accountId);
    final updated = List<Account>.from(accounts);
    updated[i] = updated[i].copyWith(displayName: displayName, updatedAt: now);
    return copyWith(accounts: updated);
  }

  /// Moves an account to a different ServiceProvider.
  VaultData withMovedAccount(
    String accountId, {
    required String newProviderId,
    required DateTime now,
  }) {
    final i = accounts.indexWhere((a) => a.id == accountId);
    if (i == -1) throw AccountNotFoundError(accountId);
    if (providers.indexWhere((p) => p.id == newProviderId) == -1) {
      throw ProviderNotFoundError(newProviderId);
    }
    final updated = List<Account>.from(accounts);
    updated[i] = updated[i].copyWith(providerId: newProviderId, updatedAt: now);
    return copyWith(accounts: updated);
  }

  VaultData withoutAccount(String accountId) {
    final i = accounts.indexWhere((a) => a.id == accountId);
    if (i == -1) throw AccountNotFoundError(accountId);
    return copyWith(
      accounts: accounts.where((a) => a.id != accountId).toList(),
    );
  }

  // --- Credential mutations ---

  VaultData withCredential(
    String accountId,
    Credential c, {
    required DateTime now,
  }) {
    final i = accounts.indexWhere((a) => a.id == accountId);
    if (i == -1) throw AccountNotFoundError(accountId);
    final account = accounts[i];
    final updated = List<Account>.from(accounts);
    updated[i] = account.copyWith(
      credentials: [...account.credentials, c],
      updatedAt: now,
    );
    return copyWith(accounts: updated);
  }

  /// Removes a credential. Account is kept even when its credentials list
  /// becomes empty.
  VaultData withoutCredential(
    String accountId,
    String credentialId, {
    required DateTime now,
  }) {
    final i = accounts.indexWhere((a) => a.id == accountId);
    if (i == -1) throw AccountNotFoundError(accountId);
    final account = accounts[i];
    final ci = account.credentials.indexWhere((c) => c.id == credentialId);
    if (ci == -1) {
      throw CredentialNotFoundError(accountId, credentialId);
    }
    final newCredentials = account.credentials
        .where((c) => c.id != credentialId)
        .toList();
    final updated = List<Account>.from(accounts);
    updated[i] = account.copyWith(credentials: newCredentials, updatedAt: now);
    return copyWith(accounts: updated);
  }

  /// Replaces the credential identified by [credentialId] inside [accountId]
  /// with [replacement]. The replacement MUST keep the same `id` (to preserve
  /// referential identity) — otherwise a [StateError] is thrown.
  ///
  /// Position in the credentials list is preserved.
  VaultData withReplacedCredential(
    String accountId,
    String credentialId,
    Credential replacement, {
    required DateTime now,
  }) {
    if (replacement.id != credentialId) {
      throw StateError(
        'withReplacedCredential: replacement.id (${replacement.id}) '
        'must equal credentialId ($credentialId).',
      );
    }
    final i = accounts.indexWhere((a) => a.id == accountId);
    if (i == -1) throw AccountNotFoundError(accountId);
    final account = accounts[i];
    final ci = account.credentials.indexWhere((c) => c.id == credentialId);
    if (ci == -1) {
      throw CredentialNotFoundError(accountId, credentialId);
    }
    final newCredentials = List<Credential>.from(account.credentials);
    newCredentials[ci] = replacement;
    final updated = List<Account>.from(accounts);
    updated[i] = account.copyWith(credentials: newCredentials, updatedAt: now);
    return copyWith(accounts: updated);
  }

  /// Moves a credential from [fromAccountId] to [toAccountId]. The credential
  /// keeps its id, createdAt, and contents; only the owning account changes.
  /// Both accounts get their `updatedAt` bumped.
  ///
  /// Throws [AccountNotFoundError] if either account is unknown,
  /// [CredentialNotFoundError] if the credential is not in [fromAccountId],
  /// and is a no-op when [fromAccountId] equals [toAccountId].
  VaultData withMovedCredential(
    String fromAccountId,
    String credentialId,
    String toAccountId, {
    required DateTime now,
  }) {
    if (fromAccountId == toAccountId) return this;

    final fromIdx = accounts.indexWhere((a) => a.id == fromAccountId);
    if (fromIdx == -1) throw AccountNotFoundError(fromAccountId);
    final toIdx = accounts.indexWhere((a) => a.id == toAccountId);
    if (toIdx == -1) throw AccountNotFoundError(toAccountId);

    final fromAccount = accounts[fromIdx];
    final credIdx = fromAccount.credentials.indexWhere(
      (c) => c.id == credentialId,
    );
    if (credIdx == -1) {
      throw CredentialNotFoundError(fromAccountId, credentialId);
    }
    final cred = fromAccount.credentials[credIdx];

    final updatedFrom = fromAccount.copyWith(
      credentials: fromAccount.credentials
          .where((c) => c.id != credentialId)
          .toList(),
      updatedAt: now,
    );
    final toAccount = accounts[toIdx];
    final updatedTo = toAccount.copyWith(
      credentials: [...toAccount.credentials, cred],
      updatedAt: now,
    );

    final updated = List<Account>.from(accounts);
    updated[fromIdx] = updatedFrom;
    updated[toIdx] = updatedTo;
    return copyWith(accounts: updated);
  }

  /// Merges [sourceAccountId] into [targetAccountId]: appends every credential
  /// from the source account to the target account in stored order, then
  /// removes the source account. Both source and target may live under
  /// different providers — merging does NOT change the target's provider.
  ///
  /// The target account's `updatedAt` is bumped to [now]. Source credentials
  /// keep their `id` and `createdAt`. The target account's other fields
  /// (id, providerId, displayName, createdAt) are unchanged.
  ///
  /// Throws [AccountNotFoundError] if either id is unknown, and a
  /// [StateError] when [sourceAccountId] equals [targetAccountId].
  VaultData withMergedAccount(
    String sourceAccountId,
    String targetAccountId, {
    required DateTime now,
  }) {
    if (sourceAccountId == targetAccountId) {
      throw StateError(
        'withMergedAccount: source and target must differ '
        '(both = $sourceAccountId).',
      );
    }
    final srcIdx = accounts.indexWhere((a) => a.id == sourceAccountId);
    if (srcIdx == -1) throw AccountNotFoundError(sourceAccountId);
    final dstIdx = accounts.indexWhere((a) => a.id == targetAccountId);
    if (dstIdx == -1) throw AccountNotFoundError(targetAccountId);

    final src = accounts[srcIdx];
    final dst = accounts[dstIdx];

    // Build the new accounts list: drop source, replace target with merged.
    final merged = dst.copyWith(
      credentials: [...dst.credentials, ...src.credentials],
      updatedAt: now,
    );
    final next = <Account>[];
    for (int i = 0; i < accounts.length; i++) {
      if (i == srcIdx) continue;
      next.add(i == dstIdx ? merged : accounts[i]);
    }
    return copyWith(accounts: next);
  }
}

// ---------------------------------------------------------------------------
// Sealed Credential hierarchy.
// ---------------------------------------------------------------------------

/// Base type for all credential kinds stored inside an [Account].
sealed class Credential {
  final String id;
  final DateTime createdAt;

  const Credential({required this.id, required this.createdAt});

  /// JSON discriminator value — a stable string never reused for another kind.
  String get kind;

  /// Encode this credential to a JSON-compatible map. Implementations MUST
  /// include `'kind': kind` so the resulting map round-trips through
  /// [Credential.fromJson].
  Map<String, dynamic> toJson();

  factory Credential.fromJson(Map<String, dynamic> json) {
    final kind = json['kind'];
    if (kind is! String) {
      throw const FormatException('Credential.kind missing or not a string');
    }
    return switch (kind) {
      TotpCredential.kindName => TotpCredential.fromJson(json),
      RecoveryCodesCredential.kindName => RecoveryCodesCredential.fromJson(
        json,
      ),
      _ => throw FormatException('Unknown credential kind: $kind'),
    };
  }
}

/// A TOTP credential. The owning [Account.displayName] supplies the visible
/// label, so this type does not carry one of its own.
final class TotpCredential extends Credential {
  static const String kindName = 'totp';

  final String secretBase32;
  final TotpHashAlgorithm algorithm;
  final int digits;
  final int period;

  const TotpCredential({
    required super.id,
    required super.createdAt,
    required this.secretBase32,
    required this.algorithm,
    required this.digits,
    required this.period,
  });

  @override
  String get kind => kindName;

  @override
  Map<String, dynamic> toJson() => <String, dynamic>{
    'kind': kindName,
    'id': id,
    'createdAt': createdAt.toIso8601String(),
    'secretBase32': secretBase32,
    'algorithm': algorithm.otpauthName,
    'digits': digits,
    'period': period,
  };

  factory TotpCredential.fromJson(Map<String, dynamic> json) {
    final id = json['id'] as String?;
    final createdAtStr = json['createdAt'] as String?;
    final secretBase32 = json['secretBase32'] as String?;
    final algorithmStr = json['algorithm'] as String?;
    final digits = json['digits'] as int?;
    final period = json['period'] as int?;

    if (id == null ||
        createdAtStr == null ||
        secretBase32 == null ||
        algorithmStr == null ||
        digits == null ||
        period == null) {
      throw const FormatException('Invalid TotpCredential JSON');
    }

    return TotpCredential(
      id: id,
      createdAt: DateTime.parse(createdAtStr),
      secretBase32: secretBase32,
      algorithm: TotpHashAlgorithmX.fromOtpauthName(algorithmStr),
      digits: digits,
      period: period,
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is TotpCredential &&
          id == other.id &&
          createdAt == other.createdAt &&
          secretBase32 == other.secretBase32 &&
          algorithm == other.algorithm &&
          digits == other.digits &&
          period == other.period;

  @override
  int get hashCode =>
      Object.hash(id, createdAt, secretBase32, algorithm, digits, period);
}

/// A credential carrying recovery codes for account recovery purposes.
///
/// The owning [Account.displayName] (with [ServiceProvider.name] as context)
/// supplies the visible label, so this type does not carry its own title.
final class RecoveryCodesCredential extends Credential {
  static const String kindName = 'recoveryCodes';

  /// Ordered list of one-time recovery codes. Unmodifiable.
  final List<String> codes;

  RecoveryCodesCredential({
    required super.id,
    required super.createdAt,
    required List<String> codes,
  }) : codes = List<String>.unmodifiable(codes);

  @override
  String get kind => kindName;

  RecoveryCodesCredential copyWith({List<String>? codes}) {
    return RecoveryCodesCredential(
      id: id,
      createdAt: createdAt,
      codes: codes ?? this.codes,
    );
  }

  @override
  Map<String, dynamic> toJson() => <String, dynamic>{
    'kind': kindName,
    'id': id,
    'createdAt': createdAt.toIso8601String(),
    'codes': List<String>.from(codes),
  };

  factory RecoveryCodesCredential.fromJson(Map<String, dynamic> json) {
    final id = json['id'] as String?;
    final createdAtStr = json['createdAt'] as String?;
    final codesAny = json['codes'];

    if (id == null || createdAtStr == null) {
      throw const FormatException('Invalid RecoveryCodesCredential JSON');
    }

    final codes = (codesAny is List)
        ? codesAny.map((e) => e.toString()).toList()
        : <String>[];

    return RecoveryCodesCredential(
      id: id,
      createdAt: DateTime.parse(createdAtStr),
      codes: codes,
    );
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    if (other is! RecoveryCodesCredential) return false;
    if (id != other.id ||
        createdAt != other.createdAt ||
        codes.length != other.codes.length) {
      return false;
    }
    for (int i = 0; i < codes.length; i++) {
      if (codes[i] != other.codes[i]) return false;
    }
    return true;
  }

  @override
  int get hashCode => Object.hash(id, createdAt, Object.hashAll(codes));
}
