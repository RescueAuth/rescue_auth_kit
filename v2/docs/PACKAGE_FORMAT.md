# PACKAGE_FORMAT.md — v2 Export Package（逻辑契约）

> 状态：**Phase 3A contract（已定稿）**；Phase 3B 实现加密 codec。
> 本文取代旧 `BACKUP_FORMAT.md` 的“自动备份”草案，定义新的
> **Portable Export Package** 逻辑契约。

## 产品定义（Phase 3 reset）

本机 Vault 与跨设备数据迁移是两个**独立安全域**：

```
本机 Vault（Phase 2 不变，禁止修改）:
Android Biometric / Device Credential
    ↓
Android Keystore
    ↓
unwrap VaultKey
    ↓
SQLCipher Vault

Export Package（Phase 3）:
每次手动导出，用户为“这一份数据包”设置一个 Export PIN
    ↓
PIN + random salt → KDF → Key Encryption Key（wrap PackageKey）
    ↓
random 256-bit PackageKey → AEAD 加密逻辑 payload
```

**不存在**：全局 backup password、永久 master password、与 VaultKey 绑定的
backup password。每份 Export Package 由**独立的 per-export PIN** 保护。

**Manual export only（Phase 3 第一版明确不做）**：automatic backup、
scheduled backup、background backup、WorkManager backup、cloud sync、
自动上传、自动 checkpoint 文件。

## 核心语义

**Export Package 是一份 versioned logical vault package（可合并数据包）**，
不是 SQLCipher 数据库文件的副本，也不是只能覆盖恢复的完整镜像。

```
decrypt package → parse → validate → deduplicate → detect conflicts
    → transactionally merge into current Vault
```

- **Restore** = empty Vault + package → merge
- **Migration** = new phone empty Vault + old phone package → merge
- **Two-vault merge** = existing Vault A + package exported from Vault B → merge

Backup / Restore / Migration / Merge 最终统一到：
**Export Package → Import Package → Merge Engine**。不存在
replace-current-database 语义，不存在 delete-and-restore。

## Envelope（Phase 3B 实现，契约预留）

```
magic
formatVersion
cryptoVersion
KDF id
KDF parameters
salt
wrapped PackageKey
AEAD nonce
encrypted payload        ← VaultPackagePayload JSON（AEAD）
authenticated metadata / AAD
```

**禁止**直接把 Export PIN 作为 encryption key。每次 export 独立生成
random salt / random PackageKey / random nonce —— 即使两个 package 使用
相同 PIN，PackageKey 与 ciphertext 也必须不同。

**敏感业务 metadata**（account list / issuer / account names / source
information）必须放在 encrypted payload 内，不暴露在 plaintext header。

**wrong PIN 与 corrupted package 必须安全失败**，不得输出部分可信
plaintext。

> 优先复用项目已验证的密码学栈：Argon2id + XChaCha20-Poly1305
> （Phase 1 已锁定 BC 1.85 原生实现，见 ADR-0002）。

## Logical payload（Phase 3A 已实现）

`VaultPackagePayload`（`core/.../export/VaultPackagePayload.kt`）：

```
logicalSchemaVersion = 1
packageId
createdAt
source（client / appVersion / vaultInstanceId）
snapshot:
  scope            FULL_VAULT | AUTHENTICATOR_ONLY | DEVELOPER_ONLY | SELECTED_ITEMS
  accounts[]
    stableId / serviceName / accountName / favorite / notes /
    sortOrder / createdAt / updatedAt
    totpCredentials[]   stableId / secretBase32 / algorithm / digits / periodSeconds / createdAt
    recoveryCodeSets[]  stableId / title / createdAt / codes[]
      recoveryCodes[]   stableId / value / status / usedAt / sortOrder
  developerEntries[]
    (五类，sealed 子类型)
    android_signing_key        stableId / projectName / packageName /
                               keystoreFileName / keystoreBase64 / storePassword /
                               keyAlias / keyPassword / title / notes / createdAt / updatedAt
    api_credential             stableId / serviceName / accountName / apiKey /
                               apiSecret / title / notes / createdAt / updatedAt
    ssh_key                    stableId / keyName / publicKey / privateKey /
                               passphrase / title / notes / createdAt / updatedAt
    environment_variable_set   stableId / projectName / variables[key,value][] /
                               title / notes / createdAt / updatedAt
    generic_secret             stableId / fields[key,value][] /
                               title / notes / createdAt / updatedAt
```

