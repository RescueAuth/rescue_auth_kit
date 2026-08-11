# Changelog

All notable changes to RescueAuthKit are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **v2 重写（2026-08-06）**：仓库自 `main` 起进入 Android 原生重写阶段，
> 代码位于 `v2/`（Kotlin + Room/SQLCipher），旧 Flutter 应用冻结于 tag `v1.2.0`。
> 以下条目反映 v2 里程碑（phase 0/1/phase1-fix/phase2/phase2-blocker-hotfix/
> phase2-closure/roadmap-v2），均已合并进 `main`。

## [v2 android-production-signing-provisioning] - 2026-08-11

RescueAuth `com.rescueauth.v2` 的长期 Android production signing identity 已
provision。PKCS12/private key/password 仅保存在仓库外和 CNB Secret Repository；
主仓只固定公开证书 metadata、master-only 手动流水线与安全重建/验证脚本。

### Added

- `v2/release/android-signing-certificate.txt`：公开 production certificate
  identity assertion（RSA 4096 / SHA256withRSA / 40-year validity / SHA-256）。
- `.cnb/web_trigger.yml`：只允许 `main` + `master` 触发 production signing。
- `scripts/build-production-apk.sh`：runner temp PKCS12 重建、`0600` 权限、退出
  cleanup、Gradle signing validation、APK signer/package/version/debuggable 断言。
- `.cnb.yml`：production secrets 仅 stage-scoped import
  `android-signing.yml`；PR/fork/push/tag/comment/API/任意分支均不读取。
- 第一份 local production-signed `1.0.0` candidate 已通过 `apksigner`、manifest
  identity/version/debuggable 与 16K alignment 验证；APK SHA-256 =
  `71D32E2DF3DFAD426DA8A4873AD4058C2A4BF354A28966DC096A06366BA4D931`。

### Security

- 本仓库不含 production keystore、Base64、password 或 private key。
- Update Ed25519 identity 未生成；没有更新 `latest.json`，没有发布 release。
- Independent offline backup 与 real-device side-by-side smoke 仍待用户完成。

## [v2 release-provisioning-step-1] - 2026-08-10（Release Provisioning Step 1 — App Identity / Version + Production Signing Infrastructure，PR OPEN）

Issue #38 Release Provisioning Step 1。**冻结新 RescueAuth Android application
identity 与首个 release version，并建立 production signing 基础设施。** 不生成
production key、不发布 APK、不运行 FTL。

### Added

- **冻结 App identity / version**：新 App `applicationId = com.rescueauth.v2`
  （≠ Legacy `com.xincy.rescue_auth_kit`，可 side-by-side 安装）、
  `namespace = com.rescueauth.v2`、`versionName = "1.0.0"`、`versionCode = 10000`。
  **“v2” 是 generation/rewrite 名称，不等于 `versionName`**（V2.0 FEATURE COMPLETE
  ≠ versionName 2.0.0）。新 App 独立 release sequence 从 `1.0.0` 开始。
- **Production signing 基础设施**（`v2/app/build.gradle.kts`）：`release` 构建在
  提供完整 config（`v2/keystore.properties` 或 `RESCUEAUTH_*` 环境变量）时使用新的
  RescueAuth production key；无 config 时 release 为 **unsigned**（**绝不 debug
  fallback**）。新增 `validateReleaseSigning` 任务，config 不完整时**明确失败**。
- **`v2/keystore.properties.example`**：仅 placeholder（`CHANGE_ME`），无真实 secret。
- **`v2/.gitignore`**：忽略 `/keystore.properties`、`*.jks`、`*.keystore`、`*.p12`、
  `*.pfx`（不影响 Developer Vault 内存 synthetic fixture）。
- **`docs/RELEASE_PROVISIONING.md`**：完整 release provisioning 文档（identity /
  versionCode / signing 基础设施 / key 生成命令模板 / secret 策略 / status）。

### Changed

- 里程碑/文档状态同步：AGENTS.md、ROADMAP §10.1、CHANGELOG 标注
  **Release Provisioning Step 1 = IMPLEMENTED / PR OPEN**、**Production Android
  signing key = NOT GENERATED**、V2.0 FEATURE COMPLETE = YES / V2.0 RELEASED = NO。

### Notes

- Room schema / package format / Legacy format / Update protocol schema / crypto
  变更 = **NONE**。production Update Ed25519 key = NOT GENERATED。Real-device
  side-by-side smoke = PENDING。FTL = PENDING。

## [v2 release-readiness audit] - 2026-08-10（V2.0 Release Readiness — Final Product Audit & Minimal Fixes）

Issue #38 最终 release-readiness / product 审计。**V2.0 FEATURE COMPLETE = YES**
（全部 v2.0 正式产品能力已实现并有测试覆盖）；**V2.0 RELEASED = NO**（仍待
production Android signing / Update Ed25519 provisioning / rescueauth-updates
基础设施 / signed release smoke / FTL，见 AGENTS.md / 审计报告 M 节）。

### Fixed

- **JVM 测试 flake（app，真实可复现）**：`DeveloperScreenTest` 偶发
  `UncaughtExceptionsBeforeTest` / `SQLiteConnectionPool closed`。根因是
  `AuthenticatorViewModelTest` / `RecoveryViewModelTest` /
  `AuthenticatorScanMigrationTest` 的 `tearDown` 在 `activeScope?.cancel()`
  后立即 `db.close()`，而仍运行在 `Dispatchers.Default` 上的 Room Flow 协程在
  已关闭的连接池上查询时抛 uncaught background-thread 异常，污染下一个
  `runTest`。修复为 `cancelAndJoin()` 先完整收敛协程再关闭 DB（lifecycle 根因，
  非 suppression/retry）。全量 suite 连续多次复跑全绿。

### Docs

- 里程碑状态同步：ROADMAP §10.1 全部 V2.0 FEATURE COMPLETE 项勾选，并明确
  FEATURE COMPLETE = YES / RELEASED = NO；AGENTS.md 里程碑与 P8/L2 状态同步。

## [v2 phase4-p8 delete-undo] - 2026-08-10（Phase 4 P8 Delete Undo 完善，已 merge #37）

Issue #20 Phase 4 P8：普通删除统一 SnackBar Undo + Recovery Code Set Move
正式能力 + 空 Account 全 scope 保留。P1–P8 全部完成（Phase 4 daily-use
feature slices = feature implementation complete）。

### Added

- **Account Delete + Undo（app）**：Account 删除由 destructive confirmation
  改为立即移除 + SnackBar Undo；删除前在同一 transaction 内捕获完整 subtree
  snapshot（Account 全部字段 + TOTP secret/params + Recovery Set/Code 的
  stableId/USED/UNUSED/usedAt），Undo 以精确 stableId/states 原子恢复；
  preflight 拒绝冲突/重复（无 source-wins overwrite）；token 单次消费。
- **普通 Developer Entry Delete + Undo（app）**：API Credential / SSH Key /
  Environment Variable Set / Generic Secret 删除后 SnackBar Undo，恢复精确
  stableId/payload；共享 in-memory `DeveloperUndoStore` 覆盖 post-navigation
  生命周期；Android Signing Key 保持 confirmation-only（无 Undo）。
- **Recovery Code Set Move（app）**：跨 Provider 移动 Recovery Set（destination
  picker 排除 current owner，无其它 Account 时禁用）；Set/code 全部 identity
  + state 保留，title 非 identity 不做 dedupe；单 transaction，失败回滚。
- **空 Account 保留（core MergePlanner 最小修复）**：源中空 Account（无
  TOTP/无 Recovery Set）在 destination 缺失时现在产生 `INSERT_ACCOUNT`，而非
  `DUPLICATE_ACCOUNT`，使其在 Full / Selected / Authenticator-only 导出导入
  中完整保留（stableId/pinned/metadata）；重复导入幂等。无 Room/package 改动。
- **Undo 安全生命周期**：所有 Undo snapshot 仅 in-memory（不进入
  SavedStateHandle/Bundle/DataStore/file/cache/clipboard/log）；session lock
  清除全部 pending Undo（TOTP/Recovery/Account/Developer），unlock 后不恢复。
- **i18n / accessibility（en + zh-CN）**：Account/Developer deleted、Undo、
  Unable to restore、Recovery moved、Move to account、No other accounts 等。

### Tests

新增 P8 测试：Account Undo（12）、Developer Undo（11）、Recovery Move（10）、
空 Account package（7，app）+ MergePlanner 空 Account（3，core）。

## [v2 phase6-l2 about-update-check] - 2026-08-10（Phase 6 L2 About + Update Check，IMPLEMENTED / PR OPEN）

Issue #20 Phase 6 L2：新增正式 About 页 + manual、signature-verified 的
Update Check，接入 Settings → About。`docs/UPDATE_PROTOCOL.md` 从 Draft 收口为
可执行 contract（Client Contract Final / Release Infrastructure Pending）。

### Added

- **About 页（app）**：runtime `versionName` / `versionCode`（BuildConfig，
  不硬编码在 strings）、产品描述、manual “Check for Updates”、update
  状态机（Idle / Checking / UpToDate / UpdateAvailable / Error）、severity
  （NORMAL / SECURITY）更强提示、minSupported 更强 unsupported 警告、
  verified 后才出现的 “Open Release Page”（外部 ACTION_VIEW，非 WebView）。
- **核心 update 协议（core，纯 Kotlin）**：
  - `UpdateManifestParser` — schema v1 strict validation（schemaVersion==1、
    channel==stable、versionCode/minSupported 正数且 minSupported<=latest、
    ISO-8601 publishedAt、HTTPS apkUrl/releaseNotesUrl、64-hex apkSha256、
    有界 apkSizeBytes、未知 additive 字段忽略）。
  - `UpdateManifestVerifier` — Ed25519 验签（复用 BouncyCastle 1.85，无新
    crypto 依赖）；`latest.json.sig` = Base64 原始 64-byte Ed25519 签名，
    覆盖 `latest.json` 的 **exact raw bytes**（不 canonicalize）。
  - `UpdateVersionDecision` — 仅用 versionCode 判断新旧；`minSupported` 只
    触发更强警告，不锁 Vault。
  - `UpdateTrustConfig` — 公钥配置边界（release provisioning 时经
    `UPDATE_PUBLIC_KEY` BuildConfig 写入；未配置返回 NOT_CONFIGURED，Vault
    继续可用）。
  - `UrlPolicy` / `Iso8601` / `Sha256` — HTTPS/ISO/hex 校验。
