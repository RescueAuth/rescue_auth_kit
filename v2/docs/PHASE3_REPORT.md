# PHASE3_REPORT.md — Phase 3 Architecture Reset & 3A 实现报告

> 本文档是 Phase 3 架构重置 + Phase 3A 的实现报告，也是后续 3B/3C/3D 的
> 唯一事实依据。Phase 3A 已实现并合入评审 PR。

## 1. Phase 3 architecture reset

### 旧目标（Phase 2 遗留草案）

- “新备份协议 + BackupKey + 恢复套件 + 导入导出”；
- 自动备份（每次变更 / 每日 / 每周 / 每月）+ 保留策略 + 健康状态；
- WorkManager 复制“已加密快照”；BackupSnapshotSink / PRE_IMPORT checkpoint；
- BackupRecord 表记录每次备份；DataStore 存非敏感偏好；
- restore 语义隐含“覆盖当前 Vault”。

### 新目标（Phase 3 reset）

- 本机 Vault 与跨设备迁移是两个独立安全域；
- **Portable Export Package**：每次手动导出，用户为这一份数据包设置
  per-export PIN；
- 只支持 **manual Export / Import**；不做 automatic / scheduled /
  background / WorkManager / cloud sync / 自动上传 / checkpoint 文件；
- **Export Package 是 versioned logical vault package（可合并数据包）**；
- **Import is merge-first**；Restore = merge into empty vault；
- Backup / Restore / Migration / Merge 统一到
  Export Package → Import Package → Merge Engine。

### 被删除的旧 backup 假设

| 旧假设 | 处置 |
| --- | --- |
| 全局 Master Password / BackupKey 保护 v2 backup | REMOVE（per-export PIN 取代） |
| 自动备份（CHANGE/DAILY/WEEKLY/MONTHLY + 保留策略 + 健康状态） | REMOVE（manual only） |
| WorkManager 正式备份路径 | REMOVE（无 WorkManager 实现） |
| BackupSnapshotSink（onCheckpoint / onChange） | REMOVE |
| PRE_IMPORT checkpoint | REMOVE |
| BackupRecord 表 / BackupRecordDao / recordBackup / latestBackup | REMOVE（schema v2 删表） |
| DataStore（备份偏好） | REMOVE（未使用依赖） |
| restore 覆盖当前 Vault | REMOVE（merge-first） |

## 2. Old backup code audit

| Item | KEEP / REWORK / REMOVE | Reason | Action |
| --- | --- | --- | --- |
| `VaultRepository` | REWORK | 核心接口，删除 snapshot sink / backup 记录，保留串行 mutation | 本轮清理 |
| `BackupSnapshotSink` | REMOVE | 只服务自动备份快照，manual export 不需要 | 删除接口 + 调用点 |
| `BackupRecordEntity` | REMOVE | 自动备份台账，新模型无自动备份 | 删除实体 + 表 |
| `BackupRecordDao` | REMOVE | 同上 | 删除 DAO |
| `recordBackup()` / `latestBackup()` | REMOVE | 同上 | 删除方法 |
| `PRE_IMPORT` checkpoint | REMOVE | 自动备份 checkpoint，与 merge-first 冲突 | 删除 |
| `import_record` 表 | KEEP（REWORK） | 导入审计仍有用；`sourceType` 增加 `V2_PACKAGE` 预留 | schema v2 保留 + stableId |
| DataStore 依赖 | REMOVE | 仅服务旧备份偏好，未使用 | 删除依赖 |
| `BACKUP_FORMAT.md` | REMOVE（废弃） | 旧自动备份草案 | 重命名为 `.obsolete`，由 PACKAGE_FORMAT.md 取代 |
| `THREAT_MODEL.md` BackupKey 资产 | REWORK | 新模型无 BackupKey | 更新资产表 |
| `UPDATE_PROTOCOL.md` | KEEP | 与备份无关（更新协议），仅删除“WorkManager 每天自动检查”表述 | 本轮不改 |
| ADR-0003 §2 BackupKey / 后台备份冲突 | REWORK | 新模型无 BackupKey、无后台备份 | 更新备注 |
| `PRODUCT.md` 备份描述 | REWORK | 手动/自动 → manual export only | 更新 |
| `AGENTS.md` 阶段 3 描述 | REWORK | “新备份协议 + BackupKey” → “Export Package + Merge” | 更新 |
| `README*.md` / `CHANGELOG.md` | REWORK | 同步 Phase 3 状态 | 更新 |

