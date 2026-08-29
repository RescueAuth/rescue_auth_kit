# PHASE3_REPORT.md — Phase 3 Architecture Reset & 3A/3B/3C/3D 实现报告

> 本文档是 Phase 3 架构重置 + Phase 3A + Phase 3B + Phase 3C + Phase 3D 的历史实现报告，
> 记录当时的 3D 验收证据并供后续回归参考。当前状态以根目录契约文档为准。Phase 3A 已实现并合入评审 PR（PR #18）；Phase 3B
> Encrypted Package Codec 已实现（PR #22）；Phase 3C Transactional Import /
> Merge Apply 已实现（独立 PR）；Phase 3D Android Export / Import + Package
> Preview 已实现（独立 PR，见 §12）。

> **文档性质**：这是 Phase 3 完成时的历史实现报告。报告正文中的阶段状态、
> “本轮不做”和 PR 语境用于审计；当前阶段状态以 `../ROADMAP.md`、
> `../AGENTS.md` 和 [`README.md`](../README.md) 为准。

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

- 同 stableId 且内容（TOTP fingerprint / recovery code values / **Developer
  任一用户语义字段**）不同 → **CONFLICT**（禁止 last-write-wins / 静默覆盖）。
  Developer Entry 的“内容”是 canonical **FULL LOGICAL PAYLOAD**（全部用户
  语义字段，见 §5.1），不是仅 sensitive payload。
- 不同 stableId 但 fingerprint 相同 → DUPLICATE（**仅限 TOTP / recovery
  set**；Developer Entry 见 §5.1，不同 stableId 一律 keep both）。

## 4. Package domain model

Phase 3A 在 `:core` 实现纯 JVM 逻辑模型（无 Android / Room 依赖）：

| 类 | 内容 |
| --- | --- |
| `VaultSnapshot` | 逻辑快照：accounts → totpCredentials / recoveryCodeSets → recoveryCodes；**+ developerEntries（五类 Developer Entry）+ scope（FULL_VAULT / AUTHENTICATOR_ONLY / DEVELOPER_ONLY / SELECTED_ITEMS）** |
| `VaultPackagePayload` | logicalSchemaVersion=1、packageId、createdAt、source metadata、snapshot |
| `PackageSourceMetadata` | client / appVersion / vaultInstanceId（非敏感技术字段） |
| `Canonicalization` | canonical 归一化 + semantic fingerprint（TOTP / Recovery / Account / **Developer Entry FULL LOGICAL PAYLOAD**） |
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
  **Developer 任一用户语义字段（FULL LOGICAL PAYLOAD）**不同 → 报告，不覆盖。
- **UNCHANGED**：destination-only 记录 → 永不删除。

### 5.1 Developer Entry merge（本轮对齐 ROADMAP §8.3）

Developer Entry 五类已纳入 shared merge foundation：

| 场景 | 决策 |
| --- | --- |
| 同 stableId + canonical **FULL LOGICAL PAYLOAD** 完全一致 | DUPLICATE |
| 同 stableId + **任意 user-meaningful logical field** 不同 | CONFLICT |
| 不同 stableId | INSERT / keep both（默认） |

- **FULL LOGICAL PAYLOAD** 覆盖各 Developer Entry 的**全部用户语义字段**，
  不仅限于 sensitive payload：
  - Android Signing Key：projectName / packageName / keystoreFileName /
    keystore 字节 / storePassword / keyAlias / keyPassword / title / notes；
  - API Credential：serviceName / accountName / apiKey / apiSecret /
    title / notes；
  - SSH Key：keyName / publicKey / privateKey / passphrase / title / notes；
  - Environment Variable Set：projectName / variable **names**+values /
    title / notes；
  - Generic Secret：field **labels**+values / title / notes。
- **`createdAt` / `updatedAt` 等纯技术 metadata 可排除**：仅当 FULL LOGICAL
  PAYLOAD 完全一致（可能只有 createdAt/updatedAt 不同）才判定 DUPLICATE。
- **Developer Entry 的 dedupe 只认 stableId**：full-logical-payload
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
- **同 stableId 时不得静默当作 DUPLICATE 的字段（任一不同即 CONFLICT）**：
  title / notes / projectName / packageName / serviceName / accountName /
  keyName / env variable names / generic field labels —— 这些是用户真实数据，
  不是纯 metadata。
- 不能因为 title / projectName / serviceName / keyName 相同就自动 dedupe。
- per-type 完整 canonical logical equivalence / per-type semantic identity
  **留到后续增强（Phase 3A 不实现）**：只有存在足够强、明确且经文档定义的
  per-type semantic identity 时才可把不同 stableId 判定为 DUPLICATE。
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

