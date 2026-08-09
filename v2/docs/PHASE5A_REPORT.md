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

## 10. Deterministic stableId strategy

- legacy 对象有持久化 id（schema3 account/credential id、schema1/2 entry id），
  但这些 id 在**跨文件**场景下不保证全局唯一。因此 stableId 采用：

```
stableId = "legacy:" + b64url(sha256(sourceFingerprint)) + ":" + kind + ":" + b64url(sha256(kind + "\0" + legacyObjectPath))
```

- 确定性：同文件同对象路径 → 同 stableId。
- 命名空间：不同 `.rakvault` 的相同对象 id 不碰撞。
- 不泄露明文：SHA-256 摘要，不含 secret / password / decrypted payload。
- 重复导入同文件 → 相同 stableId → MergePlanner 第二次导入全
  DUPLICATE / keep-both 语义一致，无新增逻辑对象。

## 11. Source fingerprint strategy

- `fingerprintOfEncryptedBytes(bytes) = b64url(sha256(original encrypted bytes))`。
- 不含 password、不含 decrypted payload；同字节文件 → 同 fingerprint。
- 不把 filename / Android Uri 当 identity。
- 供未来 Phase 5B `ImportRecord.sourceFingerprint` 使用；本轮不做 production
  apply wiring。

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

## 14. Defensive parsing limits

沿用已有 `LegacyRakVaultImporter`：
- 输入 16 MiB 上限、解密后 payload 64 MiB 上限。
- KDF header 上限在 Argon2 前校验（防 DoS）。
- 解析层不放大分配（JSON 数组/字符串随输入 bounded）。

## 15. Independent frozen-v1 fixture provenance

除既有 Dart 生成 fixture 外，新增 **独立 provenance** fixture
（`legacy-fixtures/phase5a/`）：

- 生成路径：Python 3 + `argon2-cffi` + `PyNaCl`，按 frozen v1.2.0 同一 wire
  protocol（Argon2id 19MiB/2/1/32 → XChaCha20-Poly1305 → base64url envelope）
  独立实现生成；非 Dart 工具、非 Kotlin test-encoder 自喂自。
- 交叉验证：Python 独立解密结果与 Kotlin fixture 解密路径对
  `schema3_normal` 明文逐字节一致（见本报告 provenance 注）。
- 密码全部 test-only：`test-password-1` / `test-password-2` /
  `test-password-3`。

| fixture | schema | 内容 | SHA-256 |
| --- | --- | --- | --- |
| `phase5a_schema1_basic.rakvault` | 1 | 1 TOTP + 1 recovery set | `684f66f70100dd008ed978f2cee81c6154e3d04f42b134b7b7cb15a00e2bcc76` |
| `phase5a_schema2_dev.rakvault` | 2 | 2 TOTP + 1 recovery + **5 类 Developer** | `6e4fd1d08a4ed7bd7d86def4d18ac7d66c23e57c3c8ec2c546bb428eca2beb50` |
| `phase5a_schema3_full.rakvault` | 3 | 2 providers / 2 accounts（4 TOTP，SHA1/SHA256/SHA512、digits 6/8、period 30/60）+ recovery + **5 类 Developer** + keystore 二进制 | `93c39709d2f1314fb8f61dbddb1b942311340c09bddcdef2bd5d2cf4ddc1e03e` |
| `phase5a_unicode.rakvault` | 3 | 中文 provider/account + 5 类 Developer | `869286dc6c90da71b724dbb5808ec532712967fdc2cf525ccec26d145d2938cd` |

> 无真实 credential；keystore 为合成字节 `AAECAwQFBgc=`（= 8 字节
> `00 01 02 03 04 05 06 07`）。

## 16. Idempotence

- 同一 fixture decode/map 两次 → 全部 stableId 一致（
  `LegacyVaultSnapshotMapperTest`）。
- 经 shared `MergePlanner`：`plan(dest=first, source=second)` →
  inserted=0、conflicts=0、stateDivergences=0（
  `LegacySnapshotMergeIdempotenceTest`，覆盖 schema 1/2/3 + Developer 五类）。

## 17. Native / Legacy isolation

- `LegacySnapshotIsolationTest` 双向往返锁定：
  - legacy adapter **不得** import `PortablePackageCodec` / `PackageEnvelope`；
  - shared logical（`export`）**不得** import `com.rescueauth.v2.legacy`。
- 沿用既有 `LegacyIsolationTest`（export→legacy 反向）。

## 18. Changed files

- **core production**：
  - `core/.../legacy/LegacyVaultSnapshotMapper.kt`（新增）
- **core tests**：
  - `LegacyVaultSnapshotMapperTest.kt`（新增）
  - `LegacySnapshotMergeIdempotenceTest.kt`（新增）
  - `LegacySnapshotIsolationTest.kt`（新增）
  - `legacy-fixtures/phase5a/*.rakvault`（新增，core 测试资源）
- **repo 级 fixture**：`v2/legacy-fixtures/phase5a/*.rakvault`（新增规范副本）
- **docs**：`docs/PHASE5A_REPORT.md`（本文件，新增）、`docs/LEGACY_IMPORT.md`
  （Phase 5A 节，新增）、ROADMAP / AGENTS / CHANGELOG（最小状态更新）

**未修改**：PortablePackageCodec / PackageEnvelope / .rakpkg format /
MergePlanner / 事务 apply / Developer UI / SensitiveActionGate / Export/Recovery
UI / strings / navigation。

## 19. Tests

```bash
./gradlew :core:test            # 327 tests PASS（含 Phase 5A 新增 13 个）
./gradlew :app:testDebugUnitTest # 250 tests PASS（回归，applySnapshot 边界不受影响）
./gradlew :app:lintDebug        # PASS
./gradlew :app:assembleDebug    # PASS
./gradlew :app:assembleDebugAndroidTest # PASS
```

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