- **网络（app）**：`HttpUpdateTransport` + `BoundedUrlFetcher`（平台
  HttpURLConnection，connect/read timeout、bounded ≤64 KiB manifest / ≤4 KiB
  signature、取消友好、无 cookie/auth/telemetry、拒绝 HTTPS→HTTP downgrade）。
- **AndroidManifest**：显式 `android.permission.INTERNET`；未新增任何
  storage / install-packages / notification / background-service 权限。
- **UI 状态机 + error taxonomy**：NETWORK / TIMEOUT / INVALID_SIGNATURE /
  INVALID_MANIFEST / UNSUPPORTED_SCHEMA / NOT_CONFIGURED；INVALID_SIGNATURE
  明确“无法验证更新信息”，不是“没有更新”。
- **安全契约**：update data fail closed / app fail open；update state 不进
  入 SecureSession state machine；无 secret persistence（result in memory
  only）；无隐私数据外发。

### Tests

- core：`UpdateManifestVerifierTest` / `UpdateManifestParserTest`（签名
  accepted/mutation rejected/wrong key/malformed/wrong-length/Base64/exact raw
  bytes/reformat invalidates + schema/URL/ISO/SHA 校验 + additive 字段）；
  `UpdateVersionDecisionTest`（versionCode 排序、versionName 不控制排序、
  minSupported、SECURITY 不 forced-update）。
- app：`UpdateCheckViewModelTest`（NETWORK/TIMEOUT/oversized manifest /
  oversized signature/invalid signature 无 URL/malformed/NOT_CONFIGURED/
  retry/scope cancel/no auth data）；`AboutScreenTest`（version 显示、
  Checking/UpToDate/UpdateAvailable/SECURITY/network/signature UI、invalid
  manifest 无 open link、无 secret 泄漏）；`RescueAuthAppNavigationTest`
  （Settings → About 导航）。

### 未完成（release infrastructure pending）

- `rescueauth-updates` 仓库尚未创建。
- 生产 update manifest Ed25519 key 尚未 provisioning（见
  `docs/UPDATE_PROTOCOL.md` §Release Infrastructure Pending）。
- 本轮未做 release publishing pipeline / production signing。

## [v2 phase4-p7 search+pin] - 2026-08-10（Phase 4 P7 Global Search + Account Pin/Unpin，IMPLEMENTED / PR OPEN）

Issue #20：Global Search（safe metadata only）+ Account Pin/Unpin（Account only）。
**Room schema / package format 零改动**（Pin 复用现有 `favorite` 兼容字段）。
不做 P8；不做 Favorites/Tags/Folder/Rating。与并行 Phase 6 L2（About/Update）互不等待。

### Added

- **Global Search（in-memory safe projection）**：
  - `SearchMatcher`（core）：Unicode-safe case-insensitive contains + multi-token
    AND；deterministic；无 fuzzy/Levenshtein/semantic/pinyin/regex。
  - `SearchDocument` / `SearchResult` / `SearchIndex`（app）：显式 safe 投影；
    Provider（serviceName）、Account（name+service）、TOTP（display context）、
    Recovery Set（title+context）、Developer（title + 各类型非敏感 metadata）。
  - `DeveloperRepository.observeSearchMetadata()` / `DeveloperMappers.toSearchMetadata()`：
    Developer safe 投影，绝不暴露 secret / notes / publicKey。
  - `SearchViewModel` / `SearchRoute` / `SearchScreen`：query in-memory only、
    会话锁定/离开/进程重建清空、不写 Room/DataStore/SavedStateHandle/Bundle/
    logs/analytics、无 search history。
  - 入口：Authenticator TopAppBar search icon → Search route；结果导航到真实
    stable/current IDs（Account/TOTP→Account detail，Recovery→Account detail，
    Developer→Developer detail，Provider→home）。
- **Account Pin/Unpin（Account only）**：
  - `VaultRepository.setPinned` / `AuthenticatorRepository.setPinned`（product alias
    → existing `setFavorite` storage field）；串行 mutation + session-locked 安全失败。
  - 每个 Provider 内 pinned accounts 排前、稳定排序保留；不复制/不改 stableId/
    不改 Provider 关系/无 shadow row。
  - UI：Account 溢出菜单 Pin/Unpin；`PinIndicator` a11y contentDescription 区分
    Pin/Unpin。
  - storage=`favorite`（内部兼容字段）/ product=`pinned`（`AccountUi.isPinned`）；
    无 schema/package 迁移。

### Tests

- `SearchMatcherTest`（core，8）、`SearchIndexTest`（app，23，含 secret-exclusion）、
  `SearchViewModelLifecycleTest`（2）、`P7PinTest`（7）、
  `P7PackageCompatibilityTest`（5）：合计 45 个新增测试，全 PASS。
- secret-exclusion：TOTP secret/code、Recovery plaintext、apiKey/apiSecret、
  SSH privateKey/passphrase、signing store/keyPassword、keystore base64、Env/Generic
  value、Developer notes 均不可搜索；`SearchResult.toString()` 不含 fixture secret。
- package 兼容：pinned Account Full Vault + Selected Items round-trip，stableId
  不变、无 format/version 变化。

### Notes

- 不新增 ADR（无 schema/package 语义变化）。
- `local.properties` 加入本地 `sdk.dir`（不提交）。

## [v2 provider-account-management] - 2026-08-10（Phase 4 Provider & Account Full Management，IMPLEMENTED / PR OPEN）

Issue #32：在现有 minimal hierarchy（TOTP + Recovery loop）之上补齐 Provider /
Account 正式 management 能力：Provider（create / rename / delete）与 Account
（create / rename / move / merge / delete）。**Room schema / package format 零改动**。

### Added

- **`ProviderAccountRepository`（app）**：正式 hierarchy management 全部走
  共享 `VaultRepository` 单 mutex + 单 Room transaction（原子 / rollback）。
  - Provider = `serviceName` 分组（无独立 entity / 无 package stableId），
    rename = 更新全部 descendant Account 的 `serviceName`，Account / TOTP /
    Recovery Set/Code stableIds 全部保留；rename-to-existing 显式 Conflict。
  - Empty Provider **不是当前正式能力**（Account 行是最小持久化单元，始终携带
    `serviceName`），因此 Create Provider 同时创建其首个 Account。
  - Provider delete：单事务级联删除全部 Account / TOTP / Recovery Sets+Codes，
    返回安全 counts；失败整体 rollback，无 orphan。
  - Account create / rename：preserve stableId；provider 内 duplicate account
    name 显式 Conflict（不静默 merge）。
  - Account move：跨 Provider 移动整个 hierarchy，所有 stableIds 与
    USED/usedAt 保留；目标 provider 已存在才允许；同 provider 安全 no-op。
  - Account merge（Source → Destination，Destination 存活）：source TOTP 逐个迁移，
    duplicate 复用现有官方 semantic fingerprint（secret+algorithm+digits+period），
    destination existing 存活、source duplicate 消解；Recovery Sets 全部迁移并保留
    set/code stableIds + USED/usedAt，同 title 不 dedupe；跨 Provider merge 支持；
    成功后删除 source Account（单事务）。
  - Account delete：单事务级联删除 TOTP + Recovery Sets/Codes。
- **UI**：Authenticator 首页 Provider 分组视图 + Provider 菜单（Rename /
  Add Account / Delete）+ Account 菜单（Rename / Move / Merge / Delete）+ Add
  Provider 入口；全部 destructive 操作走 confirmation dialog 且只显示安全
  counts（TOTP / Recovery Set 数量），绝不显示 secret / code value。
- **DAO**：新增 `updateServiceName` / `updateServiceNameForAll` /
  `updateAccountName` / `countByServiceName` / `listByServiceName`（Account）、
  `updateAccountId`（TOTP / Recovery Set）。

### Tests

- `ProviderAccountRepositoryTest`（25）：create/rename/delete Provider、
  Account CRUD/move/merge/delete、stableId 保留、TOTP duplicate fingerprint、
  Recovery lineage 保留、跨 Provider merge、rollback/锁定边界。
- `ProviderAccountPackageIntegrationTest`（5）：Provider rename / Account
  rename / move / merge / recovery-state 后的 Full Vault snapshot round-trip
  正确（无 orphan、stableId 保留）。
- `ProviderAccountManagementDialogTest`（6）：management dialogs 显示安全
  metadata、cancel 不改动、无 secret 泄漏。

## [v2 phase4-p6 developer-vault-completion] - 2026-08-10（Phase 4 P6 Developer Vault Completion，IMPLEMENTED / PR OPEN）

Issue #20 Phase 4 P6：补齐 Developer Vault 最后两类 Android production
CRUD/UI（Android Signing Key、Environment Variable Set），完成五类 Developer
Entry 的正式 Android daily-use closure。复用既有 Developer 单表持久化与
Sensitive Action gate，Room schema 零升级、package/merge/legacy 语义零改动。

### Added

- **Android Signing Key 全 CRUD/UI**：`DeveloperRepository.create/editAndroidSigningKey`
  （storePassword / keyPassword / keyAlias / projectName / packageName /
  keystoreFileName / opaque keystore bytes）；Create flow `Developer → Add →
  Android Signing Key`；SAF `OpenDocument` 导入 keystore（不信任扩展名/MIME，
  opaque exact-bytes 保存，大小上限由共享 logical/package per-asset contract
  推导为 raw bytes，无第三套规则）；list 仅显示安全 metadata；detail 敏感字段
  默认 masked；delete destructive confirmation（明确删除 keystore binary + 凭据）。
- **Keystore export**：`EXPORT_SIGNING_KEYSTORE` fresh re-auth → SAF
  `CreateDocument` 写 exact bytes；auth cancel 不创建输出文档；与 `.rakpkg`
  Package PIN 无关。
