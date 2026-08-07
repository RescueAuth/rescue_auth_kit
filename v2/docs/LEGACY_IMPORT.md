# LEGACY_IMPORT.md — 旧 `.rakvault` 导入契约

> 本文档描述 RescueAuth v2 对旧 Flutter 应用（RescueAuthKit v1.2.0，冻结 tag `v1.2.0`）
> 生成的 `.rakvault` 文件的只读导入规则、字段映射与兼容性 fixture。
> 属于不可逆执行契约的一部分，任何修改必须先更新本文件与相关测试。

## 1. 旧格式事实（只读）

| 项目 | 值 |
| --- | --- |
| 外层 magic | `RescueAuthKitVault` |
| 外层 version | `1` |
| KDF | Argon2id（`name: "argon2id"`） |
| 默认 KDF 参数 | memory 19456 KiB（19 MiB）、iterations 2、parallelism 1、hashLength 32 |
| salt 长度 | 16 字节 |
| Cipher | XChaCha20-Poly1305（nonce 24 字节） |
| payload | UTF-8 JSON，schemaVersion 1 / 2 / 3 |
| 文件扩展名 | `.rakvault` |

外层 envelope（JSON，字段名与 `lib/core/crypto/vault_crypto.dart` 一致）：

```json
{
  "magic": "RescueAuthKitVault",
  "version": 1,
  "kdf": {
    "name": "argon2id",
    "memoryKiB": 19456,
    "iterations": 2,
    "parallelism": 1,
    "hashLengthBytes": 32,
    "saltB64": "..."
  },
  "cipher": "xchacha20poly1305",
  "nonceB64": "...",
  "macB64": "...",
  "ciphertextB64": "..."
}
```

解密流程：header 校验 → Argon2id(password, salt) 派生 32 字节密钥 →
XChaCha20-Poly1305 解密（先验证 MAC）→ UTF-8 JSON。

## 2. Schema 1（v1.0.x）

顶层字段：

```json
{
  "schemaVersion": 1,
  "totpEntries": [ { "id", "issuer", "accountName", "secretBase32",
                     "algorithm", "digits", "period", "createdAt" } ],
  "recoveryCodeSets": [ { "id", "title", "codes": [String], "createdAt" } ]
}
```

无 Developer 数据字段。`developerSettings`/`developerEntries` 按缺省处理。

## 3. Schema 2（v1.1.x）

在 Schema 1 基础上增加：

```json
{
  "schemaVersion": 2,
  "developerSettings": { "enabled": bool },
  "developerEntries": [
    { "id", "type", "title", "notes", "createdAt", "updatedAt", "payload": {} }
  ]
}
```

`type` 枚举 JSON 名：`androidSigningKey` / `apiCredential` / `sshKey` /
`envVarSet` / `genericSecret`。

## 4. Schema 3（v1.2.0，账户中心模型）

```json
{
  "schemaVersion": 3,
  "providers": [ { "id", "name", "createdAt", "updatedAt" } ],
  "accounts": [
    {
      "id", "providerId", "displayName", "createdAt", "updatedAt",
      "credentials": [
        { "kind": "totp", "id", "createdAt",
          "secretBase32", "algorithm", "digits", "period" },
        { "kind": "recoveryCodes", "id", "createdAt", "codes": [String] }
      ]
    }
  ],
  "developerSettings": { "enabled": bool },
  "developerEntries": [ ...同 schema 2... ]
}
```

## 5. 映射规则：旧 schema → 新 schema v1

### 5.1 AuthAccount（新表）

旧模型没有直接的“账户”扁平对象。映射规则**按条目（entry-centric）**，
**禁止按 issuer 合并账户、禁止只保留首个 accountName**：

- **schema 1/2（TOTP）**：`totpEntries` 中**每条**条目 → **1 个独立 `AuthAccount`**。
  - `serviceName` = 该条目自己的 `issuer`（trim，空则 `"Untitled"`）。
  - `accountName` = 该条目自己的 `accountName`（trim，空则回退到 issuer 或 `"Account"`）。
  - 该条目自己的 `secretBase32`/`algorithm`/`digits`/`period` → 该账户下唯一的 1 条 `TotpCredential`（原样保留，禁止 normalize）。
  - 相同 `serviceName` **只允许在 UI 中分组**；不得合并账户、不得丢弃任何 accountName、
    不得把同 issuer 的多条合并到同一账户下的多个 credential。
  - `recoveryCodeSets` 每个 set → 1 个独立 `AuthAccount`：
    `serviceName` = set.title（空则 `"Recovery codes"`），`accountName` 同规则。
