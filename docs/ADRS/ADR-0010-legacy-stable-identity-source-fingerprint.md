# ADR-0010：Legacy v1 import — durable-id-first stableId、Legacy 防御上限 与 logical validator 边界（Phase 5A CR）

- 状态：**Accepted**（Phase 5A CR，2026-08-09）
- 关联：`docs/PHASE5A_REPORT.md` §10/§11/§14、`docs/LEGACY_IMPORT.md` §9、
  ADR-0004（stable identity）、ADR-0005（merge planner）、ADR-0007（package codec）

## 背景

Phase 5A 把 legacy `.rakvault` 解码为 shared `VaultSnapshot`，供现有
`MergePlanner` / `VaultRepository.applySnapshot` 复用。merge 前兼容性 CR 发现
三个 blocker，本文档记录其修复决策：

1. **stableId 错误依赖 source file fingerprint**：旧方案
   `legacy:<sha256(sourceFingerprint)>:<kind>:<path-hash>` 对同一 `.rakvault`
   文件重复导入幂等，但**不同时间生成的两个 legacy backup** 即使包含同一个
   logical object，由于 encrypted file fingerprint 不同也会产生不同 stableId
   → 重复导入产生 spurious 新逻辑对象。
2. **Legacy 防御上限借用了 Native `.rakpkg` 16 MiB contract**：frozen v1
   `.rakvault` protocol 没有 16 MiB 上限，`readAsBytes()` 无限制读取整个文件；
   16 MiB 是 Native package（Phase 3B）的格式硬限制，不是 legacy protocol limit。
3. **缺少 frozen v1 producer provenance fixture**：Python（argon2-cffi +
   PyNaCl）fixture 与 Kotlin test-encoder 都是独立复刻实现，需要至少一份由
   **frozen v1.2.0 implementation** 实际产生的加密 `.rakvault` 来锁 producer
   interoperability。

## 1. durable-id audit（frozen v1.2.0，逐类型）

以 tag `legacy-v1.2.0` 的 `lib/core/vault/vault_models.dart`、`vault_session.dart`、
`vault_migrator.dart`、`vault_repository.dart` 为 source of truth。

| 对象 | 是否存在 legacy durable id | durable id 来源 | 证据 |
| --- | --- | --- | --- |
| Provider（schema 3 `providers[].id`） | ✅ 是 | 持久化 UUID v4 | `vault_session.dart` `addProvider`：`id: _uuid.v4()`，写入 `VaultData.providers` |
| Account（schema 3 `accounts[].id`） | ✅ 是 | 持久化 UUID v4 | `vault_session.dart` `addAccount` / `addCredentialAsNewProviderAndAccount`：`id: _uuid.v4()` |
| TOTP credential | ✅ 是 | 持久化 UUID v4 | schema 3：`Credential.id`（`TotpCredential`）；schema 1/2：`totpEntries[].id`。`vault_session.dart` `addTotp` / `addCredentialAsNewProviderAndAccount` 均 `id: _uuid.v4()`；`vault_migrator.dart` 迁移 schema 1/2→3 时**保留** TOTP entry id（`id: id`） |
| Recovery Code Set | ✅ 是 | 持久化 UUID v4 | schema 3：`RecoveryCodesCredential.id`；schema 1/2：`recoveryCodeSets[].id`。`vault_migrator.dart` 迁移时**保留** recovery set id（`id: legacy.id`） |
| Recovery Code | ❌ **否** | 无 id，仅 `codes: List<String>` 位置索引 | `RecoveryCodesCredential` 只有 `codes`，无子 id |
| Developer Entry（schema 1/2/3 `developerEntries[].id`） | ✅ 是 | 持久化 UUID v4 | `vault_session.dart` `addDeveloperEntry`：`id: _uuid.v4()` |

结论：**除单个 Recovery Code 外，所有 legacy 对象都携带 durable persisted
identity（UUID v4）**。Recovery Code 是父 set 的有序列表子项，只能按
`set durable id + index` 做结构化 fallback。

## 2. 决策 A — stableId 必须优先基于 legacy durable identity

规则（用户要求）：

- **A. 存在 durable persisted identity** → stableId 由
  `namespace + object type + legacy durable id` deterministic derivation，
  **不依赖 source file fingerprint**。
- **B. 确实没有 durable identity**（只有 Recovery Code）→ 允许 deterministic
  fallback：`source fingerprint + structural path/index`。

