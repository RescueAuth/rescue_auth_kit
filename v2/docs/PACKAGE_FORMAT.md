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
  accounts[]
    stableId / serviceName / accountName / favorite / notes /
    sortOrder / createdAt / updatedAt
    totpCredentials[]   stableId / secretBase32 / algorithm / digits / periodSeconds / createdAt
    recoveryCodeSets[]  stableId / title / createdAt / codes[]
      recoveryCodes[]   stableId / value / status / usedAt / sortOrder
```

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
| DUPLICATE | destination 已存在相同记录（同 stableId+同内容，或不同 stableId 但同 semantic fingerprint）→ 跳过，保留 destination 值 |
| CONFLICT | 同 stableId（lineage）但 secret / TOTP 参数 / recovery-code 值不同 → 报告，禁止 last-write-wins / 静默覆盖 |
| UNCHANGED | destination-only 记录 → 永不删除 |

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
12. MergeResult 结构化报告 inserted / duplicates / conflicts / unchanged / invalid。