### 新增 Phase 3A 测试（`:core`，本轮累计 112）

Phase 3A 初始实现新增 36 个；本轮 compatibility CR（PR #19 对齐）新增 31 个；
Developer merge 保守化 CR 再新增 5 个 keep-both 用例；
**本轮 full-logical-equivalence blocker 修复再新增 6 个同 stableId CONFLICT 用例**（合计 112）：

- `MergePlannerTest`（35）：新增 Developer merge：同 stableId + FULL LOGICAL
  PAYLOAD 完全一致→dup；同 stableId 同 secret 异 title→conflict；同 stableId
  signing key 同 keystore 异 project/package→conflict；同 stableId API 同
  key/secret 异 service/account→conflict；同 stableId SSH 同 key 异
  keyName→conflict；同 stableId env 同 values 异 variable names→conflict；同
  stableId generic 同 values 异 labels→conflict；异 stableId 同 payload→keep
  both；异 stableId 异 payload→insert/keep both；二次 import no-op；signing
  key keystore 字节驱动 identity→conflict；destination-only 不删；同 keystore
  字节不同 project/package → keep both；同 SSH key 不同 logical usage/name →
  keep both；同 API key/secret 不同 service/account → keep both；Env sets 同
  值异 project/name → keep both；Generic secrets 同值异 label → keep
  both）+ Recovery used/unused divergence 4 个（dest=UNUSED source=USED
  →surfaced；dest=USED source=UNUSED→surfaced；同状态→无 divergence；跨独立
  stableId 仍 surfaced）。
- `CanonicalizationTest`（13）：`developerLogicalFingerprint`（FULL LOGICAL
  PAYLOAD）覆盖全部用户语义字段——异 keyName/title→异 fingerprint；异
  project/package→异 fingerprint；异 variable names→异 fingerprint；异 field
  labels→异 fingerprint；异 title→异 fingerprint；`createdAt`/`updatedAt` 纯
  技术 metadata→同 fingerprint（DUPLICATE 合法）；env/generic 顺序不敏感值敏感；
  API credential 用 key+secret+labels。
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
  **106（+5 CR 修正）** → **112（+6 full-logical-equivalence blocker 修复）**
  → **173（+61 Phase 3B codec）** 全绿。
- `:app:testDebugUnitTest`：35 → 36（+1 migration）全绿。
- `:app:lintDebug` 0 error；`:app:assembleDebug` / `:app:assembleDebugAndroidTest` 成功。

## 9. Phase 3B — Encrypted Portable Package Codec（已实现）

见 `docs/PACKAGE_FORMAT.md`（Phase 3B 契约）、`docs/ADRS/ADR-0007-portable-package-codec.md`、
`docs/THREAT_MODEL.md`。实现位于 `core/.../export/codec/`。

### 9.1 交付内容

- **`PackageFormat`**：字节布局常量、magic `RAKVPKG2`、版本号、算法 id、
  KDF DEFAULT PARAMETERS / ACCEPTABLE DECODE RANGE、格式级硬限制、
  `PackageCodecException` 错误分类。
- **`PackageLayout`**：字节偏移计算。
- **`PackageHeaderParser`**：header-is-untrusted 解析；magic/version/KDF 参数/
  长度在 Argon2 **之前**全部校验；无符号读取杜绝 overflow；trailing garbage 拒绝。
- **`PackageCrypto`**：SecureRandom 随机源 + 测试用 deterministic seam、
  Argon2id(v1.3)、XChaCha20-Poly1305 AEAD（wrap + payload）、best-effort zeroize。
- **`PortablePackageCodec`**：单一 codec（全部 SnapshotScope），
  `encode(payload, pin: String|CharArray|ByteArray): ByteArray` /
  `decode(bytes, pin): VaultPackagePayload`；纯 Kotlin/JVM，无 Android 依赖。
- **`PackageEnvelope`** / **`PayloadJson`**：parsed envelope（含 AAD 区域）与
  逻辑 payload JSON 序列化。
- **golden fixture**：`core/src/test/resources/codec-fixtures/v2_package_fixture_v1.bin`
  （test-only PIN，synthetic fake secrets）。

### 9.2 测试（Phase 3B 新增 61 个，`:core` 累计 173）

