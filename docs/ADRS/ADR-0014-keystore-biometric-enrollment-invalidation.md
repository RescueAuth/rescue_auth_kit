# ADR-0014：wrap key 不随生物识别变更作废（数据丢失修复）

- 状态：**Accepted**（2026-08-30，用户评审）
- 日期：2026-08-30
- 关联：ADR-0003（VaultKey/Keystore/会话）、AGENTS.md 禁区 #1（Keystore 配置变更需人工评审）

## 背景

`AndroidKeystoreVaultKeyCrypto.ensureKey()` 生成 wrap key 时只设置了
`setUserAuthenticationRequired(true)` 与 30 秒有效期，
**未调用 `setInvalidatedByBiometricEnrollment(false)`**——该参数平台默认为
`true`。

后果是一条链式数据丢失路径：

```
用户新增/删除任意一枚指纹
  → wrap key 被 AndroidKeyStore 永久作废（默认行为）
  → VaultKey 无法解包
  → SQLCipher 数据库（TOTP 密钥、恢复码、Developer Vault）永久不可恢复
  → UI 只显示 Blocked"保险库无法打开"死局页，无任何恢复路径
```

"添加一枚指纹"是最日常的用户操作，把它映射成"整库永久销毁"是产品级缺陷，
不是可接受的安全权衡。对照 1Password / Bitwarden 等消费级密码库，均不采用
该默认行为。

## 决策

**生成 wrap key 时显式设置 `setInvalidatedByBiometricEnrollment(false)`。**

1. 认证要求完整保留：unwrap 仍需成功 BiometricPrompt / Device Credential
   （30 秒有效期窗口不变）；
2. 生物识别** enrollment 变更**（加/删指纹）不再作废 wrap key；
3. **移除全部安全锁屏**仍会作废认证型密钥——Android 平台硬约束，无开关。
   该场景由启动流的恢复中心兜底（.rakpkg / .rakvault / 新建，见
   `VaultUnlockOutcome.KeyInvalidated` 路由）；
4. v2 尚未正式发布，**无存量旧 key**，本变更直接生效，无需迁移/版本标记
   （用户确认 2026-08-30）。

## 理由

- 安全主体不变：密钥仍不可导出、解包仍需用户认证；
- 残余权衡：极端场景（攻击者短暂持有设备并录入了指纹，用户随后删除指纹）
  下防御略弱——但此时攻击者的指纹模板已从设备移除，无法再通过认证，
  实际可利用性极低；
- 换取的是消除"日常操作 → 整库永久丢失"这一灾难路径。

## 后果

- `AndroidKeystoreVaultKeyCrypto` 是 Robolectric 不可达的生产路径
  （JVM 无 AndroidKeyStore），本变更无法用 JVM 单测覆盖，验证落在
  真机/模拟器 instrumented 冒烟上；
- AGENTS.md 禁区 #1 评审要求已满足（用户作为评审人于 2026-08-30 批准）；
- 恢复中心（Blocked 页改造）作为平台硬约束场景的兜底，随 UI 改版实施。
