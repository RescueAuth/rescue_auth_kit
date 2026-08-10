# PHASE5B_REPORT.md — Legacy v1 Android Import UI

> Phase 5B（Issue #1）—— 把 Phase 5A 的 **Legacy v1 Core Adapter** 正式接到
> Android daily-use import flow：`.rakvault` → SAF bounded read（64 MiB）→
> Master Password → decode → shared `VaultSnapshot` → MergePlanner preview →
> transactional apply。
>
> 关联文档：`docs/PHASE5A_REPORT.md`（core adapter）、`docs/LEGACY_IMPORT.md`
> （v1 契约）、`docs/PACKAGE_FORMAT.md`（Native package）、`docs/ADRS/ADR-0010`
> （durable-id-first stableId / source fingerprint）。

## 1. Existing Android import architecture audit

| 层 | 现有实现（Phase 3D / P1–P4） | 本轮结论 |
| --- | --- | --- |
| Native package import flow | `ExportImportRoute` → `ExportImportViewModel` → `ExportImportService` → `PortablePackageCodec` → `MergePlanner` → `VaultRepository.applyMergePlan` | **不修改**，作为 Native regression 基线 |
| Export/Import ViewModel/Service | `ExportImportViewModel` / `ExportImportService`（Native `.rakpkg` PIN-first） | 本轮**新增独立** Legacy VM/Service，不重构 Native VM |
| SAF OpenDocument 抽象 | `SafPackageFileIo` + `PackageFileIo` 接口 + `BoundedPackageReader`（16 MiB Native limit） | 新增 **`SafLegacyFileIo`** + `LegacyFileIo`，复用 `BoundedPackageReader` 但用 **64 MiB** Legacy limit |
| PackageIdentifier | `PackageIdentifier.identify(prefix)`（Native magic `RAKVPKG2`） | Legacy 不用它做安全验证；文件身份由 legacy importer/envelope（magic `RescueAuthKitVault` + KDF header + AEAD）决定 |
| import preview | `ImportPreview`（Native payload + MergePlan） | 新增 **`LegacyImportPreview`**（legacy bundle + snapshot + MergePlan），同样无 secret |
| MergePlanner | 纯逻辑，共享 | **完全复用**，不修改 |
| `VaultRepository.applyMergePlan` / `applySnapshot` | Native payload 入口 + shared snapshot 入口 | `applySnapshot` 新增 `sourceType` 参数，Legacy 走它 |
| ImportRecord | `ImportRecordEntity`（`sourceType` / `sourceFingerprint` / `stableId`） | Legacy 走 `sourceType="LEGACY_RAKVAULT"` + `sourceFingerprint`（encrypted-source SHA-256） |
| session lock cleanup | `ExportImportViewModel` session observer 清 Native 会话 | Legacy VM 有**同样**的 session observer 清 Legacy 明文状态 |
| Phase 5A Legacy classes | `LegacyRakVaultImporter` / `LegacyImportBundle` / `LegacyVaultSnapshotMapper` / shared `VaultSnapshot` | 本轮**只做 UI 接线**；core adapter 不动 |
| `VaultRepository` 对 `LegacyImportBundle` 的既有引用 | `importLegacy(bundle)`（Phase 1 spike，直接映射 Room） | **删除**，迁移到 dedicated `LegacyImportService` + `applySnapshot`（见 §3） |

## 2. Final Native / Legacy boundary

```
Native:  .rakpkg → PortablePackageCodec → VaultSnapshot → shared merge/apply
Legacy:  .rakvault → Legacy importer/mapper → VaultSnapshot → shared merge/apply
```

- **共享**：`VaultSnapshot`（shared logical）、`PackageValidator.validateSnapshot`
  （纯 logical）、`MergePlanner`、`VaultRepository.applySnapshot`（事务 apply）、
  safe MergePlan summary component（`LegacyImportPreview` / `ImportPreview` 各自
  safe summary，语义一致）。
- **不共享**：password/PIN validator、decoder、error enum、format identification
  state machine。
- **不创建** `GenericImporter` / `GenericEncryptedFile` / `GenericPassword` /
  `GenericDecoder`。Native PIN 与 Legacy Master Password 是两个独立概念。
- `PackageIdentifier` 可以用于 Native 文件识别；Legacy 文件身份由 legacy
  envelope/解码器验证（filename/extension/MIME 只是 UX hint）。

## 3. VaultRepository LegacyImportBundle dependency cleanup（架构清债）

- **删除**：`VaultRepository.importLegacy(bundle: LegacyImportBundle)`（Phase 1
  migration spike，直接 `LegacyToV2Mapper` → Room，不经过 shared MergePlanner）。
