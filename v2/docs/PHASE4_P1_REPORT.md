# PHASE4_P1_REPORT.md — Phase 4 P1: TOTP Daily-Use Loop

> 状态：**Implemented（Issue #20）**。
> 本文是 Phase 4 P1（TOTP usable loop）的实现报告。Scope 严格遵循
> Issue #20 的 18 节契约；本轮只做“从已解锁 Vault 到日常使用 TOTP”的
> 真实闭环，使用真实 production storage（Room + SQLCipher），
> **不使用 fake repository / preview data 代替**。

## 1. 用户闭环（已验证路径）

```
解锁 Vault（Phase 2 Biometric/Device Credential 语义不变）
→ Authenticator 首页（真实列表）
→ 添加 TOTP（Paste otpauth:// URI 或 Manual Entry）
→ 保存到真实 Vault（SQLCipher Room，schema v2 不变）
→ 立即看到真实动态验证码 + countdown
→ 复制验证码（Android clipboard + Snackbar）
→ 删除 credential（UI 立即移除 + Snackbar Undo）
→ Undo 真正恢复数据库记录（stableId 不变）
→ 退出 / 重启 App → 再次解锁 → 数据仍在并正常生成验证码
```

## 2. TOTP core（shared production）

- 位置：`core/src/main/kotlin/com/rescueauth/v2/totp/TotpCore.kt`
- **纯 Kotlin / 纯 JVM**：不依赖 Android、Room、legacy model。
- 复用：Base32 解码与算法/位数白名单复用共享逻辑层
  `export/TotpParameters`（不是 legacy 包）；HMAC+动态截断用标准
  `javax.crypto.Mac`（与 legacy cross-check 同一 primitive，不重新实现
  crypto）。
- 支持：Base32 secret（含 `=` 填充/大小写/分隔符归一化）、SHA1/SHA256/
  SHA512、digits 6..10、period 1..120（默认 6 / 30）、timestamp 生成、
  countdown（remainingSeconds / progressFraction）、validation。
- 测试：RFC 4226 / RFC 6238 known vectors（SHA1/SHA256/SHA512）、digits、
  period boundary、countdown boundary。

## 3. otpauth:// parser

- 位置：`core/src/main/kotlin/com/rescueauth/v2/totp/OtpauthParser.kt`
- 纯 Kotlin：不依赖 Room / Android / legacy models / 不直接写数据库。
- 解析：secret、issuer（query 优先于 label 前缀）、account/label、
  algorithm、digits、period；默认 SHA1 / 6 / 30。
- 语义：percent decoding、`issuer:account` label、issuer query、
  whitespace、Base32 归一化、algorithm 大小写、无效 digits / period、
  missing secret、malformed URI、HOTP 拒绝。
- 结果值对象：`ParsedTotp`（`totp/ParsedTotp.kt`）。

## 4. Manual TOTP Entry

- 位置：`app/.../ui/screens/authenticator/AddTotpSheet.kt`
- 输入：Provider/issuer、Account name、Secret、Algorithm、Digits、Period。
- 默认值：SHA1 / 6 digits / 30s。
- 校验：required fields、Base32 secret、digits、period。
- 不增加高级设置（无 advanced section）。

## 5. Repository production wiring

- **第一次真正构造并使用 VaultRepository / DAO production path**：
  - `SessionManager` 在 unlock 时已持有打开的真实 SQLCipher DB
    （Phase 2 语义不变，仅新增只读 `sessionStateFlow` 访问器）。
  - `repository/VaultAccess.kt`：全局唯一 production access 点。
    `Session unlocked → databaseOrNull() → VaultRepository（同一 mutex 串行）
    → AuthenticatorRepository → ViewModel`。
  - `AuthenticatorRepository`（新）：TOTP CRUD / findOrCreateAccount /
    delete / restore，全部经 `VaultRepository.mutate`（同一 Mutex +
    withTransaction，session-lock 校验集中一处）。
  - **Composable 不直接访问 DAO**；**Room entity 不暴露给 UI**。
- 分层：Room/Repository → domain（`domain/AuthModels.kt`，不含 secret 的
  UI model 在 `ui/model`）→ ViewModel → UI model → Compose。层级保持
  最小、清晰、可维护，不堆无意义 abstraction。
- **本轮不改 Room schema**（DAO 仅新增查询方法；schema v2 保持不变）。

## 6. ViewModel / UI architecture

