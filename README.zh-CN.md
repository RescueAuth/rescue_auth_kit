# RescueAuthKit

[English](README.md) | [中文](README.zh-CN.md)

RescueAuthKit 是一个很小但很"偏执"的 2FA 密钥库应用，核心目标只有一个：
把导入/导出（迁移与恢复）这件事做得可靠、可验证。

> **注意**：仓库正在 `v2/` 下重写为**原生 Android 应用**（Kotlin + Room/SQLCipher，
> 加密数据库 + 生物识别解锁）。下方旧 Flutter 应用冻结于 tag `v1.2.0`，仅作
> 参考与一次性迁移。v2 状态与构建命令见 [`v2/AGENTS.md`](v2/AGENTS.md)。

## 我为什么写这个

很多认证器应用在"迁移数据"这件事上体验很差：要么不支持导出，要么格式不通用，
要么流程不清晰。这个项目的优先级正好相反：

- 数据集中存放在一个加密 Vault 文件里
- 备份与恢复是第一优先级能力
- 目标是做到"手机 <-> 桌面端"可验证的闭环迁移

## 这个项目的特别之处

- 单一加密 Vault 文件，可以自由复制与保存
- 强密码学方案（基于主密码）：
  - Argon2id 作为 KDF
  - XChaCha20-Poly1305 作为 AEAD
- 强调跨设备迁移闭环：
  - 一端导出，另一端导入，再验证同样的验证码

## RescueAuth v2（Android 原生重写）

- **位置**：`v2/` — Kotlin + Jetpack Compose + Room/SQLCipher。
- **状态**：阶段 0/1 + phase1-fix + 阶段 2（加密数据库、VaultKey/Keystore、
  安全会话、自动锁、遮罩 + FLAG_SECURE）均已合并进 `main`；
  `phase2-blocker-hotfix`（PR #6）修复了 BiometricPrompt 启动崩溃并使平台
  测试可编译、断言真实化。
- **构建**：`cd v2 && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
  （需 JDK 17 + Android SDK 35）。Instrumented 测试（6 用例）已编写且可编译，
  执行仍需真机/模拟器。
- **文档**：见 `v2/docs/`（PHASE 报告、ADR、LEGACY_IMPORT、BACKUP_FORMAT、
  THREAT_MODEL、UPDATE_PROTOCOL）。

## 旧 Flutter 应用（v1.x，已冻结）

原始的跨平台 Flutter 应用，冻结于 tag `v1.2.0`。仍完整可用，但不再是活跃
开发目标。

---

## Vault 数据模型

从 v1.1.0 起，Vault 采用三层账户中心模型：

```
提供商 ServiceProvider（例如"GitHub"）
└── 账户 Account（例如"user@example.com"）
    └── 凭据 Credential[]（TOTP | 恢复码）
```

- 一个提供商可以挂多个账户。
- 一个账户里可以同时挂 TOTP 凭据和恢复码凭据，按存储顺序展示。
- 账户支持重命名、移动到其他提供商、合并到其他账户（合并会把源账户下的凭据
  追加到目标账户，再删除源账户）。

## 功能

- 首页是"提供商"列表，逐级下钻到账户和凭据。
- TOTP 动态码（实时刷新、倒计时、复制），只在账户详情页渲染。
- 恢复码：新增、查看、一键全选复制、就地编辑、移动到其他账户、删除。
- TOTP 导入：
  - Android：扫码导入
  - 桌面端：粘贴 `otpauth://totp/...`
- 添加凭据时内嵌"保存到"选择器，三种模式可选：新建提供商+账户、已有提供商+
  新建账户、已有账户（带搜索）。
- 加密备份导出/导入（核心能力）。
- 中英双语界面。

## 当前重点支持平台

- Windows 桌面端
- Android

暂不支持 Web（Vault 使用本地文件 IO）。

## 本地运行

```bash
flutter pub get
flutter analyze
flutter test
flutter run -d windows
```

Android 运行方式：

```bash
flutter devices
flutter run -d <device-id>
```

## 备份 / 恢复（推荐验证流程）

1. 在设备 A 创建并解锁 Vault
2. 导入一些 TOTP / 恢复码
3. 在设置页导出
4. 在设备 B 用同一主密码导入该 Vault 文件
5. 验证两端生成的 TOTP 一致

## 从 1.0.x 升级

1.0.x 的 Vault 在 1.1.0 第一次解锁时会自动迁移：

- 每条旧 TOTP 条目各成一个账户；issuer 相同的条目会归到同一个提供商下。
- 每个旧恢复码集合各成一个账户，挂在它专属的提供商下；之后你可以用账户菜单
  里的"合并到…"或"移动到…"自行整理。
- **1.1.0 写过的 Vault 文件，旧版本（1.0.x）无法再打开。** 如果需要保留回退路径，
  请先用 1.0.x 导出一份备份。

## 注意与限制

- 不支持 `otpauth-migration://`
- 忘记主密码就无法解密 Vault
- TOTP 凭据故意设计为不可修改：改任何字段都等于换密钥，要变更只能删除后重新添加
