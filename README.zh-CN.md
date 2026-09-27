<img src="design/brand/shiyifang-logo.svg" width="80" height="80" alt="拾遗坊蓝金钥匙标志">

# 拾遗坊 · RescueAuth

[English](README.md) | [简体中文](README.zh-CN.md)

一个本地优先、加密存储的 **Android 个人安全保险库**，支持 Android 8.0 及以上。
集中保存验证码、恢复码和开发者密钥，通过手动导出的加密数据包在设备间迁移。

## 当前状态

**进入首个原生版本 1.0.0 的发布准备阶段 · 2026-09-27 核对。**

正式功能范围已实现，本轮界面与转场修复已通过本地回归。最近的代码基线 `ae73494` 通过了
420 项核心测试、769 项 App JVM 测试和 87 项 Android 原生测试。
Debug 构建通过；lint 无错误，仍有告警。详见[验证记录](docs/UI_POLISH_REPORT.md)。

原生应用**尚未正式发布**。接下来需要完成生产更新签名与托管、确认签名密钥的独立备份，
并对发布候选版本完成最终云端 / 真机验证，进度集中维护在
[发布检查清单](docs/RELEASE_PROVISIONING.md#release-readiness)。
“v2” 表示原生重写的第二代架构；首个 Android 版本号是 `1.0.0`，不是 `2.0.0`。

## 可以做什么

| 领域 | 能力 |
| --- | --- |
| 账户 | 服务与账户管理、TOTP 倒计时与复制、二维码 / `otpauth` 导入、Google Authenticator 迁移导入、恢复码分组和已用状态 |
| 开发者保险库 | Android 签名密钥与 keystore 文件、API 凭据、SSH 密钥、环境变量集、通用机密；通用字段也可保存用户名 / 密码 |
| 备份与迁移 | 手动导出 `.rakpkg`，每份包使用独立 PIN；全量 / 选择性导出导入、预览并合并到当前保险库、只读导入旧版 `.rakvault` |
| 日常使用 | 非敏感标签搜索、账户置顶、删除撤销、中英双语、浅色 / 深色 / 跟随系统外观 |

通用机密存储暂不包含系统自动填充或 Passkey 管理。
完整范围和边界见 [PRODUCT.md](PRODUCT.md)。

## 数据如何保存

- 数据保存在本机 SQLCipher 加密数据库中，密钥由 Android Keystore 包装保护；
  使用生物识别或设备锁屏凭据解锁。
- 导出、受保护的秘密访问，以及打开既有开发者条目的完整编辑器，均要求重新验证身份。
  应用没有全局主密码。
- 备份**仅手动导出**，每份数据包使用独立 PIN；导入会与现有数据合并。
  没有账号后端、云同步或自动备份。
- 更换设备前应导出数据包，单独保管对应 PIN。
  旧版主密码只用于解密导入 legacy `.rakvault` 文件。
- 检查更新由用户手动触发，并验证清单签名；应用通过外部浏览器打开发布页，
  不自动下载或静默安装更新。

技术细节见[安全模型](docs/THREAT_MODEL.md)、[数据包格式](docs/PACKAGE_FORMAT.md)
和[更新协议](docs/UPDATE_PROTOCOL.md)。

## 构建与试用

需要 **JDK 17**、Android SDK platform **35** 和 build-tools **35**。
设置 `ANDROID_HOME`，或在不提交的 `local.properties` 中配置 `sdk.dir`。
设备使用前需要设置锁屏凭据。

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest :app:lintDebug

# 已连接测试设备或模拟器时：
./gradlew :app:connectedDebugAndroidTest
```

本地 Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，使用开发签名。
原生应用的包名是 `com.rescueauth.v2`，`versionName=1.0.0`、`versionCode=10000`。

## 构建与发布入口

主仓库位于 [CNB](https://cnb.cool/xincy22/rescue_auth_kit)。`main` 是开发分支；
稳定版本在发布门槛满足后，以不可变的 `rescueauth-vX.Y.Z` tag 标识。

| 用途 | CNB 入口 | 实际执行内容 |
| --- | --- | --- |
| 试用 Debug APK | `main` / feature / fix / auto 分支上的 **Build debug RescueAuth** | 构建、校验并上传 Debug APK；不运行单测或 lint |
| 完整回归 | 任意分支上的 **Run full RescueAuth test suite** | Core + App JVM 测试、lint、Debug 与 AndroidTest APK 构建；不读取密钥 |
| 云端设备回归 | `main` 上的 **Run Firebase device tests** | Firebase Test Lab 虚拟设备测试矩阵，与 JVM 测试独立 |
| 生产候选包 | 推送带注释的 `rescueauth-vX.Y.Z` tag | 发布门禁、生产签名、APK 校验及上传附件 |

三个按钮均由 owner 手动触发。普通 push 和 PR 合并不会自动运行测试。
Tag 流水线目前尚不发布签名更新清单；候选包验收与更新渠道发布仍是后续步骤。
操作细节见[发布说明](docs/RELEASE_PROVISIONING.md)和
[Firebase Test Lab](docs/FIREBASE_TEST_LAB.md)。

## 仓库与文档

```text
app/              Android 原生应用、Compose 界面和 Android 测试
core/             平台无关 Kotlin 逻辑与 JVM 测试
design/brand/     最终 Logo 源文件和资源生成说明
docs/             产品契约、发布指南、ADR 与实现记录
legacy-fixtures/  冻结的旧版兼容性测试数据
release/          公开签名证书元数据
scripts/          构建、验证和资源生成工具
```

本地设计草稿、截图、预览页面和构建产物不进入版本控制。
仓库保留最终 Logo 源文件和应用实际使用的资源。

- [产品范围](PRODUCT.md) · [路线图与里程碑](ROADMAP.md)
- [发布检查清单](docs/RELEASE_PROVISIONING.md#release-readiness)
- [文档索引](docs/README.md) · [维护契约](AGENTS.md)

## 从旧版迁移

Flutter 旧版冻结在 `legacy-v1.0.0`–`legacy-v1.2.0` tags 中，包名为
`com.xincy.rescue_auth_kit`，与原生应用独立。
迁移时通过 **从旧版 Rescue Auth 导入** 读取 `.rakvault` 备份；
新应用不直接读取旧应用的私有存储。详见[旧版导入契约](docs/LEGACY_IMPORT.md)。
