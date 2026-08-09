# Phase 4 P4 Report — Sensitive Action Fresh Re-auth + Developer Vault First Batch

- 状态：**IMPLEMENTED / PR OPEN**（Issue #20）
- 日期：2026-08-09
- 依赖：Phase 2（Session / Keystore / auto-lock / FLAG_SECURE）、Phase 3B/3C/3D（Codec / Merge / Export-Import）
- 关联：ROADMAP §5.3 P4、§5.6（Sensitive Action Re-authentication）、ADR-0006

## 1. Phase 2 security boundary audit

本轮开始前先审计了现有 Phase 2 安全边界，**没有重写 Phase 2**（Issue #20 §1）：

| 组件 | 状态 | 说明 |
| --- | --- | --- |
| `MainActivity` BiometricPrompt | 保持 | 普通 unlock 流程不变（`resolveAvailableAuthenticators` + `BiometricPrompt` + `biometricPromptActive` 防重入） |
| `SecureSessionStateMachine` | 保持 | LOCKED / AUTHENTICATING / UNLOCKED / KEY_INVALIDATED 状态机未被改动 |
| `SessionManager` | 保持 | `databaseOrNull` / `lock` / `onAppBackgrounded` / `onAppForegrounded` 语义不变 |
| `VaultKeyManager` + `VaultKeyCrypto` | 保持 | 不可导出 AES-GCM Keystore 包装密钥、`KEY_INVALIDATED` 恢复路径不变 |
| auto-lock | 保持 | 后台 30s 超时锁不变；re-auth 流程不绕过 auto-lock |
| background privacy mask | 保持 | `onStop` 遮罩 / `onStart` 移除逻辑不变 |
| `FLAG_SECURE` | 保持 | 根窗口截屏/最近任务保护不变 |

结论：P4A 是**已解锁 Vault 内的 fresh re-auth**，不是重新设计 local unlock。

## 2. SensitiveAction model

新增安全边界抽象（`app/src/main/kotlin/com/rescueauth/v2/security/`）：

- **`SensitiveAction`** — 高敏感操作枚举：`EXPORT_FULL_VAULT`、`REVEAL_API_SECRET`、
  `COPY_API_SECRET`、`REVEAL_SSH_PRIVATE_KEY`、`COPY_SSH_PRIVATE_KEY`、
  `REVEAL_SSH_PASSPHRASE`、`COPY_SSH_PASSPHRASE`、`REVEAL_GENERIC_SECRET`、
  `COPY_GENERIC_SECRET`。每个敏感操作（reveal 与 copy 各自）都有明确的一次性
  授权语义（security-boundary CR §1）。
- **`SensitiveActionRequest`** — 不可变请求（`action` + `SensitiveActionTarget`），
  把操作绑定到原始 target（security-boundary CR §2）。
- **`SensitiveActionTarget`** — `Global`（Export 等）/ `DeveloperField(stableId,
  fieldKey)`（具体 entry 的稳定身份 + 字段）。
- **`SensitiveActionResult`** — 结果模型：`Success(request)` / `Cancelled` / `Failed` /
  `Unavailable`。
- **`SensitiveActionGate`** — 唯一的 orchestration path。单一 pending request +
  串行请求；`authorize(request, onResult)` → 平台 prompt → 结果回调 →
  `executePending(request) { ... }` 消费 one-shot 授权。按完整 request
  （action + target）值相等才放行。
- **`SensitiveActionPrompt`** — 平台后端接口；生产 = `SensitiveActionController`
  （真实 `BiometricPrompt`），测试 = fake（不碰真机硬件）。
- **`SensitiveActionController`** — 持有真实 `BiometricPrompt` 的平台适配器；
  每个 `FragmentActivity` 一个实例；支持 prompt-active 防重入、resumed-host 约束、
  pause/destroy 取消。
- **`SensitiveActionAccess`** — 全局注册点；`MainActivity` 每次 create 重建 gate，
  destroy 清空 → pending authorization 不跨 Activity/process 存活。

## 3. re-auth architecture

```
用户触发 sensitive action（Export / Reveal / Copy）
   → ViewModel 构造 SensitiveActionRequest(action, target)
   → SensitiveActionGate.authorize(request)
   → gate 校验：单 pending、session UNLOCKED
   → SensitiveActionController.tryStart（真实 BiometricPrompt，resumed host）
   → 结果：Success(request) / Cancelled / Failed / Unavailable
   → Success 时 gate 记录 one-shot authorizedRequest（完整 request）
   → 业务层 executePending(request) { 真正执行 sensitive op }
```

每个 `Composable` 都**不会自己 new BiometricPrompt**——只有
`SensitiveActionController`（经 `SensitiveActionGate`）这一个正式路径。

## 4. freshness / one-shot semantics