- `ui/authenticator/AuthenticatorViewModel.kt`：plain class（非 androidx
  ViewModel），注入 `repositoryProvider`、session state、Clock、scope，
  JVM 可测。Combine 真实 Flow + tick → UI state。
- `ui/authenticator/AuthenticatorRoute.kt`：唯一 Android 依赖点（clipboard、
  Snackbar Undo action、lifecycle tick），把 ViewModel 事件映射到 UI。
- `ui/screens/authenticator/AuthenticatorScreen.kt`：展示层，只消费 UI model
  与回调；EmptyState / LoadingState 复用 UI Foundation 组件。
- `ui/screens/authenticator/AddTotpSheet.kt`：ModalBottomSheet，Paste/Manual
  两种模式。
- UI Foundation 组件 `TotpCard` / `CountdownIndicator` 保留并接入真实数据
  （实时 code + remaining + progress）。

## 7. Countdown implementation

- 单一 tick source：`AuthenticatorRoute` 里一个 lifecycle-aware
  `DisposableEffect + LifecycleEventObserver`，仅 STARTED/RESUMED 时在后台
  scope 每 1s 调 `viewModel.onTick()`；ON_PAUSE/ON_STOP 取消；onResume/
  onStart 立即校正。
- **以 wall clock 为准**：`viewModel.onTick()` 直接读 `Clock.currentTimeSeconds()`
  （生产为 `System.currentTimeMillis()/1000`），不依赖累计 tick 推算。
- 不在每个 Composable 塞时钟逻辑；Tick 只驱动一个 `StateFlow<Long>`，
  ViewModel 的 `combine` 重算所有 card。
- 页面离开 / app background 后停止高频工作；返回前台立即校正。

## 8. Copy flow

- 点击 TOTP code 或 copy icon → `AuthenticatorEvent.CopyCode` →
  `AuthenticatorRoute` 写 Android clipboard（`ClipboardManager`，
  `ClipData.newPlainText`）→ Snackbar「Code copied」。
- clipboard auto-clear **DEFER，不实现**；无需额外权限。

## 9. Delete + Undo semantics

- 普通 TOTP delete：UI 立即删除（Flow 驱动）→ Snackbar「{label} deleted」+
  Undo action。
- Undo 走 `AuthenticatorRepository.restoreTotpCredential`：**真正恢复数据库
  记录**，保持原 `id` / `stableId` / 原 credential 内容（secret/algorithm/
  digits/period），不创建新的 logical identity。
- 实现：`pendingUndo` 保存删除前完整 domain 对象；restore 用原 stableId
  upsert（若同 stableId 已存在则不再插入，避免重复 Undo / 数据竞争）。
- 不恢复 automatic checkpoint backup（Phase 3 契约保持）。
- Undo window 结束（snackbar 超时/划走）→ 删除正式生效。

## 10. Persistence / restart behavior

- 数据写入真实 SQLCipher Room（`TotpCredentialEntity` / `AuthAccountEntity`，
  schema v2，stableId 字段）。
- 添加 TOTP → 关闭/recreate session/database → 再次打开 → 数据仍在；
  重新解锁后同一 credential 可见、stableId 不变、TOTP 正常生成。
- 测试覆盖：`AuthenticatorRepositoryTest`（file-backed DB close/reopen）、
  已有 `RescueAuthDatabaseInstrumentedTest`（真 SQLCipher reopen persistence）。

## 11. Security boundary（保持 Phase 2 不变）

- Biometric / Device Credential unlock、SessionManager、VaultKey、SQLCipher、
  FLAG_SECURE、background privacy mask、auto-lock 全部保持，未重构。
- 本轮不实现 Sensitive Action Re-auth：显示/复制 TOTP code 属于正常
  unlocked-Vault 使用路径（Roadmap §5.6 明确定义不覆盖）。

## 12. Legacy / Package isolation（未触碰）

- 未修改：Phase 3B codec、VaultPackagePayload、MergePlanner、package format、
  Legacy importer、Native Package Import。
- **Room schema 未改**（DAO 仅新增查询）。无需 schema change。
- core 新增 `totp/` 包是独立 production core，不依赖 legacy 包。

## 13. Tests（全部通过）

```
./gradlew :core:test                        PASS（含 TOTP core + parser 新测试）
./gradlew :app:testDebugUnitTest            PASS（67 tests，含新增 repository/
                                              ViewModel/UI 测试）
./gradlew :app:lintDebug                    PASS
./gradlew :app:assembleDebug                PASS
./gradlew :app:assembleDebugAndroidTest     PASS
```

新增测试：