## 3. Identity design

### 3.1 审计结论（现状）

| 问题 | 结论 |
| --- | --- |
| Room primary key 是否跨 Vault 稳定？ | **否**。`id` 是 per-install 随机 UUID（legacy 导入时 `UUID.randomUUID()`）。 |
| Export→Import→Export 是否保持 ID？ | 旧模型无 export 路径；若直接序列化 Room id 到包，跨设备导入会产生新 UUID → 不保持。 |
| 两台设备独立扫同一 TOTP 是否同 ID？ | **否**，两设备各自随机 UUID。 |
| 当前字段能否识别“逻辑同一条 credential”？ | 无现成字段；需 stableId + semantic fingerprint。 |

### 3.2 两层概念（Phase 3A 实现）

- **A. Stable record identity（lineage）**：新增 `stableId` 列（schema v2）。
  首次创建时生成并保持；Export→Import→Export 不变；pre-Phase-3A 行
  migration 回填为 `id`。数据库层 UNIQUE index 保证 stableId 唯一。
- **B. Semantic duplicate detection（fingerprint）**：两台设备独立扫描
  同一 TOTP → stableId 不同，但 canonical fingerprint 相同。

### 3.3 Canonicalization（`Canonicalization.kt`）

| 字段 | 归一化 |
| --- | --- |
| serviceName / accountName / title | trim + 空白折叠 + uppercase |
| secretBase32 | 去空白/`-` + uppercase（RFC 4648） |
| algorithm | trim + uppercase |
| digits / period | 原样 |

### 3.4 Fingerprint

- TOTP：`SHA-256("totp\0" + secret + algo + digits + period)`。
  **不含 issuer/accountName** —— 重命名不改变 credential 身份。
- Recovery set：`SHA-256(title + 各 code value)`，不含 status/usedAt。
- Account：`SHA-256(serviceName + accountName)` —— 仅用于分组，不作为
  “同一账号”的判定。

### 3.5 安全

- fingerprint 是 secret-derived 材料：按需计算、**不落库**、**不放入
  plaintext package header**（Phase 3B 契约）。
- 绝不把两个不同 secret 错误合并：不同 secret → 不同 fingerprint。

### 3.6 Conflict 定义

- 同 stableId 且内容（TOTP fingerprint / recovery code values / Developer
  敏感 payload）不同 → **CONFLICT**（禁止 last-write-wins / 静默覆盖）。
- 不同 stableId 但 fingerprint 相同 → DUPLICATE（**仅限 TOTP / recovery
  set**；Developer Entry 见 §5.1，不同 stableId 一律 keep both）。

## 4. Package domain model

Phase 3A 在 `:core` 实现纯 JVM 逻辑模型（无 Android / Room 依赖）：

| 类 | 内容 |
| --- | --- |
| `VaultSnapshot` | 逻辑快照：accounts → totpCredentials / recoveryCodeSets → recoveryCodes；**+ developerEntries（五类 Developer Entry）+ scope（FULL_VAULT / AUTHENTICATOR_ONLY / DEVELOPER_ONLY / SELECTED_ITEMS）** |
| `VaultPackagePayload` | logicalSchemaVersion=1、packageId、createdAt、source metadata、snapshot |
| `PackageSourceMetadata` | client / appVersion / vaultInstanceId（非敏感技术字段） |
| `Canonicalization` | canonical 归一化 + semantic fingerprint（TOTP / Recovery / Account / **Developer Entry 敏感 payload**） |
| `PackageValidator` | 版本校验 + 内部一致性（重复 stableId、非法 TOTP 参数、非法 base32、非法 status、**Developer 五类结构 + keystore base64/大小上限 + scope 一致性**） |
| `MergePlanner` | 纯 deterministic merge planner → `MergePlan` / `MergeSummary`（**覆盖 Authenticator + Developer Entry；Recovery used/unused divergence 显式输出**） |
| `MergePlan` / `MergeDecision` / `AccountMergePlan` / `TotpMergePlan` / `RecoverySetMergePlan` / `RecoveryCodeMergePlan` / `RecoveryCodeStateDivergence` / `DeveloperMergePlan` | 机器可读计划（Phase 3C 执行） |

留到 Phase 3B encryption envelope：magic / formatVersion / cryptoVersion /
KDF id / KDF params / salt / wrapped PackageKey / AEAD nonce / ciphertext /
AAD；以及 Argon2id + XChaCha20-Poly1305 实际 codec。