- **Copy key.properties**：`COPY_SIGNING_KEY_PROPERTIES` fresh re-auth，内存
  构造中性格式（storeFile/storePassword/keyAlias/keyPassword），不持久化。
- **Environment Variable Set 全 CRUD/UI**：动态行 add/remove/edit name+value；
  name 非空 + exact case-sensitive 去重 + 不 uppercase/lowercase normalize；
  value 作为 opaque secret 保存（不 trim/改写）；顺序保留；每值默认 hidden。
- **Sensitive Action**：新增 `REVEAL/COPY_SIGNING_STORE_PASSWORD`、
  `REVEAL/COPY_SIGNING_KEY_PASSWORD`、`EXPORT_SIGNING_KEYSTORE`、
  `COPY_SIGNING_KEY_PROPERTIES`、`REVEAL/COPY_ENV_VAR_VALUE`，全部绑定
  stableId+fieldKey、独立 fresh one-shot re-auth。
- **Reveal lifecycle**：reveal 状态 in-memory only，session lock / 离开页面 /
  进程重建均清除。
- **i18n/a11y**：en + zh-CN 补齐 Android Signing Key / Keystore / Env Var 相关
  标签与可访问性描述。

### Docs

- `docs/PHASE4_P6_REPORT.md`（新增）。

## [v2 phase5b legacy-import-ui] - 2026-08-09（Phase 5B Legacy v1 Android Import UI，IMPLEMENTED / PR OPEN）

Issue #1 Phase 5B：把 Phase 5A 的 Legacy v1 Core Adapter 正式接到 Android
daily-use import flow。Legacy `.rakvault` 是 IMPORT ONLY；不改 Frozen protocol，
不改 Native `.rakpkg` format。

### Added

- **`LegacyImportService`（app）**：`.rakvault` → `LegacyRakVaultImporter` →
  `LegacyImportBundle`（短暂）→ `LegacyVaultSnapshotMapper` → shared
  `VaultSnapshot` → `PackageValidator.validateSnapshot`（纯 logical）→
  `MergePlanner`（preview）→ `VaultRepository.applySnapshot`（final re-plan /
  preflight / 单事务 apply）。明文 bundle/snapshot 只存在于内存 session，
  cancel / apply / lock / new-file / error 时清除。
- **`SafLegacyFileIo`（app）**：SAF OpenDocument + `BoundedPackageReader`，
  **64 MiB + 1 检测**（与 Native 16 MiB 解耦），超限 decrypt 前拒绝；
  filename/MIME 仅 UX hint。
- **`LegacyImportViewModel`（app）**：单一 sealed state machine
  （Idle / FileSelected / AwaitingPassword / Decrypting / Preview / Applying /
  Success / Error）；Master Password 无 `>=10` 硬编码 gate，任意非空密码提交
  decoder，与 Native 6-digit PIN policy 独立；session lock / cancel / new-file
  清除全部明文状态；错误区分 RETRY_PASSWORD / RESTART / BLOCKED。
- **`LegacyImportRoute` / `LegacyImportScreen`（app）**：独立入口
  “Import Legacy v1 Vault”（Settings → Backup / Transfer），与 “Import Native
  Package” 明确分开；safe preview（schemaVersion / counts / Developer 五类 /
  merge summary，无任何 secret）。
- **ImportRecord wiring**：`sourceType="LEGACY_RAKVAULT"` +
  `sourceFingerprint`（原始加密字节 SHA-256 base64url）；仅成功事务后记录。
  Room schema **不变**（无 migration）。
- **`LegacyRakVaultImporter.ErrorKind`（core）**：非破坏性 typed failure
  category（AUTHENTICATION_FAILED / UNSUPPORTED_SCHEMA / UNSUPPORTED_FORMAT /
  INVALID_ENVELOPE / MALFORMED_PAYLOAD / FILE_TOO_LARGE …），UI 映射到明确
  safe error。
- **测试**：frozen-v1/schema1/3 integration（apply / recovery UNUSED /
  Developer 五类持久化 + keystore exact bytes / 同文件与跨备份幂等 / Developer
  conflict block / Recovery divergence block / final re-plan / rollback /
  ImportRecord-on-success）；UI/security（picker cancel / oversized before
  decrypt / no >=10 gate / password not persisted / wrong-password safe error /
  preview no secrets / session lock clears / new file clears）；Native/Legacy
  isolation（VaultRepository、MergePlanApplicator、exportimport、legacyimport
  双向）；Legacy 入口与 Native 分开导航测试。

### Changed

- `VaultRepository`：**删除** Phase-1 `importLegacy(LegacyImportBundle)` spike
  （架构清债）；`applySnapshot` 新增可选 `sourceType` 参数
  （默认 `"V2_PACKAGE"` 保持 Native 语义）。
- `MergePlanApplicator.apply`：新增可选 `sourceType` 参数（ImportRecord 写入）。
- `VaultAccess`：`legacyImportService()` 生产 wiring。
- `RescueAuthRoutes` / `RescueAuthApp` / `SettingsScreen`：Legacy import 入口
  接线（与 Native 分开）。
- strings（en / zh-CN）：Legacy import 文案。
- `docs/PHASE5B_REPORT.md`（新）、`docs/LEGACY_IMPORT.md` §10、ROADMAP /
  AGENTS / CHANGELOG 最小状态更新。

Phase 5B = **IMPLEMENTED / PR OPEN**；Phase 5A = **CLOSED**；M2（Developer
数据处理）**已并入 Phase 5B**（Legacy v1 Developer Vault 五类全部正常迁移并
持久化，不降级为只读 secure note、不默认跳过；P6 只补 Signing Key / Env Var
Set 的 Android CRUD/UI，不是补 migration capability）。未修改
PortablePackageCodec / .rakpkg / MergePlanner / Native ExportImportViewModel /
Developer UI / SensitiveActionGate。

## [v2 phase4-p5 selective-export-import] - 2026-08-09（Phase 4 P5 Selective Export / Import，PR OPEN）

Issue #20 Phase 4 P5：把现有 Full Vault Native Package 流程扩展为 Selective
Export / Import，继续使用同一个 `VaultSnapshot` → `PortablePackageCodec` →
`MergePlanner` → transactional apply（不创建第二套 package format，不创建
第二套 merge engine）。

### Added

- **共享纯 Kotlin 选择引擎（core）**：`VaultSnapshotSelector` /
  `SelectedItemSet` / `SelectableItems`。stableId 语义（不依赖 list index /
  title / account name / sort / Room row id）；hierarchy/dependency closure
  （Provider → 全部 Account → 全部 TOTP/Recovery Set；Account → 自身 +
  Provider parent metadata；single TOTP → 自身 + Account + Provider；Recovery
  Set 原子；Developer Entry 原子，五类全支持）。Export 与 Import 共用同一套
  selection 语义（不重复实现）。
- **Export 四 scope**：Entire Vault / Authenticator / Developer /
  Selected Items。全部走同一 fresh re-auth
  （`SensitiveAction.EXPORT_PACKAGE`，scope+selection digest 绑定 + one-shot
  consumed）和同一 per-export PIN Product Policy（ASCII digits 6–128，双次确认）。
- **Selective Import = decoded-snapshot 内存过滤**：Everything /
  Authenticator / Developer / Selected Items；只对最终选中的 filtered
  snapshot 运行 MergePlanner，unselected conflict 不阻塞、selected
  conflict/divergence 按现有规则 BLOCK、final apply 重新 plan（stale preview
  plan 永不直接 apply）。
- **选中稳定 id 重新解析**：最终 export snapshot 在 repository shared mutex /
  consistent transaction 内重新解析 selection；stale stableId 明确失败，绝不
  静默导出/导入另一个对象。
- **decoded package 生命周期**：只存在内存 import session；cancel / apply /
  lock / recreation 后丢弃；不进入 Room / files / SavedStateHandle / Bundle /
  DataStore。

### Security

- `SensitiveAction.EXPORT_FULL_VAULT` 更名为 `SensitiveAction.EXPORT_PACKAGE`，
  新增 `SensitiveActionTarget.ExportRequest(scopeName, selectionDigest)`：scope A
  authorization 不能授权 scope B，selected export auth 绑定原始 selection。
- **selectionDigest 安全硬化（P5 security-boundary CR）**：`SelectedItemSet`
  canonical deterministic encoding（`<KIND>:<utf8-length>:<stableId>`，
  KIND ∈ ACCOUNT/TOTP/RECOVERY_SET/DEVELOPER）→ canonical byte-sort →
  SHA-256 lowercase hex。item kind + stableId 进入 identity，无歧义拼接、
  顺序无关、不依赖 hashCode()/hash seed、不含 plaintext secret。scope 属于
  authorization identity（scopeName + digest 共同绑定）。
- 任何 `.rakpkg` export scope 都要求 fresh re-auth；auth 成功只授权本次
  pending export（one-shot，不复用给第二次 export）；无 auth cache。
- ImportRecord 语义不变：只在 successful transactional apply 后记录。
- Native/Legacy 隔离保持：新增 Native production code 不引用
  `com.rescueauth.v2.legacy`。

### Changed

- `ExportImportService`：新增 `encodeExport(scope, selection, pin)`、
  `decodeForPreview(bytes, pin, filter)`、`filterActiveImport(filter)`、
  `importPreset(scope)`、`selectableExportItems()` / `selectableImportItems()`；
  `encodeFullVaultExport` 保留为 FullVault 委托。
- `ExportImportViewModel`：export scope picker + Selected-Items selection
  screen；import 先进入 ChoosingScope 再进入 filtered preview；`EXPORT_PACKAGE`
  re-auth 绑定 scope+digest。
- UI：Export Package 四 scope chooser；共享 ItemSelectionContent（Authenticator
  hierarchy + Developer safe labels，永不显示 secret）；Import 四 scope chooser
  （Authenticator/Developer option 仅当 package 存在该 section 时才可用）。

## [v2 phase5a legacy-core-adapter] - 2026-08-09（Phase 5A Legacy v1 Core Adapter，IMPLEMENTED）

Issue #1 Phase 5A：把解密后的 legacy `.rakvault` 映射为 **shared v2 logical
`VaultSnapshot`**，复用 `PackageValidator` / `MergePlanner` /
`VaultRepository.applySnapshot` 单一验证与事务 apply 路径。只做 core
适配，不做 Android Legacy UI（Phase 5B）。

