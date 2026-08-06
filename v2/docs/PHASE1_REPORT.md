# 阶段 1 技术验证报告

> 关联执行规划 §12 阶段 1。本文记录 Kotlin 技术验证 spike 的结果、
> 库选型依据、以及无法满足的兼容点与风险。测试已通过后进入下一阶段。

## 1. 最小 Kotlin/Android 工程

- `v2/` Gradle 多模块：`:core`（纯 JVM 库）+ `:app`（Android 应用）。
- Kotlin 2.0.21 / AGP 8.7.3 / Gradle 8.14 / JDK 17 / compileSdk 35 / minSdk 26。
- `app-debug.apk` 构建成功（7.2 MB）。
- 版本目录锁定（`gradle/libs.versions.toml`），wrapper 固定 Gradle 8.14。

## 2. 旧 Argon2id + XChaCha20-Poly1305 解密 spike

核心链路（`core/src/main/kotlin/com/rescueauth/v2/legacy/`）：

- `LegacyEnvelope`（kotlinx-serialization）：旧格式 envelope 解析。
- `LegacyKdfValidator`：magic/version/cipher/KDF 参数上限校验（KDF 前执行）。
- `LegacyVaultDecryptor`：Argon2id（BC `Argon2BytesGenerator`，version 13）+ 
  XChaCha20-Poly1305（BC `ChaCha20Poly1305` + 自实现 `HChaCha20`）。
- `LegacyPayloadParser`：schema 1/2/3 → 统一 `LegacyImportBundle`。
- `LegacyRakVaultImporter`：编排入口（尺寸上限 → header 校验 → 派生密钥 →
  解密 → 解析 payload → 清理）。

### 验证结果

**18/18 测试通过**，包括：

- 4 个正向 fixture（schema1/2/3 + RFC4226）全部解密到预期明文（与 Dart 验证工具一致）。
- 5 个负向 fixture 全部安全失败（错误密码/篡改/截断/错 MAC/极端 KDF 参数）。
- **HChaCha20** 与 Dart `cryptography` 2.9.0 官方向量一致（`82413b42...`）。
- **Argon2id**（1024 KiB 与 19456 KiB 两组参数）与 Dart 输出一致。
- **XChaCha20-Poly1305** 交叉向量：Kotlin 成功解密 Dart 加密的已知明文。
- RFC 4226 官方向量（`755224`/`287082`/`359152`/`969429`/`338314`/`254676`）在
  固定测试时刻一致。
- fixture 字节与 manifest SHA-256 一致性守卫通过。

### 关键发现

1. **旧格式 ciphertext 与 MAC 分字段存储**（`ciphertextB64` + `macB64`）。
   BC 的 `ChaCha20Poly1305` AEAD 需要 `ct||tag` 拼接输入——这是最初正向
   fixture 全部失败、交叉向量却通过的原因。
2. **极端 KDF 参数可导致进程 abort**：header 声称 8 GiB 时直接跑 Argon2 会
   OOM core dump。importer 必须在 KDF 前校验上限（已实现并测试）。
3. **HChaCha20 旋转方向**（历史记录）：ChaCha20 quarter round 是 **rotate left**，
   写成 rotate right 会导致只有前 16 字节正确、后 16 字节错误。
4. BC 1.78.1 **无内置 XChaCha20/HChaCha20**，需自实现 HChaCha20。
   **phase 1 fix 已升级到 BC 1.85**：BC 1.85 提供原生 `XChaCha20Poly1305`，
   与旧 fixture、IETF 官方向量、Dart 交叉向量全部兼容（见 ADR-0002），
   自实现 HChaCha20 已删除。

## 3. 库选型与依据（phase 1 fix 更新）

| 用途 | 选型 | 依据 |
| --- | --- | --- |
| Argon2id | Bouncy Castle `bcprov` 1.85 | 纯 Java、Android 可用、与 Dart 参数兼容（version 13）；比引入 native libargon2 更轻、无 NDK/ABI 负担 |
| XChaCha20-Poly1305 | Bouncy Castle `bcprov` 1.85 **原生 `XChaCha20Poly1305`** | BC 1.85 内置；官方向量 + Dart 交叉向量 + 随机属性测试锁定兼容（ADR-0002）；不再自实现 HChaCha20 |
| SQLCipher | 阶段 2 定稿 | **采用 `net.zetetic:sqlcipher-android`（当前维护，4.17.0），不用已过时的 `android-database-sqlcipher`** |
| Keystore/Biometric | AndroidX `androidx.biometric` + `KeyStore` | 平台原生，`FLAG_SECURE` + 后台遮罩用 Activity 回调 |
| 数据库 | Room（KSP） | 官方 ORM，SQLCipher 官方支持 |
| 更新签名 | Ed25519（Bouncy Castle） | 公钥小、验签快、CNB 侧无依赖 |

## 4. 无法满足的兼容点与风险

1. **旧库 Developer 数据不导入**：v1 不做 Developer 密钥管理，默认生成
   "未导入报告"并保留原始 `.rakvault`（预览必须显示数量）。
2. **旧恢复码无"已用"状态**：旧 schema 1/2/3 的恢复码均为字符串列表，
   导入后全部 `UNUSED`。用户需自行重新标记（记录于 LEGACY_IMPORT.md）。
3. **BC 自实现 HChaCha20 的维护风险**（已解除）：BC 1.85 提供原生
   `XChaCha20Poly1305`，自实现已删除，不再依赖自行维护的密码算法。
4. **Argon2id 内存占用**：默认 19 MiB，若未来 fixture 使用更大参数，
   importer 上限（256 MiB）可能拒绝——符合安全设计（防 DoS）。
5. **阶段 1 未做平台生命周期验证**（Room/SQLCipher/Keystore/Biometric/SAF/
   WorkManager）——属于阶段 2 范围，需要真机/模拟器。当前环境无 Android
   设备，仅完成纯 JVM 层验证与 app 骨架编译。
6. **fixture 生成工具依赖 Dart SDK**：CI 中如需重新验证，须安装 Dart 3.12+；
   已固化的 fixture 不依赖 Dart。

## 5. 遗留事项（进入阶段 2）

- 阶段 2：Room + SQLCipher + Keystore + BiometricPrompt 生命周期、
  VaultKey 包装/解锁/锁定/失效处理、串行 repository、自动锁/遮罩。
- 阶段 3：BACKUP_FORMAT.md 定稿 + 测试向量 + BackupKey/恢复套件。
- 阶段 4：旧库导入完整流程（预览 UI + checkpoint + 事务写入 + Developer 策略）。
