# THREAT_MODEL.md — 威胁模型与安全假设

> 阶段 0/1 版。随实现推进持续更新；任何加密/备份/导入变更必须先更新本节。
> **更新（2026-08-06，PR #6）**：同步 phase2-blocker-hotfix 新增的安全事实。
> **更新（2026-08-07，Issue #17 产品决策）**：新增 Sensitive Action
> Re-authentication（ADR-0006）；明确 Developer Vault 五类条目全部为正式
> 资产（不再视为 removed）；Global Search 不索引 secret；Clipboard
> auto-clear 列入 DEFER。
> **更新（2026-08-08，Phase 3B）**：新增 v2 Portable Package 加密 envelope
> （per-export PIN → Argon2id → KEK → wrap PackageKey → AEAD payload）、
> header-is-untrusted / DoS 保护、AAD 设计、错误分类、best-effort
> zeroization。

## 资产

| 资产 | 敏感度 | 存储 |
| --- | --- | --- |
| TOTP secrets | 高 | SQLCipher 加密数据库 |
| 恢复码 | 高 | SQLCipher 加密数据库 |
| `VaultKey`（256-bit 随机） | 极高 | Android Keystore 不可导出密钥包装 |
| `BackupKey`（256-bit 随机） | 极高 | 设备内由 VaultKey 保护 + 恢复套件（**Phase 3 reset：已移除**，改为 per-export PIN Export Package） |
| 旧密码（导入时） | 高 | 仅内存，用后即清零 |
| per-export PIN | 极高 | **永不落盘**；仅内存参与 Argon2id，用后 best-effort 清零 |
| PackageKey（256-bit 随机，per-export） | 极高 | package header 内由 PIN-derived KEK 包装（AEAD）；明文仅在内存中，用后清零 |

## 信任边界

1. **设备边界**：攻击者获得解锁后设备 = 可读 TOTP/恢复码（与主流认证器一致）。
2. **锁屏边界**：设备锁定后，未解锁时数据库关闭、密钥材料清零、UI 遮罩。
3. **离线包边界**：v2 Export Package 仅用 per-export PIN（经 Argon2id→KEK→PackageKey）加密；无 PIN 不可解密。legacy `.rakvault` 仅用旧 Master Password 解密（Phase 1 兼容层）。
4. **网络边界**：除更新检查（HTTP GET 静态 manifest + 签名验证）外，应用离线运行。

## 主要威胁与缓解

| 威胁 | 缓解 |
| --- | --- |
| 设备丢失 | 恢复套件（二维码+密钥文件）离线恢复；`BackupKey` 与 `VaultKey` 分离（Phase 2）；Phase 3 起为 manual Export Package + merge import |
| 锁屏数据泄露 | 数据库关闭 + 密钥清零 + 后台遮罩（不再全局 `FLAG_SECURE`，允许截图） |
| Keystore 密钥失效 | 不得删库；引导用户用恢复套件/备份恢复 |
| 恶意/损坏备份文件 | AEAD 先验 MAC；KDF 参数上限校验（防 OOM）；大小上限 |
| 恶意 `.rakvault` | header 校验（magic/version/KDF 上限）后再执行 Argon2 |
| **恶意 v2 Export Package** | **header-is-untrusted**：magic/version/KDF 参数/长度在 Argon2 前全部校验（`PackageHeaderParser`）；格式级硬限制（FORMAT HARD LIMIT：16 MiB 总包、4 KiB header、KDF accepted range、cryptoVersion=1 的 `kdfOutputLength` 必须 == 32）；恶意 memoryKiB/iterations/parallelism/长度/大小在 KDF 前以 `InvalidKdfParameters`/`MalformedPackage` 拒绝，杜绝 OOM/CPU DoS/ANR/过度分配 |
| **v2 Package runtime 资源耗尽** | **FORMAT HARD LIMIT ≠ RUNTIME DECODE RESOURCE POLICY**：结构合法且在格式硬限制内的参数，只要超过移动端解码预算（memoryKiB ≤ 128 MiB、iterations ≤ 8、memoryKiB×iterations ≤ 512K units），默认 decoder 就在 **Argon2 之前**以 `InvalidKdfParameters` 拒绝（`PackageRuntimePolicy.checkDecodeBudget`）——格式 accepted range 绝不自动等价于实际执行成本 |
| **v2 Package 容量不一致** | logical validator 与 codec 共享 `PackageCapacity` 预算：validator 接受 ⇒ 必然可编码；encode 超限以 `PackageTooLarge` 显式失败，绝不 OOM |
| **v2 Package header / AAD 篡改** | format/crypto/KDF metadata 全部纳入 AEAD AAD（wrap AAD 绑定 header 前缀，payload AAD 绑定完整 header 前缀）；任何 header 字段篡改 → AEAD 认证失败，无法被解释为另一种合法语义 |
| **wrong PIN / 密文损坏** | AEAD 认证失败统一为 `AuthenticationFailed`（不做精确区分），不返回 partial plaintext，不进入 MergePlanner |
| 更新源被篡改 | Ed25519 签名验证 `latest.json`；APK 校验大小+SHA-256+签名证书 |
| 非加密/伪加密数据库文件 | instrumented 用例新增：**普通 SQLite header 文件（`SQLite format 3\0`）也必须被拒绝**，不能当作加密 vault 打开（`RescueAuthDatabaseInstrumentedTest.corruptedDatabaseFileIsRejectedEvenIfItHasValidSqliteHeader`） |
| 生物识别/设备凭据不可用 | `resolveAvailableAuthenticators` 按设备实际可用性动态选择认证器；无可用认证器时明确提示需恢复流程，不静默降级；`DEVICE_CREDENTIAL` 路径不设 negative button（避免 `PromptInfo.build()` 抛异常导致启动崩溃） |
| 进程被 dump | 敏感缓冲区显式清零；不落盘密钥 |