- `PortablePackageCodecRoundTripTest`（12）：empty / authenticator-only /
  developer-only / selected-items / full vault（五类 Developer）round-trip；
  binary keystore exact byte round-trip；recovery used/unused round-trip；
  同 payload 同 PIN 两次 export 不同但 decrypt 相同；salt/nonce 每 export
  重新随机；plaintext header 不泄漏 secret / 业务 metadata。
- `PortablePackageCodecAuthTest`（15）：wrong PIN、corrupted wrapped key /
  payload / nonce、AAD/header tamper、tampered version、headerLength、
  truncated、trailing garbage、bad magic、no partial plaintext、future
  version rejection、错误信息不区分 wrong PIN vs corruption。
- `PortablePackageCodecFormatTest`（20）：malicious huge memory/iterations/
  parallelism 在 KDF 前拒绝（`InvalidKdfParameters`）、KDF range 边界、
  cryptoVersion=1 的 `kdfOutputLength` 必须 == 32（16 / 64 拒绝）、异常长度、
  oversized package / wrapped key / payload、truncated header、
  无效逻辑 payload（`LogicalPayloadInvalid`）、非法 logical schema version。
- `PortablePackageCodecKdfPolicyTest`（7）：encode 写入默认参数；decode 读取
  package 内参数（更强参数可解密）；range 边界拒绝。
- `PortablePackageCodecPinRepresentationTest`（4）：String/CharArray/ByteArray
  PIN 互换；wrong PIN 失败。
- `PortablePackageCodecGoldenFixtureTest`（4）：golden fixture 与 codec 输出
  逐字节一致；fixture 可解密为预期 payload；wrong PIN 失败；deterministic
  seam 稳定。

### 9.3 merge 前 blocker 修正（codec contract，Phase 3B CR）

merge 前 review 提出的 3 个 codec contract blocker 已按最小修正完成，
未进入 Phase 3C：

**1. KDF output length（cryptoVersion=1 固定 32）**

- 确认 **cryptoVersion=1 不存在额外的 “KDF-output → 32-byte KEK” 派生步骤**：
  Argon2id 输出直接作为 XChaCha20-Poly1305 wrapping KEK。
- `kdfOutputLength` 现按 cryptoVersion 分派校验，cryptoVersion=1 **只接受 32**
  （`PackageFormat.KDF_OUTPUT_LENGTH_FOR_CRYPTO_V1`）；旧 loose range 16..64
  已删除 —— 16 / 64 等 codec 无法消费的长度在 KDF 前以
  `InvalidKdfParameters` 拒绝。
- 写入 PACKAGE_FORMAT §Key derivation / ADR-0007 §5；未来若新增 cryptoVersion
  并引入真正派生步骤，必须定义规则 + 补 compatibility tests + fixture。

**2. Package capacity consistency（logical ↔ package 一致）**

- 新增 `PackageCapacity`（单一来源）：整包 16 MiB、header 4 KiB、ciphertext
  16 MiB − 4 KiB、序列化 payload ciphertext − 16B tag、wrapped key 512B。
- `PackageFormat` 全部委托 `PackageCapacity`；`PackageValidator` 新增
  `MAX_SERIALIZED_PAYLOAD_BUDGET`，用 `estimateSerializedSize`（可证明上界）
  在编码前拒绝超预算 payload —— **validator 接受 ⇒ 必然可编码**。
- `PortablePackageCodec.encode` 序列化后精确守卫，超限抛
  `PackageCodecException.PackageTooLarge`（显式、安全失败，绝不 OOM）。
- 包上限保持 16 MiB（encode 峰值内存 ≈ 50–60 MiB，手动 export 可接受；提到
  32 MiB 会翻倍峰值且无安全收益）；逻辑限制调整为与包上限一致。
- 边界测试：single 12 MiB keystore 预算内；两个 8 MiB keystore 超预算被
  validator 拒绝；18 MiB keystore（bypass validator）encode 抛
  `PackageTooLarge`；ciphertext / 整包超上限 → `MalformedPackage`。

**3. Argon2 runtime DoS budget（FORMAT HARD LIMIT ≠ RUNTIME POLICY）**

- 新增 `PackageRuntimePolicy`（production default decoder 资源预算）：
  memoryKiB ≤ 128 MiB、iterations ≤ 8、memoryKiB×iterations ≤ 512K units
  （≈ 4× 默认 38912）、parallelism ≤ 8。
- `decode` 在 **Argon2 之前** 调 `checkDecodeBudget`；结构合法且在格式硬限制内
  但超预算的参数（如 200 MiB/2 iter、12 iter/19 MiB、100 MiB×6 iter）以
  `InvalidKdfParameters` 拒绝，绝不执行。
