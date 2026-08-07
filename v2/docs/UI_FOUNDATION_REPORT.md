# UI_FOUNDATION_REPORT.md — Compose App Shell & Design System

> 状态：**Implemented（Issue #20）**。
> 本文记录 v2 Compose UI Foundation 的架构、导航、组件清单、设计 token、
> 预览策略与验证结果。它**不触碰** Phase 3A（PR #18）的任何数据 / Package /
> Merge 范围。

## 1. UI architecture

v2 采用 **Jetpack Compose + Material 3**，分层清晰：

```
MainActivity (Compose host, Phase 2 安全语义保持不变)
  └─ RescueAuthTheme (M3 color/typography/spacing)
       └─ RescueAuthApp (Scaffold + NavigationBar + NavHost + SnackbarHost)
            ├─ AuthenticatorScreen
            ├─ DeveloperScreen
            └─ SettingsScreen
              （全部使用 ui/model 纯 UI model，不触碰 Room entity）
```

- **呈现与领域隔离**：所有 Compose 组件只接受 UI model / primitive props，
  Room entity、VaultSnapshot、Package domain 类型一律不进入 UI 层。
- **无假数据进生产**：生产 App 不注入 FakeRepository；示例数据仅存在于
  `@Preview` 与 preview fixture（`DeveloperPreviewData`）。
- **无真实 CRUD**：本轮只搭 UI contract / visual component，不做 TOTP
  生成、otpauth 解析、二维码扫描、复制到剪贴板、CRUD、持久化、SAF 等。

## 2. Navigation structure

三个**一级 destination**（对应 PRODUCT.md 正式产品能力）：

| Route | Screen | 说明 |
| --- | --- | --- |
| `authenticator` | AuthenticatorScreen | Authenticator |
| `developer` | DeveloperScreen | Developer Vault（一级模块，不藏在 Settings） |
| `settings` | SettingsScreen | Settings |

- Navigation-Compose `NavHost` + 底部 `NavigationBar`（`TopLevelDestinations.all`）。
- **未来子层级 contract（仅声明，未接线）**：`RescueAuthRoutes` 里预声明
  `authenticator/account/{providerId}/{accountId}`、
  `authenticator/totp/{credentialId}`、
  `authenticator/recovery/{recoverySetId}`、
  `developer/entry/{entryId}`，留给后续 vertical slice。

## 3. Component inventory（全部支持 Preview）

| 组件 | 文件 | 说明 |
| --- | --- | --- |
| `ProviderAccountListItem` | ProviderAccountListItem.kt | Provider/Account 列表项 + Pin badge |
| `TotpCard` | TotpCard.kt | 当前验证码 + 倒计时环 + copy 视觉 contract |
| `CountdownIndicator` | CountdownIndicator.kt | 倒计时圆环/秒数占位组件 |
| `RecoveryCodesCard` | RecoveryCodesCard.kt | 恢复码卡片（expand/collapse、used 弱化、copy-all 占位） |
| `DeveloperEntryCard` | DeveloperEntryCard.kt | 五类 Developer Entry 卡片 |
| `SensitiveValueRow` | SensitiveValueRow.kt | hidden/reveal 敏感值行 |
| `PinIndicator` | PinIndicator.kt | Pin 状态/动作 UI |
| `EmptyState` / `LoadingState` / `ErrorState` | StateComponents.kt | 空/加载/错误三态 |
| `DestructiveConfirmationDialog` | DestructiveConfirmationDialog.kt | 高破坏操作确认 |
| `UndoSnackbarHost` / `UndoSnackbarContract` | UndoSnackbarHost.kt | Undo Snackbar host + 契约 |

## 4. Theme / design tokens

- **Color**：`ui/theme/Color.kt` — “secure teal” M3 色板（light/dark）。
- **Typography**：`ui/theme/Type.kt` — M3 type scale（无内置字体依赖）。
- **Spacing / Corner / Elevation**：`ui/theme/Dimens.kt` — `Spacing` /
  `CornerRadius` / `ElevationTokens` 单一 token 来源。
- **入口**：`ui/theme/Theme.kt` `RescueAuthTheme`（跟随系统深色）。

## 5. Preview / sample strategy

- 每个组件都带 `@Preview`；截图友好的确定态（countdown 在
  `LocalInspectionMode` 下静态绘制）。
- Preview fixture 仅存在于 `ui/model` 的 `DeveloperPreviewData` 与各
  Screen 的 `previewProviders`，**绝不进入 release production flow**。

## 6. MainActivity 是否修改

**是，但仅为最小 UI-hosting 改动**：

- 把 `simple_list_item_1` 占位 TextView 换成 `ComposeView` hosting
  `RescueAuthTheme { RescueAuthApp(...) }`。
