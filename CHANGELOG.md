# Changelog

All notable changes to RescueAuthKit are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **v2 重写（2026-08-06）**：仓库自 `main` 起进入 Android 原生重写阶段，
> 代码位于 `v2/`（Kotlin + Room/SQLCipher），旧 Flutter 应用冻结于 tag `v1.2.0`。
> 以下条目反映 v2 里程碑（phase 0/1/phase1-fix/phase2/phase2-blocker-hotfix/
> phase2-closure/phase3a），均已合并进 `main`。

## [v2 phase3a] - 2026-08-07（PR #18）

Phase 3A — **Package + Merge Foundation**。Phase 3 架构重置：
Export Package 是可合并数据包，per-export PIN 保护，merge-first import。

### Added

- 纯 JVM 逻辑 package 领域模型（`:core` `export/`）：`VaultSnapshot` /
  `VaultPackagePayload` / `PackageSourceMetadata`（无 Android/Room 依赖）。
- `Canonicalization`：stable identity + semantic fingerprint（TOTP /
  recovery-set / account）与 canonical 归一化；fingerprint 不落库、不入
  plaintext header。
- `PackageValidator`：逻辑 schema 版本 + 内部一致性校验（重复 stableId /
  非法 TOTP 参数 / 非法 base32 / 非法 status），invalid source 拒绝整个包。
- `MergePlanner`：确定性纯 merge planner → `MergePlan` / `MergeSummary`
  （INSERT / DUPLICATE / CONFLICT / UNCHANGED），12 条强制 merge 语义。
- Room schema v1→v2 migration：5 表新增 `stableId`（回填 `id` + UNIQUE
  index），删除 `backup_record` 表。schema JSON 导出到 `app/schemas/`。
- 文档：`docs/PACKAGE_FORMAT.md`（新逻辑契约）、`docs/PHASE3_REPORT.md`、
  ADR-0004 / ADR-0005；THREAT_MODEL / PRODUCT / AGENTS / README* 同步。

### Changed

- `VaultRepository` 删除 BackupSnapshotSink / PRE_IMPORT checkpoint /
  recordBackup / latestBackup（自动备份模型移除）。
- 删除 `BackupRecordEntity` / `BackupRecordDao` / DataStore 依赖。
- `:core` 的 kotlinx-serialization 由 `api` 改为 `implementation`（避免污染
  app KSP classpath）。
- kotlinx-serialization 升到 1.8.1（与 Room 2.8.4 schema-JSON 读取器对齐，
  修复 KSP/Room `AbstractMethodError` classpath 冲突）。

### Removed（旧 automatic-backup 抽象）

- `BackupSnapshotSink`（onCheckpoint / onChange）
- `BackupRecordEntity` / `BackupRecordDao` + Room 注册 + `backup_record` 表
- `VaultRepository.recordBackup()` / `latestBackup()` / PRE_IMPORT checkpoint
- DataStore 依赖（`androidx.datastore.preferences`）
- `BACKUP_FORMAT.md`（重命名为 `.obsolete`，由 `PACKAGE_FORMAT.md` 取代）

### Verified

- `:core:test` 34 → **70**（+36 新增 Phase 3A 测试）全绿。
- `:app:testDebugUnitTest` 35 → **36**（+1 migration 测试）全绿。
- `:app:lintDebug` 0 error；`:app:assembleDebug` / `:app:assembleDebugAndroidTest` 成功。

### Roadmap

- Phase 0/1/2: CLOSED。Phase 3: STARTED。
- Phase 3A: **PACKAGE + MERGE FOUNDATION**（本轮）。
- Phase 3B: NOT STARTED（encrypted codec：Argon2id + XChaCha20-Poly1305）。
- Phase 3C: NOT STARTED（transactional import/merge）。
- Phase 3D: NOT STARTED（Android manual Export/Import UI + SAF）。

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