- **迁移**：Legacy 生产路径统一走 `VaultRepository.applySnapshot(snapshot, packageIdentity=sourceFingerprint, sourceType="LEGACY_RAKVAULT")`。
- `applySnapshot` 新增可选 `sourceType` 参数（默认 `"V2_PACKAGE"` 保持 Native
  语义不变），`MergePlanApplicator.apply` 也新增 `sourceType` 参数用于
  ImportRecord 写入。
- `ImportSummary` / `InvalidImportException`（旧 spike 遗留）已删除。
- 现在 `LegacyImportBundle` **只存在于 legacy compatibility path**（core
  legacy 层 + `LegacyImportService`），`VaultRepository` / `MergePlanner` /
  Native ExportImport flow **不再 import legacy-specific model**（由新增隔离
  测试锁定，见 §16）。

## 4. SAF / 64 MiB read behavior

- Legacy Import 使用 **OpenDocument**（`ActivityResultContracts.OpenDocument`），
  MIME `*/*`（`.rakvault` MIME 在 Android 不可靠，不作安全验证）。
- 选择文件后 `SafLegacyFileIo.readBounded` 复用 `BoundedPackageReader`，但
  **limit = `LegacyRakVaultImporter.DEFAULT_MAX_INPUT_BYTES`（64 MiB）**：
  - 增量读取到 EOF，读超过 64 MiB + 1 即拒绝（实际多读一个字节证明
    “over the limit”，不信任 `OpenableColumns.SIZE`）；
  - 超限 → `LegacyFileError.FileTooLarge` → **decrypt 前明确拒绝**；
  - 空文件 → `FileEmpty`；读失败 → `ReadFailed` / `CannotOpenDocument`。
- 不 read unbounded into memory；不使用 Native 16 MiB limit（Legacy 防御上限
  64 MiB 与 Native 16 MiB 解耦，Phase 5A ADR-0010 §6）。
- filename / extension 仅作 UX hint（`displayName`），不是 trust boundary；
  最终真实性验证由 legacy importer/envelope（magic + KDF header + AEAD）完成。

## 5. Legacy password policy

- **不得硬编码 `password.length >= 10`** 作为 import decoder gate（Frozen v1
  audit 确认是 v1 vault creation UI policy，不是 decoder crypto/file-format
  requirement）。
- `LegacyImportViewModel.submitPassword` 只拒绝空密码；任意长度密码都提交给
  Legacy decoder。
- **不复用** Native 6-digit PIN policy（`PinPolicy`）。
- Password：
  - 不进入 SavedStateHandle / rememberSaveable / Bundle / DataStore / Room /
    logs；
  - 以 `CharArray` 传递，submit 后 `fill('\u0000')` best-effort zeroize；
  - 只在 `LegacyImportService.decodeForPreview` 短暂转为 `String` 供
    Argon2id 派生，随后 `toCharArray().fill('\u0000')`。

## 6. Error taxonomy / UX

Legacy AEAD 无法严格区分 wrong password vs corrupted encrypted vault → UI 诚实
表达：

> “Unable to decrypt this legacy vault. The password may be incorrect or the
> file may be corrupted.”

zh-CN 对应安全文案（见 strings）。

其它 Legacy error 显式映射（`LegacyRakVaultImporter.ErrorKind`）：

| ErrorKind | UI error（EN/zh-CN） | Recovery |
| --- | --- | --- |
| AUTHENTICATION_FAILED | wrong password / corrupted | RETRY_PASSWORD |
| ARGON2_FAILED | wrong password / corrupted（安全合并） | RETRY_PASSWORD |
| UNSUPPORTED_SCHEMA | unsupported legacy schema version | RESTART |
| UNSUPPORTED_FORMAT | not a legacy vault / unsupported protection settings | RESTART |
| INVALID_ENVELOPE | damaged vault file | RESTART |
| MALFORMED_PAYLOAD / PAYLOAD_EMPTY / PAYLOAD_TOO_LARGE | invalid legacy data | RESTART |
| FILE_EMPTY | empty file | RESTART |
| FILE_TOO_LARGE | >64 MB legacy limit | RESTART |

不得显示：ciphertext detail、secret value、stack trace、decrypted JSON。
`LegacyMappingException` → “unsupported format / could not be read” RESTART。

## 7. Legacy UI state machine

```
Idle → FileSelected → AwaitingPassword → Decrypting → Preview → Applying → Success
   └────────────────────────── Error ──────────────────────────────┘
```