- app-generated default（19 MiB / 2 iterations）**永远通过**，正常兼容；
  预算内更强参数（64 MiB / 3 iter）正常解码。

**新增测试（:core 173 → 193）**：`PortablePackageCodecCapacityTest`（6）、
`PortablePackageCodecRuntimePolicyTest`（9）、`PackageCapacityTest`（4）、
`PortablePackageCodecFormatTest` 的 outputLength 用例改造（1 拆 2）。

## 10. Current roadmap

| Phase | 状态 |
| --- | --- |
| Phase 0 | CLOSED |
| Phase 1 | CLOSED |
| Phase 2 | CLOSED |
| Phase 3 | **STARTED** |
| Phase 3A | **PACKAGE + MERGE FOUNDATION**（已实现，PR #18，**CLOSED**） |
| Phase 3B | **ENCRYPTED PACKAGE CODEC**（已实现，PR #22，**CLOSED**） |
| Phase 3C | **TRANSACTIONAL IMPORT / MERGE APPLY**（已实现，PR CLOSED，见 §11 / ADR-0008） |
| Phase 3D | **IMPLEMENTED / PR OPEN**（Android manual Export/Import UI + SAF + import preview，见 §12） |
| Phase 4+ | P1 CLOSED；其余 NOT STARTED |

## 11. Phase 3C — Transactional Import / Merge Apply（已实现，PR CLOSED）

> 状态：**Phase 3A CLOSED / 3B CLOSED / 3C CLOSED / 3D IMPLEMENTED**。
> 本轮不做 SAF / Export UI / Import UI / PIN dialog / preview / conflict
> resolution UI / Developer UI（均属 3D 或后续 slice）。

### 11.1 Persistence schema audit（编码前审计）

Phase 3C 开始前的 Room schema（v2）只有 Authenticator 侧的表：

| 表 | 覆盖 |
| --- | --- |
| `auth_account` / `totp_credential` / `recovery_code_set` / `recovery_code` | Authenticator（Provider→Account→TOTP/Recovery） |
| `import_record` | 导入审计（sourceType/fingerprint/counts） |

**结论**：Room **尚无 Developer persistence**。Portable logical schema
（`VaultSnapshot`）已能表达五类 Developer Entry（3A），但本地 Room 无法
落库。若 3C 只 apply Authenticator、Developer skip，会破坏 Native Package
Import 的“完整 Vault merge”正式语义 —— 因此本 PR 内做最小 schema 增补
（v2→v3），使五类 Developer Entry 真实落库，**不因此开始 Developer UI**。

### 11.2 Developer persistence 设计（方案 A：单表 + typed payload）

新增 `developer_entry` 表：

- `id`（Room 本地主键）、`stableId`（UNIQUE，portable lineage）、
  `entryType`（五类枚举名，索引）、`title` / `notes` / `createdAt` /
  `updatedAt` / `sortOrder`；
- `payloadJson`：类型化逻辑载荷（平台中立 sealed serializer 的 JSON）。

选择方案 A（而非 base + per-type 五表）：五类仅差 3–6 个类型字段，全量
merge 场景下方案 B 需要 5 组 FK/DAO/mapper 无数据建模收益。

- **安全**：storePassword / keyPassword / keystoreBase64 / apiKey /
  apiSecret / privateKey / passphrase / env values / generic values 只存在
  于 SQLCipher DB 内；不创建明文 sidecar；不写日志。
- **mapper**：`DeveloperMappers` 是 Room ↔ logical 的唯一桥梁；Room entity
  不渗透进 shared package/merge core（ROADMAP §9 / AGENTS 架构边界 3）。

### 11.3 Room migration（v2 → v3）

- `RescueAuthDatabase.version = 3`，`MIGRATION_2_3` 非 destructive：仅
  `CREATE TABLE developer_entry` + `stableId` UNIQUE 索引 + `entryType` 索引。
- 既有 TOTP / Recovery / ImportRecord 数据与 stableId lineage 全部保留。
- Phase 2 SQLCipher / VaultKey 安全模型不变。
- schema JSON `3.json` 导出；migration 测试验证旧库数据保留 + Developer
  表可用。

### 11.4 Transactional apply API

```
VaultPackagePayload → PackageValidator → MergePlanner
        → MergePlanApplicator（单 Room 事务）→ local Vault
```

- `MergePlanApplicator`：唯一执行 `MergePlan` 的边界；只接收已校验的
  logical snapshot + plan；不依赖 legacy / codec / SAF。
