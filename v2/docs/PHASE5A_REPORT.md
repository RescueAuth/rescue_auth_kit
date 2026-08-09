# PHASE5A_REPORT.md — Legacy v1 Import Core Adapter

> Phase 5A（Issue #1）—— 把 frozen Flutter v1.2.0 `.rakvault` 解码为 **shared
> v2 logical `VaultSnapshot`** 的核心适配层。本 PR 只做 core 适配与映射，
> **不做** Android Legacy Import UI / SAF / password dialog（属 Phase 5B）。
>
> 关联文档：`docs/LEGACY_IMPORT.md`（v1 契约）、`docs/PACKAGE_FORMAT.md`
> （Native package）、`docs/ADRS/ADR-0002`（crypto）、`docs/ADRS/ADR-0004`
> （stable identity）。

## 1. Frozen v1 protocol audit（只读，以 tag `v1.2.0` 为 source of truth）

| 项 | 值 | 依据 |
| --- | --- | --- |
| 外层 magic | `RescueAuthKitVault` | `lib/core/crypto/vault_crypto.dart` |
| 外层 version | `1` | 同上 |
| KDF | Argon2id，默认 19456 KiB / 2 iter / p1 / 32B，salt 16B | `vault_crypto.dart` + `vault_repository.dart` |
| Cipher | XChaCha20-Poly1305，nonce 24B，ciphertext 与 MAC **分字段存储** | `vault_crypto.dart` `VaultFile.toJson` |
| payload | UTF-8 JSON，`schemaVersion` 1/2/3 | `vault_models.dart` / `vault_migrator.dart` |
| schema 3 | `providers[]` + `accounts[]`（`providerId` 关联）+ `developerEntries[]` | `vault_models.dart` `VaultData.toJson` |
| schema 1/2 | 顶层 `totpEntries[]` / `recoveryCodeSets[]`（schema 2 增 Developer） | `vault_models.dart` `TotpEntry` / `RecoveryCodeSet` |
| credential kind | `"totp"` / `"recoveryCodes"`（`Credential` sealed） | `vault_models.dart` `Credential.fromJson` |
| TOTP 参数 | SHA1/SHA256/SHA512，digits 6..10，period 1..120（parser 强制） | `otpauth_parser.dart`；旧库读 schema3 时 `TotpCredential.fromJson` 无范围校验但生产经 parser 写入 |
| Recovery | 仅 `codes: List<String>`，**无 used/unused 状态** | `RecoveryCodesCredential` |
| Developer `type` JSON 名 | `androidSigningKey` / `apiCredential` / `sshKey` / `envVarSet` / `genericSecret` | `DeveloperEntryTypeX.jsonName` |
| Developer payload 字段 | 见下文 §8 | `developer_screen.dart` `_payloadForType` |
| 密码策略 | `<10` 是 **vault creation UI policy**（`create_vault_screen.dart`），**不是**文件格式/decoder 约束 | `create_vault_screen.dart` L38 |
| 旧库读取更早 schema | `VaultMigrator.migrate` 接受 schema 1/2/3（v1/v2→v3 + v3 identity） | `vault_migrator.dart` |

结论：frozen v1.2.0 真实支持 schema **1、2、3**，映射层必须覆盖三者。

## 2. Legacy crypto contract

- 复用 Phase 1/phase1-fix 的 `LegacyRakVaultImporter`（BC 1.85 原生
  XChaCha20Poly1305，ADR-0002）。**未重写任何 crypto primitive**。
- 输入 API：`ByteArray`（原始 `.rakvault` 字节）。
- `LegacyKdfValidator` 在 Argon2 前校验 header 上限（256 MiB / 16 iter /
  p8 / hash 16..64 / salt 8..64 / nonce 24）——防 DoS。
- 密码：仅用于本次解密，不落盘；派生密钥与明文 buffer best-effort
  zeroize（JVM 不做物理擦除承诺）。
- wrong password / corrupted ciphertext 统一为 `AuthenticationFailed`
  语义（AEAD 层无法区分），不伪造精确原因。

## 3. Supported legacy schema versions

**1、2、3 全部支持**（与 frozen v1 `VaultMigrator` 一致）。

## 4. Decoder architecture

```
Legacy .rakvault bytes
        ↓  (已有) LegacyRakVaultImporter (header→Argon2→XChaCha20→payload)
LegacyImportBundle (legacy raw model)
        ↓  (新增) LegacyVaultSnapshotMapper
shared VaultSnapshot (com.rescueauth.v2.export)
        ↓  (已有) PackageValidator / MergePlanner / VaultRepository.applySnapshot
```