- 每个 high-risk action 明确触发**一次新的** re-auth。
- 没有 5 分钟缓存、没有“刚才验证过所以都放行”、没有全局 `authenticated=true`。
- 成功 re-auth → 授权**恰好一个** pending action；`executePending` 立即消费，
  后续 action（包括相同 action）都需要新的 prompt。
- Product / ADR 没有正式定义 freshness window，因此采用最安全的最简 contract。

## 5. biometric/device credential behavior

- 与 Phase 2 unlock 对齐：`BIOMETRIC_STRONG`（可用时）+ `DEVICE_CREDENTIAL` fallback。
- 设备没有可用 authenticator → 返回 `Unavailable`，敏感操作**被阻止**，绝无
  silent bypass（AGENTS 禁区 / Issue #20 §4）。生产没有 `NoOpSensitiveActionGate`。
- BiometricPrompt 的 lockout / system error 映射为安全 UI state（cancel/failed），
  不 crash、不泄露 secret。

## 6. lifecycle / concurrency handling

- **单 pending request + 串行请求**：第二个 `authorize`（重复 Reveal / Reveal 时
  Export / prompt 已显示时再请求）返回 false，不会弹多个 prompt，也**不会
  替换原始 pending target**（security-boundary CR §4）。
- **Activity pause / destroy**：`onLifecyclePause` / `onLifecycleDestroy` 取消
  prompt 并丢弃 pending 授权。
- **session lock during prompt**：`MainActivity` 监听 state flow，lock 时调用
  `gate.invalidate()`（pending target 一并失效）；Developer Detail ViewModel
  同步清空 reveal 状态。
- **成功结果只授权当前 pending request**：`executePending(request)` 只对
  `authorizedRequest == request`（action + target 全等）放行，request A 的结果
  无法放行 request B（不同 entry / 不同 field / reveal→copy 均不可能）。
- **pending 不持久化**：不进入 SavedStateHandle / Bundle / DataStore / Room /
  navigation arguments。Activity/process recreation 后 gate 重建，授权丢失。

## 7. Full Vault Export integration

Phase 3D 的 `ExportImportViewModel` 已接入 re-auth（Issue #20 §5/§24）：

```
Export Vault
  → fresh Biometric/Device Credential re-auth（AwaitingReauth）
  → 成功 → per-export PIN + confirm
  → SAF CreateDocument
  → build snapshot / encode / encrypted write
```

- re-auth 在 **PIN 之前**完成：cancel/failed/unavailable 不收集 PIN、不创建
  SAF 文档、不构造 plaintext snapshot。
- 保持 Phase 3D PIN-first 语义：`Re-auth → PIN → CreateDocument → encode/write`。
- 保持 per-export PIN 两层保护（local sensitive-action authorization +
  portable package PIN encryption），不互相替代。
- 测试证明：auth cancel/failure/unavailable 时 `written.isEmpty()`、
  `deletedUris.isEmpty()`；`onExportDestinationPicked` 在 re-auth 未成功时被忽略。

## 8. Developer persistence reuse

完全复用 Phase 3C 单表 `developer_entry` + typed payload（Issue #20 §9）：

```
Room DeveloperEntryEntity
  ↔ DeveloperMappers（唯一 bridge）
  ↔ domain/logical VaultDeveloperEntry（core portable logical model）
  ↔ DeveloperRepository（新增，Phase 4 P4）
  ↔ ViewModel
  ↔ UI model
  ↔ Compose
```

- 没有创建第二套 Developer table。
- 没有把 UI model 直接存成 payload JSON。
- `DeveloperEntryDao` 只增加了 `observeAll` / `upsert` / `deleteById` /
  `maxSortOrder`（现有表结构不变，schema v3 保持）。
- Composable 不解析 `payloadJson`。

## 9–11. API Credential / SSH Key / Generic Secret CRUD

`DeveloperRepository`（`app/src/main/kotlin/com/rescueauth/v2/repository/`）提供
三类完整 CRUD：

| 类型 | 字段（logical model） | 列表默认展示 | 隐藏 |
| --- | --- | --- | --- |
| API Credential | title / notes / serviceName / accountName / apiKey / apiSecret | title / serviceName / accountName | apiKey、apiSecret（永远 hidden） |
| SSH Key | title / notes / keyName / publicKey / privateKey / passphrase | title / keyName | privateKey、passphrase（永远 hidden） |
| Generic Secret | title / notes / dynamic [{label, value}] | title / field labels | value（默认 hidden） |

- 全部 mutation 走共享 `VaultRepository` mutex + Room transaction。
- Create mint 新 stableId；Edit 保留原 stableId + createdAt（delete+recreate
  被禁止，Issue #20 §18）。
- Delete 为 destructive confirmation（P4 不要求 Undo，Issue #20 §17），
  transactionally 删除并返回被删 payload。