- `VaultRepository.applyMergePlan(payload)`：Native Package Import 完整链路
  （validate → plan → preflight → apply 全部在一个串行事务内）。
- `VaultRepository.applySnapshot(snapshot, packageIdentity)`：共享事务
  boundary，供 future Legacy / otpauth-migration adapter 复用。
- 结果：`ImportOutcome.Applied(ApplyResult)` 或 `ImportOutcome.Blocked(...)`。
- 整个 apply 在**一个 Room transaction** 内（mutex + `withTransaction`）；
  中途失败全量 rollback；成功一次性落库；DUPLICATE 不重复插入；
  destination-only 数据绝不删除。

### 11.5 Parent / child identity resolution

- `ResolvedProviderMapping`（source account stableId → destination account
  stableId）与 `ResolvedAccountMapping`（→ destination Room id，实际 FK）。
- 语义 dedupe 后，package 中属于 source Account A 的新 TOTP / Recovery
  Set 插到已解析的 destination Account B 下；不创建重复 Account A、不用
  source Room id、不丢 parent relation。

### 11.6 CONFLICT / divergence 行为（保守，不自作主张）

- 任何 unresolved CONFLICT → **不 apply**，返回 `ImportOutcome.Blocked`。
- 任何 Recovery used/unused state divergence → **不 apply**（`stateDivergences`）。
- 不 source wins / destination wins / last-write-wins / 静默 overwrite。
- 3D preview/UI 后续负责让用户确认冲突处理。
- 已由 MergePlanner 明确可自动 apply 的语义（INSERT / DUPLICATE /
  metadata-only DUPLICATE）严格按 contract 执行。

### 11.7 stableId 处理

- 新 INSERT 对象保留 package/source stableId（lineage 跨 export/import
  保持），Room `id` 为本地主键（ADR-0004 两层概念）。
- 语义 DUPLICATE 用已有 destination 对象，不生成第二个 stableId。
- CONFLICT 不得自动改 stableId 伪装新数据绕过冲突。

### 11.8 Idempotence

- 同一 package 第一次 import → 插入新数据；第二次 → 全 DUPLICATE / no-op。
- 幂等性主要依赖 stableId / merge semantics，**不是** ImportRecord。
- 覆盖测试：TOTP / Recovery / Developer 五类 / mixed full vault /
  parent semantic dedupe 后 child insert 重复 apply。

### 11.9 ImportRecord 语义

- 只在事务成功时写 `V2_PACKAGE` record（sourceFingerprint = packageId /
  timestamp / counts）；rollback 时不留 record。
- 不保存 PIN / plaintext secrets / 整个 decrypted payload；只记录必要
  metadata。

### 11.10 Rollback / failure injection

- `WriteSeam`（interface + `None`）是 repository/test dependency seam；
  生产无 debug-only failure 开关。
- 测试：Authenticator 成功后 Developer insert 失败 → 全部 rollback；
  半程失败 → DB 与 import 前完全一致；ImportRecord rollback。

### 11.11 Native / Legacy isolation

- `MergePlanApplicator` 不 import legacy parser / legacy crypto / native
  codec bytes/envelope / Android SAF/Uri；只接收已验证的 logical snapshot /
  plan。删除 Legacy compatibility layer 不影响 Native Package Import。

### 11.12 Changed files（Phase 3C）

- `app/.../database/DeveloperEntryEntity.kt` / `DeveloperEntryDao.kt`（新）
- `app/.../database/RescueAuthDatabase.kt`（version 3 + MIGRATION_2_3）
- `app/.../database/AuthAccountDao.kt`（`listAll` / `listIdAndStableId`）
- `app/.../domain/DeveloperModels.kt`（新，metadata 只读模型）
- `app/.../repository/DeveloperMappers.kt`（新，Room ↔ logical 唯一桥梁）
- `app/.../repository/MergePlanApplicator.kt`（新，事务 apply 边界）
- `app/.../repository/VaultRepository.kt`（`buildDestinationSnapshot` /
  `applyMergePlan` / `applySnapshot` / `ImportOutcome`）
- `app/.../schemas/.../3.json`（新，schema 导出）
- `app/.../test/.../MergePlanApplyTest.kt`（新，93 断言）
- `app/.../test/.../MergeTestData.kt`（新，测试数据）
- `app/.../test/.../RescueAuthDatabaseMigrationTest2To3.kt`（新）
- 文档：ADR-0008 / PHASE3_REPORT §11 / ROADMAP / AGENTS / CHANGELOG

### 11.13 Tests（Phase 3C）