> **Developer Vault 五类是正式 v2 核心资产**（ROADMAP §8.2）：`snapshot`
> 必须能表达完整 Vault（Authenticator + Developer 五类），不只是 TOTP +
> Recovery Codes。`developerEntries` 是 first-class 字段，不是 optional
> extension。

> **Android keystore 是 binary asset**（ROADMAP §6 / §8.2）：keystore
> 内容以 `keystoreBase64`（RFC 4648 base64）携带，`PackageValidator` 校验
> base64 有效性 + 大小上限（`MAX_KEYSTORE_BASE64_LENGTH`）。binary
> keystore **只能**属于 encrypted payload，不得暴露在 plaintext package
> header。

> **Selective snapshot**（ROADMAP §8.4 / §17）：`scope` 声明快照携带的
> section。partial / selected-items 快照是 first-class 契约；
> `PackageValidator` 校验 scope 与实际内容一致（AUTHENTICATOR_ONLY 不得
> 携带 developer entries，反之亦然）。Selective Import 走同一 Merge
> Engine，不产生第二套实现。

## Identity（Phase 3A 已实现，见 ADR-0004）

- **Stable record identity**：`stableId` 是逻辑记录 ID，跨 Export→Import→
  Export 保持。Room primary key 是 per-install 随机 UUID，**不是**跨设备
  稳定 identity。schema v1→v2 migration 把 pre-Phase-3A 行的 `stableId`
  回填为 `id`。
- **Semantic fingerprint**：两台设备独立扫描同一个 TOTP QR 时 stableId
  不同，但 canonical fingerprint（secret + algorithm + digits + period）
  相同 → 判定 duplicate。指纹只按需在 merge/import 时计算，**绝不落库**，
  **绝不放入 plaintext header**。

## 版本化与兼容

- `logicalSchemaVersion`：读取方必须拒绝未知新版本，不得猜测解析
  （与 legacy import 的 version 校验同一策略）。
- `formatVersion` / `cryptoVersion`：Phase 3B 在 envelope 中版本化；
  一旦以某个版本发布，算法不得在同一版本下改变。

## Legacy 边界（保持 Phase 1 不变）

```
legacy v1 package → legacy password decrypt → logical records → Merge Engine → v2 Vault
v2 Export Package → per-export PIN decrypt → logical records → Merge Engine → v2 Vault
```

旧 Flutter / legacy 数据需要 Master Password 解密，这是 **Legacy Import**，
**不是**新 v2 Export Package 的密码模型。不要为了兼容 legacy 又把
Master Password 引入 v2 Vault。

## Merge 语义（Phase 3A 已实现，见 ADR-0005 / MergePlanner）

| 决策 | 定义 |
| --- | --- |
| INSERT | source 记录在 destination 不存在 → 新增 |
| DUPLICATE | destination 已存在相同记录（同 stableId+同内容；或——仅限 TOTP / recovery set——不同 stableId 但同 semantic fingerprint）→ 跳过，保留 destination 值。**Developer Entry 不适用跨 stableId 判定**（见下） |
| CONFLICT | 同 stableId（lineage）但 secret / TOTP 参数 / recovery-code 值 / **Developer 任意用户语义字段**不同 → 报告，禁止 last-write-wins / 静默覆盖 |
| UNCHANGED | destination-only 记录 → 永不删除 |

### Developer Entry merge（ROADMAP §8.3，Phase 3A foundation 已纳入）