## 已确认的安全事实（实测）

- 旧格式 KDF 参数可被攻击者设置为任意值。header 声称 8 GiB 内存时，
  直接运行 Argon2id 会因分配失败 **abort 进程**。因此 importer 必须在
  Argon2 前校验 KDF 参数上限（见 `docs/LEGACY_IMPORT.md` §7）。
- Dart `cryptography` 2.9.0 的 XChaCha20-Poly1305 与 IETF 草案一致
  （subkey = HChaCha20(key, nonce[0:16])，subnonce = 4 零字节 + nonce[16:24]）。
- **v2 Package（Phase 3B）**：BC 1.85 原生 XChaCha20-Poly1305 已通过官方
  向量；Argon2id 默认参数（19 MiB / 2 / p1 / 32B）与 legacy 默认一致；
  每次 export 的 salt / PackageKey / wrapping nonce / payload nonce 全部
  重新随机（相同 payload + 相同 PIN 两次 export → ciphertext 不同，已测试
  锁定）。

## 安全假设（明确不保证）

- 不做云托管密钥（v1 无服务器密钥托管）。
- 不做应用内主密码（日常解锁仅系统生物识别/设备凭据）。
- 剪贴板中的验证码在可配置时间后清除（clipboard auto-clear 为 **DEFER**，
  不阻塞 daily use），但剪贴板本身是系统级风险。
- 旧库导入的 Developer 数据**完整迁移**：Legacy v1 Developer Vault 五类
  （Android Signing Key / API Credential / SSH Key / Environment Variable Set /
  Generic Secret）全部经 Legacy mapper → shared `VaultSnapshot` → merge/apply
  正常导入并持久化到 SQLCipher DB，不降级为只读 secure note、不默认跳过
  （Phase 5A/5B 正式契约）；**v2 原生 Developer Vault 是正式能力**，
  五类条目全部 KEEP。
- **敏感操作二次认证**：Export / export keystore / reveal 长期 secret 等
  在解锁会话内仍要求 fresh Biometric / Device Credential（ADR-0006）。
- **Global Search 不索引 secret**：只搜 Provider/Account/TOTP display
  metadata/Developer title 等非敏感字段；secret 明文不入任何索引。
- **JVM zeroization 是 best-effort**：JVM 无法保证物理内存擦除；codec 对
  PIN bytes / KEK / PackageKey / plaintext payload 做 best-effort 清零，
  但不声称物理内存中绝无残留。

## 依赖

- Argon2id、XChaCha20-Poly1305、ChaCha20-Poly1305、AES-256-GCM、
  HKDF-SHA256、Ed25519（更新签名）→ Bouncy Castle / Android 平台。
- 数据库加密 → SQLCipher（实现阶段锁定版本）。