本 ADR 采取比 Rule B 更进一步的方案：Recovery Code 也**不**用 source
fingerprint，而是用 **父 set 的 durable id + index**。理由：

- 同一 backup 集（相同 set durable id + 相同顺序）在不同加密备份中保持相同
  结构位置，因此 `set durable id + index` 足以跨备份稳定标识同一 code；
- 若用 source fingerprint，则两个不同加密备份中同一 recovery code 会产生
  不同 stableId，违反“同一逻辑对象跨备份稳定”的整体目标；
- source fingerprint 仍然保留，但仅作 **ImportRecord / source identity**，
  不进入 object identity（Rule B 允许的 fallback 是“可允许”，不是“必须”）。

### 2.1 最终 stableId derivation rule

```
stableId = "legacy:" + kind + ":" + b64url(sha256("legacy" + "\0" + kind + "\0" + durablePath))
```

- `namespace` = `legacy`（常量，防止与 Native v2 stableId 命名空间冲突）。
- `kind`：`account` / `totp` / `recovery_set` / `recovery_code` /
  `developer:<legacyType>`。
- `durablePath`：仅由 durable id / 结构化位置组成，例如：
  - schema 3 Account：`account:<accountId>`
  - TOTP（schema 1/2 entry 或 schema 3 credential）：`totp:<credentialId>`
  - Recovery Set：`recovery:<setId>`
  - Recovery Code：`recovery:<setId>/<index>`
  - Developer Entry：`developer:<developerId>`（kind 含类型）
- 同一 durable id 的对象，无论在哪一份 `.rakvault`（不同加密字节、不同
  source fingerprint）都得到**相同 stableId**。
- **不**把 plaintext secret / password / decrypted payload 放进 stableId
  （仅 SHA-256 摘要）。

### 2.2 逐类型最终规则

| 对象 | durable id | stableId derivation |
| --- | --- | --- |
| Provider | `providers[].id` | 不直接生成 v2 记录；作为 Account 的 serviceName 元数据。Provider 不单独进入 snapshot（v2 无 Provider 记录） |
| Account | `accounts[].id`（schema3）/ entry/set id（schema1/2 合成的 account） | `legacy:account:<b64url(sha256("legacy\0account\0account:<accountId>"))>` |
| TOTP credential | `credentials[].id` / `totpEntries[].id` | `legacy:totp:<b64url(sha256("legacy\0totp\0totp:<credId>"))>` |
| Recovery Code Set | `credentials[].id` / `recoveryCodeSets[].id` | `legacy:recovery_set:<b64url(sha256("legacy\0recovery_set\0recovery:<setId>"))>` |
| Recovery Code | ❌ 无 | `legacy:recovery_code:<b64url(sha256("legacy\0recovery_code\0recovery:<setId>/<index>"))>`（结构化 fallback：父 set durable id + index） |
| Developer Entry | `developerEntries[].id` | `legacy:developer:<type>:<b64url(sha256("legacy\0developer:<type>\0developer:<entryId>"))>` |

### 2.3 为什么

- 同一逻辑对象在不同时间/不同设备生成的 backup 中，durable UUID 保持不变
  （它属于对象本身，不属于容器文件）；只有加密字节/指纹会变。
- 用 durable id 派生 stableId → 跨备份幂等（重复导入同一 vault 的不同备份
  全 DUPLICATE，不产生新逻辑对象）。
- `legacy` 命名空间 + kind 前缀保证与 Native v2 记录（随机 UUID）不冲突，
  不同 legacy 对象类型之间不碰撞。
- 不同 durable id → 不同 hash → 不碰撞（SHA-256 抗碰撞）。

## 3. 决策 B — sourceFingerprint 的角色

```
fingerprintOfEncryptedBytes(bytes) = b64url(sha256(原始加密 .rakvault 字节))
```

- 保留作为 **legacy import source identity**（文件身份）。
- 供 Phase 5B `ImportRecord.sourceFingerprint` / ImportRecord identity 使用。
- **不再参与 object identity**：文件身份 ≠ 对象身份。一个文件里的多个对象
  共享同一 fingerprint，但对象身份由 durable id 唯一决定。

## 4. 决策 C — Legacy 防御上限（与 Native 16 MiB 解耦）