- **schema 3**：1 个 `Account` → 1 个 `AuthAccount`（保持映射不变）：
  `serviceName` = 其 `provider.name`，`accountName` = `account.displayName`；
  该 Account 下的 `totp` / `recoveryCodes` credentials 作为子行挂在该账户下。
- `favorite` = false（旧版无收藏概念）。
- `notes` = null。
- `sortOrder` = 导入顺序递增（0,1,2,...）。
- `createdAt`/`updatedAt` = 旧对象对应时间，缺失用导入时刻。
- `legacySourceId`：schema 1/2 为该条目的 id；schema 3 为 account.id。

### 5.2 TotpCredential（新表）

- schema 1/2：每条 `totpEntries[i]` → 1 条 `TotpCredential`（挂在**自己的** `AuthAccount` 下）。
- schema 3：每个 `kind=="totp"` credential → 1 条（挂在其所属 Account 的 `AuthAccount` 下）。
- `secretBase32` = 旧 `secretBase32`（原样，禁止 normalize 丢字节）。
- **非法 / 未知参数策略（phase 1 fix，禁止静默替换）**：
  - 未知或缺失 `algorithm`（非 SHA1/SHA256/SHA512）、`digits` ∉ {6,7,8}、
    `period <= 0`、或 base32 secret 非法 → **不得**被静默替换成 SHA1/6/30 后作为正常凭据导入
    （那样会生成与旧应用不一致的错误 TOTP）。
  - 正确做法：**保留原始字段**（不改写），标记为**不可用**，列入**未导入报告**
    （`ImportRecord.warningCount` + 报告列出 sourceId/issuer/accountName/原因/原始参数），
    不写入数据库。
  - 解析器与 validator 都不会回填默认值：缺失字段以 null 保留，由
    `LegacyTotpValidator`（`usability=invalid`）→ `LegacyToV2Mapper` 排除。
- `legacySourceId` = 旧 credential id。

### 5.3 RecoveryCodeSet / RecoveryCode（新表）

- schema 1/2：每个 `recoveryCodeSets[i]` → 1 个 `RecoveryCodeSet`。
- schema 3：每个 `kind=="recoveryCodes"` credential → 1 个 `RecoveryCodeSet`。
- `RecoveryCodeSet.title` = 旧 title / account displayName。
- `RecoveryCode.value` = 旧 codes 原样；`status` 全部 `UNUSED`（旧版无已使用状态）。
- `RecoveryCodeSet.legacySourceId` = 旧 set id / credential id。

### 5.4 Developer 数据（不静默丢弃）

v1 明确不做 Developer 密钥管理，因此：

- 导入预览必须显示 Developer 数据数量。
- 默认策略：**暂不导入**，写入 `ImportRecord.warningCount` 并生成"未导入报告"
  （列出条目 id/type/title），保留原始 `.rakvault`。
- 可选策略：转换为只读 `Legacy secure note`（不进 v1 主导航，仅只读展示）。
- 禁止将 Developer payload 写入任何日志、崩溃报告或截图 fixture。

## 6. 兼容性 Fixture 清单

位置：`v2/legacy-fixtures/`。由 `tools/legacy_fixtures` 生成，SHA-256 已固定，
**不得随意重新生成**（重新生成会使 SHA-256 变化，破坏兼容性基线）。

### 6.1 正向（必须成功解密且明文与 `expected/` 完全一致）

| 文件 | SHA-256 | 大小 | 说明 |
| --- | --- | --- | --- |
| `schema1/schema1_normal.rakvault` | `a4d8feda1c95af5e81c245979a3e9f7deedfa3b09c05a59f9a6fc386d0785480` | 1825 B | schema 1；5 个 TOTP（SHA1/SHA256/SHA512、6/8 位、period 30/60）+ 恢复码 |
| `schema2/schema2_normal.rakvault` | `4129e2d44808dee3763f8f6484ff98457517e51908886104d71ddc85a4910533` | 2505 B | schema 2；含 Developer 数据（apiCredential、sshKey） |
| `schema3/schema3_normal.rakvault` | `967b1a89499eabf2e4464156f9532b4a01610e55668c7a41beaa527b140dbbb7` | 2017 B | schema 3；providers/accounts/credentials + Developer |
| `schema1/rfc4226_sha1_secret.rakvault` | `a8d05fa6c1124be03d59424bf658eb6f70054b7a58eb6cb09de774d1d3ad4518` | 641 B | RFC 4226 测试向量 secret（`GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ`，SHA1/6/30） |
| `schema1/schema1_same_issuer_multi_account.rakvault` | `903910bcb3e860debe9981f301a191f3fb64f92f36c2bb14479e7277fa19348b` | 1317 B | schema 1；同一 issuer（GitHub）下 3 个不同 accountName（alice/bob/carol）+ 恢复码。**必须 1 条 → 1 个 AuthAccount** |
| `schema1/schema1_invalid_totp_params.rakvault` | `8b764df75e0cb7cb0dd4c35a3d42d51414f6d79a42a20b056c11508fcf3912b7` | 1877 B | schema 1；1 条合法 + 5 条非法参数（未知 algorithm MD5 / digits=0 / period=-30 / 缺 algorithm / 非法 base32）。**必须保留原始值并列入未导入报告，禁止静默替换** |

