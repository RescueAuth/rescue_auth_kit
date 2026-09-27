# 页面模板与组件复用

> 2026-09-27：统一普通页面骨架与滚动表单页。本文记录 UI 结构与使用方式；产品范围和阶段进度仍以 PRODUCT / ROADMAP / AGENTS 为准。

## 现有层级

RescueAuth 使用 Jetpack Compose。一个 `@Composable` 函数可以组合其它
`@Composable` 函数：参数提供数据与回调，内容插槽提供页面片段，状态变化后由
Compose 更新界面。这与 React 的组件、props、children / slots 和状态驱动渲染相近，
但最终渲染的是 Android 界面，不使用浏览器 DOM，也不需要手动 `addView`。

| 层级 | 实现 | 用途 |
| --- | --- | --- |
| 应用框架 | `RescueAuthApp` | 导航、底部 Tab、会话和共享 ViewModel；Authenticator ViewModel 继续由此持有 |
| 通用页壳 | `RescueAuthPageScaffold` | 统一标题、可选返回、标题操作、底部操作、Snackbar / FAB 插槽；正文可为列表、网格或自定义布局 |
| 滚动表单页 | `RescueAuthFormPage` | 在通用页壳上统一返回、卡片间距、正文滚动、键盘和导航栏避让，底部操作固定 |
| 业务页面家族 | `DeveloperDetailScreen` / `DeveloperFormScreen` | 五类 Developer Entry 共享详情 / 添加编辑结构，按类型提供字段 |
| 业务页面家族 | `AuthenticatorScreen` / `DeveloperScreen` | 分别复用服务、账户、凭据层级，以及开发者分类和列表结构 |
| 内容与控件 | `RescueAuthCard`、`RescueAuthRowCard`、`ProtectedFieldsCard`、`RescueAuthSummaryCard`、`RescueAuthTextField` | 普通内容、列表行、受保护字段、统计摘要、输入框 |
| 页面操作 | `RescueAuthActionBar` / `RescueAuthBottomBar` | 双操作组与通用底部位置；共享内边距、键盘和导航栏避让 |

组件位置：`app/src/main/kotlin/com/rescueauth/v2/ui/components/`。
尺寸、圆角和颜色继续来自 `ui/theme/Dimens.kt` 的现有 token。

## 本次合并范围

过去已有公共标题、卡片和按钮，但页面仍分别声明 `Scaffold`、滚动区域和底部布局。
现在普通页面统一使用两层模板：

- 账户首页 / 服务与账户详情、恢复码、开发者首页 / 分类 / 详情、搜索、设置、传输中心和关于页使用 `RescueAuthPageScaffold`。
- 开发者五类添加 / 编辑，以及 Native 导出、Native 导入、Legacy 导入的步骤页使用 `RescueAuthFormPage`。
- 密码 / PIN、条目选择和导入确认复用已选定的平面双操作栏。单操作步骤使用同一个 `RescueAuthBottomBar` 定位。
- Native 与 Legacy 导入预览使用同一套摘要卡与键值行，按来源信息、内容统计、合并摘要分组；冲突和恢复码状态差异仍保留完整提示及禁用条件。

`RescueAuthFormPage` 本身组合 `RescueAuthPageScaffold`，不是另一套独立风格。
原先两个重复的预览键值行实现也已合并；未被任何页面引用的旧 `RescueAuthPageBackground` 包装已移除。

## 选用方式

表单、密码输入、预览、结果步骤优先使用 `RescueAuthFormPage`。页面只传内容与操作，
不再自行复制 `Scaffold`、滚动 Column、底部 padding 或系统 inset：

```kotlin
RescueAuthFormPage(
    title = title,
    onBack = onBack,
    bottomBar = {
        RescueAuthActionBar(
            secondaryLabel = cancelLabel,
            secondaryIcon = Icons.Filled.Close,
            onSecondaryClick = onCancel,
            primaryLabel = continueLabel,
            primaryIcon = Icons.AutoMirrored.Filled.ArrowForward,
            onPrimaryClick = onContinue,
            primaryEnabled = canContinue,
        )
    },
) {
    RescueAuthCard {
        // 此页的说明、输入框或其它组件。
    }
}
```

大量条目的 `LazyColumn` / 网格页面使用 `RescueAuthPageScaffold`，正文消费模板传入的
`PaddingValues`。不把懒加载列表放进 `RescueAuthFormPage` 的竖向滚动正文。

启动 / 锁定页、相机取景、`ModalBottomSheet` 与 `AlertDialog` 保留各自专用容器，
内部继续复用卡片、字段和操作控件。不要为了统一模板破坏全屏相机、弹层或应用导航的职责。

## 复用边界

- 模板只处理布局，不读取仓库、解密内容或执行认证；回调、启用条件和状态仍由原有业务层决定。
- Native 与 Legacy 的 parser、crypto、errors、ViewModel / use case、文件容量限制保持隔离。共享的是展示组件；摘要卡仅接收已经格式化的标签和值。
- Legacy 主密码只要求当前输入非空；Native 导入 PIN 不继承导出 PIN 长度规则。Native 导出继续把校验交给 `PinPolicy`。
- 密码 / PIN 只保存在当前步骤的 `remember` 状态，离开步骤后移除；不使用 `rememberSaveable`。拒绝提交的 CharArray 清零，接受后的数组由相应 ViewModel 按原契约处理。
- Developer 编辑进入完整表单前的 fresh re-auth、删除 Undo、保存期间禁止退出等行为保持原有边界。
- Room、包格式、Legacy 兼容能力、密钥与签名配置没有变更。

## 验证

- `LegacyImportScreenLayoutTest`：滚动正文、统一底部操作、冲突禁用、旧密码独立规则与拒绝数组清零。
- `TransferTemplateUiTest`：Native 导入 PIN 不套用导出规则；导出拒绝数组清零；冲突 / 状态差异阻止导入回调。
- `SelectiveExportImportUiTest`：范围选择、条目选择与提交回调，长页面中的选项可通过滚动到达。
- `PageTemplateVisualTest`：Native / Legacy 预览、固定操作栏、320 dp 窄屏 / 1.5 倍字体、真实输入法与深色主题。
- 截图只使用虚构统计与空密码，不执行真实导入；生成物留在本地，不纳入版本库，可重新运行 `PageTemplateVisualTest` 复核。

当前提交的检查结果见 [UI_POLISH_REPORT.md](UI_POLISH_REPORT.md)。模拟器截图证明原生布局与交互可达性，不替代真机认证或生产发布验证。
