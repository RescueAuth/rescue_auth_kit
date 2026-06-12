// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Chinese (`zh`).
class AppLocalizationsZh extends AppLocalizations {
  AppLocalizationsZh([String locale = 'zh']) : super(locale);

  @override
  String get appTitle => '拾遗安全屋';

  @override
  String get vaultLockedTitle => 'Vault 已锁定';

  @override
  String get unlockVaultTitle => '解锁 Vault';

  @override
  String get createVaultTitle => '创建 Vault';

  @override
  String get createWarning => '主密码无法找回，请用密码管理器保存。';

  @override
  String get masterPasswordLabel => '主密码';

  @override
  String get confirmMasterPasswordLabel => '确认主密码';

  @override
  String get unlockButton => '解锁';

  @override
  String get createAndUnlockButton => '创建并解锁';

  @override
  String get passwordMinLengthError => '主密码至少需要 10 个字符。';

  @override
  String get passwordMismatchError => '两次输入不一致。';

  @override
  String unlockFailed(Object error) {
    return '解锁失败：$error';
  }

  @override
  String createFailed(Object error) {
    return '创建失败：$error';
  }

  @override
  String get incorrectPassword => '密码错误（或 Vault 已损坏）。';

  @override
  String get lockTooltip => '锁定 Vault';

  @override
  String get tabBackup => '备份';

  @override
  String get tabDeveloper => '开发者';

  @override
  String get tabSettings => '设置';

  @override
  String get settingsVaultSection => 'Vault 备份';

  @override
  String get settingsVaultSubtitle => '管理加密导出文件，以及自动保留在本地的备份副本。';

  @override
  String get settingsVersionSection => '版本';

  @override
  String get settingsVersionTitle => '版本说明';

  @override
  String get settingsVersionSubtitle =>
      '从 GitHub Releases 检查 RescueAuthKit 是否有新版本。';

  @override
  String get aboutTitle => '关于 RescueAuthKit';

  @override
  String get aboutDescription =>
      'RescueAuthKit 是一个本地优先的加密 Vault，用来保存 TOTP 密钥、恢复码、Android 签名密钥、API 凭据、SSH Key、环境变量和其他恢复材料。';

  @override
  String get aboutCurrentReleaseNotesTitle => '1.2.0 更新内容';

  @override
  String get aboutCurrentReleaseNotes =>
      '自动加密备份现在会写入 Android 的 Downloads/RescueAuthKit/Backups。\n\n导入、删除、合并等高风险操作前会创建保护点备份。\n\n设置页已简化为更聚焦的详情页，开发者凭据功能默认可用。\n\nQR 导入链路增加了自动化覆盖，可以在不使用个人手机扫码的情况下检查。';

  @override
  String get aboutLatestReleaseNotesTitle => '最新发布说明';

  @override
  String get aboutNoReleaseNotes => '这个 GitHub Release 没有提供发布说明。';

  @override
  String get settingsLoadingVersion => '正在读取版本...';

  @override
  String get settingsUnknownVersion => '未知版本';

  @override
  String settingsAppVersion(Object version) {
    return '当前版本：$version';
  }

  @override
  String get settingsCheckUpdates => '检查更新';

  @override
  String get settingsCheckingUpdates => '正在检查...';

  @override
  String get settingsOpenRelease => '打开发布页';

  @override
  String settingsUpdateAvailable(Object version) {
    return '发现新版本：$version';
  }

  @override
  String settingsNoUpdate(Object version) {
    return '已是最新版本：$version';
  }

  @override
  String get settingsNoReleaseFound => 'GitHub 上还没有发布版本。';

  @override
  String settingsUpdateCompareFailed(Object version) {
    return '找到了 GitHub 发布版本 $version，但无法比较这个标签。';
  }

  @override
  String settingsUpdateCheckFailed(Object error) {
    return '检查更新失败：$error';
  }

  @override
  String get totpCopied => '已复制验证码';

  @override
  String totpExpiresIn(Object seconds) {
    return '剩余 $seconds 秒';
  }

  @override
  String get totpInvalidSecret => 'TOTP 密钥无效';

  @override
  String get addTotpSheetScan => '扫码导入';

  @override
  String get pasteDialogTitle => '粘贴 otpauth URI';

  @override
  String get pasteDialogHint => 'otpauth://totp/Issuer:Account?secret=...';

  @override
  String get dialogCancel => '取消';

  @override
  String get dialogContinue => '继续';

  @override
  String get confirmImportTitle => '确认导入';

  @override
  String confirmImportParseError(Object error) {
    return '解析失败：$error';
  }

  @override
  String get confirmImportHint => '提示：目前仅支持 otpauth://totp/... URI。';

  @override
  String get issuerLabel => 'Issuer';

  @override
  String get accountLabel => 'Account';

  @override
  String get algoDigitsPeriod => '算法 / 位数 / 周期';

  @override
  String get saveToVault => '保存到 Vault';

  @override
  String get importSaved => '已保存 TOTP';

