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

## 5. Flat UI（2026-09-07）

当前视觉方向为 macOS 风格的扁平原生工具界面：中性灰画布、白色/石墨色面板、
细描边和蓝色操作强调。保留 Card-First 的分组语义，取消厚重阴影和大面积彩色卡片。

- 卡片：12 dp 标准圆角、10 dp 行圆角，0.5 dp 描边，静止/按下均无阴影。
- 列表：16 dp 内容内边距、8 dp 卡间距；操作触摸目标至少 48 dp。
- 全局按钮：统一 `RescueAuthButton` / `RescueAuthOutlinedButton`，8 dp 圆角。
- 页面工具栏和底部导航用细线分层；导航选中态用强调色图标与文字。
- 表单、详情、导入导出、空状态和启动页沿用同一套 tokens；深浅主题均使用中性色。
- 既有主题存储 ID、导航、认证与加密契约不变。图标/数据截图只使用无秘密的合成元数据。
