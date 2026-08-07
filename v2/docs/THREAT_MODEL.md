# THREAT_MODEL.md — 威胁模型与安全假设

> 阶段 0/1 版。随实现推进持续更新；任何加密/备份/导入变更必须先更新本节。
> **更新（2026-08-06，PR #6）**：同步 phase2-blocker-hotfix 新增的安全事实。
> **更新（2026-08-07，Phase 3A reset）**：`BackupKey` / 恢复套件 /
> 自动备份快照模型已废弃，由 **per-export PIN 的 Portable Export Package**
> 取代（见 PACKAGE_FORMAT.md / ADR-0004 / ADR-0005）。

## 资产

| 资产 | 敏感度 | 存储 |
| --- | --- | --- |
| TOTP secrets | 高 | SQLCipher 加密数据库 |
| 恢复码 | 高 | SQLCipher 加密数据库 |
| `VaultKey`（256-bit 随机） | 极高 | Android Keystore 不可导出密钥包装 |
| Export Package（含加密 payload） | 高 | 用户手动导出文件；payload 由 per-export PIN 派生的 key 保护 |
| Export PIN | 极高 | **仅内存**，用后即弃；不保存到 Vault、不改变 VaultKey |
| 旧密码（legacy 导入时） | 高 | 仅内存，用后即清零 |

> 不再存在 `BackupKey` / 恢复套件资产。跨设备迁移只通过 manual
> Export Package + per-export PIN。

## 信任边界

1. **设备边界**：攻击者获得解锁后设备 = 可读 TOTP/恢复码（与主流认证器一致）。
2. **锁屏边界**：设备锁定后，未解锁时数据库关闭、密钥材料清零、UI 遮罩。
3. **导出包边界**：Export Package 的 payload 由 per-export PIN 派生的
   key（AEAD）加密；不知道 PIN 不可解密。每次 export 独立生成
   salt / PackageKey / nonce，即使相同 PIN 也不同密文。
4. **网络边界**：除更新检查（HTTP GET 静态 manifest + 签名验证）外，应用离线运行。

## 主要威胁与缓解

| 威胁 | 缓解 |
| --- | --- |
| 设备丢失 | 迁移 = 新手机 empty Vault + 旧手机 manual Export Package → merge（需要 Export PIN）；无需恢复套件 |
| 锁屏数据泄露 | 数据库关闭 + 密钥清零 + 后台遮罩 + `FLAG_SECURE` |
| Keystore 密钥失效 | 不得删库；引导用户用 Export Package / 备份恢复 |
| 恶意/损坏导出包 | AEAD 先验 MAC；KDF 参数上限校验（防 OOM）；大小上限；wrong PIN / corrupted package 安全失败，不输出部分可信 plaintext |
| 恶意 `.rakvault` | header 校验（magic/version/KDF 上限）后再执行 Argon2 |
| 更新源被篡改 | Ed25519 签名验证 `latest.json`；APK 校验大小+SHA-256+签名证书 |
| 非加密/伪加密数据库文件 | instrumented 用例新增：**普通 SQLite header 文件（`SQLite format 3\0`）也必须被拒绝**，不能当作加密 vault 打开（`RescueAuthDatabaseInstrumentedTest.corruptedDatabaseFileIsRejectedEvenIfItHasValidSqliteHeader`） |
| 生物识别/设备凭据不可用 | `resolveAvailableAuthenticators` 按设备实际可用性动态选择认证器；无可用认证器时明确提示需恢复流程，不静默降级；`DEVICE_CREDENTIAL` 路径不设 negative button（避免 `PromptInfo.build()` 抛异常导致启动崩溃） |
| 进程被 dump | 敏感缓冲区显式清零；不落盘密钥；Export PIN 不落盘 |
| 语义去重指纹泄露 | fingerprint 是 secret-derived 材料：按需计算、不落库、**不放入 plaintext package header** |

## 已确认的安全事实（实测）

- 旧格式 KDF 参数可被攻击者设置为任意值。header 声称 8 GiB 内存时，
  直接运行 Argon2id 会因分配失败 **abort 进程**。因此 importer 必须在
  Argon2 前校验 KDF 参数上限（见 `docs/LEGACY_IMPORT.md` §7）。
- Dart `cryptography` 2.9.0 的 XChaCha20-Poly1305 与 IETF 草案一致
  （subkey = HChaCha20(key, nonce[0:16])，subnonce = 4 零字节 + nonce[16:24]）。

## 安全假设（明确不保证）

- 不做云托管密钥（v1 无服务器密钥托管）。
- 不做应用内主密码（日常解锁仅系统生物识别/设备凭据）。
- 剪贴板中的验证码在可配置时间后清除，但剪贴板本身是系统级风险。
- 旧库导入的 Developer 数据不导入（除非转只读 secure note），
  以"未导入报告"形式保留。

## 依赖

- Argon2id、XChaCha20-Poly1305、ChaCha20-Poly1305、AES-256-GCM、
  HKDF-SHA256、Ed25519（更新签名）→ Bouncy Castle / Android 平台。
- 数据库加密 → SQLCipher（实现阶段锁定版本）。
