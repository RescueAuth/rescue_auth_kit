# PACKAGE_FORMAT.md — v2 Export Package（逻辑 + 加密契约）

> 状态：**Phase 3B contract（已定稿并实现）**；Phase 3A 逻辑契约保持不变，
> 3B 实现加密 codec（`core/.../export/codec/PortablePackageCodec.kt`）。
> 本文取代旧 `BACKUP_FORMAT.md` 的“自动备份”草案，定义新的
> **Portable Export Package** 逻辑 + 加密契约。

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

Export Package（Phase 3B 已实现）:
每次手动导出，用户为“这一份数据包”设置一个 Export PIN
    ↓
PIN + random salt → Argon2id → Key Encryption Key（wrap PackageKey）
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

## Envelope（Phase 3B 已实现）

### 字节布局（big-endian，无对齐 padding）

```
offset   size  field
0        8     magic        = "RAKVPKG2"
8        1     formatVersion = 1
9        1     cryptoVersion = 1
10       1     flags         = 0
11       4     headerLength  (uint32)
15       1     kdfAlgorithm  (1 = ARGON2ID)
16       4     kdfMemoryKiB  (uint32)
20       4     kdfIterations (uint32)
24       1     kdfParallelism
25       1     kdfOutputLength (bytes)
26       1     kdfSaltLength   (bytes)
27       n     kdfSalt
next     1     wrappingAlgorithm (1 = XCHACHA20_POLY1305)
next     1     wrappingNonceLength (24)
next     24    wrappingNonce
next     2     wrappedKeyLength (uint16 = 48)
next     48    wrappedKey (ciphertext || tag of the 32-byte PackageKey)
next     1     payloadAlgorithm (1 = XCHACHA20_POLY1305)
next     1     payloadNonceLength (24)
next     24    payloadNonce
next     4     payloadCiphertextLength (uint32 = plaintext + 16-byte tag)
next     n     payloadCiphertext (AEAD ciphertext || tag)
```

**禁止**直接把 Export PIN 作为 encryption key。每次 export 独立生成
random salt / random 256-bit PackageKey / random wrapping nonce / random
payload nonce —— 即使两个 package 使用相同 PIN 与相同 payload，PackageKey
与 ciphertext 也必须不同（已用 JVM 测试锁定）。

### 密钥层级（key hierarchy）

```
per-export PIN + random salt
        ↓ Argon2id (v1.3)
PIN-derived wrapping key / KEK
        ↓ XChaCha20-Poly1305  (wrap AEAD, AAD = header 前缀至 wrapped key)
wrapped random 256-bit PackageKey
        ↓ XChaCha20-Poly1305  (payload AEAD, AAD = 完整 header 前缀)
encrypted serialized VaultPackagePayload
```

### Authenticated metadata / AAD（Phase 3B 设计）

- **Wrap AAD** = 从 magic 到 wrapped-key ciphertext 之前的全部 header 字节。
  它把 magic / formatVersion / cryptoVersion / flags / 全部 KDF 参数 /
  wrapping metadata 绑定到 wrapped PackageKey。
- **Payload AAD** = 从 magic 到 payload ciphertext 之前的**完整** header 字节
  （含 wrapped key、payload 算法 id、payload nonce、payload 长度）。它把每个
  影响“如何解析 / 如何解密”的字段绑定到明文。

因此攻击者无法修改 format/crypto/KDF metadata（例如把 cryptoVersion 改掉、
把 memoryKiB 改小、把 nonce 长度改掉）而仍然得到合法 package —— 任何 header
字段篡改都会导致 AEAD 认证失败（已用 tamper 测试锁定）。**所有用户业务敏感
信息**（provider/account 名、issuer、account identity、TOTP secret、恢复码、
Developer Entry title/details、API key/secret、SSH key、keystore filename/
内容、project/package metadata、export selection 细节）**全部留在 encrypted
payload**；plaintext header 只包含解密和版本分派真正必要的数据。

## 加密栈（Phase 3B 复用 Phase 1 已验证实现，见 ADR-0002）

- **Argon2id**（version 13）— Bouncy Castle 1.85，与 legacy importer 同一实现；
- **XChaCha20-Poly1305** — Bouncy Castle 1.85 原生 AEAD，已通过
  IETF draft-irtf-cfrg-xchacha-03 §2.2.1 官方向量 + legacy Dart 交叉向量 +
  随机属性测试。

不引入新的 crypto dependency，不自行实现任何 primitive。

## KDF policy（Phase 3B 已锁定）

| 概念 | 值 |
| --- | --- |
| **DEFAULT PARAMETERS**（encode 使用） | Argon2id, memoryKiB=19456 (19 MiB), iterations=2, parallelism=1, outputLength=32, salt=16 bytes |
| **ACCEPTABLE DECODE RANGE**（decode 接受） | memoryKiB 64..262144 (64 KiB..256 MiB)，iterations 1..16，parallelism 1..8，outputLength 16..64，salt 8..64 bytes，且 memoryKiB ≥ 8×parallelism |

- encode 使用当前推荐参数；decode **读取 package 中存储的参数**（支持未来
  升级 / 更强参数），但只接受安全且资源可控的范围（已用 KDF policy 测试
  锁定）。
- 这样未来可以：format v1 旧 package → 旧合理参数；format v1 更新的 export →
  更强参数；decoder 保持兼容且资源可控。

## 恶意 header / DoS 保护（Phase 3B 强制）

Header 是**不可信输入**。Decoder 在真正执行 Argon2id 或分配大块内存之前，
必须验证 package header 中所有 attacker-controlled 参数（
`PackageHeaderParser`）。格式级硬限制：