`:app:testDebugUnitTest` 新增 96 个断言（MergePlanApplyTest 93 + migration 3）：

- Developer persistence：五类 round-trip；keystore binary exact round-trip；
  password/private key/value 字段 round-trip；migration v2→v3 保留既有数据。
- Basic apply：empty destination + full snapshot；Authenticator-only；
  Developer-only；selected-items。
- Parent resolution：duplicate Provider + new Account 不建重复 parent；
  duplicate Account + new TOTP；semantic Account dedupe 后 child 指向正确
  destination；inserted account 自身 stableId 为 parent。
- Merge behavior：INSERT applied；DUPLICATE no-op；CONFLICT blocks；
  destination-only preserved；Developer 异 stableId keep both；同 stableId
  相同 duplicate；同 stableId 变化 conflict。
- Recovery：used/unused divergence 双向 block，不静默丢失；同状态 duplicate。
- Idempotence：同 package 二次、mixed vault 二次、Developer 二次、parent
  semantic dedupe child insert 二次。
- Transactionality：failure halfway → complete rollback；Developer insert
  失败 → Authenticator changes rollback；ImportRecord rollback；blocked
  plan 不写 record；no partial DB state。
- Concurrency：两并发 import 串行提交无重复/半完成。
- Shared boundary：`applySnapshot` 走同一事务路径；ImportRecord 无明文
  secret。
- Persistence：apply → close/reopen → imported state 仍在。

### 11.14 Remaining Phase 3D scope（已交付，见 §12）

Phase 3D 已实现：SAF / file picker / Export UI / Import UI / package PIN
对话框 / preview screen / Import all（走同一 Merge Engine）。

仍属 3D 之后的 scope（不提前实现）：conflict resolution UI / Developer
UI / Selective Import UI；ImportRecord 报告展示；Sensitive-action fresh
re-auth → Phase 4 P4。

## 12. Phase 3D — Android Export / Import + Package Preview（已实现，PR OPEN）

> 状态：**Phase 3A CLOSED / 3B CLOSED / 3C CLOSED / 3D IMPLEMENTED / PR OPEN**。
> 本轮把 3A/3B/3C 接成真正的 Android 用户闭环（SAF 文件 + per-export PIN
> 对话框 + import preview + confirm transactional apply）。完整 PR 报告见
> PR body。

### 12.1 Architecture（strict boundary，Issue #1 §1）

```
Compose
  → ExportImportViewModel（coordinator，纯 JVM 可测）
  → ExportImportService（use-case，app 模块）
  → VaultRepository / PortablePackageCodec（复用 3A/3B/3C）
```

- UI 不直接访问 DAO；Composable 不直接调用 codec / Room。
- 复用：`PortablePackageCodec`（3B）、`PackageValidator` / `MergePlanner` /
  `PackageIdentifier`（3A）、`VaultRepository.applyMergePlan` /
  `buildConsistentExportSnapshot`（3C）。
- 未重新实现：crypto / package format / Argon2 / XChaCha20 / serialization /
  semantic fingerprint / merge rules / transactional apply。
- 未创建 GenericImporter / GenericEncryptedFileImporter —— Native 与 Legacy
  import 完全隔离（ROADMAP §9）。

### 12.2 SAF export flow（Issue #1 §5 / §8）

```
Export Vault（Settings → Backup/Transfer）
  → per-export PIN 对话框（输入 + 确认，隐藏/短暂 reveal，PIN 先于文件）
  → SAF CreateDocument（.rakpkg，MIME hint）
  → ExportImportService.encodeFullVaultExport
      = VaultRepository.buildConsistentExportSnapshot（FULL_VAULT scope）
        → VaultPackagePayload（packageId/createdAt/source metadata）
        → PortablePackageCodec.encode(pin)
  → SAF OutputStream 只写 encrypted package bytes
  → success/failure
```

- **顺序：PIN + confirm → CreateDocument → encode/write**。PIN 取消不会创建
  文件；SAF destination 取消也什么都不写（回到 AwaitingPin）——普通取消操作
  不留下空/无效 `.rakpkg` 文档（Issue #1 §8 / §28）。
- 快照一致性：`buildConsistentExportSnapshot` 在 repository mutex + 单
  Room 事务内构造（Issue #1 §17）。
- 只允许 encrypted package bytes 离开 App：不写 plaintext 临时文件、
  不把 decrypted snapshot 写 cache、不把 PackageKey/KEK/PIN 落盘、不写日志。
- 写失败：明确返回 export failure；best-effort delete 部分 document
  （provider 支持时），不 crash、不宣称成功（Issue #1 §8）。