新增文件：`core/.../legacy/LegacyVaultSnapshotMapper.kt`（仅依赖
`export` 逻辑模型 + 标准库，不依赖 Native codec）。

## 5. Raw legacy models

`LegacyImportBundle` / `LegacyTotpEntry` / `LegacyRecoveryCodeSet` /
`LegacyDeveloperEntry`（schema-version-agnostic，只读，已有）。

## 6. Logical mapping

- schema 1/2（entry-centric）：每条 `totpEntry` → 1 个 `VaultAccount`
  （serviceName=issuer，accountName=entry.accountName，1 条 TotpCredential）；
  每个 `recoveryCodeSets[i]` → 1 个 `VaultAccount` + 1 个 `RecoveryCodeSet`。
  相同 issuer **只允许在 UI 分组，绝不合并账户**（沿用 Phase 1 fix 契约）。
- schema 3：1 个 legacy `Account` → 1 个 `VaultAccount`，其
  `totp` / `recoveryCodes` credentials 作为子行；provider name 作为
  serviceName。
- `favorite=false`、`notes=null`（旧版无这些概念）。

## 7. TOTP mapping

- `secretBase32` 原样保留；`algorithm` 归一为 `SHA1/SHA256/SHA512`（
  旧库 `TotpHashAlgorithmX.otpauthName` 输出即大写）；`digits`/`period`
  原样。
- 非法参数（未知 algorithm / digits 越界 / period<=0 / 非法 base32）沿用
  `LegacyTotpValidator` 策略：**不静默替换**，不进入 snapshot（保留在
  LegacyImportReport 由 5B UI 展示）。

## 8. Recovery default-state mapping

- legacy Recovery 无 used 状态 → v2 `status="UNUSED"`、`usedAt=null`、
  `sortOrder` 按源顺序递增。这是明确的 migration default，由测试锁定。
- code 值原样保留（大小写、内部 `-` 不改写）。

## 9. Developer five-type mapping

逐字段映射（源：`developer_screen.dart` `_payloadForType`）：

| 类型 | 字段 → v2 logical |
| --- | --- |
| androidSigningKey | projectName / packageName / keystoreFileName / keystoreBytesBase64→`keystoreBase64` / storePassword / keyAlias / keyPassword |
| apiCredential | serviceName / accountName / apiKey / apiSecret |
| sshKey | keyName / publicKey / privateKey / passphrase |
| envVarSet | projectName / variables（`[{name,value}]` → `[{key,value}]`） |
| genericSecret | fields（`[{label,value}]` → `[{key,value}]`） |

keystore 二进制以原始 base64 进入 `keystoreBase64`，`PackageValidator`
校验 base64 合法 + 大小上限 → **exact byte round-trip**。同名 entry 不自动
合并（dedupe 交给 shared MergePlanner）。

## 10. Deterministic stableId strategy — durable-id FIRST（CR 修复）

### 10.1 durable-id audit（frozen v1.2.0）

以 tag `v1.2.0` 的 `vault_session.dart` / `vault_models.dart` /
`vault_migrator.dart` 为 source of truth：**除单个 Recovery Code 外，所有
legacy 对象都携带 durable persisted identity（UUID v4，创建时生成、随 vault
JSON 持久化）**。

| 对象 | durable id | 证据 |
| --- | --- | --- |
| Provider | `providers[].id`（schema 3） | `addProvider`: `id: _uuid.v4()` |
| Account | `accounts[].id`（schema 3） | `addAccount`: `id: _uuid.v4()` |
| TOTP credential | `credentials[].id` / `totpEntries[].id` | `addTotp`: `id: _uuid.v4()`；迁移保留 entry id |
| Recovery Code Set | `credentials[].id` / `recoveryCodeSets[].id` | 迁移保留 set id（`id: legacy.id`） |
| Recovery Code | **无**（仅 `codes: List<String>` 位置索引） | `RecoveryCodesCredential` |
| Developer Entry | `developerEntries[].id` | `addDeveloperEntry`: `id: _uuid.v4()` |

### 10.2 最终 derivation rule

```
stableId = "legacy:" + kind + ":" + b64url(sha256("legacy" + "\0" + kind + "\0" + durablePath))
```

