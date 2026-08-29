# PHASE4_P3_REPORT.md — Phase 4 P3: Recovery Codes Daily-Use Slice

> 状态：**Implemented / PR OPEN**。
> 本文是 Phase 4 P3（Recovery Codes Daily-Use Slice）的实现报告。Scope
> 严格遵循 Issue #1 §P3 契约；本轮让 Recovery Codes 从“底层已经存在的
> 数据类型”变成真正可日常使用的完整 Android 功能。真实 production
> storage（Room + SQLCipher）贯穿全部路径。

> **文档性质**：历史实现报告。正文中的 `PR OPEN` 等状态只代表报告生成时的
> 状态；当前状态以 `../ROADMAP.md`、`../AGENTS.md` 和 [`README.md`](../README.md) 为准。

## 1. 用户闭环（已验证路径）

```
解锁 Vault（Phase 2 语义不变）
→ Authenticator 首页（Provider/Account 分组列表 + recovery 摘要）
→ 点击 Account → Account detail（Recovery Codes）
→ Add Recovery Codes → 输入 title + 批量粘贴 codes（多行）→ preview → save
→ 查看 remaining count / total count / used count（折叠卡片）
→ 展开 → 每条 code：masked / reveal / hide / copy / mark used / mark unused
→ Copy All / Copy Remaining（纯文本，每行一个 code）
→ Edit（title + code list，最小 diff，stableId / USED state 保留）
→ Delete + Undo（UI 立即移除 → Snackbar → Undo 真正恢复）
→ restart / reopen → 所有内容、stableId、USED state、usedAt 均保留
```

## 2. existing persistence audit（结论：Room schema 零改动）

审计了当前 main 的 Recovery persistence / domain / package 语义：

- `RecoveryCodeSetEntity`：id / accountId / title / createdAt / legacySourceId /
  **stableId**（Phase 3A 已具备）。
- `RecoveryCodeEntity`：id / setId / value / **status（USED|UNUSED）** /
  **usedAt** / sortOrder / **stableId**（Phase 3A 已具备）。
- `RecoveryCodeSetDao` / `RecoveryCodeDao`：observe/list/insert/markUsed/
  markUnused 已存在。
- `VaultRepository` 已有 `markRecoveryCodeUsed` / `markRecoveryCodeUnused`
  （mutex + transaction）。
- `VaultSnapshot` / `MergePlanner` / `Canonicalization`：Recovery Set 与
  Code 的 stableId、value、status、usedAt、sortOrder 全在 portable logical
  schema 中；merge 的 used/unused state divergence 显式输出且 blocked。

**结论**：现有 persistence 已经能表达 set / code value / used-unused /
usedAt / stableId / account relation，**本轮不改 Room schema**（无 migration，
无 destructive migration）。

## 3. Recovery domain / repository design

- `domain/AuthModels.kt`：新增 `RecoveryCodeSet`（含 `usedCount` /
  `totalCount` / `remainingCount`）与 `RecoveryCode`（isUsed / usedAt /
  sortOrder / stableId）。
- `repository/RecoveryCodeRepository.kt`（新）：所有 mutation 经
  `VaultRepository.mutate`（共享 mutex + 单 Room transaction）。提供
  `observeSetsByAccount` / `observeAllSets`（combine set flow + code flow，
  USED 变化实时驱动 UI 刷新）、`createSet` / `editSet` / `deleteSet` /
  `restoreSet` / `markUsed` / `markUnused`。
- `repository/AuthMappers.kt`：新增 entity → domain 映射。
- `repository/VaultAccess.kt`：新增 `recoveryRepository()`（与
  AuthenticatorRepository / ExportImportService 共享同一 VaultRepository
  mutex，写操作全程串行）。
- **不新建第二套 Recovery model**；不把 Room entity 暴露给 Compose。

## 4. create / batch-input 行为

- 入口：Account detail → FAB → Add Recovery Codes sheet。
- title（必填）+ 多行 code 输入（一行一个）。
- **多行粘贴**：`lines()` 按行拆分，`trim()` 每行、过滤 blank lines，
  实时显示“已解析 N 个 code”的 preview（不把 codes 作为 secret 列表展示）。
- 不做任何“智能规范化”：不 uppercase / lowercase / 删内部 `-` / 改写 code
  内容。Recovery code 是 **opaque secret string**。

## 5. duplicate 策略

- **exact / canonical whitespace-level dedupe only**：`ABC-123` 与 `ABC123`
  是不同 secret，绝不自动视为相同。
- 创建/编辑时同一 Set 内出现 exact duplicate → 明确 validation 错误
  （`duplicate:<code>` → 用户可见文案），避免重复粘贴无提示制造重复记录。
- **不做跨 Set 的全局 dedupe**：同一个 code 出现在两个不同 Set 是真实数据，
  允许。

## 6. UI hierarchy