  @override
  String importSaveFailed(Object error) {
    return '保存失败：$error';
  }

  @override
  String recoveryCodesCount(Object count) {
    return '$count 个恢复码';
  }

  @override
  String get deleteButton => '删除';

  @override
  String get copyAllTooltip => '复制全部';

  @override
  String get copied => '已复制';

  @override
  String get addRecoveryTitle => '新增恢复码';

  @override
  String get recoveryCodesLabel => '恢复码（每行一个）';

  @override
  String get recoveryNeedOne => '至少输入一个恢复码。';

  @override
  String get recoverySaved => '恢复码已保存';

  @override
  String recoverySaveFailed(Object error) {
    return '保存失败：$error';
  }

  @override
  String get backupCurrentPath => '当前 Vault 路径：';

  @override
  String get backupManualTitle => '手动备份';

  @override
  String get backupManualEntrySubtitle => '导出或导入加密的 Vault 文件。';

  @override
  String get backupManualSubtitle => '需要自己迁移数据时，导出或导入加密的 .rakvault 文件。';

  @override
  String get backupExport => '导出备份';

  @override
  String get backupImport => '导入备份';

  @override
  String get backupExportShared => '备份文件已生成并分享';

  @override
  String backupExportedTo(Object path) {
    return '已导出到：$path';
  }

  @override
  String backupExportFailed(Object error) {
    return '导出失败：$error';
  }

  @override
  String get backupImportReplaceTitle => '导入会覆盖当前 Vault';

  @override
  String get backupImportReplaceBody => '确认继续？建议先导出当前备份。';

  @override
  String get backupPasswordTitle => '请输入备份文件的主密码';

  @override
  String backupImportedFrom(Object name) {
    return '已从 $name 导入';
  }

  @override
  String get backupWrongPassword => '密码错误（或备份文件损坏）';

  @override
  String backupImportFailed(Object error) {
    return '导入失败：$error';
  }

  @override
  String get backupAutoTitle => '自动备份';

  @override
  String get backupAutoSubtitle => '在应用数据目录之外保留最近的加密副本。';

  @override
  String backupAutoEntrySubtitle(Object frequency, Object status) {
    return '$frequency · $status';
  }

  @override
  String get backupAutoDisabledSubtitle => '自动备份已关闭。';

  @override
  String backupAutoLocation(Object location) {
    return '位置：$location';
  }

  @override
  String get backupAutoLocationLabel => '备份位置';

  @override
  String get backupAutoFrequency => '自动备份频率';

  @override
  String get backupAutoFrequencyEveryChange => '每次更改';

  @override
  String get backupAutoFrequencyDaily => '每天';

  @override
  String get backupAutoFrequencyWeekly => '每周';

  @override
  String get backupAutoFrequencyMonthly => '每月';

  @override
  String get backupAutoRetentionDescription =>
      '自动备份保留最近 5 个文件。高风险操作前的保护点备份会另外保留最近 5 个文件。';

  @override
  String get backupAutoStatusNone => '还没有运行过备份。';

  @override
  String get backupAutoLastResult => '最近结果';

  @override
  String backupAutoStatusSuccess(Object message) {
    return '最近一次备份：$message';
  }

  @override
  String backupAutoStatusIssue(Object message) {
    return '最近一次备份异常：$message';
  }

  @override
  String get backupAutoCreated => '已创建自动备份。';

  @override
  String get backupCheckpointCreated => '已创建保护点备份。';

  @override
  String backupCleanupFailed(Object count) {
    return '有 $count 个旧备份文件清理失败。';
  }

  @override
  String backupAutoFailed(Object error) {
    return '自动备份失败：$error';
  }

  @override
  String backupCheckpointFailed(Object error) {
    return '保护点备份失败：$error';
  }

  @override
  String get developerAddTitle => '新增开发者备份';

  @override
  String get developerEmpty =>
      '还没有开发者备份，点击 + 添加 Android 签名密钥、API Key、SSH Key、环境变量或通用密钥。';

  @override
  String get developerAndroidSigningKey => 'Android 签名密钥';

  @override
  String get developerApiCredential => 'API 凭据';

  @override
  String get developerSshKey => 'SSH Key';

  @override
  String get developerEnvVarSet => '环境变量';

  @override
  String get developerGenericSecret => '通用密钥';

  @override
  String get developerSave => '保存';

  @override
  String get developerTitleLabel => '标题';

  @override
  String get developerNotesLabel => '备注';

  @override
  String get developerProjectNameLabel => '项目名';

  @override
  String get developerPackageNameLabel => '包名';

  @override
  String get developerKeystoreFileLabel => 'Keystore 文件';

  @override
  String get developerChooseFile => '选择 .jks / .keystore 文件';

  @override
  String get developerServiceNameLabel => '服务名';

  @override
  String get developerApiKeyLabel => 'API Key';

  @override
  String get developerApiSecretLabel => 'API Secret';

