# PHASE3_REPORT.md — Phase 3 Architecture Reset & 3A/3B 实现报告

> 本文档是 Phase 3 架构重置 + Phase 3A + Phase 3B 的实现报告，也是后续
> 3C/3D 的唯一事实依据。Phase 3A 已实现并合入评审 PR（PR #18）；Phase 3B
> Encrypted Package Codec 已实现（独立 PR）。

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
| Phase 3A | **PACKAGE + MERGE FOUNDATION**（已实现，PR #18） |
| Phase 3B | **ENCRYPTED PACKAGE CODEC**（本轮实现，PR OPEN） |
| Phase 3C | NOT STARTED（transactional import/merge：MergePlan → Room apply + rollback + idempotency） |
| Phase 3D | NOT STARTED（Android manual Export/Import UI + SAF） |
| Phase 4+ | NOT STARTED |