- `namespace` = `legacy`（常量，防与 Native v2 冲突）。
- `kind`：`account` / `totp` / `recovery_set` / `recovery_code` /
  `developer:<legacyType>`。
- `durablePath` 仅由 durable id / 结构化位置组成（`account:<accId>` /
  `totp:<credId>` / `recovery:<setId>` / `recovery:<setId>/<index>` /
  `developer:<devId>`），**不含** source fingerprint、不含明文 secret。

性质：
- 同一 durable id 的对象在不同 `.rakvault`（不同加密字节、不同 source
  fingerprint）→ **相同 stableId**。
- 同一文件重复导入 → 相同 stableId（幂等）。
- 不同 durable id → 不碰撞（SHA-256）。
- 不泄露明文：仅 SHA-256 摘要。
- 唯一无 durable id 的对象（Recovery Code）用父 set durable id + index 做
  结构化 fallback（ADR-0010 Rule B），同样跨备份稳定。

### 10.3 为什么 durable-id-first

同一逻辑对象在不同时间/设备生成的 backup 中，durable UUID 属于对象本身
（不变），只有加密字节/指纹会变。用 fingerprint 当 object identity 会把
“文件身份”与“对象身份”混为一谈 → 同一对象在不同备份中产生不同 stableId
→ 重复导入产生 spurious 新对象。

## 11. Source fingerprint strategy（CR 修复后角色）

- `fingerprintOfEncryptedBytes(bytes) = b64url(sha256(原始加密字节))`。
- **角色 = legacy import source identity（文件身份）**：供未来 Phase 5B
  `ImportRecord.sourceFingerprint` / ImportRecord identity 使用。
- **不参与 object identity**：文件身份 ≠ 对象身份。stableId 由 durable id
  派生（见 §10），source fingerprint 不再进入 stableId。
- 不含 password、不含 decrypted payload；同字节文件 → 同 fingerprint。
- 不把 filename / Android Uri 当 identity。

## 12. Password / zeroization

- 密码仅一次用于 Argon2id 派生；派生 key 与 `ct||tag` 在 `finally` 中
  `fill(0)`；明文 payload 解析后 `fill(0)`。
- `<10` 是 v1 creation UI policy，decoder **不**因此拒绝合法文件。

## 13. Error taxonomy

| 概念 | 实现 |
| --- | --- |
| UnsupportedLegacyFormat | `LegacyKdfValidator.ValidationException`（magic/version/cipher/KDF 超限） |
| UnsupportedLegacySchema | `LegacyPayloadParser.ParseException("Unsupported legacy schemaVersion")` |
| MalformedLegacyVault | JSON 解析/字段缺失类 ParseException |
| AuthenticationFailed | `LegacyVaultDecryptor.DecryptException`（"wrong password or corrupted vault"） |
| LegacyPayloadInvalid | `LegacyPayloadParser.ParseException` |
| MappingFailed | `LegacyVaultSnapshotMapper.LegacyMappingException` |

## 14. Defensive parsing limits（CR 修复：与 Native 16 MiB 解耦）

frozen v1 `.rakvault` protocol **没有 16 MiB 上限**（`vault_repository.dart`
`readAsBytes()` 无限制读整个文件）。旧实现把 Native `.rakpkg` 的 16 MiB
contract 复制到了 Legacy decoder —— 这是错误的（Native package limit ≠ Legacy
protocol limit）。CR 后 Legacy 单独选择有依据的防御上限：

- **输入 `.rakvault` 上限：64 MiB**。依据：frozen v1 Developer Vault 可包含
  Android signing keystore binary + 多个 Developer entry；base64url JSON
  envelope 携带数 MiB keystore 是真实历史场景。64 MiB 对最大合理历史 vault
  留有 ~4x 余量，同时限制整文件读取 + Argon2id working set 峰值内存
  （防御性，不是格式契约）。
- **解密后 payload 上限：64 MiB**（post-decrypt backstop）。依据：envelope
  以 base64url 存 ciphertext（4/3 膨胀）+ 16B AEAD tag，64 MiB 输入不可能
  产生 >~48 MiB 明文，因此对合法 envelope 实际不可达；保留为独立文档化边界。
- KDF header 上限在 Argon2 前校验（防 DoS）：256 MiB / 16 iter / p8 /
  hash 16..64 / salt 8..64 / nonce 24。
- 解析层不放大分配（JSON 数组/字符串随输入 bounded）。

**不修改** Native `.rakpkg` format 或其 16 MiB contract。