### 6.2 负向（必须安全失败，不得崩溃/不得改变当前数据库）

| 文件 | SHA-256 | 大小 | 说明 |
| --- | --- | --- | --- |
| `negative/wrong_password.rakvault` | `5de720373c235aa58c8c30e0501869b027141d61868a086ecc7f457e8fe681c2` | 2505 B | 错误密码 |
| `negative/tampered_ciphertext.rakvault` | `4b7584533b1984f06e965a2a5cfc7db054a9bcef470d3887cd988701a2d1dd6e` | 1825 B | ciphertext 被篡改 |
| `negative/truncated_ciphertext.rakvault` | `51d7041a96a9d6f307689a40508d99415e49f71d6a105cd2f40b256a875044ad` | 1785 B | ciphertext 截断（JSON 解析层即失败） |
| `negative/wrong_mac.rakvault` | `a696d1f0978e11a41c996756eb62be0a548570c19a9172d7bd62548f6e514850` | 1844 B | MAC 错误（AEAD 校验失败） |
| `negative/extreme_kdf_params.rakvault` | `a93bf914d93e06fe59af718363b3daec346425fd2977e15f4685d787fb818227` | 1827 B | header 声明 memoryKiB = 8 GiB；**必须在运行 KDF 前拒绝** |

> ⚠️ 实测：直接对 `extreme_kdf_params` 运行 8 GiB Argon2id 会因内存分配失败
> 使进程 abort（core dump）。这证明导入器**必须先校验 KDF 参数上限**，
> 绝不能在读 header 后直接执行 Argon2。

### 6.3 fixture 密码

| fixture | 密码 |
| --- | --- |
| `schema1_normal` | `test-password-1` |
| `schema2_normal` | `test-password-2` |
| `schema3_normal` | `test-password-3` |
| `rfc4226_sha1_secret` | `rfc-test` |
| `schema1_same_issuer_multi_account` | `test-password-1` |
| `schema1_invalid_totp_params` | `test-password-1` |
| 负向（除 wrong_password 外） | `test-password-1` |

密码仅用于测试工具与 Kotlin importer 测试；真实导入中密码由用户输入一次，不落盘。

## 7. 导入器 KDF 参数安全上限（实现前必须固化）

```text
memoryKiB:      1 <= m <= 262144   (256 MiB)
iterations:     1 <= i <= 16
parallelism:    1 <= p <= 8
hashLengthBytes: 16 <= h <= 64
saltB64:        解码后 8 <= len <= 64
nonceB64:       解码后必须为 24 字节（XChaCha20-Poly1305）
```

超限 → `LegacyKdfValidator` 直接拒绝，不进入 Argon2。
实际运行的 Argon2 还必须在**后台线程**执行，避免卡 UI。

## 8. 兼容性验收

- Kotlin importer 对上述 fixture 的验证结果与 Dart 验证工具完全一致：
  正向 fixture 全部解密到预期明文；负向全部安全失败。
- **phase 1 fix 追加验收**（`LegacyToV2MapperTest`）：
  - `schema1_same_issuer_multi_account`：同一 issuer 下 3 个不同 accountName
    → 3 个独立 AuthAccount（各自完整保留 issuer/accountName/TotpCredential），
    `sortOrder` 唯一，不合并不丢弃。
  - `schema1_invalid_totp_params`：5 条非法 TOTP 全部**保留原始字段**并列入
    未导入报告（原因含具体值），合法条目仍正常导入。
  - schema 3 保持 1 个旧 Account → 1 个 AuthAccount（TOTP + recoveryCodes
    作为子行）。
- 导入时 TOTP 参数逐条校验，并对每个**可导入** TOTP 计算**测试时刻验证码**
  （RFC 6238，固定测试时刻），与 RFC 4226 向量一致；不可导入条目不计算。
- 任一失败不得改变当前数据库（单事务，失败即回滚）。

> **Phase 3A 更新（2026-08-07）**：旧文档中的“PRE_IMPORT checkpoint /
> 导入后新格式备份”已随自动备份模型移除。legacy 导入仍为单事务；
> 未来 legacy importer 会复用新的 Merge Engine（见 PACKAGE_FORMAT.md §Legacy）。