```
Authenticator 首页
  └─ Provider/Account 列表（每组带 recovery 摘要行：Recovery codes · N remaining）
       └─ Account detail（authenticator/account/{accountId}，Recovery Codes）
            ├─ RecoveryCodeSetCard（折叠：title + N remaining · M total）
            │    └─ 展开：每条 code masked/reveal/copy/mark used/unused
            ├─ Copy All / Copy Remaining / Edit / Delete（overflow menu）
            └─ FAB → Add Recovery Codes sheet
```

- 账号 detail 使用正式 hierarchy（Provider → Account → Recovery Code Set），
  无临时 fake hierarchy。
- 本轮未实现 Provider rename / Account move / merge / full management（P3
  边界，见 §17）。

## 7. reveal / copy 行为

- **不默认展开 plaintext**：折叠卡片只显示 counts；展开后每条 code 默认
  masked（`••••••••`）。
- 每条 code 有 reveal/hide（复用 UI Foundation 的 `SensitiveValueRow`
  视觉契约，但以行内控件实现；reveal 状态由 ViewModel 持有，in-memory
  only，session lock 清空）。
- **单条 code copy**（v1 只有 copy-all）：点击 copy 图标 → Android
  clipboard 写入该 code 原始值 → Snackbar「Recovery code copied」。
- clipboard auto-clear 保持 DEFER，不实现 clipboard monitoring / delayed clear。

## 8. Copy All / Copy Remaining

- Copy All = 全部 codes（保留最接近 v1 的语义；每行一个 code 的纯文本）。
- 额外提供 **Copy Remaining**（成本很低，已实现）：只复制 UNUSED codes。
- 输出均为一组 plaintext lines（可迁移、可粘贴回编辑框）。

## 9. USED / UNUSED 语义

- UNUSED → Mark as used：`status = USED`，`usedAt = 当前时间`（写入真实 DB）。
- USED → Mark as unused：`status = UNUSED`，`usedAt = NULL`（清空）。
- remaining count 实时更新（Flow 驱动）。
- **不删除 USED code**：USED 是状态，不是 delete。

## 10. State change safety

- USED / UNUSED 修改**实时写入真实 Vault**：`VaultRepository.mutate`
  （共享 mutex + withTransaction），不是 ViewModel-only、不等到退出页面。
- Composable 不直接访问 DAO。

## 11. Edit Recovery Code Set

- 编辑整个 Set：title + code list（多行，一行一个）。
- **按 code identity 做最小 diff**（value trim 后按值匹配）：
  - 未变 code：保留原 stableId + USED/UNUSED + usedAt，仅刷新 sortOrder；
  - 移除的 code：删除；
  - 新增 code：新 stableId / UNUSED；
  - code value 本身被修改 → 视为删除旧 + 新建新（不偷偷沿用旧 stableId
    表示另一个 secret）。
- 保存是 transactional：Set metadata + code add/remove/preserve 一次性提交，
  失败整体 rollback（duplicate 输入在事务外校验，拒绝后零写入）。

## 12. Delete + Undo

- Delete Set → UI 立即移除 → Snackbar「{title} deleted」+ Undo。
- Undo 调用 `restoreSet`：**真正恢复** Set stableId、child RecoveryCode
  stableId、values、USED/UNUSED、usedAt、relation，不创建新的 logical
  identity。
- 复用 P1 TOTP Delete + Undo 的架构模式（pending 完整 domain 对象 →
  restore 幂等 upsert）。
- 不恢复旧 checkpoint / automatic backup。

## 13. 单条 Recovery Code 删除

PRODUCT / ROADMAP 未明确要求单条 code delete；编辑整体 code list 已覆盖
code 增删。本轮未强加单条 delete（保持简单，P3 不膨胀 scope）。

## 14. Persistence / restart

- 创建 Set → 标记部分 USED → 编辑 title/list → close/reopen DB → 所有内容、
  stableId、USED state、usedAt 均保留（file-backed DB 测试覆盖）。

## 15. Package / Merge compatibility

- **未修改**：PortablePackageCodec、package byte format、crypto、
  MergePlanner semantic rules、transactional package apply（Phase 3 已
  CLOSED）。
- P3 写入的 Recovery 数据自然通过现有 Full Vault Export → snapshot →
  package/import → merge apply 完整 round-trip（测试覆盖：Recovery Set +
  used/unused states → export logical snapshot → import 空库 → state 保留）。
- used/unused divergence 仍显式 surfaced 且 blocked（不改变 merge 为
  source/destination wins）。

## 16. Security / logging

- Recovery codes 不写日志、不 analytics、不 exception message 泄漏 value。
- UI model 不含 plaintext secret，直到用户显式 reveal。
- accessibility contentDescription 不含 code value。
- FLAG_SECURE / privacy mask / session lock 保持；session locked 时清空
  reveal 状态与 UI。

## 17. i18n（en + zh-CN）

新增 string：Recovery Codes / Remaining / Used / Unused / Mark used /
Mark unused / Reveal / Hide / Copy / Copy all / Copy remaining / Add
Recovery Codes / Edit / Delete / Undo / duplicate validation / empty state
等，en 与 zh-CN 双份。未做全项目翻译审计。

## 18. Search / Pin boundary