### Added

- **`LegacyVaultSnapshotMapper`（core）**：`LegacyImportBundle` →
  `VaultSnapshot`（FULL_VAULT）。schema 1/2 entry-centric、schema 3
  account-centric；TOTP 原样映射（非法参数不静默替换）；Recovery →
  `UNUSED` / `usedAt=null` 明确默认；五类 Developer 逐字段映射（keystore
  exact byte round-trip）。
- **确定性 stableId（CR 修复：durable-id-first）**：
  `legacy:<kind>:<sha256("legacy\0kind\0durablePath")>` —— 基于 legacy
  durable persisted id（UUID v4），同一对象在不同加密备份 → 相同 stableId；
  **不依赖 source fingerprint**；不含明文 secret。
- **Source fingerprint**：原始加密 `.rakvault` 字节 SHA-256（base64url），
  角色 = legacy import source identity，供 Phase 5B `ImportRecord` 使用；
  不参与 object identity。
- **独立 provenance fixture**：`legacy-fixtures/phase5a/` 由 Python
  （argon2-cffi + PyNaCl）按 frozen v1.2.0 wire protocol 独立生成
  （schema1/2/3 + Unicode），非 Dart 工具/非 Kotlin test-encoder。
- **frozen v1 producer fixture（CR 新增）**：`tools/legacy_fixtures_frozen/`
  逐字节复制 frozen v1.2.0 `vault_crypto.dart` + `vault_models.dart`，实际
  生产 `.rakvault`（含另一份不同 salt/nonce 的 alt-backup），锁 actual
  producer interoperability。
- **Legacy 防御上限（CR 修复）**：输入 16→64 MiB，与 Native `.rakpkg`
  16 MiB contract 解耦（frozen v1 无大小上限）。
- **logical validator 边界（CR 修复）**：提取
  `PackageValidator.validateSnapshot` 公共 logical 入口；Legacy 只走纯
  logical validation，不经 Native package capacity budget。
- **测试**：映射逐字段断言、幂等（同一 fixture 二次 import → MergePlanner
  inserted=0）、跨备份幂等（不同加密备份同一逻辑 vault → 全 DUPLICATE）、
  不同 durable id 不碰撞、frozen v1 interop（TOTP/Recovery/五类 Developer）、
  logical/capacity 边界、Native/Legacy 双向隔离。

### Changed

- `docs/LEGACY_IMPORT.md`：新增 §9 Phase 5A 契约（CR 修复：durable-id-first
  stableId / source fingerprint 角色 / Developer 映射 / fixture provenance /
  Legacy 防御上限 / logical validator 边界）。
- `docs/PHASE5A_REPORT.md`（新）、ROADMAP / AGENTS / CHANGELOG 最小状态更新。

Phase 5A = **IMPLEMENTED / merged（#29）**；Phase 5B（Legacy Android UI）= **NOT
STARTED**。未修改 PortablePackageCodec / .rakpkg / MergePlanner / Developer
UI / SensitiveActionGate / Export/Recovery UI。

## [v2 phase4-p4 reauth+developer] - 2026-08-09（Phase 4 P4 Sensitive Action Fresh Re-auth + Developer Vault First Batch，PR OPEN）

Issue #20 P4：Sensitive Action Fresh Re-auth Foundation（P4A）+ Developer Vault
第一批（P4B：API Credential / SSH Key / Generic Secret）。

### Added

- **Sensitive Action Re-auth Foundation**：`SensitiveAction` /
  `SensitiveActionRequest` / `SensitiveActionTarget` / `SensitiveActionGate` /
  `SensitiveActionResult` 单一 orchestration path，
  fresh Biometric/Device Credential one-shot 语义（ADR-0011）；生产无
  NoOp gate，unavailable → blocked。授权绑定原始 request
  （action + stableId + fieldKey），reveal 与 copy 完全分离、各自独立
  re-auth。
- **Full Vault Export 接入 re-auth**：Export → fresh re-auth → PIN + confirm →
  SAF CreateDocument → encode/write；auth cancel/failed/unavailable 不收集
  PIN、不创建文档、不构造 snapshot（保持 Phase 3D PIN-first 语义）。
- **Developer Vault 第一批**：API Credential / SSH Key / Generic Secret 全
  CRUD（list/detail/create/edit/delete）；列表只展示非敏感 metadata；
  reveal/copy 走 SensitiveActionGate；edit 保留 stableId；delete 用 destructive
  confirmation（P4 不要求 Undo，P8 统一）。
- **Room schema 零改动**：复用 Phase 3C `developer_entry` 单表 + typed
  payload；P4 数据自然进入 Full Vault Export/Import round-trip。

### Security

- secret 不进入 SavedStateHandle / Bundle / rememberSaveable / DataStore /
  logs；reveal 状态独立受控；敏感值 contentDescription 不含 plaintext。
- 单 pending request + 串行请求，避免多 prompt / 跨 action 授权；
  session lock / Activity pause/destroy 清空授权与 reveal 状态。
- **security-boundary CR**：新增 `COPY_SSH_PASSPHRASE` / `COPY_GENERIC_SECRET`
  action（每种 sensitive operation 都有独立一次性授权）；授权绑定原始
  target（stableId + fieldKey + operation），prompt 期间 navigation / field
  selection / 第二个同类型请求不会把成功结果作用于其它 entry / field；
  reveal 授权绝不复用于 copy。

### Tests

- 新增 `SensitiveActionGateTest`、`DeveloperRepositoryTest`、
  `DeveloperDetailViewModelTest`、`DeveloperFormViewModelTest`、
  `DeveloperScreenTest`、`DeveloperPackageIntegrationTest`；
  `ExportImportViewModelTest` 增加 re-auth gate 用例。
- `:core:test` / `:app:testDebugUnitTest`（306 tests）/ lint / assemble 全 PASS。

## [v2 phase4-p3 recovery-codes] - 2026-08-09（Phase 4 P3 Recovery Codes Daily-Use Slice，PR OPEN）

Issue #1 P3：让 Recovery Codes 从“底层已经存在的数据类型”变成真正可
日常使用的完整 Android 功能。Room schema 零改动（复用 Phase 3A 正式
schema：RecoveryCodeSetEntity / RecoveryCodeEntity / stableId / status /
usedAt）。

### Added

- **Account detail（Recovery Codes）正式 hierarchy**：Provider → Account →
  Recovery Code Set → Recovery Code[]；首页 Provider/Account 分组列表 + 每组
  recovery 摘要（counts，无 plaintext）。
- **Create Recovery Code Set**：Add Recovery Codes sheet，title + 多行粘贴，
  live preview（已解析 N 个），只做 trim / 空行过滤，不改写 code 内容
  （opaque secret）。
- **批量输入 & duplicate 策略**：exact whitespace-normalised duplicate →
  明确 validation 错误；`ABC-123` vs `ABC123` 是不同 secret；不做跨 Set 全局
  dedupe。
- **展开/收起 + reveal/hide**：折叠卡片显示 remaining · total；展开后每条
  code 默认 masked，可 reveal/hide（session lock 清空 reveal 状态）。
- **单条 copy** + **Copy All** + **Copy Remaining**（每行一个 code 的纯文本）。
- **USED / UNUSED**：mark used（usedAt=now）/ mark unused（usedAt 清空），
  remaining count 实时更新；USED 是状态不是 delete；实时写入真实 Vault。
- **Edit**：title + code list 最小 diff，未变 code 保留 stableId + USED +
  usedAt，移除删除、新增新 stableId / UNUSED，code value 变更=删除+新建；
  事务性保存。
- **Delete + Undo**：UI 立即移除 → Snackbar → Undo 真正恢复 exact
  stableIds / values / USED / usedAt / relation。
- **Package 兼容**：未改 codec / MergePlanner / package format；P3 数据自然
  进入 Full Vault Export/Import round-trip（含 used/unused + usedAt）。
- **i18n**：本轮新增 UI 文案 en + zh-CN。

### Changed

- `v2/AGENTS.md` / `v2/ROADMAP.md`：P2 / P3 勾选为已实现，新增
  `docs/PHASE4_P3_REPORT.md`；ROADMAP DAILY-USE READY 的 basic Recovery
  Codes 勾选。

### Tests（全部通过）

- `:core:test` / `:app:testDebugUnitTest`（250 tests）/ `:app:lintDebug` /
  `:app:assembleDebug` / `:app:assembleDebugAndroidTest` 全部 PASS。


## [v2 phase4-p2 strict-protocol-convergence] - 2026-08-08（P2 最终严格协议收敛，PR #25 rebase 最新 main）

Issue #20 产品原则明确：本项目只实现真实 Google Authenticator
`otpauth-migration://` 协议，不自行增加未被真实协议支持的兼容扩展。

### Changed（严格收敛，删除未由真实 GA 协议证明的兼容逻辑）

- **data decoding 只实现真实 GA 协议**：固定流程
  `URL percent-decode → standard RFC 4648 Base64 decode → MigrationPayload
  protobuf decode`。保留 percent-decoding、standard Base64、正常 RFC4648
  padding；**删除** Base64URL（`-`/`_`）接受、自行接受 no-padding、
  standard/Base64URL 混合 alphabet normalization。不符合真实格式的 data →
  explicit malformed migration payload（`invalid-data-character` /
  `malformed-base64`）。
- **batch metadata 只来自 protobuf**：batchSize / batchIndex / batchId 唯一
  authoritative source 是 decoded `MigrationPayload` protobuf 字段；**删除**
  对 `batch_size` / `batch_index` / `batch_id` 自定义 query 参数的正式支持
  （不再 override / fallback / 额外接受）。正式 URI 只允许 `data=`，出现
  `batch_*` query → `unknown-query-parameter` 拒绝。Parser contract 单一无歧义：
  `URI → data → protobuf → batch metadata`。
- **protobuf enum 严格按真实 schema**：保持已修正的 enum semantics，不把
  raw number 当业务值；未知/unsupported enum → UNSUPPORTED / INVALID。
  `MigrationModels.algorithmToken` 修正显示映射：4=MD5（非 SHA224）、
  0=UNSPECIFIED（非 MD5）。