- 单一 sealed `State` 值，**非法组合不可能同时出现**（替换式 transition）。
- Error 区分 recovery：`RETRY_PASSWORD`（回到 password entry）、`RESTART`
  （回到 file selection，清全部明文）、`BLOCKED`（merge blocked，回 file
  selection）。
- state machine 纯 JVM 可测（`LegacyImportViewModelTest`）。

## 8. Safe preview

`LegacyImportPreview` 只含安全 metadata / 计数：

- Legacy v1 vault、schemaVersion 1/2/3、Providers/Accounts 数、TOTP 数、
  Recovery Sets 数、Recovery Codes 数、Developer 总数 + 五类分项计数、
  merge inserts / duplicates / conflicts / stateDivergences。
- 可显示安全 metadata：Provider name、Account name、Developer title/type。
- **不得显示**：TOTP secret/code、Recovery Code value、API key/secret、
  SSH private key/passphrase、Generic Secret value、Env var value、signing
  password、keystore bytes。
- 不为“让用户确认”把 secret 放进 preview（`LegacyImportPreview` 不持有任何
  secret 字段，`toString()` 不含 secret）。

## 9. MergePlanner / final re-plan

- Legacy 复用 **shared `MergePlanner`**，无 source-wins / replace / overwrite /
  whole-vault-restore。
- 不同 stableId → existing MergePlanner semantics；same logical duplicate →
  duplicate；same stableId differing Developer payload → conflict；Recovery
  used-state divergence → block。
- **`LegacyImportService.confirmImport` 不直接 apply preview plan**：调用
  `VaultRepository.applySnapshot`，它在**同一事务内**重新读当前 destination →
  当前 MergePlanner 重新 plan → preflight（conflict / divergence → block）→
  apply。preview 后 destination 变化 → 最终 plan 重新得出，新增 conflict /
  divergence → BLOCK。
- 集成测试 `preview then destination mutation then confirm re-plans and blocks`
  覆盖。

## 10. Cross-backup idempotence（UI → DB 贯通）

- Phase 5A 已做到 durable-id-first stableId。Phase 5B integration 证明：
  - 同一 backup 二次 import → 全 DUPLICATE（no duplicates）；
  - 不同加密 backup B（同 durable legacy object IDs）→ no duplicate
    Developer/TOTP/etc.
- 测试覆盖 TOTP、Recovery Set、Developer 五类（`LegacyImportServiceTest`）。
- 不在 Android UI layer 重新 randomize stableId（mapper 派生已确定性）。

## 11. Recovery migration semantics

- v1 无 USED/UNUSED 状态 → mapper 输出 `UNUSED` / `usedAt=null`。
- 不增加 “是否标记已使用” migration wizard；直接展示 migration summary，
  按 mapper 结果 merge/apply。
- destination 已有相同 logical set 但 v2 状态 divergence → MergePlanner block
  （集成测试覆盖）。

## 12. Developer five-type persistence

- Legacy UI 一次 migration 完整带入五类（Android Signing Key / API
  Credential / SSH Key / Environment Variable Set / Generic Secret）。
- 即使 P6 尚未提供 Signing Key / Env Var Set 完整 CRUD UI，它们也必须：
  - 正确进入 SQLCipher DB（`DeveloperEntryDao` typed payload）；
  - 正确进入 logical snapshot（`buildDestinationSnapshot` → Native Full Vault
    export 不丢失）；
  - 不因“当前 UI 不认识”被 drop。
- preview 只显示 type + title + count；不为显示它们实现 P6 CRUD。
- 集成测试：五类全部持久化 + keystore exact byte round-trip（
  `AAECAwQFBgc=` → 8 字节 `00 01 02 03 04 05 06 07`）。

## 13. ImportRecord wiring

- 审计现有 `ImportRecordEntity` / `ImportRecordDao`：已有
  `sourceType` / `sourceFingerprint` / `stableId`，**字段足够，不改 Room
  schema**（无 migration）。
- `LegacyImportService.confirmImport` 经 `VaultRepository.applySnapshot`
  → `MergePlanApplicator.apply(..., sourceType="LEGACY_RAKVAULT")`，同一事务内
  写 ImportRecord：
  - `sourceType = "LEGACY_RAKVAULT"`；
  - `sourceFingerprint = LegacyVaultSnapshotMapper.fingerprintOfEncryptedBytes`
    （原始加密 `.rakvault` 字节 SHA-256 base64url）；
  - `packageIdentity` = 同一 fingerprint。
- **仅 successful transactional apply 后记录**：cancel 不记录、decode failure
  不记录、merge blocked 不记录、apply rollback 不记录（单事务 + preflight）。