P7 才做 Global Search / Pin；本轮不实现。未来 Search 只能搜索 recovery
metadata（provider / account / set title），绝不索引 code value。本轮不新增
secret index。

## 19. Tests（全部通过）

```
./gradlew :core:test                 PASS
./gradlew :app:testDebugUnitTest     PASS（250 tests）
./gradlew :app:lintDebug             PASS
./gradlew :app:assembleDebug         PASS
./gradlew :app:assembleDebugAndroidTest PASS
```

新增测试：

| 文件 | 覆盖 |
| --- | --- |
| `app/.../repository/RecoveryCodeRepositoryTest.kt` | ① create Set ② batch create ③ duplicate 校验 ④ Set 归属 Account ⑤ mark USED ⑥ usedAt 持久化 ⑦ USED→UNUSED ⑧ usedAt 清空 ⑨ remaining count ⑩ edit title ⑪ edit 保留 stableId ⑫ edit 保留 USED state ⑬ removed code 删除 ⑭ 新 code 新 stableId/UNUSED ⑮ duplicate edit 拒绝 ⑯ delete Set ⑰ Undo 恢复 exact stableIds/states ⑱ close/reopen persistence |
| `app/.../repository/RecoveryPackageCompatibilityTest.kt` | ⑲ Set 出现在 Full Vault snapshot ⑳ used/unused 逻辑 round-trip ㉑ usedAt 保留 ㉒ export/import 空库精确状态 ㉓ 同包二次导入幂等 ㉔ used/unused divergence 仍 blocked ㉕ MergePlanner 冲突语义不变 |
| `app/.../ui/authenticator/RecoveryViewModelTest.kt` | ㉖ empty ㉗ create flow ㉘ multiline parse ㉙ validation error ㉚ mark used 后 remaining 更新 ㉛ mark unused 恢复 ㉜ reveal/hide ㉝ 单条 copy ㉞ Copy All ㉟ edit flow ㊱ delete→Undo ㊲ session lock 清除可见敏感状态 |
| `app/.../ui/authenticator/RecoveryCodesScreenTest.kt` | empty state、折叠卡片显示 counts 且不显示 plaintext |

## 20. FTL expectation

本 PR 修改真实 Android product flow（`v2/app/**` 与 `v2/core/**` 均变更），
合入 main 后 `changed-file-gate` 预计输出 `TEST_LAB_GATE=run`，Firebase
Test Lab 将被自动触发。本轮不主动运行 FTL。

## 21. Changed files（summary）

**app（production）**：
- `database/RecoveryCodeSetDao.kt`（+ observeAll / getById / updateTitle）
- `database/RecoveryCodeDao.kt`（+ observeBySet / observeAll / getById /
  getByStableId / upsert / deleteById / updateSortOrder）
- `domain/AuthModels.kt`（+ RecoveryCodeSet / RecoveryCode）
- `repository/AuthMappers.kt`（+ recovery mappers）
- `repository/RecoveryCodeRepository.kt`（新）
- `repository/VaultAccess.kt`（+ recoveryRepository()）
- `ui/authenticator/RecoveryViewModel.kt`（新）
- `ui/authenticator/RecoveryCodesRoute.kt`（新）
- `ui/authenticator/AuthenticatorViewModel.kt`（+ recovery 摘要 + OpenAccount）
- `ui/authenticator/AuthenticatorRoute.kt`（+ onOpenAccount / recovery provider）
- `ui/components/RecoveryCodeSetCard.kt`（新，替换旧 RecoveryCodesCard）
- `ui/model/AuthenticatorUiModels.kt`（+ remainingCount / usedAt / 摘要）
- `ui/navigation/RescueAuthRoutes.kt`（+ 正式 account detail route）
- `ui/RescueAuthApp.kt`（+ account detail destination）
- `ui/screens/authenticator/AuthenticatorScreen.kt`（+ account 列表/摘要）
- `ui/screens/authenticator/RecoveryCodesScreen.kt`（新）
- `ui/screens/authenticator/RecoveryCodeEditorSheet.kt`（新）
- `res/values/strings.xml` + `values-zh-rCN/strings.xml`（en + zh-CN）
- 删除旧 `ui/components/RecoveryCodesCard.kt`（被正式 card 取代）

**tests**：见 §19。

**docs**：`docs/PHASE4_P3_REPORT.md`（本文件）；`ROADMAP.md`、`AGENTS.md`、
`CHANGELOG.md` 最小状态更新。

## 22. 与 Phase 3 冲突检查

无冲突。本轮未触碰：codec / VaultPackagePayload / MergePlanner /
package format / Legacy importer / schema migration。Room schema 未改。

## 23. 尚未实现并留给后续 P4+ 的能力

- Developer Vault UI / Sensitive Action Re-auth（P4）
- Selective Export / Import（P5）
- Developer Vault 第二批（P6）
- Search / Pin（P7）
- Delete Undo 完善（P8）
- Provider/Account full management（rename / move / merge / delete UI）
- clipboard auto-clear（DEFER）
- 单条 Recovery Code delete（PRODUCT 未要求，保持编辑整体 list 语义）
