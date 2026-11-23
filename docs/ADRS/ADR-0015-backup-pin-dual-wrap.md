# ADR-0015：VaultKey 双槽独立包装（Android Keystore + 备用 PIN 保底）

- 状态：**Accepted**（2026-11-17，用户评审批准）
- 日期：2026-11-17
- 关联：ADR-0002（BC 1.85 XChaCha20-Poly1305）、ADR-0003（VaultKey/Keystore/会话）、ADR-0011（Sensitive Action one-shot）、ADR-0014（Keystore 作废边界）

## 背景

`ADR-0003` 与 `ADR-0014` 将随机生成的 32 字节 `VaultKey` 唯一绑定到 Android Keystore 认证型密钥（`setUserAuthenticationRequired(true)`）。这一单路径模型在两类真实场景下形成死局：

1. **无安全锁屏设备不可用**：未设置系统锁屏（仅滑动解锁）的设备无法生成或使用认证型 Keystore 密钥，应用直接停留在阻塞页，无法建库或解锁。
2. **移除系统锁屏导致永久锁死**：用户在有锁屏时创建保险库后，若在系统设置中关闭锁屏，Android 平台会永久销毁认证型 Keystore 密钥（平台硬约束，无开关）。此时 `VaultKey` 无法解包，若用户此前未手动导出 `.rakpkg`，本机数据库永久不可恢复。

同时，项目明确维持 `AGENTS.md` 架构边界第 5 条：**不恢复全局 Master Password**，即 `VaultKey` 绝不由用户口令派生，有系统锁屏设备的日常解锁仍保持零记忆的生物识别/系统凭据体验。

## 决策

### 1. 双槽独立包装（Dual-Slot Envelope）

保持 256-bit 随机 `VaultKey`（`SecureRandom` 生成）与 SQLCipher 数据库层完全不变，允许由两个独立的本机密钥槽分别包装同一把 `VaultKey`，任一有效槽解包成功即可打开保险库：

```
随机 VaultKey (32 bytes, CSPRNG)
  ├─ 槽 A（系统槽，vault_key_wrapped.b64）
  │    └─ AndroidKeyStore AES-256-GCM 不可导出认证型密钥（零记忆主路径）
  └─ 槽 B（备用 PIN 槽，vault_key_pin_wrapped.bin）
       └─ 备用 PIN + 16B 随机 Salt ──Argon2id v13──▶ 32B KEK
            └─ XChaCha20-Poly1305（24B 随机 Nonce，AAD = 66B Envelope Header）包装 VaultKey
```

- **单库不变量扩展**：当系统槽文件、备用 PIN 槽文件或 SQLCipher 数据库文件中**任意一项**存在时，`SessionManager.needsFirstRunSetup()` 返回 `false`，严禁静默重新生成第二把 `VaultKey` 覆盖既有库。
- **本机隔离**：两份密文均存于 `filesDir`，受 `allowBackup="false"` 与 `dataExtractionRules` 约束，不随系统云备份或换机传输导出；跨设备迁移仍唯一通过 `.rakpkg` 完成。

### 2. 备用 PIN 规范与密码学参数

- **PIN 格式策略（B2）**：6–128 位 ASCII 数字（`0-9`），与导出包 `PinPolicy` 统一，设置时需二次确认。
- **密码学原语复用**：直接复用 `:core` 模块已验证的 Bouncy Castle 1.85 **Argon2id v13** 与 **XChaCha20-Poly1305**，不引入任何新密码学依赖。
- **KDF 成本档位（D2）**：默认 `memoryKiB = 65536`（64 MiB）、`iterations = 3`、`parallelism = 1`、`outputLength = 32`（`costUnits = 196,608`），严格落在 `PackageRuntimePolicy` 的移动端安全预算上限（`128 MiB` / `512,000 units`）之内，避免 Android ART Java Heap 发生 `OutOfMemoryError`。
- **二进制信封格式（`RAVKPIN1`）**：固定 114 字节（8B Magic `"RAVKPIN1"` + 1B Version `1` + 1B KDF ID + 1B AEAD ID + 1B Reserved + 12B KDF 参数 + 1B SaltLen + 16B Salt + 1B NonceLen + 24B Nonce + 48B Ciphertext+Tag），整个 66B 头部作为 AEAD AAD 防止参数降级或篡改。

### 3. 适用范围与生命周期（A3 + C2 自愈与兜底）

