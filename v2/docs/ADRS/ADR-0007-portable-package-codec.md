# ADR-0007：v2 Portable Package 加密 Codec（Phase 3B）

- 状态：**Accepted**（Phase 3B 实现）
- 日期：2026-08-08
- 关联：docs/PACKAGE_FORMAT.md（v2 Export Package 契约）、
  docs/THREAT_MODEL.md、ADR-0002（BC 1.85 原生 XChaCha20-Poly1305）、
  ADR-0005（merge-first planner）、ROADMAP §5.2（3B）

## 背景

Phase 3A 交付了逻辑 package 模型 + merge foundation（`VaultPackagePayload` /
`VaultSnapshot` / Developer 五类 / `SnapshotScope` / `MergePlanner`），但
package 尚未加密。Phase 3B 必须实现 **Encrypted Portable Package Codec**：

```
VaultPackagePayload → serialize → encrypt with per-export PIN → portable package bytes
portable package bytes → parse envelope → decrypt with PIN → authenticate →
    deserialize → validate → VaultPackagePayload
```

约束（来自 Issue #1 / ROADMAP）：

1. 本机 Vault 安全模型（Biometric → Keystore → VaultKey → SQLCipher）与
   Portable Package 完全独立；
2. 优先复用 Phase 1 已验证的 crypto primitive（Argon2id + XChaCha20-Poly1305，
   BC 1.85，ADR-0002）；
3. 每次 export 必须独立随机（salt / PackageKey / wrapping nonce / payload
   nonce）；
4. Header 是不可信输入，KDF 前必须校验全部 attacker-controlled 参数；
5. 只有 `PortablePackageCodec` 一个 codec（selective snapshot 一视同仁）；
6. wrong PIN / corruption 必须安全失败，不做部分明文；
7. 版本化：`formatVersion` / `cryptoVersion`（外层）与
   `logicalSchemaVersion`（内层）是两个概念；
8. 纯 Kotlin / JVM 可验证，不暴露 Android / Room / SAF / UI。

## 决策

1. **字节级 envelope**（big-endian，无 padding）：magic `"RAKVPKG2"` +
   formatVersion=1 + cryptoVersion=1 + flags=0 + headerLength(uint32) +
   KDF(id, memoryKiB, iterations, parallelism, outputLength, saltLength, salt)
   + wrapping(algorithm, nonceLength, nonce, wrappedKeyLength, wrappedKey)
   + payload(algorithm, nonceLength, nonce, ciphertextLength, ciphertext)。
2. **密钥层级**：
   ```
   PIN + random salt → Argon2id(v1.3) → KEK
   KEK + wrap nonce → XChaCha20-Poly1305(wrap AAD) → wrapped 256-bit PackageKey
   PackageKey + payload nonce → XChaCha20-Poly1305(payload AAD) → ciphertext
   ```
   PIN **绝不**直接作为 payload key。
3. **每次 export 独立随机**：salt / PackageKey / wrapping nonce / payload
   nonce 全部重新生成（`SecureRandom`，CSPRNG）。
4. **AAD 设计**：
   - wrap AAD = header 前缀（magic → wrapped key 前）；
   - payload AAD = 完整 header 前缀（magic → payload ciphertext 前）；
   因此 format/crypto/KDF metadata 全部受认证保护，无法被篡改成另一种
   合法语义。
5. **KDF policy**：
   - DEFAULT PARAMETERS（encode）= Argon2id, 19 MiB, 2 iter, p1, 32B,
     salt 16B（与 legacy 默认 / OWASP 交互登录推荐同量级）；
   - ACCEPTABLE DECODE RANGE = memoryKiB 64..262144, iterations 1..16,
     parallelism 1..8, outputLength 16..64, salt 8..64, memoryKiB ≥ 8×p；
   - decode 读取 package 内参数（支持未来更强参数），但只接受安全范围。
6. **恶意 header 防护**（KDF 前）：格式级硬限制（总包 16 MiB、header 4 KiB、
   payload ciphertext 16 MiB、wrapped key 512B）+ KDF accepted range +
   无符号 uint32/uint16 读取 + headerLength 交叉校验 + trailing garbage 拒绝。
7. **错误分类**：`UnsupportedFormat` / `UnsupportedCrypto` /
   `InvalidKdfParameters` / `MalformedPackage` / `AuthenticationFailed` /
   `LogicalPayloadInvalid`。wrong PIN 与 corruption 统一为
   `AuthenticationFailed`（AEAD 层无法安全区分，UI 决定文案）。
8. **序列化**：`VaultPackagePayload` JSON（kotlinx.serialization，
   encodeDefaults=true），platform-neutral；不序列化 Room entity / SQLite /
   Bundle / Java 序列化。
9. **PIN 表示 / zeroization**：codec 接受 String / CharArray / ByteArray；
   内部复制为 owned ByteArray，用后 best-effort `zeroize()`。JVM 无法保证
   物理擦除，文档如实描述为 best-effort。
10. **API**：`PortablePackageCodec.encode(payload, pin): ByteArray` 与
    `decode(bytes, pin): VaultPackagePayload`，纯 Kotlin，无 Android 依赖。
11. **测试**：round-trip（全 scope + 五类 Developer + binary keystore exact
    round-trip + recovery used/unused）、随机性（同 payload 同 PIN 两次
    export 不同）、认证失败（wrong PIN / wrapped key / payload / nonce /
    AAD tamper / no partial plaintext）、格式（bad magic / truncation /
    unsupported version / 长度 / oversized / invalid KDF / malicious huge
    Argon2 在 KDF 前拒绝）、KDF policy（decode 读 package 参数）、golden
    fixture（deterministic randomness seam，仅测试用，生产默认路径用
    SecureRandom）。

## 备选方案与理由

- **直接用 PIN 作为 payload key**（拒绝）：无 KDF 拉伸、无 wrapping 分层，
  攻击者离线暴力成本低；且违反“每份 package 独立随机”要求。
- **换 crypto library（libsodium / Tink）**（拒绝）：Phase 1 已验证 BC 1.85
  原生 XChaCha20-Poly1305 与 Argon2id 并锁定（ADR-0002）；当前依赖完全满足
  格式要求，无需引入新依赖/原生负担。
- **Two codecs（FullBackupCodec / SelectiveBackupCodec）**（拒绝）：selective
  snapshot 是同一逻辑 payload 的 scope 差异，单一 codec 即可（ROADMAP §8.4）。
- **header 字段不全部纳入 AAD**（拒绝）：format/crypto/KDF metadata 影响
  解密语义，必须受认证保护（Issue #1 §12）。
- **decode 允许任意 KDF 参数**（拒绝）：攻击者可声称 memoryKiB=8 GiB →
  OOM abort（legacy 已实测）。必须 KDF 前校验 accepted range。

## 后果

- 好处：format 自 Phase 3B 起具有兼容性意义；golden fixture 锁定；恶意
  package 无法 DoS；wrong PIN/corruption 安全失败；selective snapshot
  复用同一 codec。
- 代价：envelope 为自定义二进制布局（非标准格式），需严格版本化与 fixture
  维护；JVM zeroization 只能 best-effort。
- 不可逆点：`formatVersion=1` / `cryptoVersion=1` 的字节布局、AAD 设计、
  KDF DEFAULT / decode range、错误分类在发布后不得在同一版本下改变。
