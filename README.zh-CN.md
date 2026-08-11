# RescueAuthKit

[English](README.md) | [中文](README.zh-CN.md)

RescueAuthKit 是一个很小但很"偏执"的 **Android 个人安全库**：可靠的 TOTP
认证器 + 恢复码 + 开发者密钥存储，并提供可移植、加密、merge-first 的
导出/导入格式，让你可以在设备间迁移数据而不用猜哪个应用支持什么。

> **注意**：仓库正在 `v2/` 下重写为**原生 Android 应用**（Kotlin + Room/SQLCipher，
> 加密数据库 + 生物识别解锁）。下方旧 Flutter 应用冻结于 tag `legacy-v1.2.0`，仅作
> 参考与一次性迁移。v2 状态与构建命令见 [`v2/AGENTS.md`](v2/AGENTS.md)。

## 分支 / 版本 / 发布策略

> **`main` 是开发分支；release 是 tag。**

### Development（开发）

`main` 是活跃开发分支。它可能包含尚未进入稳定版本的功能变更。

**不要**把当前的 `main` 当作稳定版本。

### Legacy RescueAuth（旧版发布）

旧版发布使用：

```
legacy-vX.Y.Z
```

例如：

```
legacy-v1.0.0
legacy-v1.0.1
legacy-v1.1.0
legacy-v1.2.0
```

旧版 applicationId：

```
com.xincy.rescue_auth_kit
```

### Current RescueAuth（当前 RescueAuth 发布）

当前发布使用：

```
rescueauth-vX.Y.Z
```

例如：

```
rescueauth-v1.0.0
rescueauth-v1.0.1
rescueauth-v1.1.0
...
```

当前 applicationId：

```
com.rescueauth.v2
```

### Development（开发）

`main` 是活跃开发分支，不是稳定发布。正式的稳定源码快照以 tag 标识。

### 构建入口

| 用途 | 入口 |
|--------|-------------|
| 开发（真机 smoke 用的 debug APK） | `main` / feature / fix 分支上的 Web 触发器 **"Build debug RescueAuth"** |
| 正式生产发布 | 推送 `rescueauth-vX.Y.Z` **release tag**（tag-only 流水线） |

`main` / feature / fix 分支**绝不**直接进行生产发布。

## 我为什么写这个

很多认证器应用在"迁移数据"这件事上体验很差：要么不支持导出，要么格式不通用，
要么流程不清晰。这个项目的优先级正好相反：

- 数据集中存放在本机加密 Vault 里
- 导出/导入是第一优先级能力
- 目标是可靠的手机到手机迁移与恢复

## 这个项目的特别之处

- Android-only、local-first、加密个人安全库（无账号/登录后端、无云同步）
- 三大正式能力：**Authenticator**（TOTP + 恢复码）、**Developer Vault**
  （签名密钥/API 凭据/SSH 密钥/环境变量/通用密钥）、**Portable Vault
  Package**（手动导出、per-export PIN、merge-first 导入）
- 强本地加密：
  - Android Keystore 包装的 VaultKey + SQLCipher 数据库
  - Portable Package 由每次导出的 per-export PIN 保护
- merge-first 导入：导入即合并到当前 Vault（无覆盖式 restore 语义）

## RescueAuth v2（Android 原生重写）

- **位置**：`v2/` — Kotlin + Jetpack Compose + Room/SQLCipher。
- **状态**：阶段 0/1/2 已收口合并进 `main`（加密数据库、VaultKey/Keystore、
  安全会话、自动锁、遮罩 + FLAG_SECURE）；数据库 instrumented 测试
  （`RescueAuthDatabaseInstrumentedTest`，6 用例）已在 Firebase Test Lab
  真实执行 **6/6 PASS**（MediumPhone.arm / API 33）。**Phase 3 进行中**：
  Phase 3A（Package + Merge Foundation）已在 PR #18 实现 —— 逻辑导出包模型、
  stable identity + semantic fingerprint、merge planner、schema v2
  （见 `v2/docs/PHASE3_REPORT.md`）。
- **构建**：`cd v2 && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
  （需 JDK 17 + Android SDK 35）。Instrumented 测试经 `main` push 在
  Firebase Test Lab 执行（见 `docs/FIREBASE_TEST_LAB.md`）。
- **文档**：见 `v2/docs/`（PHASE 报告、ADR、LEGACY_IMPORT、PACKAGE_FORMAT、
  THREAT_MODEL、UPDATE_PROTOCOL）与正式路线图 [`v2/ROADMAP.md`](v2/ROADMAP.md)。

## 旧 Flutter 应用（v1.x，已冻结）

原始的跨平台 Flutter 应用，冻结于 tag `legacy-v1.2.0`。仍完整可用，但不再是活跃
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

> 以下为**旧 Flutter v1.2.0（已冻结）**的功能清单。v2 目标产品范围见
> [`v2/ROADMAP.md`](v2/ROADMAP.md)，当前实现进度见
> [`v2/AGENTS.md`](v2/AGENTS.md)。
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

## 当前重点支持平台（旧 v1.2.0，已冻结）

- Windows 桌面端
- Android

暂不支持 Web（Vault 使用本地文件 IO）。

> **v2 目标平台：Android only**。Windows / macOS / Linux / iOS / Web 客户端
> 明确不开发（见 `v2/ROADMAP.md §1`）。上方为本节旧 Flutter 应用事实。

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

## 备份 / 恢复（旧 v1.2.0 流程，已冻结）

> v2 已改为 **Portable Vault Package**：手动 Export（per-export PIN）+ merge-first
> Import（无主密码、无 automatic backup）。下方为旧版事实。

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

- 旧 v1 不支持 `otpauth-migration://`（v2 新增 **仅导入** 支持，作为外部导入
  适配器，见 `v2/ROADMAP.md §4.3`）。
- 忘记主密码就无法解密旧版 Vault（v2 不恢复全局主密码）。
- TOTP 凭据故意设计为不可修改：改任何字段都等于换密钥，要变更只能删除后重新添加
  （v2 保留该规则，见 `v2/ROADMAP.md §4.2`）。
