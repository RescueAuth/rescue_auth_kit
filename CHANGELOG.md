# Changelog

All notable changes to RescueAuthKit are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **v2 重写（2026-08-06）**：仓库自 `main` 起进入 Android 原生重写阶段，
> 代码位于 `v2/`（Kotlin + Room/SQLCipher），旧 Flutter 应用冻结于 tag `v1.2.0`。
> 以下条目反映 v2 里程碑（phase 0/1/phase1-fix/phase2/phase2-blocker-hotfix/
> phase2-closure/roadmap-v2），均已合并进 `main`。

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