## 12. reveal / copy security behavior

- **Reveal**（apiSecret / apiKey / privateKey / passphrase / generic value）：
  必须 fresh re-auth 成功后才从 repository 加载 plaintext 到 ViewModel 的
  in-memory map；leaving screen / session lock / app recreation / manual hide
  → hidden；再次 reveal 需要重新 re-auth。
- **Copy**：本身就是 high-risk action，独立 fresh re-auth（即使当前处于
  revealed 状态也不绕过 copy gate）。**reveal 授权绝不复用于 copy**：
  `REVEAL_SSH_PASSPHRASE` vs `COPY_SSH_PASSPHRASE`、
  `REVEAL_GENERIC_SECRET` vs `COPY_GENERIC_SECRET` 均为不同 action，各自独立
  one-shot（security-boundary CR §1）。
- **target binding**：每个请求绑定 `stableId + fieldKey + operation`，auth
  success 只能作用于原始请求（security-boundary CR §2/§3）。
- clipboard auto-clear 继续 DEFER（未实现 watcher/timer）。

## 13. stableId / edit semantics

- Create → 新 stableId；Edit → 保留既有 stableId。
- 三类 entry 的编辑测试锁定 stableId 不变（package lineage / merge conflict /
  Legacy identity 不破坏）。
- 未因编辑 title/field 而 delete + recreate（Generic Secret 保持同一 logical
  identity）。

## 14. UI / navigation

- Developer 是顶级一级 destination（Authenticator / Developer / Settings），
  不藏在 Settings 里。
- 列表页：按类型分组展示 title + 非敏感 metadata；Add sheet 只提供三个
  P4 类型（不展示 P6 类型为可点击假功能）。
- 详情页：per-type metadata + sensitive rows（reveal/copy 走 re-auth）。
- 编辑页：三类共用一套表单；Generic Secret 支持添加/删除 field row。
- 新增路由：`developer/entry/{entryId}`、`developer/form?editStableId=&type=`。
- 文案 en + zh-CN 双语补齐（含 Authenticate to continue / Authentication
  cancelled / Authentication unavailable / API Key / API Secret / Private Key /
  Passphrase / field label/value / destructive confirmation 等）。

## 15. package round-trip compatibility

- 未修改 `PortablePackageCodec` / package format / `MergePlanner` /
  Developer logical merge rules。
- P4 CRUD 写入的数据自然进入 Full Vault Export → `.rakpkg` → Import →
  preview → apply。
- 新增 integration tests：三类 entry create/edit → full logical snapshot →
  package round-trip → exact logical data preserved；空 Vault 导入 exact
  round-trip；第二次导入 idempotent；同 stableId 改 payload 仍 CONFLICT。
- 未测试 SAF system picker（符合 Issue #20 §22）。

## 16. security / privacy

- secret 不进入 navigation route / SavedStateHandle / rememberSaveable /
  Bundle / DataStore / logs。
- reveal 状态单独受控；`UiState.toString()` 不含 plaintext secret（detail
  state 只带 metadata）。
- 敏感值 contentDescription 默认不含 plaintext secret。
- 不能只靠颜色表达 hidden/auth/error state（文本+图标+snackbar）。

## 17. changed files

新增：

- `app/src/main/kotlin/com/rescueauth/v2/security/SensitiveAction.kt`
- `app/src/main/kotlin/com/rescueauth/v2/security/SensitiveActionRequest.kt`
- `app/src/main/kotlin/com/rescueauth/v2/security/SensitiveActionResult.kt`
- `app/src/main/kotlin/com/rescueauth/v2/security/SensitiveActionGate.kt`
- `app/src/main/kotlin/com/rescueauth/v2/security/SensitiveActionController.kt`
- `app/src/main/kotlin/com/rescueauth/v2/security/SensitiveActionAccess.kt`
- `app/src/main/kotlin/com/rescueauth/v2/repository/DeveloperRepository.kt`
- `app/src/main/kotlin/com/rescueauth/v2/ui/developer/DeveloperListViewModel.kt`
- `app/src/main/kotlin/com/rescueauth/v2/ui/developer/DeveloperDetailViewModel.kt`
- `app/src/main/kotlin/com/rescueauth/v2/ui/developer/DeveloperFormViewModel.kt`
- `app/src/main/kotlin/com/rescueauth/v2/ui/developer/DeveloperRoute.kt`
- `app/src/main/kotlin/com/rescueauth/v2/ui/screens/developer/DeveloperScreen.kt`（改写为真实数据屏）
- `app/src/main/kotlin/com/rescueauth/v2/ui/screens/developer/DeveloperAddSheet.kt`
- `app/src/main/kotlin/com/rescueauth/v2/ui/screens/developer/DeveloperDetailScreen.kt`
- `app/src/main/kotlin/com/rescueauth/v2/ui/screens/developer/DeveloperFormScreen.kt`
- `app/src/main/kotlin/com/rescueauth/v2/ui/model/DeveloperUiModels.kt`（扩展 detail/UI models）
- 测试：`SensitiveActionGateTest`、`DeveloperRepositoryTest`、
  `DeveloperDetailViewModelTest`、`DeveloperFormViewModelTest`、
  `DeveloperScreenTest`、`DeveloperPackageIntegrationTest`、
  `FakeSensitiveActionPrompt`

