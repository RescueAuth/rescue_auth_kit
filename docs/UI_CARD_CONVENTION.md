# UI 卡片式开发约定（Card-First Convention）

> **状态**：生效（2026-08，Issue #70）
> **范围**：全项目 UI（Authenticator / Developer / Settings 及所有从其进入的子页面、对话框、底部表单）
> **强制级别**：项目级 UI 约束。新增/修改 UI 必须遵守，评审时作为必查项。

## 1. 为什么卡片式

卡片式容器把"一条数据 / 一个分组 / 一个条目"封装为视觉上独立、圆角、带表面色的块，
达到以下目标：

- **层次清晰**：Provider → Account → TOTP → Recovery 的逻辑路径在视觉上有明确的分级容器；
- **分组语义**：同属一个条目的字段（名称、码、计数、操作）天然聚合，不散落；
- **一致性**：整个 app 无论进入哪个页面，都用同一套卡片语言，学习成本低；
- **可维护**：全局统一 token / 组件，未来改视觉是一次性改动。

## 2. 强制规则

以下情形**必须**使用卡片容器（而不是裸平铺的 Row / 无背景的列表行）：

| 场景 | 示例 | 必须使用 |
|------|------|----------|
| 列表行 / 条目 | Provider 行、Account 卡、TOTP 卡、Recovery set 卡、Developer entry 卡 | ✅ |
| 分组内容块 | 账户卡内的 TOTP 行、恢复码卡内的码行 | ✅（外层为卡） |
| 表单分区 | Add TOTP 字段、Add/Edit Recovery 字段 | ✅（字段用卡片分组） |
| 弹窗 / 底部表单的说明区 | Migration preview、扫描提示 | ✅ |

**例外**（允许不使用卡片，因为系统组件本身已是标准容器）：

- `ModalBottomSheet` 本身（但内部字段/列表仍按上表卡片化）；
- `AlertDialog` 本身（但内部分组内容仍按上表卡片化）；
- 全屏相机取景框（QrScannerScreen 的相机 Preview）。

## 3. 使用方式

### 3.1 首选：复用统一卡片组件

- `com.rescueauth.v2.ui.components.RescueAuthCard` —— 竖向内容卡片（默认中性 surface 填充、`CardTokens.shape` 圆角、统一内边距）。
- `com.rescueauth.v2.ui.components.RescueAuthRowCard` —— 单行（Row 布局）卡片，content 是 `RowScope`，可直接用 `Modifier.weight(...)`。默认中性 surface 填充。

```kotlin
RescueAuthCard {                       // 标准竖向卡
    Text("Account")
    TotpInlineRow(...)
}

RescueAuthRowCard(onClick = { ... }) { // 可点击单行卡
    Text("GitHub", modifier = Modifier.weight(1f))
    Icon(...)
}
```

### 3.2 或：使用全局 token

如需原生 `Card` / `ElevatedCard`，必须引用统一 token，禁止硬编码颜色/圆角/内边距：

```kotlin
import com.rescueauth.v2.ui.theme.CardTokens

Card(
    shape = CardTokens.shape,
    colors = CardDefaults.cardColors(containerColor = CardTokens.containerColor()),
) { /* ... */ }
```

### 3.3 全局 token 单一来源

`com.rescueauth.v2.ui.theme.CardTokens`（`app/.../ui/theme/Dimens.kt`）定义了：

| token | 含义 |
|-------|------|
| `containerColor()` | 标准卡片填充（`surfaceContainer`） |
| `elevatedContainerColor()` | 强调/可点击卡填充（`surfaceContainerLow`） |
| `shape` | 卡片圆角（`CornerRadius.md`） |
| `contentPadding` | 卡片内边距（`Spacing.md`） |
| `listSpacing` | 卡片间间距（`Spacing.sm`） |
| `listOuterPadding` | 卡片列表距容器边距（`Spacing.md`） |

## 4. 评审检查项（Code Review 必查）

- [ ] 列表行 / 条目是否用了 `RescueAuthCard` / `RescueAuthRowCard`，或引用 `CardTokens`？
- [ ] 是否存在硬编码 `surfaceContainer*`、`RoundedCornerShape(...)`、`Spacing.md` 替代 token 的写法？
- [ ] 新增子页面是否延续卡片式（而非裸平铺）？
- [ ] 卡片内容是否为纯 UI 模型（不含 Room 实体 / secret 明文）？

## 5. Studio 视觉设计（2026-09-13）

本节替代 2026-09-07 的 Flat UI 样式约定。产品范围、Card-First 分组边界、认证与存储契约保持原样。

- 暖白画布与墨色主面板，淡紫色统一用于导航选中态、分类方格和图标底色；薄荷绿只保留在选定的 SVG Logo 中。
- 启动页：小品牌栏 → 深色矢量缎带画面 → 左对齐主标题 → 设备认证说明 → 单一开启操作；旧版导入仍为独立入口。
- 认证器：一张概览、搜索入口、服务目录；不重复堆放多组统计。添加按钮明确区分验证码与新建服务。
- Developer：五类分类方格；窄屏与大字体自动改为单列。分类页只显示元数据条目，揭示与复制仍走原有认证 gate。
- 设置：数据归属说明、备份传输入口、关于入口和设备保护说明，避免重复展示版本与大段标题。
- 24 dp 内容卡 / 18 dp 行卡 / 32 dp 主面板，全由 `CardTokens` 派生；平面填色取代每行描边，细分隔线保留于长分组。
- 认证器 / Developer / 设置的主 banner 共用 144 dp 高度、20 dp 内边距、84 dp 图形；大字体按同一规则增高，不随文案长度改变。
- 浮动墨色导航栏保留三个永久文字标签，选中项以淡紫色背景标识；最小触摸区域仍为 48 dp。
- `StudioEntrance` 为 440 ms 进入动效，`RescueAuthMark` 为 600 ms 单次淡入与轻缩放；无无限动画、认证延迟或持久化动效状态，遵循系统动画时长缩放。
- 品牌图形以 `design/brand/rescueauth-symbol.svg` 为单一来源，并生成 Android VectorDrawable；没有网络图片、字体下载或新的运行时依赖。截图使用 instrumented 测试注入的虚构元数据，验证码为空，不包含秘密。
- 英文 / 简体中文与深色主题同时验证；界面不能依赖固定屏幕高度，长内容必须可滚动。