frozen v1 `.rakvault` protocol **没有 16 MiB 上限**：
`vault_repository.dart` `open()`/`importBytes()` 用 `readAsBytes()` 读取整个
文件，`VaultFile.decode()` 对全字符串 `jsonDecode`。16 MiB 是 Native
`.rakpkg`（ADR-0007 / PACKAGE_FORMAT.md）的格式硬限制，**不是 legacy
protocol limit**。

legacy 单独选择防御上限（ADR-0010 §4 / PHASE5A_REPORT §14）：

- **输入 `.rakvault` 上限：64 MiB**。
  - 依据：frozen v1 的 Developer Vault 可包含 Android signing keystore
    binary + 多个 Developer entry；base64url JSON envelope 携带数 MiB keystore
    是真实历史场景。64 MiB 对最大合理历史 vault 留有 ~4x 余量，同时限制
    整文件读取 + Argon2id working set 的峰值内存（防御性，不是格式契约）。
  - 明确**不是** Native `.rakpkg` 的 16 MiB（不复制 Phase 3B contract）。
- **解密后 payload 上限：64 MiB**（post-decrypt backstop）。
  - 依据：envelope 以 base64url 存 ciphertext（4/3 膨胀）+ 16B AEAD tag，
    64 MiB 输入不可能产生 >~48 MiB 明文，因此该上限对合法 envelope 实际
    不可达；保留为独立、有文档的 legacy 防御边界。
- KDF header 上限（256 MiB / 16 iter / p8 / hash 16..64 / salt 8..64 /
  nonce 24）在 Argon2 前校验，防 DoS（沿用 Phase 1，未变）。

## 5. 决策 D — logical validator 依赖边界

- **Legacy 映射后走纯 logical `VaultSnapshot` validation**
  （`PackageValidator.validate(VaultSnapshot)`），不经过 Native package
  serialized-size / 16 MiB capacity budget。
- 已提取公共入口 `PackageValidator.validateSnapshot(VaultSnapshot)`，Native
  Package 与 Legacy 共享 **logical validation**；package capacity budget
  （`validateCapacityBudget`，16 MiB 上限）只属于 Native package
  codec/validator 的 `validate(VaultPackagePayload)` 路径。
- **不改 `.rakpkg` format，不改其 16 MiB contract。**
- 依赖方向锁定：`legacy → shared logical`；shared logical 不 import legacy；
  legacy 不 import Native codec（`LegacySnapshotIsolationTest` 双向往返）。

## 6. 决策 E — frozen v1 producer provenance fixture

- 新增 `tools/legacy_fixtures_frozen/`：把 **frozen v1.2.0 的
  `vault_crypto.dart` + `vault_models.dart` 逐字节复制**，通过
  `VaultCrypto.encryptToFile` / `VaultFile.encode` / `VaultData.toJson`
  实际生产 `.rakvault` —— 这是 v1 应用写文件的同一生产代码路径，不是
  Python/Kotlin/独立 Dart 复刻。
- 输出两份**同一逻辑 vault 的不同加密备份**：
  - `frozen_v1_producer_schema3.rakvault`
  - `frozen_v1_producer_schema3_alt_backup.rakvault`（不同随机 salt/nonce → 不同指纹）
- fixture 只含 synthetic 测试材料（无真实 credential；keystore 为 8 合成字节）。
- 作用：锁 **actual producer interoperability**；独立 Python fixture 继续
  保留，负责 broad protocol/schema coverage。两类 fixture 作用不同。

## 后果

- **好处**：
  - 同一逻辑对象跨不同加密备份稳定（durable-id-first）；
  - 重复导入（含不同备份）全 DUPLICATE，无 spurious 新对象；
  - Legacy 防御上限有独立依据，不再误借 Native 16 MiB；
  - legacy 只经 logical validation，不因 Native capacity 被误拒；
  - frozen v1 真实 producer fixture 锁实际互操作。
- **代价**：
  - stableId 不再含 source fingerprint 命名空间 → 不同用户 vault 若恰好共用
    同一 UUID（v1 用 UUID v4，碰撞概率可忽略）会视为同一 lineage；这是
    durable-id-first 的固有取舍，已记录。
  - legacy 输入上限从 16 MiB 提到 64 MiB → 防御性放宽，仍限制峰值内存。
- **不做**：不改 `.rakpkg` format / 16 MiB contract；不写 `ImportRecord`；
  不做 UI；不改 Native codec / MergePlanner / apply 路径。
