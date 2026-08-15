# ADR-0002：生产加密依赖 — 使用 BC 1.85 原生 XChaCha20-Poly1305，移除自实现 HChaCha20

- 状态：**Accepted**（phase 1 fix）
- 日期：2026-08-06
- 关联：执行规划 §12 阶段 1 复核；docs/LEGACY_IMPORT.md；ADR-0001

## 背景

阶段 1 spike 使用 Bouncy Castle `bcprov` **1.78.1** + 自实现 `HChaCha20`
构造 XChaCha20-Poly1305 解密旧 `.rakvault`（因为 1.78.x 没有内置
XChaCha20/HChaCha20）。执行契约要求复核生产加密依赖：

1. 优先避免自行维护密码算法；
2. 评估当前受支持的 Tink XChaCha20-Poly1305 或 libsodium 实现；
3. 若仍保留自实现 HChaCha20，必须隔离在只读 legacy importer 内、不得用于
   新格式，并保留 Dart 交叉向量、官方向量、随机属性测试和清晰 ADR；
4. 检查 Bouncy Castle 1.78.1 是否仍为当前安全版本（不能仅因 spike 通过就锁定旧版）。

## 评估

### A. Tink（`com.google.crypto.tink:tink` 1.16.0，2026-07 发布）

- 提供公开 `subtle.XChaCha20Poly1305`（`Aead` 接口，`encrypt(plaintext, associatedData)`，
  内部把 associatedData 当作 nonce 的前 12 字节）。
- **实测：对旧 `.rakvault` fixture 解密失败（AEAD MAC 不匹配）**——Tink 的
  XChaCha20 内部构造（`hChaCha20` 后 nonce 布局 / Poly1305 key 派生）与旧 Dart
  `cryptography` 2.9.0 的 XChaCha20-Poly1305 不逐字节兼容。
- 结论：**不能**作为 legacy importer 的解密实现。

### B. libsodium

- 有 JNI/native 负担（ABI、NDK），且对旧格式同样需要验证字节兼容性；
- 未提供纯 JVM 的官方 Kotlin artifact 的直接、零 native 依赖方案（`sodium` 0.0.3
  仅为 spike 预留，未验证兼容）。
- 结论：不优先。

### C. Bouncy Castle 1.85（2025-09-17 发布，当前最新 1.85）

- **BC 1.85 新增原生 `XChaCha20Engine` / `XChaCha20Poly1305`**（AEAD，直接接受
  24 字节 nonce）。
- 实测三个旧 fixture（schema 1/2/3）全部解密成功、与 Dart 明文逐字节一致；
  错误密码 / 错 MAC 正确失败。
- 实测匹配 **IETF draft-irtf-cfrg-xchacha-03 §2.2.1 官方向量**（130 字节 CT||tag
  完全一致）。
- BC 1.85 保留 Argon2id（version 13），旧 KDF 兼容性不变。
- 结论：**采用 BC 1.85 原生 XChaCha20Poly1305**，可**移除自实现 HChaCha20**。

## 决策

1. 将 `bcprov-jdk18on` 从 1.78.1 升级到 **1.85**（当前安全稳定版本）。
2. 删除 `core/.../legacy/HChaCha20.kt`（自实现）及其测试；`LegacyVaultDecryptor`
   改用 BC 原生 `XChaCha20Poly1305`（ct||tag 拼接逻辑保留，因为旧格式
   ciphertext 与 MAC 分字段存储）。
3. 不再自维护任何密码算法；HChaCha20 自实现已彻底移除（不是“隔离”，而是
   **用厂商原生实现替代**）。
4. 保留/新增测试矩阵（全部通过）：
   - Dart 交叉向量（旧 Dart `cryptography` 加密 → Kotlin BC 解密）；
   - 官方向量（IETF draft-irtf-cfrg-xchacha-03 §2.2.1）；
   - 随机属性测试（round-trip、任意字节翻转失败、换 key/nonce 失败、
     空明文、最大 64 KiB）；
   - 既有 Argon2id 交叉向量与 RFC 4226 TOTP 向量。
5. 新格式（备份协议 v1，阶段 3）不使用 legacy 解密实现；使用标准库/受支持
   AEAD（后续 ADR 定稿）。

## 后果

- 好处：移除自实现密码算法，降低维护与审计面；BC 1.85 为当前最新稳定版；
  官方原生实现 + 官方向量 + 交叉向量 + 随机属性测试四重锁定兼容性。
- 代价：BC 1.85 需要重新验证全部 fixture（已通过）；libsodium/Tink 不适用于
  旧格式，新格式阶段 3 仍可选 AES-256-GCM（Android Keystore/BC 均支持）。
- 不可逆点：`bcprov-jdk18on` 版本从 1.85 起为基线；不得在未重新验证全部
  fixture + 向量前回退或升级。