- `MAX_PACKAGE_SIZE = 16 MiB`（总包大小上限，读取前先拒绝）；
- `MAX_HEADER_LENGTH = 4 KiB`；
- `MAX_PAYLOAD_CIPHERTEXT_SIZE = MAX_PACKAGE_SIZE`；
- `MAX_WRAPPED_KEY_BYTES = 512`；
- KDF 参数 accepted range（见上）；长度一致性校验（headerLength 字段与
  实际 computed 一致）；uint32/uint16 全部以无符号读取，杜绝 integer
  overflow / allocation abuse。

因此恶意 package 声称 memoryKiB=极大值 / iterations=极大值 /
parallelism=极大值 / 异常长度 / 超大 ciphertext 会在 **Argon2 之前**以
`InvalidKdfParameters` / `MalformedPackage` 被拒绝（已用 DoS 测试锁定），
不可能造成 OOM / CPU DoS / 崩溃 / ANR / 过度分配。

## 错误分类（Phase 3B error taxonomy）

| 异常 | 语义 |
| --- | --- |
| `UnsupportedFormat` | 坏 magic / 未知 reserved flags / 不支持的 formatVersion |
| `UnsupportedCrypto` | 不支持的 cryptoVersion / 算法 id |
| `InvalidKdfParameters` | KDF 参数超出 accepted decode range（KDF 前拒绝） |
| `MalformedPackage` | 结构损坏：截断 / 长度不一致 / trailing garbage / headerLength mismatch |
| `AuthenticationFailed` | AEAD 失败 —— wrong PIN 或 corrupted package（**不做精确区分**） |
| `LogicalPayloadInvalid` | crypto 成功但 inner payload JSON 解析失败或 `PackageValidator` 校验失败 |

要求：任何 authentication failure **不得**返回 partial plaintext、
**不得**返回 partially parsed `VaultSnapshot`、**不得**继续进入
MergePlanner。wrong PIN 与 ciphertext corruption 在 AEAD 层无法安全区分，
统一为 `AuthenticationFailed`（“wrong PIN or corrupted package”），UI 后续
决定文案。

## 序列化（Phase 3B 已实现）

- 复用 Phase 3A 的 `VaultPackagePayload` / `VaultSnapshot` / Developer
  logical model / `SnapshotScope`；不序列化 Room entity / SQLite / Bundle /
  Java 对象序列化。
- portable schema 保持 platform-neutral（JSON，kotlinx.serialization）。
- 外层 `formatVersion` / `cryptoVersion` 与内层 `logicalSchemaVersion` 是
  **两个不同概念**，不得混成一个版本号（已分别校验）。

## 版本化与兼容

- `logicalSchemaVersion`：读取方必须拒绝未知新版本，不得猜测解析。
- `formatVersion` / `cryptoVersion`：在 envelope 中版本化；一旦以某个版本
  发布，算法不得在同一版本下改变。
- 未来 format/crypto 升级：新增版本号，decoder 按版本分派；旧版本 package
  仍可解密（golden fixture 锁定）。

## Selective snapshot（Phase 3B codec 一视同仁）

只有 **`PortablePackageCodec`** 一个 codec。`FULL_VAULT` /
`AUTHENTICATOR_ONLY` / `DEVELOPER_ONLY` / `SELECTED_ITEMS` 全部走同一
encode/decode；payload 里是什么由 `VaultPackagePayload` / `SnapshotScope`
决定。**不创建** `FullBackupCodec` / `SelectiveBackupCodec` 两套格式。

## PIN 表示 / zeroization（Phase 3B 已实现）

- Crypto core 不长期保存 PIN `String`；codec 接受 `String` / `CharArray` /
  `ByteArray` 三种 PIN 表示，内部统一复制为 owned `ByteArray` 供 KDF 使用，
  用后 `zeroize()`（覆盖为 0）。
- 敏感临时材料（PIN bytes / KEK / unwrapped PackageKey / plaintext
  serialized payload / 临时 secret buffers）在生命周期结束后 best-effort
  zeroize。
- **JVM 无法提供绝对内存擦除保证** —— 文档如实描述为 **best-effort
  zeroization**，不声称物理内存中绝无残留。
- PIN 是否必须纯数字 / 最短位数属 Phase 3D 产品输入策略；本轮 codec **不**
  把 crypto API 写死成 6/8 位数字 regex。Codec 只处理“本次 package secret”。

## API boundary（Phase 3B 已实现）

纯 Kotlin API（`:core`）：

```
PortablePackageCodec.encode(payload: VaultPackagePayload, pin: String|CharArray|ByteArray): ByteArray
PortablePackageCodec.decode(packageBytes: ByteArray, pin: String|CharArray|ByteArray): VaultPackagePayload
```

不暴露 Room / Android Context / SAF / Uri / Activity / BiometricPrompt；
Phase 3B 完全在 JVM 测试中验证。

## 确定性测试向量 / golden fixture（Phase 3B 已实现）

- 生产默认路径使用 `SecureRandom`（CSPRNG），**不使用** deterministic RNG。
- 测试提供 `PackageCrypto.withDeterministicRandom` seam（仅测试用），用于
  生成**固定 golden fixture**：
  `core/src/test/resources/codec-fixtures/v2_package_fixture_v1.bin`
  （test-only PIN `fixture-pin-0000`，synthetic fake secrets，无真实
  credential）。future codec 重构时可用它验证旧 package 仍能解密。

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
  锁定，`codec` 子包同样遵守）。
- 未来删除 Legacy Import ⇒ 删除 legacy compatibility layer，**不得要求
  重构 Native Package Import**。