Developer Entry 五类全部进入 shared merge foundation，每类至少具备**保守、
可扩展的 insert / duplicate / conflict 基础语义**：

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
- **Developer Entry 的 dedupe 只认 stableId**：只有同 stableId（同
  lineage）才可能判定为 DUPLICATE / CONFLICT。full-logical-payload
  fingerprint 仅用于同 stableId 时区分“FULL LOGICAL PAYLOAD 完全一致 →
  DUPLICATE”与“任意用户语义字段不同 → CONFLICT”。
- **不同 stableId 默认 INSERT / keep both**：相同 secret / private key /
  keystore 字节 / env values / generic values **本身不能证明两条不同
  stableId 的 Developer Entry 是同一条逻辑资产**。同一个 API key 可能被
  用户按不同 service/account 保存为两个用途；同一 SSH private key 可能
  对应不同 server/usage；同一个 Android keystore 可被多个
  project/package 使用；Env Var Set 相同 value 不代表 variable
  name/project 相同；Generic Secret 相同 value 不代表 label/语义相同。
  因此**不得因敏感 payload 相同就静默丢掉一条记录**。
- **同 stableId 时不得因 title / notes / projectName / packageName /
  serviceName / accountName / keyName / env variable names / generic field
  labels 不同就静默当作 DUPLICATE**：这些是用户真实数据，任一不同即
  CONFLICT（不静默保留 destination、不静默覆盖为 source、不静默丢弃）。
- per-type 更复杂 semantic fingerprint / per-type semantic identity
  **留到后续增强（Phase 3A 不实现）**：只有当存在足够强、明确且经文档定义
  的 per-type semantic identity 时，才可把不同 stableId 判定为 DUPLICATE。
- 目标：**宁可漏 dedupe，不要错误 dedupe 造成用户数据或语义丢失**。

### Recovery used/unused divergence（用户状态，不静默丢弃）

- Recovery set fingerprint 不含 `status`/`usedAt`（一台设备标记 used 不会
  让后续 import 变成假 conflict）。
- 但 **used/unused 是用户状态，不是纯 metadata**：
  - destination=UNUSED、source=USED → 显式 divergence（`stateDivergence`）；
  - destination=USED、source=UNUSED → 显式 divergence（不能 un-use）；
  - 任何情况下 planner **不静默保留 destination 状态、也不静默覆盖为
    source 状态**。
- `MergePlan` 输出 `RecoveryCodeStateDivergence`，`MergeSummary` 计数
  `stateDivergences`；Phase 3C/3D 必须向用户呈现。
- 本轮不做复杂 CRDT / timestamp merge（保留 future 空间）。

强制不变式（已用 JVM 测试锁定）：

1. destination-only 数据绝不因 source 缺少而删除。
2. source-only 数据可以插入。
3. exact duplicate 去重，不生成重复记录。
4. 同一 package 重复 import，第二次应尽可能 no-op。
5. merge 尽可能 idempotent。
6. identity 相同且内容相同 → duplicate / skip。
7. identity 相同但 secret / 安全敏感内容不同 → conflict。
8. 无法可靠判断是否相同 → 宁可 conflict 或 keep-both，不误删。
9. parent / child 关系保持正确。
10. merge 必须 deterministic。
11. 真正写入数据库必须 transactional（Phase 3C 实现）。
12. MergeResult 结构化报告 inserted / duplicates / conflicts / unchanged / invalid / stateDivergences。

### Legacy / Native Import 隔离（ROADMAP §9，source 级锁定）

```
legacy parser/crypto/models   （`legacy` 包）
        ↓
shared logical snapshot / merge（`export` 包）
        ↑
native package codec          （Phase 3B，`export`/codec）
```

- 允许共享：`VaultSnapshot`（logical schema）、validation、`MergeEngine`、
  MergeResult。
- **shared logical / merge 层不得依赖 legacy 类型**：`export` 包不 import
  `com.rescueauth.v2.legacy` 任何类型（`LegacyIsolationTest` 在 source 级
  锁定）。
- 未来删除 Legacy Import ⇒ 删除 legacy compatibility layer，**不得要求
  重构 Native Package Import**。