> **latest-main 对齐（PR #19 后）：** 本轮兼容性修正把 Phase 3A 与最新
> PRODUCT / ROADMAP（Issue #17 落定）对齐：Developer Vault 五类是正式
> v2 核心资产，`VaultSnapshot / VaultPackagePayload / shared logical
> domain` 现在即能表达完整 Vault（不只 TOTP + Recovery Codes）；binary
> keystore 以 `keystoreBase64` 安全携带；partial / selective snapshot
> 是 first-class 契约；Developer Entry 已进入 shared merge foundation。

## 5. Merge semantics

见 [PACKAGE_FORMAT.md §Merge](PACKAGE_FORMAT.md)。要点：

- **INSERT**：source-only 记录 → 新增（child 挂到匹配的 destination 或新建
  account）。
- **DUPLICATE**：同 stableId+同内容，或（仅限 TOTP / recovery set）不同
  stableId+同 fingerprint → 跳过。metadata 差异（title/favorite/notes/
  account label）一律 DUPLICATE，保留 destination 值。
- **CONFLICT**：同 stableId 但 secret / TOTP 参数 / recovery values /
  Developer 敏感 payload 不同 → 报告，不覆盖。
- **UNCHANGED**：destination-only 记录 → 永不删除。

### 5.1 Developer Entry merge（本轮对齐 ROADMAP §8.3）

Developer Entry 五类已纳入 shared merge foundation：

| 场景 | 决策 |
| --- | --- |
| 同 stableId + 同 canonical 敏感 payload | DUPLICATE |
| 同 stableId + 不同敏感 payload | CONFLICT |
| 不同 stableId | INSERT / keep both（默认） |

- **Developer Entry 的 dedupe 只认 stableId**：sensitive-payload
  fingerprint 仅用于同 stableId 时区分 DUPLICATE / CONFLICT；**不同
  stableId 不做指纹 dedupe**。
- **原因（保守 merge 原则）**：相同 secret / private key / keystore 字节 /
  env values / generic values 本身不能证明两条不同 stableId 的 Developer
  Entry 是同一条逻辑资产——同一个 API key 可能被用户按不同
  service/account 保存为两个用途；同一 SSH private key 可能对应不同
  server/usage；同一个 Android keystore 可被多个 project/package 使用；
  Env Var Set 相同 value 不代表 variable name/project 相同；Generic
  Secret 相同 value 不代表 label/语义相同。因此不得因敏感 payload 相同就
  静默丢掉一条记录。
- fingerprint 只含敏感 payload（keystore 字节 / apiKey+apiSecret /
  private key+passphrase / env values / generic field values）；
  title / projectName / serviceName / keyName / notes 不参与。
- 不能因为 title / projectName / serviceName / keyName 相同就自动 dedupe。
- per-type 完整 canonical logical equivalence / per-type semantic identity
  **留到后续增强（Phase 3A 不实现）**：只有存在足够强、明确且经文档定义的
  per-type semantic identity 时才可把不同 stableId 判定为 DUPLICATE，且
  不得只比较 sensitive payload，也不得静默丢掉 service/account/project/
  keyName/variable names/field labels 等语义信息。
- 目标：**宁可漏 dedupe，不要错误 dedupe 造成用户数据或语义丢失**。

### 5.2 Recovery used/unused divergence（本轮修正）

- set fingerprint 仍不含 status/usedAt；但 used/unused 是用户状态。
- destination=UNUSED & source=USED（或反向）→ `RecoveryCodeStateDivergence`
  显式输出，`MergeSummary.stateDivergences` 计数；不静默保留/覆盖。
- 不做复杂 CRDT / timestamp merge（保留 future 空间）。

### 5.3 Legacy / Native 隔离（本轮确认 + source 级锁定）

- `export` 包不再 import 任何 `com.rescueauth.v2.legacy` 类型（原先
  `PackageValidator` 复用 `legacy.TotpVerifier`，本轮改为共享层自带的
  `TotpParameters`）。
- `LegacyIsolationTest` 在 source 级锁定“shared logical / merge 层不得
  依赖 legacy 类型”。

## 6. Schema changes

### v1 → v2（最小 migration）

- `auth_account` / `totp_credential` / `recovery_code_set` /
  `recovery_code` / `import_record` 各加 `stableId TEXT NOT NULL`，
  回填 `stableId = id`，建 UNIQUE index。
