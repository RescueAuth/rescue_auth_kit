// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appTitle => 'RescueAuthKit';

  @override
  String get vaultLockedTitle => 'Vault Locked';

  @override
  String get unlockVaultTitle => 'Unlock Vault';

  @override
  String get createVaultTitle => 'Create Vault';

  @override
  String get createWarning =>
      'Master password cannot be recovered. Save it in a password manager.';

  @override
  String get masterPasswordLabel => 'Master Password';

  @override
  String get confirmMasterPasswordLabel => 'Confirm Master Password';

  @override
  String get unlockButton => 'Unlock';

  @override
  String get createAndUnlockButton => 'Create and Unlock';

  @override
  String get passwordMinLengthError =>
      'Master password must be at least 10 characters.';

  @override
  String get passwordMismatchError => 'Passwords do not match.';

  @override
  String unlockFailed(Object error) {
    return 'Failed to unlock vault: $error';
  }

  @override
  String createFailed(Object error) {
    return 'Failed to create vault: $error';
  }

  @override
  String get incorrectPassword => 'Incorrect password (or vault is corrupted).';

  @override
  String get lockTooltip => 'Lock Vault';

  @override
  String get tabBackup => 'Backup';

  @override
  String get tabDeveloper => 'Developer';

  @override
  String get tabSettings => 'Settings';

  @override
  String get settingsVaultSection => 'Vault Backup';

  @override
  String get settingsVaultSubtitle =>
      'Manage encrypted exports and automatic local backup copies.';

  @override
  String get settingsVersionSection => 'Version';

  @override
  String get settingsVersionTitle => 'Version information';

  @override
  String get settingsVersionSubtitle =>
      'Checks GitHub Releases for newer RescueAuthKit builds.';

  @override
  String get aboutTitle => 'About RescueAuthKit';

  @override
  String get aboutDescription =>
      'RescueAuthKit is a local-first encrypted vault for TOTP secrets, recovery codes, Android signing keys, API credentials, SSH keys, environment variables, and other recovery material.';

  @override
  String get aboutCurrentReleaseNotesTitle => 'What\'s new in 1.2.0';

  @override
  String get aboutCurrentReleaseNotes =>
      'Automatic encrypted backups now write to Downloads/RescueAuthKit/Backups on Android.\n\nCheckpoint backups are created before risky operations such as import, delete, and merge.\n\nSettings has been simplified into focused detail pages, and developer credentials are available by default.\n\nQR import flow has additional automated coverage so it can be checked without testing on a personal phone.';

  @override
  String get aboutLatestReleaseNotesTitle => 'Latest release notes';

  @override
  String get aboutNoReleaseNotes =>
      'No release notes were provided for this GitHub release.';

  @override
  String get settingsLoadingVersion => 'Loading version...';

  @override
  String get settingsUnknownVersion => 'Unknown version';

  @override
  String settingsAppVersion(Object version) {
    return 'Current version: $version';
  }

  @override
  String get settingsCheckUpdates => 'Check for updates';

  @override
  String get settingsCheckingUpdates => 'Checking...';

  @override
  String get settingsOpenRelease => 'Open release';

  @override
  String settingsUpdateAvailable(Object version) {
    return 'New version available: $version';
  }

  @override
  String settingsNoUpdate(Object version) {
    return 'Already on latest version: $version';
  }

  @override
  String get settingsNoReleaseFound => 'No GitHub release was found yet.';

  @override
  String settingsUpdateCompareFailed(Object version) {
    return 'Found GitHub release $version, but its tag cannot be compared.';
  }

  @override
  String settingsUpdateCheckFailed(Object error) {
    return 'Update check failed: $error';
  }

  @override
  String get totpCopied => 'TOTP code copied';

  @override
  String totpExpiresIn(Object seconds) {
    return 'Expires in $seconds seconds';
  }

  @override
  String get totpInvalidSecret => 'Invalid TOTP secret';

  @override
  String get addTotpSheetScan => 'Scan QR';

  @override
  String get pasteDialogTitle => 'Paste otpauth URI';

  @override
  String get pasteDialogHint => 'otpauth://totp/Issuer:Account?secret=...';

  @override
  String get dialogCancel => 'Cancel';

  @override
  String get dialogContinue => 'Continue';

  @override
  String get confirmImportTitle => 'Confirm Import';

  @override
  String confirmImportParseError(Object error) {
    return 'Parsing error: $error';
  }

  @override
  String get confirmImportHint =>
      'Hint: only otpauth://totp/... URIs are supported.';

  @override
  String get issuerLabel => 'Issuer';

  @override
  String get accountLabel => 'Account';

  @override
  String get algoDigitsPeriod => 'Algorithm / Digits / Period';

  @override
  String get saveToVault => 'Save to Vault';

  @override
  String get importSaved => 'TOTP entry saved';

  @override
  String importSaveFailed(Object error) {
    return 'Failed to save TOTP entry: $error';
  }

  @override
  String recoveryCodesCount(Object count) {
    return '$count codes';
  }

  @override
  String get deleteButton => 'Delete';

  @override
  String get copyAllTooltip => 'Copy all';

  @override
  String get copied => 'Copied';

  @override
  String get addRecoveryTitle => 'Add Recovery Codes';

  @override
  String get recoveryCodesLabel => 'Recovery codes (one per line)';

  @override
  String get recoveryNeedOne => 'Enter at least one code.';

  @override
  String get recoverySaved => 'Recovery codes saved';

  @override
  String recoverySaveFailed(Object error) {
    return 'Save failed: $error';
  }

  @override
  String get backupCurrentPath => 'Current vault path:';

  @override
  String get backupManualTitle => 'Manual backup';

  @override
  String get backupManualEntrySubtitle =>
      'Export or import an encrypted vault file.';

  @override
  String get backupManualSubtitle =>
      'Export or import an encrypted .rakvault file when you need to move data yourself.';

  @override
  String get backupExport => 'Export Vault';

  @override
  String get backupImport => 'Import Vault';

  @override
  String get backupExportShared => 'Backup file generated and shared';

  @override
  String backupExportedTo(Object path) {
    return 'Vault exported to $path';
  }

  @override
  String backupExportFailed(Object error) {
    return 'Error exporting vault: $error';
  }

  @override
  String get backupImportReplaceTitle =>
      'Import will replace the current vault';

  @override
  String get backupImportReplaceBody =>
      'Are you sure? It is recommended to export the current vault first.';

  @override
  String get backupPasswordTitle => 'Enter the backup\'s master password';

  @override
  String backupImportedFrom(Object name) {
    return 'Vault imported from $name';
  }

  @override
  String get backupWrongPassword =>
      'Incorrect password (or backup file is corrupted)';

  @override
  String backupImportFailed(Object error) {
    return 'Error importing vault: $error';
  }

  @override
  String get backupAutoTitle => 'Automatic backups';

  @override
  String get backupAutoSubtitle =>
      'Keep recent encrypted copies outside the app data directory.';

  @override
  String backupAutoEntrySubtitle(Object frequency, Object status) {
    return '$frequency · $status';
  }

  @override
  String get backupAutoDisabledSubtitle => 'Automatic backups are turned off.';

  @override
  String backupAutoLocation(Object location) {
    return 'Location: $location';
  }

  @override
  String get backupAutoLocationLabel => 'Backup location';

  @override
  String get backupAutoFrequency => 'Automatic backup frequency';

  @override
  String get backupAutoFrequencyEveryChange => 'Every change';

  @override
  String get backupAutoFrequencyDaily => 'Daily';

  @override
  String get backupAutoFrequencyWeekly => 'Weekly';

  @override
  String get backupAutoFrequencyMonthly => 'Monthly';

  @override
  String get backupAutoRetentionDescription =>
      'Auto backups keep the latest 5 files. Checkpoint backups before risky operations keep another latest 5 files.';

  @override
  String get backupAutoStatusNone => 'No backup has run yet.';

  @override
  String get backupAutoLastResult => 'Last result';

  @override
  String backupAutoStatusSuccess(Object message) {
    return 'Last backup: $message';
  }

  @override
  String backupAutoStatusIssue(Object message) {
    return 'Last backup issue: $message';
  }

  @override
  String get backupAutoCreated => 'Automatic backup created.';

  @override
  String get backupCheckpointCreated => 'Checkpoint backup created.';

  @override
  String backupCleanupFailed(Object count) {
    return 'Failed to clean up $count old backup file(s).';
  }

  @override
  String backupAutoFailed(Object error) {
    return 'Automatic backup failed: $error';
  }

  @override
  String backupCheckpointFailed(Object error) {
    return 'Checkpoint backup failed: $error';
  }

  @override
  String get developerAddTitle => 'Add developer backup';

  @override
  String get developerEmpty =>
      'No developer backups yet. Tap + to add Android signing keys, API keys, SSH keys, environment variables, or generic secrets.';

  @override
  String get developerAndroidSigningKey => 'Android Signing Key';

  @override
  String get developerApiCredential => 'API Credential';

  @override
  String get developerSshKey => 'SSH Key';

  @override
  String get developerEnvVarSet => 'Environment Variables';

  @override
  String get developerGenericSecret => 'Generic Secret';

  @override
  String get developerSave => 'Save';

  @override
  String get developerTitleLabel => 'Title';

  @override
  String get developerNotesLabel => 'Notes';

  @override
  String get developerProjectNameLabel => 'Project name';

  @override
  String get developerPackageNameLabel => 'Package name';

  @override
  String get developerKeystoreFileLabel => 'Keystore file';

  @override
  String get developerChooseFile => 'Choose .jks / .keystore file';

  @override
  String get developerServiceNameLabel => 'Service name';

  @override
  String get developerApiKeyLabel => 'API key';

  @override
  String get developerApiSecretLabel => 'API secret';

  @override
  String get developerKeyNameLabel => 'Key name';

  @override
  String get developerPublicKeyLabel => 'Public key';

  @override
  String get developerPrivateKeyLabel => 'Private key';

  @override
  String get developerPassphraseLabel => 'Passphrase';

  @override
  String get developerEnvVariablesLabel =>
      'Environment variables, one NAME=value per line';

  @override
  String get developerGenericFieldsLabel => 'Fields, one label=value per line';

  @override
  String get developerFileRequired => 'Choose a keystore file first.';

  @override
  String get developerSaved => 'Developer backup saved';

  @override
  String developerSaveFailed(Object error) {
    return 'Save failed: $error';
  }

  @override
  String get developerEntryMissing => 'This developer backup no longer exists.';

  @override
  String get developerEdit => 'Edit';

  @override
  String get developerDeleteTitle => 'Delete this developer backup?';

  @override
  String get developerExportKeystore => 'Export keystore file';

  @override
  String get developerCopyKeyProperties => 'Copy key.properties';

  @override
  String get developerFileShared => 'Keystore file generated and shared';

  @override
  String developerFileExported(Object path) {
    return 'Keystore exported to $path';
  }

  @override
  String get developerShowSecret => 'Show secret';

  @override
  String get developerHideSecret => 'Hide secret';

  @override
  String get tabAccounts => 'Accounts';

  @override
  String get tabProviders => 'Providers';

  @override
  String get accountsListEmpty => 'No accounts yet. Tap + to add a credential.';

  @override
  String get providersListEmpty =>
      'No providers yet. Tap + to add a credential.';

  @override
  String providersListAccountCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count accounts',
      one: '1 account',
      zero: 'No accounts',
    );
    return '$_temp0';
  }

  @override
  String get providerNameLabel => 'Provider name';

  @override
  String get providerActions => 'Provider actions';

  @override
  String get providerRename => 'Rename provider';

  @override
  String get providerDelete => 'Delete provider';

  @override
  String providerDeleteConfirm(String name) {
    return 'Delete provider \"$name\" and all its accounts?';
  }

  @override
  String get pickerModeNewProvider => 'New provider';

  @override
  String get pickerModeExistingProvider => 'Existing provider';

  @override
  String get pickerModeExistingAccount => 'Existing account';

  @override
  String get destinationSelectorTitle => 'Save to';

  @override
  String get destinationLockedToProvider => 'Save under provider';

  @override
  String get destinationLockedToAccount => 'Save to account';

  @override
  String get accountMoveTitle => 'Move to provider';

  @override
  String get accountMoveAction => 'Move to provider...';

  @override
  String get accountMoveConfirm => 'Move';

  @override
  String get accountMergeTitle => 'Merge into another account';

  @override
  String get accountMergeAction => 'Merge into...';

  @override
  String get accountMergeConfirm => 'Merge';

  @override
  String get accountMergeTargetLabel => 'Target account';

  @override
  String get accountMergeNoOtherAccounts =>
      'There are no other accounts to merge into.';

  @override
  String accountMergePrompt(String name) {
    return 'Append all credentials of \"$name\" to the target account, then delete this account.';
  }

  @override
  String get credentialMoveTitle => 'Move to another account';

  @override
  String get credentialMoveAction => 'Move to account...';

  @override
  String get credentialMoveNoOtherAccounts =>
      'There are no other accounts to move this credential to.';

  @override
  String get credentialEditAction => 'Edit codes...';

  @override
  String get editRecoveryTitle => 'Edit Recovery Codes';

  @override
  String get accountsBadgeTotp => 'TOTP';

  @override
  String get accountsBadgeRecovery => 'Recovery';

  @override
  String get accountDetailRename => 'Rename';

  @override
  String get accountDetailDeleteAccount => 'Delete account';

  @override
  String get accountDetailDeleteAccountConfirm =>
      'Delete this account and all its credentials?';

  @override
  String get accountDetailAddCredential => 'Add credential to this account';

  @override
  String get accountDetailDeleteCredential => 'Delete credential';

  @override
  String get accountDetailDeleteCredentialConfirm => 'Delete this credential?';

  @override
  String get accountDetailEmptyState =>
      'No credentials yet. Add one to get started.';

  @override
  String get accountPickerTitle => 'Choose destination';

  @override
  String get accountPickerCreateNew => 'Create new account';

  @override
  String get accountPickerAttachExisting => 'Attach to existing account';

  @override
  String get accountPickerSearchPlaceholder => 'Search accounts';

  @override
  String get addCredentialSheetTitle => 'Add credential';

  @override
  String get addCredentialScan => 'Scan QR';

  @override
  String get addCredentialPaste => 'Paste otpauth URI';

  @override
  String get addCredentialRecoveryCodes => 'Add recovery codes';

  @override
  String vaultFormatErrorFutureVersion(int n) {
    return 'Vault was created by a newer app version (schemaVersion = $n).';
  }

  @override
  String get vaultFormatErrorGeneric =>
      'Vault format is invalid and cannot be opened.';
}