- source identity 使用 encrypted source fingerprint，不用 filename / Uri /
  plaintext hash / master password。
- `ImportRecordDao.findLatestByFingerprint` 可用于跨备份幂等审计。

## 14. Session / plaintext lifecycle

以下数据只保留于必要内存生命周期：password、decrypted `LegacyImportBundle`、
含 secret 的 `VaultSnapshot`、merge working data。

- cancel / successful import / session lock / switch file / fatal error /
  Activity/process recreation 时丢弃（`LegacyImportViewModel.clearSensitiveState`
  + `LegacyImportService.clearSession`）。
- 不保存到 SavedStateHandle / Bundle / Room temp table / DataStore /
  plaintext cache file。
- process recreation：允许安全地 restart Legacy import flow，不自动恢复
  密码 / decoded snapshot（VM 无 SavedStateHandle / rememberSaveable 持久化）。
- **Session lock**：file selected + password entered + preview visible 时锁 →
  清 password / decoded bundle / VaultSnapshot / merge preview / block Apply；
  unlock 后重新开始必要 decode/import flow（VM session observer 覆盖）。

## 15. Native regression

Native `.rakpkg` 流程（`ExportImportViewModel` / `ExportImportService` /
`PinPolicy` / `PackageIdentifier` / `ImportPreview` / `applyMergePlan`）**未修改**
（除 `applySnapshot` / `MergePlanApplicator.apply` 新增带默认值的 `sourceType`
参数——Native 调用方不受影响）。

- Native wrong PIN error / preview / full import apply 全部保持（既有
  `ExportImportServiceTest` / `ExportImportViewModelTest` 全绿）。
- Legacy password policy 不影响 Native PIN policy（`PinPolicy` 独立）。
- Legacy error type 不泄漏到 Native UI（`LegacyImportService` 与
  `ExportImportError` 完全独立，隔离测试锁定）。

## 16. Isolation tests（新增）

- `LegacyRepositoryIsolationTest`：
  - `VaultRepository` / `MergePlanApplicator` 不 import legacy types；
  - Native `exportimport` 不 import legacy types；
  - `legacyimport` 不依赖 Native codec / `ExportImportService` /
    `ExportImportViewModel` / `ExportImportError` / `PinPolicy` /
    `PortablePackageCodec`。
- core 已有 `LegacySnapshotIsolationTest`（legacy ↔ native codec 双向隔离），
  本轮未改 core。

## 17. Changed files

- **app production（新增）**：
  - `legacyimport/LegacyImportService.kt`（decode/map/preview + transactional
    apply + ImportRecord wiring）
  - `legacyimport/SafLegacyFileIo.kt`（64 MiB bounded SAF read + `LegacyFileIo`
    接口 + fake）
  - `legacyimport/LegacyImportViewModel.kt`（state machine + error taxonomy +
    session/plaintext lifecycle）
  - `ui/screens/legacyimport/LegacyImportRoute.kt`（SAF OpenDocument 接线）
  - `ui/screens/legacyimport/LegacyImportScreen.kt`（password entry + safe
    preview + result）
- **app production（修改）**：
  - `repository/VaultRepository.kt`（删除 `importLegacy(LegacyImportBundle)`
    spike；`applySnapshot` 新增 `sourceType` 参数）
  - `repository/MergePlanApplicator.kt`（`apply` 新增 `sourceType` 参数；
    ImportRecord 用 `sourceType`）
  - `repository/VaultAccess.kt`（`legacyImportService()` 生产 wiring）
  - `database/RecoveryCodeDao.kt`（新增 `listAll()` 供测试断言）
  - `ui/navigation/RescueAuthRoutes.kt`（`LEGACY_IMPORT` route）
  - `ui/RescueAuthApp.kt`（LegacyImportRoute 接线）
  - `ui/screens/settings/SettingsScreen.kt`（新增 “Import Legacy v1 Vault”
    入口，与 Native 分开）
  - `res/values/strings.xml` + `res/values-zh-rCN/strings.xml`（en/zh-CN）
- **app tests**：
  - `legacyimport/LegacyImportServiceTest.kt`（integration 13–26）
  - `legacyimport/LegacyImportViewModelTest.kt`（UI/security 1–12）
  - `legacyimport/LegacyRepositoryIsolationTest.kt`（isolation）
  - `ui/RescueAuthAppNavigationTest.kt`（Legacy entry separate from Native）
  - `repository/VaultRepositoryTest.kt`（迁移到 `applySnapshot`）
  - `src/test/resources/legacy-fixtures/phase5a/*.rakvault`（复制的 core fixture）