- 取消任一步（PIN 取消 / SAF 取消）→ 不产生 error 状态。
- PIN 在确认后短暂保存在 ViewModel 内存字段，CreateDocument 选择完成后立即
  使用并 zeroize；cancel / session lock / dispose 同样 zeroize。

### 12.3 SAF import flow（Issue #1 §5 / §7 / §11 / §24）

```
Import Native Package（Settings → Backup/Transfer）
  → SAF OpenDocument（*/*）
  → readPrefix → PackageIdentifier（native magic 检查，非 native 立即拒绝）
  → package PIN 对话框（输入一次）
  → BoundedPackageReader.readBounded（流式增量读取，最多 16 MiB + 1，超限即拒）
  → PortablePackageCodec.decode(pin)
  → PackageValidator
  → VaultRepository.buildDestinationSnapshot（当前真实 destination）
  → MergePlanner.plan → safe ImportPreview
  → user confirm → VaultRepository.applyMergePlan（Phase 3C 最终 authority）
  → result
```

- SAF input 是不可信数据：不信任 MIME / displayName / extension /
  `OpenableColumns.SIZE`；最终以实际 package bytes + codec validation 为准
  （Issue #1 §7）。
- bounded reader 是纯 Kotlin / JVM 可测 utility（`:core`），SAF adapter
  保持很薄（Issue #1 §24）。
- wrong PIN / corruption 统一为 `AuthenticationFailed`，UI 文案为
  “Wrong PIN or corrupted package”，绝不显示底层 crypto exception。

### 12.4 Preview 模型 + secret-redaction（Issue #1 §12 / §25）

- `ImportPreview` 只含安全 summary：package metadata（scope / createdAt /
  source）、内容计数（Provider/Account / TOTP / recovery sets/codes /
  Developer 五类 counts）、merge summary（inserts / duplicates / conflicts /
  unchanged / stateDivergences）。
- Developer 条目只展示非敏感 metadata（type / title / project/service/key
  name），**不展示** TOTP secret / recovery values / API keys / SSH key
  内容 / passwords / env values / generic values / keystore bytes。
- 测试锁定：preview 的字符串形式不包含任何 secret。

### 12.5 Conflict / divergence UX（Issue #1 §13）

- preview 有 unresolved CONFLICT 或 recovery used/unused divergence →
  `blocked`：显示“Import cannot be applied automatically” + N conflicts /
  N state divergences；用户只能 Cancel / Back，不能点 Import 强推
  source/destination wins / overwrite all。
- confirm 时仍由 Phase 3C `applyMergePlan` 重新 validate / plan / preflight /
  apply —— preview plan 从不直接写库（Issue #1 §14）。若 destination 在
  preview 与 confirm 之间变化，最终 apply 会安全 replan / block。

### 12.6 Import session 敏感生命周期（Issue #1 §15 / §16 / §27）

- decode 后的 `VaultPackagePayload`（含完整 plaintext secret）只存在于
  `ExportImportService` 的内存 activeImportSession 中。
- cancel import / successful apply / session LOCKED → 清除引用。
- 不放入 SavedStateHandle / Bundle / rememberSaveable / disk cache /
  DataStore / Room 临时表；process/state recreation 不恢复 plaintext。
- App 在 import preview 期间 auto-lock → decrypted package session 失效，
  unlock 后要求重新执行完整 import decode 流程。

### 12.7 Per-export PIN UX / policy（Issue #1 §9）

- Export：输入 PIN + 确认，两次一致才能继续；Import：只输入一次。
- PIN 字段默认隐藏、可短暂 reveal；不写日志、不进 SavedStateHandle /
  Bundle / rememberSaveable / 持久化；优先 CharArray，完成/取消/failure 后
  best-effort 清理。
- 具体 charset / min length：PACKAGE_FORMAT 未正式定义 → **不写入 codec
  format**，只在 Android UI 层建立 `PinPolicy`（Product Policy constant）：
  6–128 位纯数字（保守最小安全策略）。最终报告中明确列出，不作为
  package-format requirement。
- **最终策略（merge 前收尾锁定）**：
  - **charset**：纯数字 ASCII `0-9`（`PinPolicy.isAllowedExportCharacter`）。
  - **minimum length**：6（`PinPolicy.MIN_PIN_LENGTH`）。
  - **maximum length**：128（`PinPolicy.MAX_PIN_LENGTH`，防御性）。
  - **validation location**：所有规则只在 `PinPolicy.validateExportPin` /
    `validateImportPin`（ExportImportViewModel 再叠加 defense-in-depth
    校验）。Composable 只把 `PinPolicy.Reason` 映射为字符串资源，**不内嵌
    任何 policy 常量**。
  - **Import 只拒绝空 PIN**：长度/charset 规则是 Export UI 产品策略，不是
    package-format requirement；历史/第三方包的任何 codec 合法 PIN
    （含短 PIN、非数字 PIN）都必须能导入。