- **adapter 定位写入文档**：`MigrationPayloadParser` 是 Google Authenticator
  migration compatibility adapter，不是通用 OTP migration parser；未来如需
  支持其它工具应新建明确 adapter / compatibility decision，不无证据放宽。

### Added（协议锁）

- 测试明确锁定：real GA percent-encoded standard Base64 + padding → accepted；
  Base64URL payload → rejected；no-padding → rejected；batch metadata from
  protobuf → accepted；`batch_*` query → rejected；real GA multi-QR fixture →
  正确 batch assembly；real GA enum semantics → 正确 TOTP/HOTP/algorithm/digits。

### Rebase

- PR #25 已 rebase / merge 到最新 main（含 Phase 3C：Room schema v3 /
  Developer persistence / transactional apply），仅 CHANGELOG.md 与
  v2/AGENTS.md 出现文档冲突并已解决；无代码冲突。

### Tests（全部通过）

- `:core:test` / `:app:testDebugUnitTest` / `:app:lintDebug` /
  `:app:assembleDebug` / `:app:assembleDebugAndroidTest` 全部 PASS。


## [v2 phase4-p2 interop-cr] - 2026-08-08（P2 merge 前 interoperability compatibility CR）

Issue #20 P2 整体 review 后的 merge 前 interop blocker 修复（**不 merge**，已推 PR #25 源分支）。

### Fixed（真实 Google Authenticator wire format interop）

- **wire enum 语义修正**：`MigrationPayload.OtpParameters` 的 `algorithm` /
  `digits` / `type` 是 **protobuf enum**（经真实 GA v6.0 export + Aegis / ente /
  Go otpauth 三份独立实现验证），不是 raw int。修复后：
  - `type`：`2`=TOTP（此前误读 raw `1`），`1`=HOTP（→unsupported），`0`=UNSPECIFIED（→TOTP）；
  - `digits`：`1`=SIX(6)、`2`=EIGHT(8)、`0`=UNSPECIFIED(→6)（此前误读 raw 值）；
  - `algorithm`：`4`=MD5（→unsupported，此前误标 SHA224）。
- **Base64 / URI decoding**：`data` 先 percent-decode（真实 export 的 `%2B`/
  `%2F`/`%3D` 不误判 malformed）；standard Base64（`+` `/` `=`，GA 实际输出）与
  URL-safe Base64（`-` `_`）都接受；合法 no-padding 接受；非法 Base64 明确拒绝。
- **batch metadata source**：真实-compatible URI 仅需 `data=...`，batchSize /
  batchIndex / batchId 从 decoded `MigrationPayload` 读取；`&batch_size=` 等
  query 参数只是额外容忍，不依赖。

### Added

- **独立 interop fixtures**：`InteropFixtures`（真实 GA v6.0 test export URI，
  synthetic test accounts）+ `tools/interop-fixture/`（protoc + Python
  google.protobuf 独立生成，不调用本项目 MinimalProtobuf/ProtoFixture）+
  `InteropFixtureTest` 断言全部解码值。

### Tests（全部通过）

- `:core:test` / `:app:testDebugUnitTest` / `:app:lintDebug` /
  `:app:assembleDebug` / `:app:assembleDebugAndroidTest`。


## [v2 phase4-p2 qr-migration] - 2026-08-08（Phase 4 P2：QR Scan + otpauth-migration Import）

Phase 4 P2（Issue #20）——让用户通过摄像头扫码添加 TOTP：普通
`otpauth://totp/...` QR 与 Google Authenticator 风格 `otpauth-migration://`
批量 QR。**IMPORT ONLY**，不实现 migration export。

### Added

- **QR scanner**：CameraX（core/camera2/lifecycle/view 1.4.1）+ ML Kit
  `barcode-scanning` 17.3.0；权限仅在 Scan QR 时请求（granted / denied /
  permanently-denied 明确 UI）；lifecycle-aware（页面离开/后台自动 release）；
  torch 切换；不保存/上传图像；同 QR 防重复触发（last-value + cooldown）。
- **otpauth-migration parser（core，纯 Kotlin）**：
  `migration/MinimalProtobuf.kt`（边界严格的最小 protobuf wire decoder，
  零新增依赖）、`migration/MigrationPayloadParser.kt`（URI → 分类 entries +
  batch metadata）、`migration/MigrationModels.kt`（IMPORTABLE / UNSUPPORTED /
  INVALID per-entry status）。不依赖 Camera/Compose/Room/Android Context/legacy。
- **多 QR batch session（core）**：`migration/MigrationBatchSession.kt`，支持
  batchId / batchIndex / batchSize、乱序收齐、重复帧幂等、冲突明确拒绝；
  内存态，app kill 不恢复。
- **ScannerResultRouter（app）**：raw String → otpauth（复用 P1 OtpauthParser）/
  migration / not-supported / malformed 分类路由。
- **Repository batch import**：`AuthenticatorRepository.importTotpBatch`
  （单事务，Provider/Account 复用，stableId 生成，TOTP semantic fingerprint
  dedupe；不碰 Phase 3C Merge）。
- **UI**：Add 菜单三选（Scan QR / Paste URI / Manual）；全屏 camera preview；
  扫描完成后进入 Compose confirmation state（MigrationImportSheet：预览每项
  issuer/account/algorithm/digits，**不显示 secret**；多 QR 进度；结果计数
  Imported / duplicates / unsupported / invalid）。
- 文档：`docs/PHASE4_P2_REPORT.md`；ROADMAP §5.3 P2 标记已实现。

### Changed

- `AndroidManifest.xml`：新增 CAMERA 权限 + camera feature（required=false）。
- `build.gradle.kts` / `libs.versions.toml`：CameraX 1.4.1 + ML Kit 17.3.0。
- `AuthenticatorViewModel` / `AuthenticatorRoute` / `AddTotpSheet`：scan +
  migration import 状态机与 UI 接线。
- strings en + zh-CN。

### Tests（全部通过）

- `:core:test`：migration parser（single/multi、issuer/name、secret→Base32
  exact、SHA1/256/512、digits、malformed base64/protobuf、missing secret、
  HOTP、unsupported algorithm、mixed）+ batch session（single、1/3→3/3、
  乱序、重复幂等、duplicate-index、batchId/size 冲突、incomplete）。
- `:app:testDebugUnitTest`：ScannerResultRouter、ViewModel scan/migration
  （preview / multi-QR progress / import result / duplicate / cancel release）、
  repository batch import（3→3、Provider/Account 复用、duplicate skip、mixed、
  unsupported 不落库、restart reopen）、Add menu（Scan/Paste/Manual）。
- `:app:lintDebug` / `:app:assembleDebug` / `:app:assembleDebugAndroidTest` 均 PASS。

## [v2 phase3d android-export-import] - 2026-08-08（Phase 3D：Android Export / Import + Package Preview，PR OPEN）

Phase 3D 独立 PR：把 3A/3B/3C 接成真正的 Android 用户闭环 —— SAF 文件 +
per-export PIN 对话框 + import preview + confirm transactional apply。

### Added

- **Android Export / Import orchestration**（`app/.../exportimport/`）：
  `ExportImportViewModel`（coordinator，纯 JVM 可测）+ `ExportImportService`
  （use-case）→ 复用 `VaultRepository`（3C）/ `PortablePackageCodec`（3B）/
  `PackageValidator` / `MergePlanner`（3A）。UI 不直接访问 DAO，Composable
  不直接调用 codec / Room。
- **SAF export flow**：`ActivityResultContracts.CreateDocument`（`.rakpkg`，
  MIME hint `application/vnd.rescueauth.v2-package`）→ per-export PIN 对话框
  （输入 + 确认，隐藏/reveal）→ `buildConsistentExportSnapshot`
  （FULL_VAULT，mutex + 单 Room 事务内一致构造）→ `PortablePackageCodec.encode`
  → SAF OutputStream 只写 encrypted bytes；写失败明确报错 + best-effort 清理，
  不宣称成功。
- **SAF import flow**：`ActivityResultContracts.OpenDocument` → `PackageIdentifier`
  （native magic 检查，Legacy 隔离）→ PIN → `BoundedPackageReader`
  （流式增量读取，最多 16 MiB + 1，超限即拒，纯 JVM 可测）→
  `PortablePackageCodec.decode` → validate → plan → **safe preview** →
  confirm 走 Phase 3C `applyMergePlan`（re-plan/preflight/apply 最终 authority）。
- **safe import preview**：`ImportPreview` 只含非 secret summary（package
  metadata / Authenticator 计数 / Developer 五类计数 / merge summary）；
  Developer 只展示非敏感 metadata（type / title / project/service/key name）。
  CONFLICT / recovery state divergence → blocked，用户只能 Cancel / Back，
  不能强推 source/destination wins。
- **bounded untrusted-file reading**：`BoundedPackageReader`（`:core`，纯
  Kotlin）—— 不信任 MIME / displayName / extension / `OpenableColumns.SIZE`；
  不预分配攻击者声称的巨大 size；不 OOM。
- **per-export PIN UX / policy**：`PinPolicy`（Product Policy constant，
  6–128 位纯数字，不写入 codec format）；PIN 默认隐藏、可短暂 reveal；
  不写日志、不进 SavedStateHandle / Bundle / rememberSaveable / 持久化；
  优先 CharArray，用后 best-effort 清理。
- **import session 敏感生命周期**：decoded plaintext payload 只存在
  `ExportImportService` 内存 session；cancel / apply / session lock → 清除；
  process/state recreation 不恢复；auto-lock 后要求重新完整 decode。
- **`.rakpkg` 扩展名 / MIME contract**：正式写入 PACKAGE_FORMAT.md（与
  legacy `.rakvault` 视觉 + 代码可区分，两个 import 路径隔离）。
- 文档：ADR-0009 / PHASE3_REPORT §12 / ROADMAP / AGENTS。

### Tests