修改：

- `MainActivity.kt`（gate 创建/生命周期/会话 lock invalidate）
- `ExportImportViewModel.kt`（Export 接入 re-auth）
- `ExportImportViewModelTest.kt`（新增 §24 re-auth tests + gate 注入）
- `ExportImportRoute.kt`（gate 注入）
- `ExportImportScreens.kt`（AwaitingReauth 状态）
- `DeveloperEntryDao.kt`（observeAll/upsert/delete/maxSortOrder）
- `VaultAccess.kt`（developerRepository()）
- `DeveloperEntryCard.kt`（icon 工具保持）
- `RescueAuthApp.kt`（Developer 路由）
- `RescueAuthRoutes.kt`（新路由）
- `values/strings.xml`、`values-zh-rCN/strings.xml`（文案）
- `ROADMAP.md`、`AGENTS.md`、`CHANGELOG.md`、本报告、`ADR-0011`

## 18. tests

- `:core:test` — 全量 PASS。
- `:app:testDebugUnitTest` — 全量 PASS。
- `:app:lintDebug` — 0 errors。
- `:app:assembleDebug` / `:app:assembleDebugAndroidTest` — PASS。

覆盖 Issue #20 §24–§29 的核心用例 + security-boundary CR race/security 用例：

- §24 Export re-auth：request→reauth、success→PIN、cancel→no PIN/file、
  failure→no export、unavailable→blocked、one-shot、second export fresh、
  session lock invalidates、no SAF before reauth。
- §25 re-auth foundation：one pending、duplicate no second prompt、
  A result not B、cancel/failure clears、lifecycle destroy、session lock、
  no persistence across recreation。
- §26 API Credential：create/read/edit stableId/delete/hidden/reveal/copy/lock/persistence。
- §27 SSH：create/edit stableId/delete/private key hidden/reveal/copy/passphrase/persistence。
- §28 Generic：create multi-field/edit labels-values/stableId/add-remove field/
  values hidden/reveal/copy/lock/persistence。
- §29 Package：三类进 snapshot、encode/decode 保真、空 Vault round-trip、
  idempotent、同 stableId 改 payload CONFLICT。
- **security-boundary CR race/security（新增）**：reveal Generic field A success
  不能 reveal field B；copy Generic field A success 不能 copy field B；reveal
  授权不能授权 copy；SSH passphrase reveal 授权不能授权 passphrase copy；
  Entry A pending 切换到 Entry B 时 auth success 只作用于原始 A（或安全取消）；
  prompt 激活时第二个 same-type request 不替换原始 target；session lock 使
  pending target 失效。

BiometricPrompt 本体在 Robolectric fake boundary 测试，不在 JVM 假装 biometric
hardware（Issue #20 §25）。

## 19. FTL expectation

本 PR 修改 Biometric product flow + Developer UI + Full Vault Export gate。
合入 main 后预计触发 Firebase Test Lab（`.cnb.yml` main push 流水线）。
本轮**不主动运行 FTL**。若增加 instrumented tests，重点验证 biometric
orchestration/session wiring，不尝试自动化真实指纹硬件。

## 20. P6 remaining scope

- Android Signing Key（含 keystore import/export、key.properties 复制）
- Environment Variable Set
- Developer completion（其余 UI 细节 / Reveal / Copy 统一）

P4 first batch = Sensitive Action Re-auth Foundation + API Credential + SSH Key +
Generic Secret。P5（Selective Export/Import）、P6（Signing Key / Env Var）、
Legacy UI 均未进入本轮。

## 21. parallel conflict check with Phase 5A Legacy adapter

- 未修改：frozen legacy parser、legacy crypto、legacy raw models、
  legacy stableId mapping、Phase 5 adapter tests。
- 两边唯一共享点：current Developer logical model
  （`VaultDeveloperEntry` / `DeveloperEntryEntity` / `DeveloperMappers`）。
- 本轮未扩展 logical model（三类 P4 entry 字段与 Phase 3A/3C 完全一致），
  因此没有发现与 Phase 5A 的冲突。若 Phase 5A 需要新增字段，应确认 frozen
  v1 / PRODUCT 确实要求并做最小向后兼容变更。

## 22. PR URL / branch / commits

见 PR 描述。