| 模块 | 覆盖 |
| --- | --- |
| `core/totp/TotpCoreTest` | RFC vectors（SHA1/SHA256/SHA512）、digits、period/countdown boundary、invalid input |
| `core/totp/OtpauthParserTest` | normal URI、encoded label、issuer、defaults、algorithm/digits/period、malformed/missing secret、HOTP 拒绝、malformed URI |
| `app/repository/AuthenticatorRepositoryTest` | insert、observe/list、delete、Undo restore、stableId 保持、close/reopen persistence、locked 拒绝 |
| `app/ui/authenticator/AuthenticatorViewModelTest` | empty、add success（paste+manual）、validation error、real list、countdown tick、copy、delete→Undo |
| `app/ui/authenticator/AuthenticatorScreenTest` | empty state、real list 渲染（issuer/account/code/countdown） |
| `SecureScreenFlagTest`（补充 teardown） | 修复因新 route 引入后台协程导致的 Compose idling 污染 |

## 14. Changed files（summary）

**core（纯 JVM）**：
- `core/src/main/kotlin/com/rescueauth/v2/totp/TotpCore.kt`（新）
- `core/src/main/kotlin/com/rescueauth/v2/totp/OtpauthParser.kt`（新）
- `core/src/main/kotlin/com/rescueauth/v2/totp/ParsedTotp.kt`（新）
- `core/src/test/kotlin/com/rescueauth/v2/totp/TotpCoreTest.kt`（新）
- `core/src/test/kotlin/com/rescueauth/v2/totp/OtpauthParserTest.kt`（新）

**app（production wiring / UI）**：
- `app/.../domain/AuthModels.kt`（新：TotpCredential / AuthAccount）
- `app/.../repository/AuthMappers.kt`（新）
- `app/.../repository/AuthenticatorRepository.kt`（新）
- `app/.../repository/VaultAccess.kt`（新：Session→DB→Repository 唯一入口）
- `app/.../repository/VaultRepository.kt`（+ 通用串行 `mutate`）
- `app/.../database/TotpCredentialDao.kt` / `AuthAccountDao.kt`（+ 查询方法）
- `app/.../session/SessionManager.kt`（+ `sessionStateFlow` 只读访问器）
- `app/.../MainActivity.kt`（注册/清理 VaultAccess）
- `app/.../ui/authenticator/AuthenticatorViewModel.kt`（新）
- `app/.../ui/authenticator/AuthenticatorRoute.kt`（新）
- `app/.../ui/screens/authenticator/AuthenticatorScreen.kt`（接真实 state）
- `app/.../ui/screens/authenticator/AddTotpSheet.kt`（新）
- `app/.../ui/components/TotpCard.kt`（接 TotpCardUi + delete 动作）
- `app/.../ui/RescueAuthApp.kt`（Authenticator 路由接 production route）
- `app/.../ui/navigation/RescueAuthRoutes.kt`（+ AUTHENTICATOR_ADD 契约）
- `res/values/strings.xml` + `values-zh-rCN/strings.xml`（本轮新页面 en+zh-CN）

**tests**：见 §13。

## 15. 与 Phase 3B 是否冲突

无冲突。本轮未触碰 3B codec / VaultPackagePayload / MergePlanner /
package format / Legacy importer / schema。core 新增 `totp/` 独立于
`export/` 与 `legacy/`。与 PR #18（3A）共用 `TotpParameters`（Base32 /
白名单），只读复用，无修改。

## 16. 尚未实现并留给后续 P2+ 的能力

- QR scanner / CAMERA permission
- otpauth-migration:// import（P2）
- Recovery Codes（P3）
- Developer Vault slices（P4/P6）
- Selective Export/Import（P5）
- Search / Pin（P7）
- Delete Undo 全类型完善（P8）
- Provider/Account full management（rename / move / merge / delete UI）
- Sensitive Action Re-auth（P4 起接入）
- clipboard auto-clear（DEFER）
- i18n 全量补齐（本轮仅新增页面 en + zh-CN）

## 17. 合入 main 后 changed-file gate

本 PR 包含真实 Android product flow（v2/app/… 与 v2/core/… 均变更），
`scripts/check-test-lab-gate.sh` 对 `v2/app/**` 与 `v2/core/**` 视为
**trigger path**，因此合入 main 后 `full-cloud-test-loop` 的
`changed-file-gate` 预计输出 `TEST_LAB_GATE=run`，Firebase Test Lab 会被
**自动触发**。本轮不主动运行 FTL。