- `:core` 新增 `BoundedPackageReaderTest`（9）+ `PackageIdentifierTest`（6）。
- `:app` 新增 `ExportImportServiceTest`（28）+ `ExportImportViewModelTest`（15）：
  empty vault Full Export / Authenticator+Recovery export / 五类 Developer
  export / binary keystore exact round-trip / entered PIN decode / wrong PIN /
  同 vault 同 PIN 两次 export 不同字节 / exported scope == FULL_VAULT /
  VaultKey 不在 package / 写失败 error / PIN confirm mismatch；valid package
  read / 16 MiB 边界 / >limit 拒绝 / invalid magic / corrupted / wrong PIN /
  unsupported version / malformed / user cancel / read failure；preview
  （inserts / duplicate-only / mixed / conflict block / divergence block /
  five-type counts / no secrets / selected / auth-only / dev-only）；apply
  （preview→confirm→success / duplicate-only no-op / conflict block / apply
  failure 无部分 DB 变更 / 幂等 / preview 后 destination 变化安全 replan
  block / ImportRecord 只在成功路径写入）；敏感生命周期（cancel / apply /
  lock clears session、PIN 不进 UI state）。
- 全量：`:core:test` + `:app:testDebugUnitTest` **422 tests / 0 failures**；
  `:app:lintDebug` 0 error；`:app:assembleDebug` / `:app:assembleDebugAndroidTest`
  成功。不主动运行 FTL（合入 main 后 changed-file gate 自动触发）。

### Merge 前收尾（PR #26 rebase latest main + Export/PIN UX contract）

- **Export PIN Product Policy 锁定**：新增 `PinPolicyTest`（19 tests）锁定
  charset = ASCII 数字、minimum 6、maximum 128、confirm 一致；Import 只拒绝
  空 PIN（历史/第三方包的任意 codec 合法 PIN 均可导入）。规则全部集中在
  `PinPolicy.validateExportPin` / `validateImportPin`，Composable 不再内嵌
  policy 常量。
- **Export 流程重排为 PIN + confirm → CreateDocument → encode/write**：
  PIN 取消不会创建文件；SAF destination 取消回到 AwaitingPin；写失败
  best-effort 清理（provider 不支持删除时不 crash）。新增 ViewModel 测试：
  PIN cancel 不创建文档、destination cancel 无文件、写失败清理、delete
  抛异常不 crash、session lock 丢弃 pending PIN、短 PIN 导入仍可解密。
- 保持范围：不实现 Sensitive Action Re-auth / selective export / Legacy
  Import / conflict resolution / Phase 4 features。

## [v2 phase3c transactional-import-merge] - 2026-08-08（Phase 3C：Transactional Import / Merge Apply，PR #24 已 merge）

Phase 3C 独立 PR：把 Phase 3A 的 `MergePlan` 以事务方式应用到本地 encrypted
Vault，并使 Developer Vault 五类条目首次真实落库（schema v2→v3）。


### Added

- **Developer persistence（schema v2→v3）**：新增 `developer_entry` 单表 +
  typed payload（`DeveloperEntryEntity` / `DeveloperEntryDao`）；五类 Developer
  Entry（Android Signing Key / API Credential / SSH Key / Env Var Set /
  Generic Secret）可真实落库；`stableId` first-class（UNIQUE）；secret 只存于
  SQLCipher DB，无明文 sidecar，不写日志。
- **`MergePlanApplicator`**：唯一执行 `MergePlan` 的边界 —— 单 Room 事务内
  INSERT / DUPLICATE no-op / CONFLICT 阻止 / destination-only 不删；
  parent/child identity mapping（ResolvedProvider / ResolvedAccount）；
  新对象保留 source stableId（Room id 为本地主键）。
- **`VaultRepository.applyMergePlan(payload)`**（Native Package Import 完整
  链路：validate → plan → preflight → apply）+ **`applySnapshot(snapshot)`**
  （shared 事务 boundary，future Legacy / otpauth adapter 复用）；
  `buildDestinationSnapshot()`（当前 Vault → logical snapshot）。
- **`ImportOutcome.Applied / Blocked`**：CONFLICT 与 Recovery used/unused
  state divergence 保守阻止 apply，由 Phase 3D 呈现，不静默解决。
- **migration v2→v3**：非 destructive，仅建 `developer_entry` 表 + 索引；
  既有 Authenticator / Recovery / ImportRecord 数据与 stableId lineage 保留。
- 文档：ADR-0008 / PHASE3_REPORT §11 / ROADMAP / AGENTS。

### Tests

- `:app:testDebugUnitTest` 新增 96 个断言（`MergePlanApplyTest` 93 +
  `RescueAuthDatabaseMigrationTest2To3` 3）：五类 Developer round-trip /
  keystore binary exact round-trip / migration 保留既有数据；basic apply
  （empty / Auth-only / Dev-only / selected-items）；parent resolution
  （duplicate Provider+Account、semantic dedupe 后 child 指向正确 destination、
  不创建重复 parent）；merge behavior（INSERT / DUPLICATE no-op / CONFLICT
  blocks / destination-only preserved / Developer stableId 语义）；Recovery
  used/unused divergence 双向 block；idempotence（同 package / mixed vault /
  Developer / parent dedupe child 二次）；transactionality（failure halfway
  → complete rollback、Developer 失败 → Authenticator rollback、ImportRecord
  rollback、无 partial state）；concurrency（两并发 import 串行提交）；
  `applySnapshot` shared boundary；close/reopen persistence。
- `:core:test` / `:app:testDebugUnitTest` / `:app:lintDebug` /
  `:app:assembleDebug` / `:app:assembleDebugAndroidTest` 全部 PASS。


## [v2 phase4-p1 totp-compat] - 2026-08-08（PR #23 merge 前 TOTP compatibility CR）

Phase 4 P1 merge 前的最小 TOTP 兼容性修正（Issue #20）。**冻结 v1 Authenticator
契约：digits 6..10、period 1..120**（默认仍为 SHA1 / 6 / 30）。避免后续
Legacy Import 产生无法正常使用的已迁移 credential。

### Changed

- `TotpParameters`：`SUPPORTED_DIGITS` 扩为 **6..10**；新增
  `MIN_DIGITS` / `MAX_DIGITS` / `MIN_PERIOD_SECONDS` / `MAX_PERIOD_SECONDS`
  （1..120）作为正式 domain contract 单一来源。
- `TotpCore.generate`：支持 **9/10-digit** 生成（`mod 1e9 / 1e10`）；
  `validate` / `remainingSeconds` 的 period 校验改为 **1..120**。
- `OtpauthParser`：接受 digits 6..10、period 1..120；超范围拒绝。
- `AuthenticatorRepository.addTotpCredential`（Manual Entry validation）对齐
  正式 domain contract（digits 6..10、period 1..120）。
- `AddTotpSheet`：Manual Entry 的 Digits 选择器覆盖 6..10，Period 选择器覆盖
  1 / 30 / 60 / 120（`FlowRow` 换行）。
- `PackageValidator` / `Canonicalization` 注释同步正式契约；period 校验对齐 1..120。
- `ROADMAP.md §4.2` / `PHASE4_P1_REPORT.md` 同步 digits/period 契约描述。

### Tests（全部通过）

- `:core:test`：TotpCore 9/10-digit generation、period=1/120、超范围
  digits(5/11)/period(0/121) 拒绝；Parser digits=9/10、period=1/120 解析、
  超范围拒绝；PackageValidator 新边界。
- `:app:testDebugUnitTest`：Repository 新增 9/10-digit、period 1/120 持久化；
  **已有 Provider+Account 再添加第二个 TOTP 复用 Account、不创建重复
  Provider/Account** 确认测试。
- `:app:lintDebug` / `:app:assembleDebug` / `:app:assembleDebugAndroidTest` 均 PASS。
## [v2 phase3b encrypted-package-codec] - 2026-08-08（Phase 3B：Encrypted Portable Package Codec）

Phase 3B 独立 PR（不进入 3C/3D）：纯 Kotlin/JVM 加密 package codec。

### Added

- `core/.../export/codec/`：
  - `PackageFormat`：字节布局常量、magic `RAKVPKG2`、formatVersion/cryptoVersion=1、
    KDF DEFAULT PARAMETERS（Argon2id 19 MiB / 2 / p1 / 32B / salt 16B）与
    ACCEPTABLE DECODE RANGE（memoryKiB 64..262144 / iterations 1..16 /
    parallelism 1..8 / outputLength 16..64 / salt 8..64）、格式级硬限制（总包
    16 MiB / header 4 KiB / payload 16 MiB / wrapped key 512B）、错误分类
    `PackageCodecException`（UnsupportedFormat / UnsupportedCrypto /
    InvalidKdfParameters / MalformedPackage / AuthenticationFailed /
    LogicalPayloadInvalid）。
  - `PackageHeaderParser`：header-is-untrusted；magic/version/KDF 参数/长度在
    Argon2 **之前**全部校验；无符号读取杜绝 overflow；headerLength 交叉校验；
    trailing garbage 拒绝。
  - `PackageCrypto`：SecureRandom（CSPRNG）+ 测试用 deterministic seam、
    Argon2id(v1.3)、XChaCha20-Poly1305 AEAD（wrap + payload）、best-effort
    zeroize。
  - `PortablePackageCodec`：单一 codec（全部 SnapshotScope），
    `encode(payload, pin: String|CharArray|ByteArray): ByteArray` /
    `decode(bytes, pin): VaultPackagePayload`；纯 Kotlin/JVM，无 Android 依赖。
  - `PackageEnvelope` / `PayloadJson`：parsed envelope（含 wrap/payload AAD 区域）
    与逻辑 payload JSON 序列化（kotlinx.serialization，platform-neutral）。
- **AAD 设计**：wrap AAD = header 前缀（magic → wrapped key 前）；payload AAD =
  完整 header 前缀（magic → payload ciphertext 前）；format/crypto/KDF metadata
  全部受认证保护，无法被篡改成另一种合法语义。
- **golden fixture**：`core/src/test/resources/codec-fixtures/v2_package_fixture_v1.bin`
  （test-only PIN `fixture-pin-0000`，synthetic fake secrets，无真实 credential）。
- 文档：`PACKAGE_FORMAT.md`（Phase 3B 契约）、`THREAT_MODEL.md`、
  `ADRS/ADR-0007-portable-package-codec.md`、`PHASE3_REPORT.md` §9、
  `ROADMAP.md` §5.2、`AGENTS.md`。

