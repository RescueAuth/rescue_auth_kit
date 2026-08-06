# THREAT_MODEL.md — 威胁模型与安全假设

> 阶段 0/1 版。随实现推进持续更新；任何加密/备份/导入变更必须先更新本节。

## 资产

| 资产 | 敏感度 | 存储 |
| --- | --- | --- |
| TOTP secrets | 高 | SQLCipher 加密数据库 |
| 恢复码 | 高 | SQLCipher 加密数据库 |
| `VaultKey`（256-bit 随机） | 极高 | Android Keystore 不可导出密钥包装 |
| `BackupKey`（256-bit 随机） | 极高 | 设备内由 VaultKey 保护 + 恢复套件 |
| 旧密码（导入时） | 高 | 仅内存，用后即清零 |

## 信任边界

1. **设备边界**：攻击者获得解锁后设备 = 可读 TOTP/恢复码（与主流认证器一致）。
2. **锁屏边界**：设备锁定后，未解锁时数据库关闭、密钥材料清零、UI 遮罩。
3. **离线备份边界**：备份文件仅用 `BackupKey`（AEAD）加密；无恢复套件不可解密。
4. **网络边界**：除更新检查（HTTP GET 静态 manifest + 签名验证）外，应用离线运行。

## 主要威胁与缓解

| 威胁 | 缓解 |
| --- | --- |
| 设备丢失 | 恢复套件（二维码+密钥文件）离线恢复；`BackupKey` 与 `VaultKey` 分离 |
| 锁屏数据泄露 | 数据库关闭 + 密钥清零 + 后台遮罩 + `FLAG_SECURE` |
| Keystore 密钥失效 | 不得删库；引导用户用恢复套件/备份恢复 |
| 恶意/损坏备份文件 | AEAD 先验 MAC；KDF 参数上限校验（防 OOM）；大小上限 |
| 恶意 `.rakvault` | header 校验（magic/version/KDF 上限）后再执行 Argon2 |
| 更新源被篡改 | Ed25519 签名验证 `latest.json`；APK 校验大小+SHA-256+签名证书 |
| 进程被 dump | 敏感缓冲区显式清零；不落盘密钥 |

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