- Export 流程顺序为 **PIN + confirm → CreateDocument → encode/write**，
  PIN 取消不会创建文件；写失败 best-effort 清理（`fileIo.deleteIfPossible`，
  失败不 crash）。

### 12.8 Sensitive-action re-auth boundary（Issue #1 §10）

- 本轮**不实现**完整 Sensitive Action Re-auth subsystem。
- Export orchestration 有清晰单一入口（`ExportImportViewModel` 的 export
  flow），以后 Phase 4 P4 可在 build snapshot / encode / write **之前**插入
  fresh Biometric/Device-credential re-auth gate，无需重写 Export flow。
- UI / 文档明确：fresh re-auth integration remains P4 dependency。
- **不把 Phase 3D 宣称为最终 security-complete export UX。**

### 12.9 Error taxonomy → Android UX（Issue #1 §19）

`ExportImportError` 覆盖：user cancelled / cannot open document / read failed
/ package too large / write failed / unsupported format / unsupported crypto /
malformed package / invalid KDF params / authentication failed / logical
payload invalid / session locked / unknown。UI 文案简洁、安全、可操作；
不显示 stack trace / raw URI / secret / ciphertext / KDF internals。

### 12.10 测试（Issue #1 §23–§27）

`:core` 新增：`BoundedPackageReaderTest`（9，含 16 MiB 边界 / 超限 / 空文档
/ lying SIZE / read failure）、`PackageIdentifierTest`（6，native/legacy
隔离）。合计 422 tests，0 failures。

`:app` 新增：

- `ExportImportServiceTest`（31）：empty vault Full Export；Authenticator
  export；Recovery export；五类 Developer export；binary keystore exact
  round-trip；entered PIN decode；wrong PIN 不能 decode；同 vault 同 PIN 两次
  export 不同字节；exported scope == FULL_VAULT；VaultKey 不在 package；写失败
  返回 error；PIN confirm mismatch（policy 层）；valid package read；16 MiB
  边界；>limit 拒绝；corrupted；wrong PIN；unsupported version；malformed；
  各种 preview（empty destination inserts / duplicate-only / mixed /
  conflict block / recovery divergence block / five-type counts / no secrets
  / selected-scope / authenticator-only / developer-only）；apply（preview→
  confirm→success；duplicate-only no-op；conflict block；apply failure 无
  部分 DB 变更；重复导入幂等；**preview 后 destination 变化 → confirm 重新
  plan 并安全 block**；**ImportRecord 只在成功路径写入**）；敏感生命周期
  （cancel clears session / apply clears session / lock invalidates / PIN 不
  进持久化 / 不序列化 payload）。
- `ExportImportViewModelTest`（15）：export cancel 无 success；export 只写
  encrypted bytes；write failure error；PIN policy；valid package → preview；
  invalid magic；empty document；user cancel；read failure；preview→confirm→
  apply；conflict block；duplicate-only；cancel/apply clears session；lock
  invalidates；PIN 不进 UI state。

> #43（apply → close/reopen DB → state persists）由 Phase 3C
> `MergePlanApplyTest`（`apply then close reopen keeps imported state`）已覆盖；
> #44/#45/#46 由 service + ViewModel 测试共同锁定。

### 12.11 FTL 期望

- 本轮修改 SAF / Compose / ContentResolver / 真实产品流程。本地已跑：
  `:core:test`、`:app:testDebugUnitTest`、`:app:lintDebug`、
  `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 全绿。
- 不主动运行 Firebase Test Lab；合入 main 后 changed-file gate 预计自动
  触发 FTL。
- instrumented 优先：ViewModel/orchestrator wiring、lock/session boundary
  （不自动化真实系统文件 picker）。

### 12.12 剩余依赖

- Sensitive-action fresh re-auth → Phase 4 P4
- Selective Export / Import UI → Phase 4 P5（同一 codec / package model，
  复用 `VaultPackagePayload` / `SnapshotScope`）
- Legacy Import UI → Phase 5
- conflict resolution 产品策略 → 后续单独设计

### 12.13 Changed files

见 PR body（Phase 3D Final Report §16）。