### Tests

- `:core:test`：112 → **173**（+61 Phase 3B codec 测试）全绿；
  merge 前 CR 后 **173 → 193**（+20：capacity / runtime policy / output-length）。
  - Round-trip（12）：empty / authenticator-only / developer-only /
    selected-items / full vault（五类 Developer）；binary keystore exact byte
    round-trip；recovery used/unused；同 payload 同 PIN 两次 export 不同但
    decrypt 相同；salt/nonce 每 export 重新随机；plaintext header 不泄漏 secret /
    业务 metadata。
  - Auth（15）：wrong PIN / corrupted wrapped key / payload / nonce / AAD
    tamper；tampered version；truncated / trailing garbage；no partial
    plaintext；future version rejection；错误信息不区分 wrong PIN vs corruption。
  - Format/DoS（20）：malicious huge memory/iterations/parallelism 在 KDF 前拒绝；
    KDF range 边界；cryptoVersion=1 的 `kdfOutputLength` 必须 == 32（16 / 64
    拒绝）；异常长度 / oversized package / wrapped key / payload；
    invalid logical payload（LogicalPayloadInvalid）；invalid logical schema
    version。
  - KDF policy（7）：encode 写默认参数；decode 读 package 内参数；range 边界拒绝。
  - PIN representation（4）：String / CharArray / ByteArray 互换；wrong PIN。
  - Golden fixture（4）：fixture 与 codec 输出逐字节一致；fixture 解密为预期
    payload；wrong PIN 失败；deterministic seam 稳定。
  - **Capacity（6）**：codec 与 validator 共享 `PackageCapacity` 预算；
    validator 接受 ⇒ 必然可编码；encode 超限抛 `PackageTooLarge`（不 OOM）。
  - **Runtime policy（9）**：FORMAT HARD LIMIT ≠ RUNTIME DECODE RESOURCE
    POLICY；格式内但超预算参数在 Argon2 前拒绝；默认 19 MiB/2 iter 永远通过。
  - **PackageCapacity（4）**：estimateSerializedSize 为可证明上界；single max
    keystore 预算内；multi 大 keystore 超预算 validator 拒绝；常量一致。

## [v2 phase3a full-logical-equivalence] - 2026-08-07（PR #18 Developer merge blocker 最终修正）

Phase 3A compatibility CR 的最后一个 merge blocker 修正（最小 scope，不进入 Phase 3B）：

### Changed

- `Canonicalization.developerFingerprint` → `Canonicalization.developerLogicalFingerprint`：
  同 stableId 判重从 **sensitive payload** 改为 **FULL LOGICAL PAYLOAD**（覆盖各
  Developer Entry 的**全部用户语义字段**）：
  - Android Signing Key：title / notes / projectName / packageName /
    keystoreFileName / keystore 字节 / storePassword / keyAlias / keyPassword；
  - API Credential：title / notes / serviceName / accountName / apiKey / apiSecret；
  - SSH Key：title / notes / keyName / publicKey / privateKey / passphrase；
  - Environment Variable Set：title / notes / projectName / variable **names**+values；
  - Generic Secret：title / notes / field **labels**+values。
  `createdAt` / `updatedAt` 等纯技术 metadata 排除。
- `MergePlanner`：同 stableId 时——FULL LOGICAL PAYLOAD 完全一致 → **DUPLICATE**；
  **任意 user-meaningful logical field 不同（title / notes / projectName /
  packageName / serviceName / accountName / keyName / env variable names /
  generic field labels 任一不同，不仅限于 sensitive payload）→ CONFLICT**。
  不同 stableId 一律 **INSERT / keep both**（不变，跨 stableId 不做指纹 dedupe）。
- `canonicalKeyValues`：key（env variable names / generic field labels）改为
  label canonicalization（保留 `_` / `-`，避免 `API_KEY` vs `API-KEY` 被折叠），
  value 保持 secret canonicalization。
- `PACKAGE_FORMAT.md` / `PHASE3_REPORT.md` / ADR-0004 / ADR-0005：同步
  full-logical-equivalence 契约。

### Tests

- `MergePlannerTest` 新增 7 个 CR 要求的用例：同 stableId 同 secret 异 title →
  CONFLICT；同 stableId signing key 同 keystore 异 project/package → CONFLICT；
  同 stableId API 同 key/secret 异 service/account → CONFLICT；同 stableId SSH 同
  key 异 keyName → CONFLICT；同 stableId env 同 values 异 variable names →
  CONFLICT；同 stableId generic 同 values 异 labels → CONFLICT；同 stableId FULL
  LOGICAL PAYLOAD 完全一致 → DUPLICATE。
- `CanonicalizationTest` 改为 `developerLogicalFingerprint` 语义（+1）：任一用户
  语义字段不同 → 异 fingerprint；`createdAt`/`updatedAt` 纯技术 metadata → 同
  fingerprint。
- `:core:test` 106 → **112** 全绿（0 failure / 0 error）。

## [v2 phase3a merge-blocker] - 2026-08-07（PR #18 Developer merge 保守化 CR）

Phase 3A compatibility CR 的 merge blocker 修正（最小 scope，不进入 Phase 3B）：

### Changed

- `MergePlanner`：Developer Entry 的 dedupe **只认 stableId**。移除了“异
  stableId + 同敏感 payload → DUPLICATE”的规则：相同 secret / private key /
  keystore 字节 / env values / generic values 本身不能证明两条不同 stableId
  的 Developer Entry 是同一条逻辑资产（同一 API key 可按不同 service/account
  保存为两个用途；同一 SSH key 可对应不同 server/usage；同一 keystore 可被
  多个 project/package 使用；Env/Generic 同 value 不代表 name/label 相同），
  因此不同 stableId 一律 **INSERT / keep both**，不得静默丢数据/语义。
- `Canonicalization.developerFingerprint`：明确仅用于**同 stableId** 比较
  （同 payload → DUPLICATE；异 payload → CONFLICT），不再用于跨 stableId dedupe。
- `PACKAGE_FORMAT.md` / `PHASE3_REPORT.md` / ADR-0004 / ADR-0005：同步保守
  merge 契约；per-type 完整 canonical logical equivalence 留到后续增强。

### Tests

- `MergePlannerTest` 新增 5 个 keep-both 用例：同 keystore 字节异
  project/package → keep both；同 SSH key 异 logical usage/name → keep both；
  同 API key/secret 异 service/account → keep both；Env sets 同值异
  project/name → keep both；Generic secrets 同值异 label → keep both。
- `:core:test` 101 → **106** 全绿（0 failure / 0 error）。

## [v2 phase3a compat] - 2026-08-07（PR #18 兼容性修正，对齐 PR #19 最新 main）

PR #19（roadmap-v2）合并后，最新 PRODUCT / ROADMAP 成为新的 source of truth。
本轮把 PR #18 的 Phase 3A foundation 与最新产品定义严格对齐（限界 CR，不进入
Phase 3B）：

### Added

- `VaultSnapshot.developerEntries`：Developer Vault 五类（Android Signing Key /
  API Credential / SSH Key / Environment Variable Set / Generic Secret）作为
  first-class 逻辑资产进入 portable logical schema（ROADMAP §8.2）。
- `SnapshotScope`（FULL_VAULT / AUTHENTICATOR_ONLY / DEVELOPER_ONLY /
  SELECTED_ITEMS）+ `PackageValidator` scope 一致性校验：partial / selective
  snapshot 是 first-class 契约（ROADMAP §8.4 / §17）。
- `VaultAndroidSigningKey.keystoreBase64`：binary keystore 以 base64 携带，
  校验有效性 + 大小上限；只属于 encrypted payload，不进 plaintext header。
- `Canonicalization.developerFingerprint`：五类 Developer Entry 的敏感 payload
  语义 fingerprint（label 不参与）。
- `MergePlanner` Developer Entry merge 语义（同 stableId+同 payload→dup；同
  stableId+异 payload→conflict；**异 stableId→insert/keep both，不做跨
  stableId 指纹 dedupe**——相同敏感 payload 不证明同一逻辑资产，避免静默
  丢数据/语义）。
- `RecoveryCodeStateDivergence` / `MergeSummary.stateDivergences`：Recovery
  used/unused 差异显式输出，不静默保留/覆盖（用户状态不是纯 metadata）。
- `TotpParameters`（shared 层自带 base32/算法校验）+ `LegacyIsolationTest`
  （source 级锁定 shared logical 层不依赖 legacy 类型）。

### Changed

- `PackageValidator` 移除对 `legacy.TotpVerifier` 的依赖（Legacy/Native 隔离，
  ROADMAP §9）。
- `PACKAGE_FORMAT.md` / `PHASE3_REPORT.md` / ADR-0004 / ADR-0005 同步最新契约。

### Tests

- `:core:test` 70 → **101**（+31：MergePlanner +11、Canonicalization +4、
  PackageValidator +11、LegacyIsolation +1、Serialization +4）；`:app:testDebugUnitTest` 36/36、
  lint 0 error、assembleDebug / assembleDebugAndroidTest 成功。

## [v2 roadmap] - 2026-08-07（Issue #17）

正式产品 Roadmap 定稿（docs-only）：

### Added

- `v2/ROADMAP.md`：正式路线图 source of truth——产品定位（Android-only /
  local-first / encrypted personal security vault）、三支柱（Authenticator /
  Developer Vault / Portable Vault Package）、Phase 3 之后全部重新规划
  （3A→3D + Phase 4 daily-use slices P1–P8 + Phase 5 legacy 收口 + Phase 6
  polish）、DAILY-USE READY 与 V2.0 FEATURE COMPLETE 里程碑定义（见
  ROADMAP §10 / §10.1）、Phase 3A Review Checklist。
- `v2/docs/ADRS/ADR-0006-sensitive-action-reauth.md`：Sensitive Action
  Re-authentication 正式能力（Export / export keystore / reveal 长期 secret
  要求 fresh 生物识别/设备凭据）。

### Changed

