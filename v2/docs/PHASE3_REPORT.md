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

- 同 stableId 且内容（TOTP fingerprint / recovery code values）不同 →
  **CONFLICT**（禁止 last-write-wins / 静默覆盖）。
- 不同 stableId 但 fingerprint 相同 → DUPLICATE。

## 4. Package domain model

Phase 3A 在 `:core` 实现纯 JVM 逻辑模型（无 Android / Room 依赖）：

| 类 | 内容 |
| --- | --- |
| `VaultSnapshot` | 逻辑快照：accounts → totpCredentials / recoveryCodeSets → recoveryCodes |
| `VaultPackagePayload` | logicalSchemaVersion=1、packageId、createdAt、source metadata、snapshot |
| `PackageSourceMetadata` | client / appVersion / vaultInstanceId（非敏感技术字段） |
| `Canonicalization` | canonical 归一化 + semantic fingerprint |
| `PackageValidator` | 版本校验 + 内部一致性（重复 stableId、非法 TOTP 参数、非法 base32、非法 status） |
| `MergePlanner` | 纯 deterministic merge planner → `MergePlan` / `MergeSummary` |
| `MergePlan` / `MergeDecision` / `AccountMergePlan` / `TotpMergePlan` / `RecoverySetMergePlan` | 机器可读计划（Phase 3C 执行） |

留到 Phase 3B encryption envelope：magic / formatVersion / cryptoVersion /
KDF id / KDF params / salt / wrapped PackageKey / AEAD nonce / ciphertext /
AAD；以及 Argon2id + XChaCha20-Poly1305 实际 codec。

## 5. Merge semantics

见 [PACKAGE_FORMAT.md §Merge](PACKAGE_FORMAT.md)。要点：

- **INSERT**：source-only 记录 → 新增（child 挂到匹配的 destination 或新建
  account）。
- **DUPLICATE**：同 stableId+同内容，或不同 stableId+同 fingerprint →
  跳过。metadata 差异（title/favorite/notes/account label）一律
  DUPLICATE，保留 destination 值。
- **CONFLICT**：同 stableId 但 secret / TOTP 参数 / recovery values 不同 →
  报告，不覆盖。
- **UNCHANGED**：destination-only 记录 → 永不删除。

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

### 新增 Phase 3A 测试（`:core`，36 个）

- `MergePlannerTest`（14）：empty+source→insert；dest+empty→unchanged；
  全同→duplicate；二次 import→no-op；union；不同 stableId 同
  fingerprint→duplicate；同 stableId 异 secret→conflict；metadata-only→
  duplicate；recovery parent/child；destination 不删；source 顺序不影响
  语义；deterministic；recovery-set conflict；inserted child 挂正确
  account。
- `CanonicalizationTest`（8）：secret/label 归一化；fingerprint 对
  secret/参数敏感、对 label 不敏感；recovery-set fingerprint 忽略
  status；SHA-256 格式。
- `PackageValidatorTest`（14）：版本拒绝；重复 stableId；非法算法/digits/
  base32/status；invalid source 不产生部分 plan；deterministic。

### 新增 App 测试（1 个）

- `RescueAuthDatabaseMigrationTest`（1）：v1→v2 数据保留、删 backup 表、
  UNIQUE index 存在。

### 原有测试

- `:core:test`：34（legacy）→ 70（+36 新增）全绿。
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
