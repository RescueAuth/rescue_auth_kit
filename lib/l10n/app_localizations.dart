import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';
import 'app_localizations_zh.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'l10n/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations)!;
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('en'),
    Locale('zh'),
  ];

  /// No description provided for @appTitle.
  ///
  /// In en, this message translates to:
  /// **'RescueAuthKit'**
  String get appTitle;

  /// No description provided for @vaultLockedTitle.
  ///
  /// In en, this message translates to:
  /// **'Vault Locked'**
  String get vaultLockedTitle;

  /// No description provided for @unlockVaultTitle.
  ///
  /// In en, this message translates to:
  /// **'Unlock Vault'**
  String get unlockVaultTitle;

  /// No description provided for @createVaultTitle.
  ///
  /// In en, this message translates to:
  /// **'Create Vault'**
  String get createVaultTitle;

  /// No description provided for @createWarning.
  ///
  /// In en, this message translates to:
  /// **'Master password cannot be recovered. Save it in a password manager.'**
  String get createWarning;

  /// No description provided for @masterPasswordLabel.
  ///
  /// In en, this message translates to:
  /// **'Master Password'**
  String get masterPasswordLabel;

  /// No description provided for @confirmMasterPasswordLabel.
  ///
  /// In en, this message translates to:
  /// **'Confirm Master Password'**
  String get confirmMasterPasswordLabel;

  /// No description provided for @unlockButton.
  ///
  /// In en, this message translates to:
  /// **'Unlock'**
  String get unlockButton;

  /// No description provided for @createAndUnlockButton.
  ///
  /// In en, this message translates to:
  /// **'Create and Unlock'**
  String get createAndUnlockButton;

  /// No description provided for @passwordMinLengthError.
  ///
  /// In en, this message translates to:
  /// **'Master password must be at least 10 characters.'**
  String get passwordMinLengthError;

  /// No description provided for @passwordMismatchError.
  ///
  /// In en, this message translates to:
  /// **'Passwords do not match.'**
  String get passwordMismatchError;

  /// No description provided for @unlockFailed.
  ///
  /// In en, this message translates to:
  /// **'Failed to unlock vault: {error}'**
  String unlockFailed(Object error);

  /// No description provided for @createFailed.
  ///
  /// In en, this message translates to:
  /// **'Failed to create vault: {error}'**
  String createFailed(Object error);

  /// No description provided for @incorrectPassword.
  ///
  /// In en, this message translates to:
  /// **'Incorrect password (or vault is corrupted).'**
  String get incorrectPassword;

  /// No description provided for @lockTooltip.
  ///
  /// In en, this message translates to:
  /// **'Lock Vault'**
  String get lockTooltip;

  /// No description provided for @tabBackup.
  ///
  /// In en, this message translates to:
  /// **'Backup'**
  String get tabBackup;

  /// No description provided for @tabDeveloper.
  ///
  /// In en, this message translates to:
  /// **'Developer'**
  String get tabDeveloper;

  /// No description provided for @tabSettings.
  ///
  /// In en, this message translates to:
  /// **'Settings'**
  String get tabSettings;

  /// No description provided for @settingsVaultSection.
  ///
  /// In en, this message translates to:
  /// **'Vault Backup'**
  String get settingsVaultSection;

  /// No description provided for @settingsVaultSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Manage encrypted exports and automatic local backup copies.'**
  String get settingsVaultSubtitle;

  /// No description provided for @settingsVersionSection.
  ///
  /// In en, this message translates to:
  /// **'Version'**
  String get settingsVersionSection;

  /// No description provided for @settingsVersionTitle.
  ///
  /// In en, this message translates to:
  /// **'Version information'**
  String get settingsVersionTitle;

  /// No description provided for @settingsVersionSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Checks GitHub Releases for newer RescueAuthKit builds.'**
  String get settingsVersionSubtitle;

  /// No description provided for @aboutTitle.
  ///
  /// In en, this message translates to:
  /// **'About RescueAuthKit'**
  String get aboutTitle;

  /// No description provided for @aboutDescription.
  ///
  /// In en, this message translates to:
  /// **'RescueAuthKit is a local-first encrypted vault for TOTP secrets, recovery codes, Android signing keys, API credentials, SSH keys, environment variables, and other recovery material.'**
  String get aboutDescription;

  /// No description provided for @aboutCurrentReleaseNotesTitle.
  ///
  /// In en, this message translates to:
  /// **'What\'s new in 1.2.0'**
  String get aboutCurrentReleaseNotesTitle;

  /// No description provided for @aboutCurrentReleaseNotes.
  ///
  /// In en, this message translates to:
  /// **'Automatic encrypted backups now write to Downloads/RescueAuthKit/Backups on Android.\n\nCheckpoint backups are created before risky operations such as import, delete, and merge.\n\nSettings has been simplified into focused detail pages, and developer credentials are available by default.\n\nQR import flow has additional automated coverage so it can be checked without testing on a personal phone.'**
  String get aboutCurrentReleaseNotes;

  /// No description provided for @aboutLatestReleaseNotesTitle.
  ///
  /// In en, this message translates to:
  /// **'Latest release notes'**
  String get aboutLatestReleaseNotesTitle;

  /// No description provided for @aboutNoReleaseNotes.
  ///
  /// In en, this message translates to:
  /// **'No release notes were provided for this GitHub release.'**
  String get aboutNoReleaseNotes;

  /// No description provided for @settingsLoadingVersion.
  ///
  /// In en, this message translates to:
  /// **'Loading version...'**
  String get settingsLoadingVersion;

  /// No description provided for @settingsUnknownVersion.
  ///
  /// In en, this message translates to:
  /// **'Unknown version'**
  String get settingsUnknownVersion;

  /// No description provided for @settingsAppVersion.
  ///
  /// In en, this message translates to:
  /// **'Current version: {version}'**
  String settingsAppVersion(Object version);

  /// No description provided for @settingsCheckUpdates.
  ///
  /// In en, this message translates to:
  /// **'Check for updates'**
  String get settingsCheckUpdates;

  /// No description provided for @settingsCheckingUpdates.
  ///
  /// In en, this message translates to:
  /// **'Checking...'**
  String get settingsCheckingUpdates;

  /// No description provided for @settingsOpenRelease.
  ///
  /// In en, this message translates to:
  /// **'Open release'**
  String get settingsOpenRelease;

  /// No description provided for @settingsUpdateAvailable.
  ///
  /// In en, this message translates to:
  /// **'New version available: {version}'**
  String settingsUpdateAvailable(Object version);

  /// No description provided for @settingsNoUpdate.
  ///
  /// In en, this message translates to:
  /// **'Already on latest version: {version}'**
  String settingsNoUpdate(Object version);

  /// No description provided for @settingsNoReleaseFound.
  ///
  /// In en, this message translates to:
  /// **'No GitHub release was found yet.'**
  String get settingsNoReleaseFound;

  /// No description provided for @settingsUpdateCompareFailed.
  ///
  /// In en, this message translates to:
  /// **'Found GitHub release {version}, but its tag cannot be compared.'**
  String settingsUpdateCompareFailed(Object version);

  /// No description provided for @settingsUpdateCheckFailed.
  ///
  /// In en, this message translates to:
  /// **'Update check failed: {error}'**
  String settingsUpdateCheckFailed(Object error);

  /// No description provided for @totpCopied.
  ///
  /// In en, this message translates to:
  /// **'TOTP code copied'**
  String get totpCopied;

  /// No description provided for @totpExpiresIn.
  ///
  /// In en, this message translates to:
  /// **'Expires in {seconds} seconds'**
  String totpExpiresIn(Object seconds);

  /// No description provided for @totpInvalidSecret.
  ///
  /// In en, this message translates to:
  /// **'Invalid TOTP secret'**
  String get totpInvalidSecret;

  /// No description provided for @addTotpSheetScan.
  ///
  /// In en, this message translates to:
  /// **'Scan QR'**
  String get addTotpSheetScan;

  /// No description provided for @pasteDialogTitle.
  ///
  /// In en, this message translates to:
  /// **'Paste otpauth URI'**
  String get pasteDialogTitle;

  /// No description provided for @pasteDialogHint.
  ///
  /// In en, this message translates to:
  /// **'otpauth://totp/Issuer:Account?secret=...'**
  String get pasteDialogHint;

  /// No description provided for @dialogCancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get dialogCancel;

  /// No description provided for @dialogContinue.
  ///
  /// In en, this message translates to:
  /// **'Continue'**
  String get dialogContinue;

  /// No description provided for @confirmImportTitle.
  ///
  /// In en, this message translates to:
  /// **'Confirm Import'**
  String get confirmImportTitle;

  /// No description provided for @confirmImportParseError.
  ///
  /// In en, this message translates to:
  /// **'Parsing error: {error}'**
  String confirmImportParseError(Object error);

  /// No description provided for @confirmImportHint.
  ///
  /// In en, this message translates to:
  /// **'Hint: only otpauth://totp/... URIs are supported.'**
  String get confirmImportHint;

  /// No description provided for @issuerLabel.
  ///
  /// In en, this message translates to:
  /// **'Issuer'**
  String get issuerLabel;

  /// No description provided for @accountLabel.
  ///
  /// In en, this message translates to:
  /// **'Account'**
  String get accountLabel;

  /// No description provided for @algoDigitsPeriod.
  ///
  /// In en, this message translates to:
  /// **'Algorithm / Digits / Period'**
  String get algoDigitsPeriod;

  /// No description provided for @saveToVault.
  ///
  /// In en, this message translates to:
  /// **'Save to Vault'**
  String get saveToVault;

  /// No description provided for @importSaved.
  ///
  /// In en, this message translates to:
  /// **'TOTP entry saved'**
  String get importSaved;

  /// No description provided for @importSaveFailed.
  ///
  /// In en, this message translates to:
  /// **'Failed to save TOTP entry: {error}'**
  String importSaveFailed(Object error);

  /// No description provided for @recoveryCodesCount.
  ///
  /// In en, this message translates to:
  /// **'{count} codes'**
  String recoveryCodesCount(Object count);

  /// No description provided for @deleteButton.
  ///
  /// In en, this message translates to:
  /// **'Delete'**
  String get deleteButton;

  /// No description provided for @copyAllTooltip.
  ///
  /// In en, this message translates to:
  /// **'Copy all'**
  String get copyAllTooltip;

  /// No description provided for @copied.
  ///
  /// In en, this message translates to:
  /// **'Copied'**
  String get copied;

  /// No description provided for @addRecoveryTitle.
  ///
  /// In en, this message translates to:
  /// **'Add Recovery Codes'**
  String get addRecoveryTitle;

  /// No description provided for @recoveryCodesLabel.
  ///
  /// In en, this message translates to:
  /// **'Recovery codes (one per line)'**
  String get recoveryCodesLabel;

  /// No description provided for @recoveryNeedOne.
  ///
  /// In en, this message translates to:
  /// **'Enter at least one code.'**
  String get recoveryNeedOne;

  /// No description provided for @recoverySaved.
  ///
  /// In en, this message translates to:
  /// **'Recovery codes saved'**
  String get recoverySaved;

  /// No description provided for @recoverySaveFailed.
  ///
  /// In en, this message translates to:
  /// **'Save failed: {error}'**
  String recoverySaveFailed(Object error);

  /// No description provided for @backupCurrentPath.
  ///
  /// In en, this message translates to:
  /// **'Current vault path:'**
  String get backupCurrentPath;

  /// No description provided for @backupManualTitle.
  ///
  /// In en, this message translates to:
  /// **'Manual backup'**
  String get backupManualTitle;

  /// No description provided for @backupManualEntrySubtitle.
  ///
  /// In en, this message translates to:
  /// **'Export or import an encrypted vault file.'**
  String get backupManualEntrySubtitle;

  /// No description provided for @backupManualSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Export or import an encrypted .rakvault file when you need to move data yourself.'**
  String get backupManualSubtitle;

  /// No description provided for @backupExport.
  ///
  /// In en, this message translates to:
  /// **'Export Vault'**
  String get backupExport;

  /// No description provided for @backupImport.
  ///
  /// In en, this message translates to:
  /// **'Import Vault'**
  String get backupImport;

  /// No description provided for @backupExportShared.
  ///
  /// In en, this message translates to:
  /// **'Backup file generated and shared'**
  String get backupExportShared;

  /// No description provided for @backupExportedTo.
  ///
  /// In en, this message translates to:
  /// **'Vault exported to {path}'**
  String backupExportedTo(Object path);

  /// No description provided for @backupExportFailed.
  ///
  /// In en, this message translates to:
  /// **'Error exporting vault: {error}'**
  String backupExportFailed(Object error);

  /// No description provided for @backupImportReplaceTitle.
  ///
  /// In en, this message translates to:
  /// **'Import will replace the current vault'**
  String get backupImportReplaceTitle;

  /// No description provided for @backupImportReplaceBody.
  ///
  /// In en, this message translates to:
  /// **'Are you sure? It is recommended to export the current vault first.'**
  String get backupImportReplaceBody;

  /// No description provided for @backupPasswordTitle.
  ///
  /// In en, this message translates to:
  /// **'Enter the backup\'s master password'**
  String get backupPasswordTitle;

  /// No description provided for @backupImportedFrom.
  ///
  /// In en, this message translates to:
  /// **'Vault imported from {name}'**
  String backupImportedFrom(Object name);

  /// No description provided for @backupWrongPassword.
  ///
  /// In en, this message translates to:
  /// **'Incorrect password (or backup file is corrupted)'**
  String get backupWrongPassword;

  /// No description provided for @backupImportFailed.
  ///
  /// In en, this message translates to:
  /// **'Error importing vault: {error}'**
  String backupImportFailed(Object error);

  /// No description provided for @backupAutoTitle.
  ///
  /// In en, this message translates to:
  /// **'Automatic backups'**
  String get backupAutoTitle;

  /// No description provided for @backupAutoSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Keep recent encrypted copies outside the app data directory.'**
  String get backupAutoSubtitle;

  /// No description provided for @backupAutoEntrySubtitle.
  ///
  /// In en, this message translates to:
  /// **'{frequency} · {status}'**
  String backupAutoEntrySubtitle(Object frequency, Object status);

  /// No description provided for @backupAutoDisabledSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Automatic backups are turned off.'**
  String get backupAutoDisabledSubtitle;

  /// No description provided for @backupAutoLocation.
  ///
  /// In en, this message translates to:
  /// **'Location: {location}'**
  String backupAutoLocation(Object location);

  /// No description provided for @backupAutoLocationLabel.
  ///
  /// In en, this message translates to:
  /// **'Backup location'**
  String get backupAutoLocationLabel;

  /// No description provided for @backupAutoFrequency.
  ///
  /// In en, this message translates to:
  /// **'Automatic backup frequency'**
  String get backupAutoFrequency;

  /// No description provided for @backupAutoFrequencyEveryChange.
  ///
  /// In en, this message translates to:
  /// **'Every change'**
  String get backupAutoFrequencyEveryChange;

  /// No description provided for @backupAutoFrequencyDaily.
  ///
  /// In en, this message translates to:
  /// **'Daily'**
  String get backupAutoFrequencyDaily;

  /// No description provided for @backupAutoFrequencyWeekly.
  ///
  /// In en, this message translates to:
  /// **'Weekly'**
  String get backupAutoFrequencyWeekly;

  /// No description provided for @backupAutoFrequencyMonthly.
  ///
  /// In en, this message translates to:
  /// **'Monthly'**
  String get backupAutoFrequencyMonthly;

  /// No description provided for @backupAutoRetentionDescription.
  ///
  /// In en, this message translates to:
  /// **'Auto backups keep the latest 5 files. Checkpoint backups before risky operations keep another latest 5 files.'**
  String get backupAutoRetentionDescription;

  /// No description provided for @backupAutoStatusNone.
  ///
  /// In en, this message translates to:
  /// **'No backup has run yet.'**
  String get backupAutoStatusNone;

  /// No description provided for @backupAutoLastResult.
  ///
  /// In en, this message translates to:
  /// **'Last result'**
  String get backupAutoLastResult;

  /// No description provided for @backupAutoStatusSuccess.
  ///
  /// In en, this message translates to:
  /// **'Last backup: {message}'**
  String backupAutoStatusSuccess(Object message);

  /// No description provided for @backupAutoStatusIssue.
  ///
  /// In en, this message translates to:
  /// **'Last backup issue: {message}'**
  String backupAutoStatusIssue(Object message);

  /// No description provided for @backupAutoCreated.
  ///
  /// In en, this message translates to:
  /// **'Automatic backup created.'**
  String get backupAutoCreated;

  /// No description provided for @backupCheckpointCreated.
  ///
  /// In en, this message translates to:
  /// **'Checkpoint backup created.'**
  String get backupCheckpointCreated;

  /// No description provided for @backupCleanupFailed.
  ///
  /// In en, this message translates to:
  /// **'Failed to clean up {count} old backup file(s).'**
  String backupCleanupFailed(Object count);

  /// No description provided for @backupAutoFailed.
  ///
  /// In en, this message translates to:
  /// **'Automatic backup failed: {error}'**
  String backupAutoFailed(Object error);

  /// No description provided for @backupCheckpointFailed.
  ///
  /// In en, this message translates to:
  /// **'Checkpoint backup failed: {error}'**
  String backupCheckpointFailed(Object error);

  /// No description provided for @developerAddTitle.
  ///
  /// In en, this message translates to:
  /// **'Add developer backup'**
  String get developerAddTitle;

  /// No description provided for @developerEmpty.
  ///
  /// In en, this message translates to:
  /// **'No developer backups yet. Tap + to add Android signing keys, API keys, SSH keys, environment variables, or generic secrets.'**
  String get developerEmpty;

  /// No description provided for @developerAndroidSigningKey.
  ///
  /// In en, this message translates to:
  /// **'Android Signing Key'**
  String get developerAndroidSigningKey;

  /// No description provided for @developerApiCredential.
  ///
  /// In en, this message translates to:
  /// **'API Credential'**
  String get developerApiCredential;

  /// No description provided for @developerSshKey.
  ///
  /// In en, this message translates to:
  /// **'SSH Key'**
  String get developerSshKey;

  /// No description provided for @developerEnvVarSet.
  ///
  /// In en, this message translates to:
  /// **'Environment Variables'**
  String get developerEnvVarSet;

  /// No description provided for @developerGenericSecret.
  ///
  /// In en, this message translates to:
  /// **'Generic Secret'**
  String get developerGenericSecret;

  /// No description provided for @developerSave.
  ///
  /// In en, this message translates to:
  /// **'Save'**
  String get developerSave;

  /// No description provided for @developerTitleLabel.
  ///
  /// In en, this message translates to:
  /// **'Title'**
  String get developerTitleLabel;

  /// No description provided for @developerNotesLabel.
  ///
  /// In en, this message translates to:
  /// **'Notes'**
  String get developerNotesLabel;

  /// No description provided for @developerProjectNameLabel.
  ///
  /// In en, this message translates to:
  /// **'Project name'**
  String get developerProjectNameLabel;

  /// No description provided for @developerPackageNameLabel.
  ///
  /// In en, this message translates to:
  /// **'Package name'**
  String get developerPackageNameLabel;

  /// No description provided for @developerKeystoreFileLabel.
  ///
  /// In en, this message translates to:
  /// **'Keystore file'**
  String get developerKeystoreFileLabel;

  /// No description provided for @developerChooseFile.
  ///
  /// In en, this message translates to:
  /// **'Choose .jks / .keystore file'**
  String get developerChooseFile;

  /// No description provided for @developerServiceNameLabel.
  ///
  /// In en, this message translates to:
  /// **'Service name'**
  String get developerServiceNameLabel;

  /// No description provided for @developerApiKeyLabel.
  ///
  /// In en, this message translates to:
  /// **'API key'**
  String get developerApiKeyLabel;

  /// No description provided for @developerApiSecretLabel.
  ///
  /// In en, this message translates to:
  /// **'API secret'**
  String get developerApiSecretLabel;

  /// No description provided for @developerKeyNameLabel.
  ///
  /// In en, this message translates to:
  /// **'Key name'**
  String get developerKeyNameLabel;

  /// No description provided for @developerPublicKeyLabel.
  ///
  /// In en, this message translates to:
  /// **'Public key'**
  String get developerPublicKeyLabel;

  /// No description provided for @developerPrivateKeyLabel.
  ///
  /// In en, this message translates to:
  /// **'Private key'**
  String get developerPrivateKeyLabel;

  /// No description provided for @developerPassphraseLabel.
  ///
  /// In en, this message translates to:
  /// **'Passphrase'**
  String get developerPassphraseLabel;

  /// No description provided for @developerEnvVariablesLabel.
  ///
  /// In en, this message translates to:
  /// **'Environment variables, one NAME=value per line'**
  String get developerEnvVariablesLabel;

  /// No description provided for @developerGenericFieldsLabel.
  ///
  /// In en, this message translates to:
  /// **'Fields, one label=value per line'**
  String get developerGenericFieldsLabel;

  /// No description provided for @developerFileRequired.
  ///
  /// In en, this message translates to:
  /// **'Choose a keystore file first.'**
  String get developerFileRequired;

  /// No description provided for @developerSaved.
  ///
  /// In en, this message translates to:
  /// **'Developer backup saved'**
  String get developerSaved;

  /// No description provided for @developerSaveFailed.
  ///
  /// In en, this message translates to:
  /// **'Save failed: {error}'**
  String developerSaveFailed(Object error);

  /// No description provided for @developerEntryMissing.
  ///
  /// In en, this message translates to:
  /// **'This developer backup no longer exists.'**
  String get developerEntryMissing;

  /// No description provided for @developerEdit.
  ///
  /// In en, this message translates to:
  /// **'Edit'**
  String get developerEdit;

  /// No description provided for @developerDeleteTitle.
  ///
  /// In en, this message translates to:
  /// **'Delete this developer backup?'**
  String get developerDeleteTitle;

  /// No description provided for @developerExportKeystore.
  ///
  /// In en, this message translates to:
  /// **'Export keystore file'**
  String get developerExportKeystore;

  /// No description provided for @developerCopyKeyProperties.
  ///
  /// In en, this message translates to:
  /// **'Copy key.properties'**
  String get developerCopyKeyProperties;

  /// No description provided for @developerFileShared.
  ///
  /// In en, this message translates to:
  /// **'Keystore file generated and shared'**
  String get developerFileShared;

  /// No description provided for @developerFileExported.
  ///
  /// In en, this message translates to:
  /// **'Keystore exported to {path}'**
  String developerFileExported(Object path);

  /// No description provided for @developerShowSecret.
  ///
  /// In en, this message translates to:
  /// **'Show secret'**
  String get developerShowSecret;

  /// No description provided for @developerHideSecret.
  ///
  /// In en, this message translates to:
  /// **'Hide secret'**
  String get developerHideSecret;

  /// No description provided for @tabAccounts.
  ///
  /// In en, this message translates to:
  /// **'Accounts'**
  String get tabAccounts;

  /// No description provided for @tabProviders.
  ///
  /// In en, this message translates to:
  /// **'Providers'**
  String get tabProviders;

  /// No description provided for @accountsListEmpty.
  ///
  /// In en, this message translates to:
  /// **'No accounts yet. Tap + to add a credential.'**
  String get accountsListEmpty;

  /// No description provided for @providersListEmpty.
  ///
  /// In en, this message translates to:
  /// **'No providers yet. Tap + to add a credential.'**
  String get providersListEmpty;

  /// No description provided for @providersListAccountCount.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =0{No accounts} =1{1 account} other{{count} accounts}}'**
  String providersListAccountCount(int count);

  /// No description provided for @providerNameLabel.
  ///
  /// In en, this message translates to:
  /// **'Provider name'**
  String get providerNameLabel;

  /// No description provided for @providerActions.
  ///
  /// In en, this message translates to:
  /// **'Provider actions'**
  String get providerActions;

  /// No description provided for @providerRename.
  ///
  /// In en, this message translates to:
  /// **'Rename provider'**
  String get providerRename;

  /// No description provided for @providerDelete.
  ///
  /// In en, this message translates to:
  /// **'Delete provider'**
  String get providerDelete;

  /// No description provided for @providerDeleteConfirm.
  ///
  /// In en, this message translates to:
  /// **'Delete provider \"{name}\" and all its accounts?'**
  String providerDeleteConfirm(String name);

  /// No description provided for @pickerModeNewProvider.
  ///
  /// In en, this message translates to:
  /// **'New provider'**
  String get pickerModeNewProvider;

  /// No description provided for @pickerModeExistingProvider.
  ///
  /// In en, this message translates to:
  /// **'Existing provider'**
  String get pickerModeExistingProvider;

  /// No description provided for @pickerModeExistingAccount.
  ///
  /// In en, this message translates to:
  /// **'Existing account'**
  String get pickerModeExistingAccount;

  /// No description provided for @destinationSelectorTitle.
  ///
  /// In en, this message translates to:
  /// **'Save to'**
  String get destinationSelectorTitle;

  /// No description provided for @destinationLockedToProvider.
  ///
  /// In en, this message translates to:
  /// **'Save under provider'**
  String get destinationLockedToProvider;

  /// No description provided for @destinationLockedToAccount.
  ///
  /// In en, this message translates to:
  /// **'Save to account'**
  String get destinationLockedToAccount;

  /// No description provided for @accountMoveTitle.
  ///
  /// In en, this message translates to:
  /// **'Move to provider'**
  String get accountMoveTitle;

  /// No description provided for @accountMoveAction.
  ///
  /// In en, this message translates to:
  /// **'Move to provider...'**
  String get accountMoveAction;

  /// No description provided for @accountMoveConfirm.
  ///
  /// In en, this message translates to:
  /// **'Move'**
  String get accountMoveConfirm;

  /// No description provided for @accountMergeTitle.
  ///
  /// In en, this message translates to:
  /// **'Merge into another account'**
  String get accountMergeTitle;

  /// No description provided for @accountMergeAction.
  ///
  /// In en, this message translates to:
  /// **'Merge into...'**
  String get accountMergeAction;

  /// No description provided for @accountMergeConfirm.
  ///
  /// In en, this message translates to:
  /// **'Merge'**
  String get accountMergeConfirm;

  /// No description provided for @accountMergeTargetLabel.
  ///
  /// In en, this message translates to:
  /// **'Target account'**
  String get accountMergeTargetLabel;

  /// No description provided for @accountMergeNoOtherAccounts.
  ///
  /// In en, this message translates to:
  /// **'There are no other accounts to merge into.'**
  String get accountMergeNoOtherAccounts;

  /// No description provided for @accountMergePrompt.
  ///
  /// In en, this message translates to:
  /// **'Append all credentials of \"{name}\" to the target account, then delete this account.'**
  String accountMergePrompt(String name);

  /// No description provided for @credentialMoveTitle.
  ///
  /// In en, this message translates to:
  /// **'Move to another account'**
  String get credentialMoveTitle;

  /// No description provided for @credentialMoveAction.
  ///
  /// In en, this message translates to:
  /// **'Move to account...'**
  String get credentialMoveAction;

  /// No description provided for @credentialMoveNoOtherAccounts.
  ///
  /// In en, this message translates to:
  /// **'There are no other accounts to move this credential to.'**
  String get credentialMoveNoOtherAccounts;

  /// No description provided for @credentialEditAction.
  ///
  /// In en, this message translates to:
  /// **'Edit codes...'**
  String get credentialEditAction;

  /// No description provided for @editRecoveryTitle.
  ///
  /// In en, this message translates to:
  /// **'Edit Recovery Codes'**
  String get editRecoveryTitle;

  /// No description provided for @accountsBadgeTotp.
  ///
  /// In en, this message translates to:
  /// **'TOTP'**
  String get accountsBadgeTotp;

  /// No description provided for @accountsBadgeRecovery.
  ///
  /// In en, this message translates to:
  /// **'Recovery'**
  String get accountsBadgeRecovery;

  /// No description provided for @accountDetailRename.
  ///
  /// In en, this message translates to:
  /// **'Rename'**
  String get accountDetailRename;

  /// No description provided for @accountDetailDeleteAccount.
  ///
  /// In en, this message translates to:
  /// **'Delete account'**
  String get accountDetailDeleteAccount;

  /// No description provided for @accountDetailDeleteAccountConfirm.
  ///
  /// In en, this message translates to:
  /// **'Delete this account and all its credentials?'**
  String get accountDetailDeleteAccountConfirm;

  /// No description provided for @accountDetailAddCredential.
  ///
  /// In en, this message translates to:
  /// **'Add credential to this account'**
  String get accountDetailAddCredential;

  /// No description provided for @accountDetailDeleteCredential.
  ///
  /// In en, this message translates to:
  /// **'Delete credential'**
  String get accountDetailDeleteCredential;

  /// No description provided for @accountDetailDeleteCredentialConfirm.
  ///
  /// In en, this message translates to:
  /// **'Delete this credential?'**
  String get accountDetailDeleteCredentialConfirm;

  /// No description provided for @accountDetailEmptyState.
  ///
  /// In en, this message translates to:
  /// **'No credentials yet. Add one to get started.'**
  String get accountDetailEmptyState;

  /// No description provided for @accountPickerTitle.
  ///
  /// In en, this message translates to:
  /// **'Choose destination'**
  String get accountPickerTitle;

  /// No description provided for @accountPickerCreateNew.
  ///
  /// In en, this message translates to:
  /// **'Create new account'**
  String get accountPickerCreateNew;

  /// No description provided for @accountPickerAttachExisting.
  ///
  /// In en, this message translates to:
  /// **'Attach to existing account'**
  String get accountPickerAttachExisting;

  /// No description provided for @accountPickerSearchPlaceholder.
  ///
  /// In en, this message translates to:
  /// **'Search accounts'**
  String get accountPickerSearchPlaceholder;

  /// No description provided for @addCredentialSheetTitle.
  ///
  /// In en, this message translates to:
  /// **'Add credential'**
  String get addCredentialSheetTitle;

  /// No description provided for @addCredentialScan.
  ///
  /// In en, this message translates to:
  /// **'Scan QR'**
  String get addCredentialScan;

  /// No description provided for @addCredentialPaste.
  ///
  /// In en, this message translates to:
  /// **'Paste otpauth URI'**
  String get addCredentialPaste;

  /// No description provided for @addCredentialRecoveryCodes.
  ///
  /// In en, this message translates to:
  /// **'Add recovery codes'**
  String get addCredentialRecoveryCodes;

  /// No description provided for @vaultFormatErrorFutureVersion.
  ///
  /// In en, this message translates to:
  /// **'Vault was created by a newer app version (schemaVersion = {n}).'**
  String vaultFormatErrorFutureVersion(int n);

  /// No description provided for @vaultFormatErrorGeneric.
  ///
  /// In en, this message translates to:
  /// **'Vault format is invalid and cannot be opened.'**
  String get vaultFormatErrorGeneric;
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en', 'zh'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppLocalizationsEn();
    case 'zh':
      return AppLocalizationsZh();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