1. **统一首启 PIN 页面**：所有设备的首次建库都进入同一个备用 PIN 设置页面，页面根据设备认证能力决定可用操作。具备 Strong 生物识别或设备凭据（Device Credential）的设备显示「稍后设置」；无安全锁屏设备（No Secure Lock）不得显示该入口，必须设置备用 PIN 才能进入应用。Weak-only 生物识别不得当作 Strong 能力，也不得在文案中暗示它保护 Android Keystore 密钥。
2. **有系统认证能力且设置备用 PIN**：用户在该页面提交 PIN 后，调用 `SessionManager.createVaultWithSystemAndBackupPinAndOpen(pin)` 完成双槽首启建库。实现生成一把随机 `VaultKey`，先写入 Android Keystore 系统槽（槽 A），再写入备用 PIN 槽（槽 B），两槽与 SQLCipher 数据库全部成功后才提交会话；成功后 `refreshSecurityStatus()` 必须同时报告 `hasSystemKeySlot = true` 与 `hasBackupPin = true`。任一槽写入失败、数据库打开失败或状态机进入失败，都必须整体回滚：按已写状态清理槽 A / 槽 B 及临时文件，关闭已打开的数据库，清零临时 key，状态机回到 LOCKED，`needsFirstRunSetup()` 仍为 true，安全状态不报告任何误导性成功。严禁静默降级为仅系统槽或仅 PIN 槽：系统槽成功而 PIN 槽失败、PIN 槽成功而系统槽失败均属于整体失败，UI 停留在 PIN 页面展示通用错误。
3. **有系统认证能力且选择「稍后设置」**：这是用户在统一首启页面显式选择的系统槽单槽建库路径，不触发双槽 API。建库仅写入槽 A；后续可在解锁状态下通过「设置 → 安全」添加备用 PIN。已有旧库保持零迁移成本兼容，解锁后可随时添加、修改或（在槽 A 正常时）移除备用 PIN。
4. **无系统认证能力设备**：按设备认证能力拆分三种情况。
   - **No Secure Lock**：缺少设备安全锁屏，首启页面必须阻塞并设置备用 PIN，建库时仅写入槽 B；日常启动与重锁后通过备用 PIN 解锁。该路径是平台能力决定的显式分支，不得由双槽建库失败静默转入。
   - **Weak-only 且无设备凭据**：弱生物识别不得当作 Strong 能力，也不显示「稍后设置」；页面使用 Weak-only 专用文案并必须设置备用 PIN，按能力探测结果走单槽 PIN 建库路径。
   - **Weak-only 且存在设备凭据**：设备凭据单独即可用于系统槽，`hasSystemAuth = true`，统一首启页面按 DUAL_SLOT 处理；由于 `hasSystemAuth = true`，该类设备显示「稍后设置」，用户显式选择时仍可走仅系统槽建库路径。弱生物识别仅影响文案口径，不得暗示它保护 Android Keystore 密钥。
5. **忘记备用 PIN 与系统槽自愈**：
   - 若设备系统槽（槽 A）完好，用户忘记备用 PIN 不影响指纹/锁屏进库；进库后通过一次系统重认证（`SensitiveAction`）即可直接用内存中的 `VaultKey` 覆盖重设新备用 PIN，无需旧 PIN。
   - 若系统槽因曾移除锁屏而作废（`KeyInvalidated`），启动流自动回退至备用 PIN（槽 B）解锁；当用户恢复系统安全锁屏后，可在「设置 → 安全」通过一次系统认证重新绑定修复槽 A（原地自愈）。
6. **双槽均不可恢复时的重置兜底（C2）**：当槽 A 已作废/不存在且未设置或遗忘槽 B 时，阻塞/恢复页提供带二次高危确认的「重置本机保险库」入口，清理失效密文与本机数据库后回到初始引导页，以便用户重新建库或导入 `.rakpkg` / `.rakvault` 备份。

### 4. 敏感操作重认证回退（E1）与防暴力冷却

- **保持 `ADR-0011` one-shot 语义**：`SensitiveActionGate` 绑定精确 `SensitiveActionRequest`（`action + target`）且一次消费即失效的契约不变。
- **系统优先、备用 PIN 回退（E1）**：当系统生物识别/设备凭据可用且系统槽正常时，敏感操作继续调用系统 `BiometricPrompt`；仅当设备无安全锁屏或系统槽不可用且已配置备用 PIN 时，回退至应用内备用 PIN 验证弹窗（通过对磁盘上的槽 B 密文执行一次 AEAD 解密验真并立即清零临时缓冲区，不在内存中常驻明文 PIN 或 KEK）。
- **应用层连续错误退避**：所有应用内备用 PIN 验证（启动解锁与敏感操作验证）共享持久化错误计数与退避冷却（连续失败 ≥5 次冷却 30 秒，≥6 次冷却 60 秒，≥8 次冷却 5 分钟；验证成功后立即清零），防止杀进程绕过在线连猜。

## 后果

- **收益**：彻底消除无锁屏设备不可用及取消系统锁屏导致的永久锁死问题；旧库零迁移成本平滑升级；上层业务 ViewModel 无需改动即可在无锁屏设备上完成高敏感操作闭环。
- **残余风险**：若设备被物理获取且攻击者具备 root / 取证级文件提取能力，6 位纯数字备用 PIN 的离线暴力搜索空间小于随机 256-bit 硬件密钥；通过 64 MiB / 3 轮 Argon2id 提高单次尝试成本，并允许用户设置更长 PIN（最高 128 位）或在槽 A 完好时选择不启用/移除槽 B。