- `v2/PRODUCT.md`：改为正式产品范围文档；明确 Developer Vault 五类全部
  KEEP（废止“v2 removed”旧假设）、manual-only 导出、per-export PIN、
  merge-first 导入、Sensitive re-auth、Search/Pin、Delete Undo、
  otpauth-migration import-only。
- `v2/AGENTS.md`：阶段跟踪改为 slice 化清单（3B/3C/3D、P1–P8、M1/M2、
  L1–L3），新增架构边界（Legacy/Native 隔离、Package 独立 schema、
  no automatic backup、Sensitive re-auth）。
- `v2/docs/THREAT_MODEL.md`：Developer Entry 列为正式资产；新增
  Sensitive re-auth、Global Search 不索引 secret、Clipboard auto-clear DEFER。
- `v2/docs/UPDATE_PROTOCOL.md`：范围收敛为 check releases + 外部打开，
  不做 self-update 安装。
- `README.md` / `README.zh-CN.md`：产品描述改为 v2 三支柱；旧 Flutter
  功能/平台/备份/限制段落标注为 frozen v1.2.0 事实。

## [v2 phase2-closure] - 2026-08-07（PR #16）

Phase 2 收口 / 状态同步：数据库 instrumented 验证在 Firebase Test Lab
真实执行 6/6 PASS 后的文档状态重建 + 历史 PR #12（launcher icon）吸收。

### Added

- 应用启动图标（源自设计稿 SVG）：`v2/tools/launcher_icon/src/icon.svg`
  （单一事实来源）+ 自适应图标（API 26+，含 Android 13 monochrome）+ 传统
  PNG 回退 + 可复现生成脚本 `v2/tools/launcher_icon/generate_icons.py`；
  `AndroidManifest.xml` 设置 `android:icon` / `android:roundIcon`。
  （吸收 PR #12 中仍有效的产品内容，非新增功能。）

### Changed

- 文档状态同步：`v2/AGENTS.md`、`v2/docs/PHASE2_REPORT.md`、`README*.md`
  更新为最新 main 真实状态：JVM/Robolectric 35/35、数据库 instrumented
  6/6 PASS（Firebase Test Lab，MediumPhone.arm / API 33）。

### Notes

- 生物识别 / Keystore 认证有效期 / 截图保护 / 锁屏 / 生命周期等仍为
  **未真机验证**的验证缺口（non-blocking backlog），见 PHASE2_REPORT §4.2。
- Phase 3 未开始（BACKUP_FORMAT 仍为 Draft）。

## [v2 phase2-blocker-hotfix] - 2026-08-06（PR #6）

阶段 2 合并后回归审计（只读）发现的 3 个 blocker 的修复，未夹带 phase 3 功能。

### Fixed

- **BiometricPrompt 启动崩溃**：`DEVICE_CREDENTIAL` 与 negative button 同设导致
  `PromptInfo.build()` 抛 `IllegalArgumentException`、首次启动即崩。现在按设备实际
  可用认证器动态解析（`resolveAvailableAuthenticators`），`DEVICE_CREDENTIAL` 路径
  不设 negative button；认证从 `onCreate` 移到 `onResume`（resumed 后才触发），
  并用 `AtomicBoolean` 防止取消/失败/重复 onResume 造成认证循环。
- **instrumented 测试 APK 无法编译**：反引号空格方法名改为合法标识符（minSdk 26
  可 dex）；`corrupted database` 恒真断言（`threw || true`）重写为真实断言；新增
  "普通 SQLite header 文件被拒绝"用例。
- **平台证据链断裂**：`SecureScreenFlagTest` 改为真实启动 `MainActivity` 并断言窗口
  `FLAG_SECURE`；`PageSize16KTest` 在 APK 缺失时 fail 而非静默返回；
  `testDebugUnitTest` 依赖 `assembleDebug` 并注入 APK 路径。

### Changed

- `MainActivity` 使用 AppCompat 主题（`Theme.RescueAuth`），避免 `AppCompatDelegate`
  在 `setContentView` 抛异常。
- `SessionManager` 新增可注入 `vaultKeyManagerFactory`；`MainActivity` 新增
  `sessionManagerFactory`（平台测试注入缝，Robolectric 无 AndroidKeyStore）。

### Verified

- `:core:test` 34/34、`:app:testDebugUnitTest` 31/31（后续 PR #15 后为 35/35）、
  `:app:assembleDebug` 成功。
- `:app:assembleDebugAndroidTest` 编译通过（instrumented 6 用例可 dex）。
- `:app:lintDebug` 0 error。

> 注：该版本的真机 instrumented 验证尚未执行；**后续（PR #15 合并后）**
> `RescueAuthDatabaseInstrumentedTest` 已在 Firebase Test Lab 真实执行
> 6/6 PASS（MediumPhone.arm / API 33）。

## [v2 phase 2] - 2026-08-06（PR #5）

### Added

- Room schema v1：六实体/六 DAO（`AuthAccount`/`TotpCredential`/`RecoveryCodeSet`/
  `RecoveryCode`/`ImportRecord`/`BackupRecord`），级联删除 + Flow 观察。
- SQLCipher 全库加密（Zetetic `sqlcipher-android` 4.17.0，当前维护版）。
- `VaultKey`（256-bit SecureRandom）+ Android Keystore AES-256-GCM 不可导出包装。
- 安全会话状态机 `LOCKED/AUTHENTICATING/UNLOCKED/KEY_INVALIDATED` +
  `SessionManager`（解锁/锁定/后台超时自动锁/前台取消/密钥清零）。
- 串行 `VaultRepository`（Mutex + withTransaction + 备份快照 sink）。
- 后台遮罩 + `FLAG_SECURE`；`BiometricPrompt` 解锁。
- ADR-0003（数据库加密、VaultKey 分层、安全会话、16KB page）。

### Notes

- 真机 instrumented 验证待设备环境（详见 `v2/docs/PHASE2_REPORT.md`）；
  已于 phase2-closure 前的 main（PR #15 合并后）在 Firebase Test Lab
  真实执行 6/6 PASS。

## [v2 phase1-fix] - 2026-08-06（PR #3）

### Fixed

- 旧 schema 1/2 映射改为 **entry-centric**：每条 legacy TOTP 条目独立转换为一个
  `AuthAccount`，完整保留 issuer/accountName/TotpCredential；禁止按 issuer 合并。
- 非法/未知 TOTP 参数**不再静默替换**为 SHA1/6/30：保留原始字段、标记不可用、
  列入未导入报告，禁止生成错误 TOTP。

### Changed

- Bouncy Castle `bcprov` 1.78.1 → **1.85**（当前稳定版），改用官方原生
  `XChaCha20Poly1305`，删除自实现 HChaCha20（详见 ADR-0002）。

## [v2 phase 1] - 2026-08-06（提交 `4ea76b4`）

### Added

- 最小 Kotlin/Android 多模块工程（`:core` 纯 JVM + `:app`）。
- 旧 `.rakvault` Argon2id + XChaCha20-Poly1305 解密 spike（18/18 测试通过）。

## [v2 phase 0] - 2026-08-06（提交 `f0f2ad0`）

### Added

- 冻结旧 Flutter 应用（不可变 tag `v1.2.0`）。
- 生成 legacy import fixtures（正/负向，SHA-256 固定）。
- 映射文档 `v2/docs/LEGACY_IMPORT.md`。

---

## [1.1.0] - 2026-05-26

This release reshapes the vault around a three-tier account-centric model:
**Provider → Account → Credential**. The legacy flat list of TOTP entries and
recovery code sets is gone — both kinds of credentials now live inside named
accounts, which themselves belong to providers.

### ⚠️ Migration notice

- A 1.0.x vault opens normally in 1.1.0 and is silently upgraded from
  schema v2 to v3 on first unlock. Each legacy TOTP entry becomes its own
  account; entries that share an issuer are grouped under a single provider.
  Each recovery code set becomes its own account under a dedicated provider
  (you can merge or move them afterwards).
- **Once 1.1.0 has written the file, older versions can no longer open it.**
  Export a backup from 1.0.x first if you need a rollback path.

### Added

- Three-tier model: `ServiceProvider` groups one or more `Account`s; each
  `Account` holds an ordered list of `Credential`s (`TotpCredential` or
  `RecoveryCodesCredential`).
- New tabs: top-level "Providers" list, drill-down "Accounts under provider",
  detail screen for a single account.
- Provider operations: rename, delete (cascades to its accounts).
- Account operations: rename, move to a different provider, **merge into
  another account** (appends source credentials to target, then deletes
  source), delete.
- Recovery codes: edit codes list in place (preserves id and createdAt),
  move to another account.
- Inline destination selector when adding a credential — pick provider and
  account up-front before saving, with three modes (new provider+account,
  existing provider+new account, existing account).

### Changed

- `Account` no longer carries an `issuer` field — the owning provider's
  `name` covers it.
- `TotpCredential` no longer carries a `label` — `Account.displayName`
  replaces it.
- `RecoveryCodesCredential` no longer carries a `title` — provider+account
  names provide the context.
- Vault schema bumped from v2 to v3.
- The crypto envelope and KDF parameters are unchanged. A v2 vault is
  re-encrypted in place after migration; no `.bak` of v2 cleartext is left
  on disk.

### Removed

- The legacy "TOTP" and "Recovery codes" tabs and their list screens.
- The standalone account picker bottom sheet (folded into the inline
  destination selector).

### Notes

- Same-display-name accounts are not auto-merged — use the **Merge into…**
  action when you want to combine them.
- The TOTP credential remains immutable on purpose (changing any of its
  fields would silently invalidate the secret); to change a TOTP, delete
  and re-add it.

## [1.0.1]

### Added

- Settings tab with developer-vault toggle, version display, and update
  checker against GitHub Releases.
- Developer vault entries: Android signing keys, API credentials, SSH
  keys, environment variables, and generic secrets.
- TOTP delete with confirmation dialog.

## [1.0.0]

Initial public release.

### Added

- Single encrypted vault file (Argon2id + XChaCha20-Poly1305).
- TOTP codes with live countdown and copy.
- Recovery codes: add, view, copy, delete.
- TOTP import via QR scan (Android) and otpauth URI paste (desktop).
- Encrypted backup export and import.
- Bilingual UI (English / Simplified Chinese).