  @override
  String get developerKeyNameLabel => '密钥名';

  @override
  String get developerPublicKeyLabel => '公钥';

  @override
  String get developerPrivateKeyLabel => '私钥';

  @override
  String get developerPassphraseLabel => 'Passphrase';

  @override
  String get developerEnvVariablesLabel => '环境变量，每行一个 NAME=value';

  @override
  String get developerGenericFieldsLabel => '字段，每行一个 label=value';

  @override
  String get developerFileRequired => '请先选择 keystore 文件。';

  @override
  String get developerSaved => '开发者备份已保存';

  @override
  String developerSaveFailed(Object error) {
    return '保存失败：$error';
  }

  @override
  String get developerEntryMissing => '这条开发者备份已不存在。';

  @override
  String get developerEdit => '编辑';

  @override
  String get developerDeleteTitle => '删除这条开发者备份？';

  @override
  String get developerExportKeystore => '导出 keystore 文件';

  @override
  String get developerCopyKeyProperties => '复制 key.properties';

  @override
  String get developerFileShared => 'Keystore 文件已生成并分享';

  @override
  String developerFileExported(Object path) {
    return 'Keystore 已导出到：$path';
  }

  @override
  String get developerShowSecret => '显示密钥';

  @override
  String get developerHideSecret => '隐藏密钥';

  @override
  String get tabAccounts => '账户';

  @override
  String get tabProviders => '提供商';

  @override
  String get accountsListEmpty => '还没有账户，点击 + 添加凭据。';

  @override
  String get providersListEmpty => '还没有提供商，点击 + 添加凭据。';

  @override
  String providersListAccountCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count 个账户',
      one: '1 个账户',
      zero: '无账户',
    );
    return '$_temp0';
  }

  @override
  String get providerNameLabel => '提供商名称';

  @override
  String get providerActions => '提供商操作';

  @override
  String get providerRename => '重命名提供商';

  @override
  String get providerDelete => '删除提供商';

  @override
  String providerDeleteConfirm(String name) {
    return '确定删除提供商 \"$name\" 及其下的所有账户？';
  }

  @override
  String get pickerModeNewProvider => '新建提供商';

  @override
  String get pickerModeExistingProvider => '已有提供商';

  @override
  String get pickerModeExistingAccount => '已有账户';

  @override
  String get destinationSelectorTitle => '保存到';

  @override
  String get destinationLockedToProvider => '保存到此提供商下';

  @override
  String get destinationLockedToAccount => '保存到此账户';

  @override
  String get accountMoveTitle => '移动到提供商';

  @override
  String get accountMoveAction => '移动到提供商…';

  @override
  String get accountMoveConfirm => '移动';

  @override
  String get accountMergeTitle => '合并到其他账户';

  @override
  String get accountMergeAction => '合并到…';

  @override
  String get accountMergeConfirm => '合并';

  @override
  String get accountMergeTargetLabel => '目标账户';

  @override
  String get accountMergeNoOtherAccounts => '没有可合并到的其他账户。';

  @override
  String accountMergePrompt(String name) {
    return '将“$name”下的所有凭据追加到目标账户，然后删除此账户。';
  }

  @override
  String get credentialMoveTitle => '移动到其他账户';

  @override
  String get credentialMoveAction => '移动到其他账户…';

  @override
  String get credentialMoveNoOtherAccounts => '没有可移动到的其他账户。';

  @override
  String get credentialEditAction => '编辑恢复码…';

  @override
  String get editRecoveryTitle => '编辑恢复码';

  @override
  String get accountsBadgeTotp => 'TOTP';

  @override
  String get accountsBadgeRecovery => '恢复码';

  @override
  String get accountDetailRename => '重命名';

  @override
  String get accountDetailDeleteAccount => '删除账户';

  @override
  String get accountDetailDeleteAccountConfirm => '删除此账户及其所有凭据？';

  @override
  String get accountDetailAddCredential => '为此账户添加凭据';

  @override
  String get accountDetailDeleteCredential => '删除凭据';

  @override
  String get accountDetailDeleteCredentialConfirm => '删除此凭据？';

  @override
  String get accountDetailEmptyState => '还没有凭据，添加一个开始使用。';

  @override
  String get accountPickerTitle => '选择目标';

  @override
  String get accountPickerCreateNew => '创建新账户';

  @override
  String get accountPickerAttachExisting => '附加到已有账户';

  @override
  String get accountPickerSearchPlaceholder => '搜索账户';

  @override
  String get addCredentialSheetTitle => '添加凭据';

  @override
  String get addCredentialScan => '扫码导入';

  @override
  String get addCredentialPaste => '粘贴 otpauth URI';

  @override
  String get addCredentialRecoveryCodes => '添加恢复码';

  @override
  String vaultFormatErrorFutureVersion(int n) {
    return 'Vault 由更新版本的应用创建（schemaVersion = $n）。';
  }

  @override
  String get vaultFormatErrorGeneric => 'Vault 格式无效，无法打开。';
}