- **core production（修改）**：
  - `legacy/LegacyRakVaultImporter.kt`（新增 typed `ErrorKind`，非破坏性）
- **docs**：
  - `docs/PHASE5B_REPORT.md`（本文件）
  - `docs/LEGACY_IMPORT.md`（Phase 5B 接线契约）
  - `ROADMAP.md` / `AGENTS.md` / `CHANGELOG.md`（最小状态更新）

## 18. Tests

```bash
./gradlew :core:test               # PASS
./gradlew :app:testDebugUnitTest   # PASS
./gradlew :app:lintDebug           # PASS
./gradlew :app:assembleDebug       # PASS
./gradlew :app:assembleDebugAndroidTest  # PASS
./gradlew :app:assembleRelease     # PASS（app-release-unsigned.apk）
```

新增测试（app，~30）：

- `LegacyImportServiceTest`（integration）：
  - frozen-v1 schema3 fixture → preview；
  - schema1/schema3 apply into empty DB → accounts/totps persisted；
  - recovery migrate UNUSED/null usedAt；
  - five Developer types persisted + keystore exact bytes；
  - same file second import idempotent；
  - alternate encrypted backup same durable ids idempotent；
  - Developer conflict blocks；
  - Recovery divergence blocks；
  - preview → destination mutation → final re-plan catches conflict；
  - failed apply rolls back fully；
  - ImportRecord only on success + sourceFingerprint correct。
- `LegacyImportViewModelTest`（UI/security）：
  - file picker cancel；
  - oversized file blocked before decrypt；
  - any non-empty password submitted（no >=10 gate）；
  - password not persisted（preview 无 secret）；
  - wrong password/corruption safe retry error；
  - non-legacy file safe restart error；
  - preview safe counts/schema + no secrets；
  - session lock clears decoded state；
  - new file clears old state；
  - preview → confirm → success；cancel password。
- `LegacyRepositoryIsolationTest`：VaultRepository/MergePlanApplicator/Native
  flow/legacy flow 双向隔离。
- `RescueAuthAppNavigationTest`：Legacy entry 与 Native 分开、不进 Developer。

## 19. Release build status

`./gradlew :app:assembleRelease` 通过，输出 `app-release-unsigned.apk`
（未创建正式 signing key；production signing 不在本轮范围）。

## 20. FTL expectation

merge 到 main 后 `full-cloud-test-loop` 会触发 Firebase Test Lab（main push
gate）。**本轮不主动运行 Firebase Test Lab**；本地已完成 JVM/Robolectric/lint/
APK 构建验证。

## 21. Parallel conflict check with P5

另一条 PR 正在实现 **P5 — Native Selective Export / Import**，会主要修改：
`ExportImportViewModel/Route`、package scope UI、Native import selection、
snapshot selection/filter。本轮 Phase 5B：

- 只新增独立 `LegacyImportService` / `LegacyImportViewModel` /
  `LegacyImportRoute` / `LegacyImportScreen`；
- 只对 Settings/import hub、`RescueAuthRoutes`、`RescueAuthApp`、strings 做
  最小接线；
- **没有大规模重构 `ExportImportViewModel`**；
- 复用 preview 只抽取 shared logical safe-summary（语义一致，不抽
  `GenericImporter`）；
- `VaultRepository.applySnapshot` / `MergePlanApplicator.apply` 的 `sourceType`
  是带默认值的新增参数，不破坏 P5 对既有 API 的使用。

## 22. Remaining work after Phase 5

- **M2 — Developer 数据处理（legacy）**：~~默认“未导入 + 报告”，可选转只读
  secure note~~ **已废弃**。Phase 5B 正式契约：Legacy v1 Developer Vault 五类
  （Android Signing Key / API Credential / SSH Key / Environment Variable Set /
  Generic Secret）全部经 Legacy mapper → `VaultSnapshot` → shared merge/apply
  正常迁移并持久化（见 §12），不降级、不默认跳过。M2 不再代表“未导入”，
  仅作为后续 UI/UX 增强（例如迁移结果报告的呈现体验）保留。
- P6 — Android Signing Key / Env Var Set 完整 CRUD UI（数据已可经 Legacy
  import 与 Full Vault Export 持久化/携带；P6 只补这两类的 Android CRUD/UI，
  不是补 migration capability）。
- Native Selective Export / Import（P5，并行 PR）。
- Phase 6 L1/L2/L3 polish。

## 23. PR URL / branch / commits

- PR: <本 PR>
- Branch: `auto/phase5b-legacy-import-ui-<suffix>`
- Commit: <commit>