- **未改动**：BiometricPrompt 触发时机/认证器解析、`SessionManager`、
  `SecureSessionStateMachine`、`FLAG_SECURE`、后台遮罩、auto-lock、
  Keystore 失效处理、Vault lifecycle。
- Biometric 文案改用 string resource（en + zh-CN）。
- 因修改 MainActivity，补充验证 `assembleDebugAndroidTest`（通过）。

## 7. 与 PR #18 的冲突检查

PR #18（`auto/phase3a-merge-foundation-a299`）修改范围：core `export/*`、
merge planner、schema v2 migration、`VaultRepository`、docs、`app` /
`core` build.gradle.kts（追加 serialization 1.8.1 force）与
`gradle/libs.versions.toml`（serialization 版本 + 删 datastore）。

本 PR 的改动与 PR #18 的关系：

- **不触碰**：Room entity/DAO/schema、`VaultRepository`、Package /
  VaultSnapshot / MergePlanner / PackageValidator / legacy importer /
  backup/import/export 架构、Phase 3 docs/status。
- **共用但兼容**：
  - `gradle/libs.versions.toml`：本 PR 只**新增** compose/navigation 版本
    与 library 条目；PR #18 只改 `kotlinxSerialization` 与删 datastore ——
    无冲突（不同行）。
  - `v2/app/build.gradle.kts`：本 PR 新增 compose plugin/deps +
    `buildFeatures.compose`；PR #18 只在文件末尾追加
    `configurations.configureEach { resolutionStrategy { force(...) } }` ——
    无行冲突。
- 合并时若两者同时存在，需由 CI/人工确认无语义冲突；**本 PR 不 merge**。

## 8. Changed files

新增（UI foundation）：

```
gradle/libs.versions.toml                    (+ compose/navigation 版本)
v2/build.gradle.kts                          (+ kotlin-compose plugin)
v2/app/build.gradle.kts                      (+ compose plugin/deps/buildFeatures)
v2/app/src/main/res/values/strings.xml       (+ 全部新增文案 en)
v2/app/src/main/res/values-zh-rCN/strings.xml(+ 全部新增文案 zh-CN)
v2/app/src/main/kotlin/com/rescueauth/v2/ui/
  theme/Color.kt Type.kt Dimens.kt Theme.kt
  navigation/RescueAuthRoutes.kt TopLevelDestinations.kt
  components/StateComponents.kt SensitiveValueRow.kt
    DestructiveConfirmationDialog.kt UndoSnackbarHost.kt
    CountdownIndicator.kt ProviderAccountListItem.kt TotpCard.kt
    RecoveryCodesCard.kt DeveloperEntryCard.kt PinIndicator.kt
  model/AuthenticatorUiModels.kt DeveloperUiModels.kt
  screens/authenticator/AuthenticatorScreen.kt
  screens/developer/DeveloperScreen.kt
  screens/settings/SettingsScreen.kt
  RescueAuthApp.kt
v2/app/src/main/kotlin/com/rescueauth/v2/MainActivity.kt  (最小 hosting)
v2/docs/UI_FOUNDATION_REPORT.md                          (本报告)
```

新增测试：

```
v2/app/src/test/kotlin/com/rescueauth/v2/ui/
  RescueAuthAppNavigationTest.kt
  SensitiveValueRowTest.kt
  StateComponentsTest.kt
  DestructiveConfirmationDialogTest.kt
```

## 9. Tests

| 命令 | 结果 |
| --- | --- |
| `:core:test` | 34/34 PASS |
| `:app:testDebugUnitTest` | 47/47 PASS（含新增 Compose UI 测试） |
| `:app:lintDebug` | 0 error |
| `:app:assembleDebug` | SUCCESS |
| `:app:assembleDebugAndroidTest` | SUCCESS（因修改 MainActivity） |

## 10. 下一步最适合接入的 vertical slice

**Phase 4 P1（TOTP usable loop）**：

- 本 PR 已建立 Authenticator 首页列表 + TOTP card + countdown 视觉 contract +
  empty/loading/error + Undo Snackbar host + 导航结构。
- P1 只需：接入真实 TOTP core（`:core` 已有 legacy TOTP validator 可复用
  验证逻辑）、把 domain 记录映射到 `ui/model`（`TotpCredentialUi` 等）、
  在 `SensitiveValueRow`/copy 位置接入真正的复制与 re-auth 门禁。
- 其余 slice（P3 Recovery、P4/P6 Developer、P5 Selective、P8 Undo 完善）
  都可复用本 PR 的组件与导航。

> 不实现 TOTP CRUD；不 merge。