## 15. Fixture provenance（CR 修复：新增 frozen v1 producer fixture）

### 15.1 既有独立 provenance fixture（Python，保留）

`legacy-fixtures/phase5a/` 由 **Python 3 + argon2-cffi + PyNaCl** 按 frozen
v1.2.0 同一 wire protocol（Argon2id 19MiB/2/1/32 → XChaCha20-Poly1305 →
base64url envelope）**独立实现**生成；非 Dart 工具、非 Kotlin test-encoder
自喂自。

| fixture | schema | 内容 | SHA-256 |
| --- | --- | --- | --- |
| `phase5a_schema1_basic.rakvault` | 1 | 1 TOTP + 1 recovery set | `684f66f7…bcc76` |
| `phase5a_schema2_dev.rakvault` | 2 | 2 TOTP + 1 recovery + **5 类 Developer** | `6e4fd1d0…beb50` |
| `phase5a_schema3_full.rakvault` | 3 | 2 providers / 2 accounts（4 TOTP，SHA1/SHA256/SHA512、digits 6/8、period 30/60）+ recovery + **5 类 Developer** + keystore 二进制 | `93c39709…c1e03e` |
| `phase5a_unicode.rakvault` | 3 | 中文 provider/account + 5 类 Developer | `869286dc…2938cd` |

> 无真实 credential；keystore 为合成字节 `AAECAwQFBgc=`（= 8 字节
> `00 01 02 03 04 05 06 07`）。

### 15.2 新增 frozen v1 producer fixture（Blocker 3 修复）

`tools/legacy_fixtures_frozen/` 使用 **frozen v1.2.0 实际实现**（`vault_crypto.dart`
+ `vault_models.dart` 从 tag `v1.2.0` **逐字节复制**），通过
`VaultCrypto.encryptToFile` / `VaultFile.encode` / `VaultData.toJson` 实际生产
`.rakvault` —— 这是 v1 应用写文件的同一生产代码路径，**不是** Python / Kotlin
/ 独立 Dart 复刻。

| fixture | 内容 | SHA-256 |
| --- | --- | --- |
| `frozen_v1_producer_schema3.rakvault` | schema 3：1 provider + 1 account（TOTP + Recovery）+ **5 类 Developer** | `eb8f03e6…72398a` |
| `frozen_v1_producer_schema3_alt_backup.rakvault` | 同一逻辑 vault 的**另一个加密备份**（不同随机 salt/nonce → 不同指纹） | `82f18246…e145ad` |

- 密码：`test-password-frozen`（test-only）。
- 无真实 credential；keystore 为合成 8 字节。
- 作用：锁 **actual producer interoperability**（frozen v1 → importer → mapper
  → expected VaultSnapshot），覆盖 TOTP / Recovery / 全部五类 Developer。

### 15.3 两类 fixture 作用不同

| 类别 | 用途 |
| --- | --- |
| frozen v1 fixture | actual producer compatibility（真实 v1 实现产物） |
| independent Python fixture | broad protocol/schema coverage（独立复刻，schema 1/2/3 + Unicode） |

## 16. Idempotence

- 同一 fixture decode/map 两次 → 全部 stableId 一致（
  `LegacyVaultSnapshotMapperTest`）。
- 经 shared `MergePlanner`：`plan(dest=first, source=second)` →
  inserted=0、conflicts=0、stateDivergences=0（
  `LegacySnapshotMergeIdempotenceTest`，覆盖 schema 1/2/3 + Developer 五类）。
- **跨备份幂等（CR 新增）**：`LegacyCrossBackupMergeTest` 用两份不同加密备份
  （同一逻辑 vault，不同 salt/nonce → 不同指纹）互导 → 全 DUPLICATE，
  无新增逻辑对象；不同 durable id 的 vault 互导 → 正常 INSERT，无碰撞。

## 17. Native / Legacy isolation

- `LegacySnapshotIsolationTest` 双向往返锁定：
  - legacy adapter **不得** import `PortablePackageCodec` / `PackageEnvelope`；
  - shared logical（`export`）**不得** import `com.rescueauth.v2.legacy`。
- 沿用既有 `LegacyIsolationTest`（export→legacy 反向）。

## 17a. Logical validator 依赖边界（CR 修复）

- Legacy 映射后走 **纯 logical `VaultSnapshot` validation**
  （`PackageValidator.validate(VaultSnapshot)`），**不经过** Native package
  serialized-size / 16 MiB capacity budget。
