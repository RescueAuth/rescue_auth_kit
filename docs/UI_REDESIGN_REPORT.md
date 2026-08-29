# UI_REDESIGN_REPORT.md — 当前原生 UI 重构记录

> 状态：Implemented（2026-08-28）
>
> 本报告描述当前生产 UI 的结构与验证结果。产品范围、数据格式、安全语义和
> 阶段状态仍分别以 `PRODUCT.md`、`ROADMAP.md`、`AGENTS.md` 和相关 ADR 为准。

## 目标

- 删除旧的 Compose 视觉实现和不可达组件。
- 为 Android 原生应用建立统一、克制的蓝紫色安全工具视觉语言。
- 保留现有 Authenticator、Developer Vault、Export/Import、Legacy Import、
  Search、Sensitive Action re-auth 和导航契约。
- 不改变 Room schema、Portable Package 格式、加密算法、签名配置或
  `applicationId`。

## 当前结构

```text
MainActivity
  └─ RescueAuthTheme
      ├─ Startup splash / intro / auth / opening / blocked hosts
      └─ RescueAuthApp
          ├─ Authenticator → Provider → Account → TOTP / Recovery
          ├─ Developer Vault → list → detail → typed form
          └─ Settings → Export / Native Import / Legacy Import / About
```

所有生产页面使用 `RescueAuthPageHeader` 作为页面标题层级，列表行、分组块、
条目和表单区使用 `RescueAuthCard` / `RescueAuthRowCard`。卡片形状、边框、
内边距和列表节奏来自 `CardTokens`、`CornerRadius` 和 `Spacing`，页面不再各自
定义一套圆角和表面颜色。

## 视觉系统

- 背景使用冷中性灰白，卡片使用相邻的中性表面层级。
- 主品牌色为蓝紫色，Primary、Secondary、Container 和导航选中态保持同一
  色相家族；错误色仍是独立的安全语义红色。
- 标题、说明、元数据和敏感值采用固定的 Material 3 type scale；TOTP、恢复码
  和已授权敏感值使用等宽字体。
- 页面不使用营销式大 Banner、装饰性渐变或互相冲突的彩色主题。
- 动作按钮使用图标和清晰的语义标签；敏感值默认隐藏，reveal/copy 仍由
  fresh one-shot re-auth gate 控制。

## 清理范围

以下组件只有自身 Preview 或旧测试引用，已经从生产源树删除：

- `ui/components/Breadcrumb.kt`
- `ui/components/PinIndicator.kt`
- `ui/components/ProviderAccountListItem.kt`
- `ui/components/TotpCard.kt`

Breadcrumb 顶栏被统一页面 Header 取代；Authenticator 现在由一个明确的
Provider/Account 卡片层级展示 TOTP，避免旧卡片和新卡片并存。Legacy Import
相关代码没有删除，因为它是产品规定的只读兼容能力，并且与 Native Package
Import 保持独立。

## 启动与性能

- 首帧使用明确的 splash/opening 状态，不先闪出空列表。
- 生产环境的 Keystore 解包、SQLCipher native load、Room 开库和迁移在
  `Dispatchers.IO` 执行。
- Authenticator ViewModel 提升到 app shell，底部 Tab 切换不会销毁 Room
  collection。
- TOTP 结果按周期缓存，账户索引按快照一次构建，倒计时 tick 不再执行
  `accounts × credentials` 的重复扫描。
- 后台期间若开库操作完成，UI 保持遮罩；只有前台且会话仍有效时才进入主界面。

## 兼容性与安全

- Legacy `.rakvault`、Native `.rakpkg`、Merge Planner、Room schema 和所有
  stableId 语义保持不变。
- UI model 不携带 TOTP secret；敏感值只在明确授权的短生命周期内存在内存。
- 未新增日志、截图 fixture、DataStore 或 Bundle 中的敏感数据。
- 旧主题存储 ID 继续保留，历史偏好可读取；所有调色板已映射到蓝紫色家族。

## 验证

提交前执行：

```bash
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew --no-daemon :app:lintDebug
```

本轮源码修复后的 JVM/Robolectric、Debug APK、instrumented APK 编译和 lint 均
通过。当前开发机没有连接 Android 真机或模拟器，因此尚未提供真实设备截图、
生物识别行为验证或 signed release smoke；这些仍属于发布门禁中的剩余风险。