- **删除 `backup_record` 表**（自动备份台账）。
- Room `version = 2`，`RescueAuthDatabase.MIGRATION_1_2` 实现。
- schema JSON 导出到 `app/schemas/`（此前 exportSchema=true 但未配置
  `room.schemaLocation` —— 本轮补齐）；v1/v2 schema 进入 debug assets
  供 migration 测试读取。

migration/schema 测试：`RescueAuthDatabaseMigrationTest`（数据保留 + 删表 +
UNIQUE index）。

## 7. Cleanup

已删除的旧 automatic-backup 抽象：

- `BackupSnapshotSink`（接口）+ `VaultRepository` 中全部 snapshot 调用点
- `BackupRecordEntity` / `BackupRecordDao` + Room entity/DAO 注册 + v1 表
- `VaultRepository.recordBackup()` / `latestBackup()`
- PRE_IMPORT checkpoint 逻辑
- DataStore 依赖（`androidx.datastore.preferences`，未使用）

## 8. Tests

### 新增 Phase 3A 测试（`:core`，本轮累计 106）

Phase 3A 初始实现新增 36 个；本轮 compatibility CR（PR #19 对齐）新增 31 个；
Developer merge 保守化 CR 再新增 5 个 keep-both 用例（合计 106）：

- `MergePlannerTest`（初始 14 + 本轮 11 + CR 修正 5 = 30）：新增 Developer
  merge 12 个（同 stableId 同 payload→dup；同 stableId 异 payload→conflict；
  异 stableId 同 payload→**keep both**；异 stableId 异 payload→insert/keep
  both；二次 import no-op；signing key keystore 字节驱动 identity→conflict；
  destination-only 不删；**CR 修正：同 keystore 字节不同 project/package →
  keep both；同 SSH key 不同 logical usage/name → keep both；同 API
  key/secret 不同 service/account → keep both；Env sets 同值异
  project/name → keep both；Generic secrets 同值异 label → keep both**）+ Recovery
  used/unused divergence 4 个（dest=UNUSED source=USED →surfaced；dest=USED
  source=UNUSED→surfaced；同状态→无 divergence；跨独立 stableId 仍 surfaced）。
- `CanonicalizationTest`（初始 8 + 本轮 4 = 12）：Developer fingerprint
  忽略 label 但用敏感 payload（fingerprint 仅用于同 stableId 比较）；
  keystore 字节/password 敏感；env/generic 顺序不敏感值敏感；API
  credential 用 key+secret。
- `PackageValidatorTest`（初始 14 + 本轮 11 = 25）：五类全验；重复 developer
  stableId；非法/超大 keystore base64；空 apiKey；空 env var key；
  DEVELOPER_ONLY / AUTHENTICATOR_ONLY / SELECTED_ITEMS 合法；scope 与内容
  不一致拒绝。
- `LegacyIsolationTest`（新增 1）：shared logical 层 source 级不得 import
  legacy 类型。
- `VaultSnapshotSerializationTest`（新增 4）：五类 Developer Entry + scope 的
  JSON round-trip；binary keystore 不丢；package payload round-trip。

### 新增 App 测试（1 个）

- `RescueAuthDatabaseMigrationTest`（1）：v1→v2 数据保留、删 backup 表、
  UNIQUE index 存在。

### 原有测试

- `:core:test`：34（legacy）→ 70（+36 初始）→ **101（+31 本轮）** →
  **106（+5 CR 修正）** 全绿。
- `:app:testDebugUnitTest`：35 → 36（+1 migration）全绿。
- `:app:lintDebug` 0 error；`:app:assembleDebug` / `:app:assembleDebugAndroidTest` 成功。

## 9. Current roadmap

| Phase | 状态 |
| --- | --- |
| Phase 0 | CLOSED |
| Phase 1 | CLOSED |
| Phase 2 | CLOSED |
| Phase 3 | **STARTED** |
| Phase 3A | **PACKAGE + MERGE FOUNDATION**（本轮实现） |
| Phase 3B | NOT STARTED（encrypted package codec：Argon2id + XChaCha20-Poly1305 + package header） |
| Phase 3C | NOT STARTED（transactional import/merge：MergePlan → Room apply + rollback + idempotency） |
| Phase 3D | NOT STARTED（Android manual Export/Import UI + SAF） |
| Phase 4+ | NOT STARTED |