- 已提取公共入口 `PackageValidator.validateSnapshot(VaultSnapshot)`：Native
  Package 与 Legacy 共享 logical validation；package capacity budget 只属于
  Native package codec/validator 的 `validate(VaultPackagePayload)`。
- 测试 `LegacyDefensiveLimitsAndValidatorBoundaryTest`：logical-valid legacy
  snapshot 不因 Native capacity policy 被错误拒绝；oversized/malicious legacy
  输入在明确 Legacy 防御上限下安全拒绝。
- **未修改** `.rakpkg` format / 16 MiB contract。

## 18. Changed files

- **core production**：
  - `core/.../legacy/LegacyVaultSnapshotMapper.kt`（新增，CR 修复：durable-id-first stableId）
  - `core/.../legacy/LegacyRakVaultImporter.kt`（CR 修复：legacy 防御上限 16→64 MiB，与 Native 解耦）
  - `core/.../export/PackageValidator.kt`（CR 修复：提取公共 `validateSnapshot` logical 入口）
- **core tests**：
  - `LegacyVaultSnapshotMapperTest.kt`（新增）
  - `LegacySnapshotMergeIdempotenceTest.kt`（新增）
  - `LegacySnapshotIsolationTest.kt`（新增）
  - `LegacyDurableIdStableIdTest.kt`（CR 新增）
  - `LegacyFrozenV1InteropTest.kt`（CR 新增）
  - `LegacyCrossBackupMergeTest.kt`（CR 新增）
  - `LegacyDefensiveLimitsAndValidatorBoundaryTest.kt`（CR 新增）
  - `legacy-fixtures/phase5a/*.rakvault`（新增，含 frozen v1 producer 两份）
- **repo 级 fixture**：`v2/legacy-fixtures/phase5a/*.rakvault`（新增规范副本，含 frozen v1 producer 两份）
- **fixture 工具**：`tools/legacy_fixtures_frozen/`（新增，frozen v1.2.0 实现逐字节复制 + 生成器）
- **docs**：`docs/PHASE5A_REPORT.md`（本文件）、`docs/LEGACY_IMPORT.md`、
  ADR-0010、ROADMAP / AGENTS / CHANGELOG（最小状态更新）

**未修改**：PortablePackageCodec / PackageEnvelope / .rakpkg format /
MergePlanner / 事务 apply / Developer UI / SensitiveActionGate / Export/Recovery
UI / strings / navigation。

## 19. Tests

```bash
./gradlew :core:test            # 344 tests PASS（原 327 + CR 新增 17）
./gradlew :app:testDebugUnitTest # 250 tests PASS（回归，applySnapshot 边界不受影响）
./gradlew :app:lintDebug        # PASS
./gradlew :app:assembleDebug    # PASS
./gradlew :app:assembleDebugAndroidTest # PASS
```

CR 新增测试（:core，17）：
- `LegacyDurableIdStableIdTest`（6）——同文件幂等、不同加密备份同 durable id →
  同 stableId、不同 durable id 不碰撞、Developer 覆盖、Recovery Code 结构化
  fallback、无明文 secret / 无 source fingerprint 泄漏。
- `LegacyFrozenV1InteropTest`（2）——frozen v1 producer fixture → importer →
  mapper → expected VaultSnapshot（TOTP / Recovery / 五类 Developer）。
- `LegacyCrossBackupMergeTest`（2）——两份不同加密备份互导全 DUPLICATE；
  不同 vault 正常 INSERT。
- `LegacyDefensiveLimitsAndValidatorBoundaryTest`（7）——Legacy 上限 64 MiB
  ≠ Native 16 MiB、oversized/malicious 输入安全拒绝、logical-valid legacy
  snapshot 不被 Native capacity 误拒、capacity 只在 payload 边界生效。

本轮 production 变更仅在 `:core`，未主动运行 Firebase Test Lab（契约 §21）。

## 20. Remaining Phase 5B scope

- Android Legacy file picker（SAF）
- Legacy password dialog
- Legacy import preview / result UI
- `ImportRecord` wiring（sourceFingerprint 已提供，未接线）
- apply 通过 `VaultRepository.applySnapshot`（已存在共享边界，未接 UI）

## 21. PR URL / branch / commits

- PR: <本 PR>
- Branch: `auto/phase5a-legacy-core-adapter-<suffix>`
- Commit: <commit>
