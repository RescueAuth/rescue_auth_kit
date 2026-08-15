# RescueAuthKit

[English](README.md) | [中文](README.zh-CN.md)

RescueAuthKit 是一个很小但很"偏执"的 **Android 个人安全库**：可靠的 TOTP
认证器 + 恢复码 + 开发者密钥存储，并提供可移植、加密、merge-first 的
导出/导入格式，让你可以在设备间迁移数据而不用猜哪个应用支持什么。

仓库主体是一个**原生 Android 应用**（Kotlin + Jetpack Compose + Room/SQLCipher，
加密数据库 + 生物识别解锁）。旧 Flutter 重写历史已冻结在 `legacy-vX.Y.Z` tag 中，
仅作参考与一次性迁移。

## 项目结构

```
.
├── app/                  # Android 应用模块（Compose UI + Room）
│   └── src/
│       ├── main/         # 应用源码（com.rescueauth.v2）
│       ├── test/         # Robolectric 单元测试
│       └── androidTest/  # instrumented 测试（Firebase Test Lab）
├── core/                 # 平台无关的 Kotlin 核心（codec、totp、import...）
│   └── src/main/kotlin/com/rescueauth/v2/
├── docs/                 # 设计报告（PHASE*、ADR、THREAT_MODEL、...）
├── gradle/               # Gradle wrapper + version catalog
├── legacy-fixtures/      # 冻结的 legacy v1 fixtures（用于导入测试）
├── release/              # 公开的 Android 签名证书元数据
├── scripts/              # 构建 / 发布 / CI 辅助脚本
├── tools/                # 图标与 interop fixture 生成器
├── build.gradle.kts      # 根构建文件
├── settings.gradle.kts   # 包含 :app 与 :core
├── AGENTS.md             # AI / 人类维护者契约
├── PRODUCT.md            # 产品范围
└── ROADMAP.md            # 路线图与阶段跟踪
```

## 分支 / 版本 / 发布策略

> **`main` 是开发分支；release 是 tag。**

### Development（开发）

`main` 是活跃开发分支，不是稳定发布。它可能包含尚未进入稳定版本的变更。
**不要**把当前的 `main` 当作稳定版本。

### Legacy RescueAuth（旧版发布）

旧 Flutter 应用已冻结并保留在 tag：

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

当前 applicationId：

```
com.rescueauth.v2
```

正式的稳定源码快照以 tag 标识。

### 构建入口

| 用途 | 入口 |
|--------|-------------|
| 开发（真机 smoke 用的 debug APK） | `main` / feature / fix 分支上的 Web 触发器 **"Build debug RescueAuth"** |
| 完整回归测试（手动，无密钥） | 任意分支上的 Web 触发器 **"Run full RescueAuth test suite"** |
| Firebase 云上设备测试（手动，仅 `main`） | `main` 上的 Web 触发器 **"Run Firebase device tests"** |
| 正式生产发布 | 推送 `rescueauth-vX.Y.Z` **release tag**（tag-only 流水线） |

`main` / feature / fix 分支**绝不**直接进行生产发布。

**测试触发策略：** 普通 push / PR merge / `main` update 都不自动运行任何
RescueAuth 测试（含 Firebase Test Lab）。需要完整回归时，由 owner 手动点击
**"Run full RescueAuth test suite"**（core JVM、Robolectric、lint、
`assembleDebug`、`assembleDebugAndroidTest`，无密钥）；需要云上设备验证时，
在 `main` 上点击 **"Run Firebase device tests"**（仅此按钮可使用 FTL 凭据）。
**"Build debug RescueAuth"** 是快速真机 smoke APK 工厂：只执行
`assembleDebug`、校验产出的 APK 并上传，**不**运行 core/Robolectric 测试或
lint，因此**不能**作为"完整回归已通过"的依据。

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

## 构建

需 JDK 17 + Android SDK 35。

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

- Core JVM 测试：`./gradlew :core:test`
- App Robolectric 单元测试：`./gradlew :app:testDebugUnitTest`
- Lint：`./gradlew :app:lintDebug`
- Instrumented 测试通过 `main` 上的手动按钮 **"Run Firebase device tests"**
  在 Firebase Test Lab 执行（见 `docs/FIREBASE_TEST_LAB.md`）。

## 文档

- **维护契约**：[`AGENTS.md`](AGENTS.md)
- **产品范围**：[`PRODUCT.md`](PRODUCT.md)
- **路线图与阶段**：[`ROADMAP.md`](ROADMAP.md)
- **设计 / 阶段报告与 ADR**：[`docs/`](docs/)
  （PHASE 报告、ADR、PACKAGE_FORMAT、THREAT_MODEL、UPDATE_PROTOCOL、
  LEGACY_IMPORT、RELEASE_PROVISIONING、FIREBASE_TEST_LAB）

## 旧 Flutter 应用（v1.x，已冻结）

原始跨平台 Flutter 应用冻结于 tag `legacy-v1.2.0`，保留在 `legacy-vX.Y.Z` tag
中。它不再是活跃开发目标，仅用于参考与向 v2 原生应用的一次性迁移。
