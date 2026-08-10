# PHASE 4 P8 — Delete Undo & Authenticator Final Completion 报告

> **状态**：IMPLEMENTED / PR OPEN（Issue #20，Ref #20）
> **日期**：2026-08-10
> **基准**：最新 `main`（含 P7 Search+Pin 与 L2 About+Update 均已 merge）
> **范围**：v2.0 最后一个主要 product-feature closure——P8 Delete Undo 完善、
> Recovery Code Set Move、Empty Account Native Package round-trip 回归/最小修复。
> **明确不做**：release signing / clipboard auto-clear / UI 大重构；Provider
> Delete Undo / Signing Key Undo / Account Merge Undo / 全局 Undo history /
> persistent Undo queue / checkpoint backup / package schema v2 / Room schema v4。

---

## 1. 现有 Undo architecture 审计

Phase 4 P1–P7 合并后，删除相关的现状如下：

| 删除对象 | 删除方式 | Undo 现状 |
|---|---|---|
| TOTP Credential | `AuthenticatorViewModel.deleteCard` → `AuthenticatorRepository.deleteTotpCredential`（返回被删 row） | ✅ 已有 Undo：`pendingUndo: TotpCredential?`，`restoreTotpCredential` 精确 stableId+secret+params 恢复 |
| Recovery Code Set | `RecoveryViewModel.deleteSet` → `RecoveryCodeRepository.deleteSet`（返回 domain set） | ✅ 已有 Undo：`pendingUndo: RecoveryCodeSet?`，`restoreSet` 精确 stableId + codes + USED/UNUSED/usedAt 恢复 |
| Account | `ProviderAccountRepository.deleteAccount`（cascade 删除）→ `AuthenticatorViewModel.deleteAccount` | ❌ 无 Undo（此前是 destructive confirmation） |
| Provider | `ProviderAccountRepository.deleteProvider`（cascade） | ❌ 无 Undo（保留 confirmation，P8 不要求） |
| 普通 Developer Entry（API/SSH/Env/Generic） | `DeveloperDetailViewModel.delete` → `DeveloperRepository.delete` | ❌ 无 Undo（此前是 confirmation） |
| Android Signing Key | 同上（`delete`） | ❌ 无 Undo（P8 例外，保留 confirmation） |
| Account merge | `mergeAccounts` | ❌ 无 Undo（保留 confirmation，P8 不要求） |

**snapshot/token 保存**：TOTP 与 Recovery 目前用 ViewModel 内的内存字段保存
被删对象；session lock 时 `RecoveryViewModel` 已清 `_revealedIds`，TOTP 与
Recovery 的 pending undo 在 lock 分支被清空（P8 补齐 `RecoveryViewModel.pendingUndo`
与 `AuthenticatorViewModel.pendingAccountUndo`）。

**snackbar 持有**：每个 route 自建 `SnackbarHostState`，经共享
`UndoSnackbarContract.showUndoSnackbar`（`withDismissAction=true`，返回
`UndoResult.UNDO/DISMISSED`）呈现。一次只支持一个 Snackbar：新 delete 会取代
上一个（旧 token 释放）。

**restore 的 stableId 保留**：TOTP/Recovery 的 restore 都用原 stableId（`upsert`
时若同 stableId 已存在则跳过，避免重复 identity）。这正是 P8 需要的语义。

**session lock / process recreation**：`SecureSessionStateMachine` 状态收集在各
ViewModel init 中；lock 时清 UI state。P8 在此之上统一补全 pending secret
snapshot 的清理（见 §9）。

> 结论：P1/P3 的 TOTP/Recovery Undo 已正确，**不重写**，只补共享生命周期/
> 安全清理与回归测试。

---

## 2. 最终删除分类（v2.0 正式 contract）

### 普通删除（统一体验：delete → 立即 DB/UI 移除 → SnackBar → 短窗口 Undo）

1. **TOTP**
2. **Recovery Code Set**
3. **Account**（本轮由 confirmation 改为普通删除 + Undo）
4. **ordinary Developer Entry**（API Credential / SSH Key / Env Var Set / Generic Secret）

### 高破坏性操作（保留 confirmation，**不加** Undo）

1. **Provider cascading delete**（confirmation + cascade，无 Undo）
2. **Android Signing Key**（confirmation + delete，含 keystore binary，无 Undo）
3. **Account merge / large destructive merge**（confirmation + transactional merge，无 Undo）

不给普通删除再叠 confirmation + delete + Undo。P8 目标是减少普通误删的摩擦。

---

## 3. ordinary Developer Entry 范围

ordinary = **API Credential**、**SSH Key**、**Environment Variable Set**、
**Generic Secret**。删除 → 立即移除 + Undo。Android Signing Key 保持
confirmation-only（携带 keystore binary + signing credentials），且不改其
fresh re-auth / export security。删除 ordinary Developer Entry 不需要 fresh
biometric re-auth。

---

## 4. Undo architecture：复用现有 mechanism

未创建 automatic checkpoint backup，未恢复 v1 checkpoint system。Undo 通过
**database/domain transaction + short-lived in-memory restore snapshot/token**
完成。新增最小共享抽象：

- `domain/UndoSnapshots.kt` — `DeletedAccountSnapshot`、`DeletedDeveloperEntrySnapshot`、
  `UndoRestoreOutcome`（纯值对象，无序列化注解，防误持久化）。
- 未建 persistent Undo table / Undo history / event sourcing / command journal /
  shadow database / backup files / WorkManager / global undo stack。

---

## 5. Undo snapshot 安全生命周期

Account/Developer Undo payload 含真正 secret（TOTP secret、Recovery
plaintext、API secret、SSH private key/passphrase、Env/Generic value），因此：

- **只允许 in-memory**；不进入 SavedStateHandle / rememberSaveable / Bundle /
  DataStore / Room undo table / file / cache / clipboard / logs / analytics /
  navigation route。
- process death / Activity recreation 允许丢失 Undo opportunity。
- 不为恢复 Snackbar Undo 持久化 secret snapshot——这是正式安全取舍。

---

## 6. Session lock 清 Undo state

`VaultRepository` 的写路径在 lock 时抛 `SessionLockedException`。各 ViewModel
在 session state 离开 UNLOCKED 时清空 pending undo：

- `AuthenticatorViewModel`：`pendingUndo = null; pendingAccountUndo = null`
- `RecoveryViewModel`：`pendingUndo = null`（恢复码 plaintext）
- `DeveloperDetailViewModel` / `DeveloperListViewModel`：`DeveloperUndoStore.clear()`
- unlock 后不恢复任何之前 Undo token；lock 前已 delete 未 Undo 的保持删除。

---

## 7. Undo window / multiple deletes

沿用当前 Snackbar duration / UX convention（`showSnackbar` 默认时长 +
`withDismissAction`）。当前 UI 一次只支持一个 Snackbar：

- 新 delete → 上一个 Snackbar 被替换、上一个 Undo token 被释放、新 Undo 生效。
- 不为并发 Undo 做 secret snapshot 无限排队 / Undo history panel / persistent queue。
- 必须确保上一个 payload reference 被释放（新的 delete 直接覆盖旧 token 字段）。
- TOTP/Recovery 走各自 route 的 `LaunchedEffect(events)`（串行、一次一个）；
  Developer 走共享 `DeveloperUndoStore`（单 token）；Account 走
  `AuthenticatorViewModel.pendingAccountUndo`（单 token）。

**具体 policy**：单 pending token per 类型、新 delete 替换旧、token 单次消费
（Undo 或 dismiss 后即失效）。

---

## 8. Account Delete + Undo：完整 subtree 恢复

`ProviderAccountRepository.deleteAccountWithSnapshot(accountId)` 在同一
transaction 内删除前捕获**完整、精确、可恢复**的 logical/domain snapshot：

- Account：stableId / serviceName / accountName / favorite(pinned) / notes /
  sortOrder / createdAt / updatedAt。
- 全部 TOTP：stableId / secretBase32 / algorithm / digits / periodSeconds /
  createdAt。
- 全部 Recovery Set：stableId / title / createdAt。
- 全部 Recovery Code：stableId / value / USED-UNUSED / usedAt / sortOrder。

不只恢复“看得见的 metadata”，而是完整 subtree。

---

## 9. Account Undo restore semantics

`ProviderAccountRepository.restoreAccount(snapshot)` 单 transaction 恢复
Account + TOTP + Recovery Sets/Codes：

- exact Account stableId、exact TOTP stableIds、exact Recovery Set stableIds、
  exact Recovery Code stableIds；TOTP secret/params 不变；Recovery
  USED/UNUSED、usedAt 不变；pinned 不变；timestamps 按原值精确保留。
- 无 orphan、无 partial restore。
- **不** delete 后 create 一个“等价 Account”、**不** mint 新 stableId。

---

## 10. Account Undo conflict/race behavior

restore 前在同一 serialized mutation boundary 做 preflight，至少拒绝：

- same Account stableId 已重新存在（payload 任意不同）。
- 任一 TOTP / Recovery Set / Recovery Code stableId 已在 destination 存在
  （schema uniqueness collision）。
- 失败 → transaction rollback、UI 中性提示“Unable to restore because the
  vault changed”、清 pending secret snapshot。
- 不做 source-wins overwrite；不修改 PackageMergePlanner（这是 local mutation
  restore，不是 package import）。

---

## 11. Account delete UI

- 最终普通 Account delete：立即移除 + Snackbar “Account deleted” + Undo。
- 可带 safe account metadata/name；**不**在 Snackbar 显示 TOTP secret /
  Recovery plaintext / secret counts 之外的敏感 detail。
- Account 删除后当前 screen/navigation 回到合理 parent（原本就是 Account list
  view，列表自动经 Room Flow 刷新）。
- Undo 成功：Room Flow 恢复 hierarchy；若 P7 已存在，Search index 自动重新
  出现（由 safe projection 生成）；pinned ordering 正常恢复。
- 不手动 patch 多份 UI cache；Room/domain Flow 为 source of truth。

---

## 12. ordinary Developer delete + Undo

`DeveloperRepository.deleteWithSnapshot(stableId)` 删除前保存 exact logical
entry snapshot（四类 ordinary）。`restoreFromSnapshot(snapshot)` 恢复：

- exact same Developer stableId、exact type、exact title/notes、exact payload、
  exact createdAt / updatedAt、sortOrder。
- transactionally insert。
- **不** mint 新 stableId、**不** drop unknown fields、**不** normalize secret、
  **不** reorder env variables、**不** regenerate generic field identity。

Android Signing Key 在 `deleteWithSnapshot` 中返回 null（保持 confirmation，
无 Undo 动作）。共享 `DeveloperUndoStore`（单 token）覆盖 detail 页 pop 后
的 post-navigation 生命周期（§32）。

---

## 13. Developer Undo security

- Developer undo payload 含长期 secret → in-memory only。
- Snackbar 只显示 safe entry title / type；不显示 API key/secret / SSH private
  key/passphrase / Env value / Generic value。
- session lock 清 pending payload；process recreation 不恢复 Undo。
- Undo 不调用 SensitiveAction reveal/copy path——Undo 是数据恢复，不是 secret
  disclosure。

---

## 14. Android Signing Key 保持高破坏性 confirmation

P6 Signing Key delete 继续当前 confirmation。**不**把 keystore bytes 放进
Snackbar Undo payload；**不**在内存长期挂大型 keystore snapshot。Signing Key
删除后无 Undo requirement。若当前 UI 已有 confirmation：保持。

---

## 15. TOTP / Recovery existing Undo regression

审计并锁定现有行为：

- TOTP Undo：exact stableId / secret / algorithm/digits/period / parent account。
- Recovery Set Undo：set stableId / code stableIds / values / USED/UNUSED /
  usedAt / order。

现有实现已满足，未重写 production code，只补共享 lifecycle/security cleanup
与回归测试（见 §35）。

---

## 16. Recovery Code Set Move — 正式能力

`RecoveryCodeRepository.moveSet(setId, destinationAccountId)`：

- 用户：Account A → Recovery Set X → Move → 选 destination Account B →
  confirm → Set X 出现在 B。
- 支持跨 Provider（destination Account 自带其 serviceName）。
- destination picker：按现有 Provider/Account hierarchy 显示，排除 current
  owning Account；无其它 Account 时 Move disabled / safe empty state。
- Move dialog 内不顺手 Create Account/Provider；flow 保持小。

---

## 17. Recovery Move identity contract

Move 保留：Recovery Set stableId / title / createdAt / updatedAt / sort-order
metadata；每个 Recovery Code stableId / value / USED-UNUSED / usedAt /
sortOrder。Move 只改变 parent Account relation。**不** delete old set +
recreate、**不** mint new IDs、**不** reset USED、**不** clear usedAt、**不**
normalize code、**不** reorder codes、**不**只 move remaining/unused codes。
Set 是 atomic collection。

---

## 18. Recovery Move duplicate semantics

不按 title dedupe（destination 已有同 title set 则两者共存，title 不是
identity）。不 merge sets、不 dedupe code plaintext、不 source-wins / destination
wins。若 same stableId 已在 destination 出现（corrupt/impossible case）：
safe reject + rollback。

---

## 19. Recovery Move transaction boundary

Move 走现有 `VaultRepository` 共享 serialized mutex + Room transaction。
**不**单独 delete source → insert destination 两个 transaction。任何失败整个
move rollback，不留 orphan code。

---

## 20. Recovery Move 与 package / search

- Full Vault snapshot → parent Account 正确（`buildConsistentExportSnapshot`）。
- Selected Recovery Set export → closure 包含新的 parent Account/Provider
  （`VaultSnapshotSelector.selected` 的 dependency closure）。
- Native package round-trip → ownership 正确。
- 若 P7 已合入：Recovery Set search result context 自动变成新 Provider/Account
  context（Flow projection 自然刷新，不手动改 search index）。

---

## 21. Empty Account package regression：先证明是否真实存在

**先写并运行 focused regression test，再决定是否修。**

正式场景：source Vault 中 `Google / empty@example.com` 的 Account（有 stableId、
可有 pinned/notes，但 **NO TOTP / NO Recovery Set**）。执行 Full Vault Export
→ `.rakpkg` → Import into empty destination → preview/merge/apply，最终必须
存在 `Google / empty@example.com`。

**结果**：确认 bug 存在。在 `MergePlanner.plan()` 中：

```
val hasInsert = totpPlans.any{INSERT} || setPlans.any{INSERT}
...
else if (hasInsert) { INSERT_ACCOUNT }
else { DUPLICATE_ACCOUNT }   // ← 空 Account（无 child insert）走到这里
```

空 Account 因无任何 child 要 insert 而被判为 `DUPLICATE_ACCOUNT`，destination
缺失时不会被创建——**container 只在“必须有至少一个 INSERT child”时才被插入**，
正是 P8 §24 描述的 bug。

---

## 22. Empty Account Selected Export / Import

还必须覆盖 Selected Items：select 该空 Account → export → import into empty
Vault 必须恢复 Account。不得因“没有 child record”生成 logical empty/no-op
selection。`VaultSnapshotSelector.selected` 已正确保留显式选中的空 Account
（Account 本身是 selected logical object，children 是 descendants），且
`SelectedItemSet.digest()` selection security contract 未改动。

---

## 23. Empty Account Authenticator-only package

`SnapshotScope.AUTHENTICATOR_ONLY` 当前 scope contract 包含 Account container
（`VaultSnapshotSelector.authenticatorOnly` 保留全部 accounts）。因此
Authenticator-only export/import 会保留空 Account，无需改 scope 定义。
默认目标达成：Authenticator logical hierarchy 不因没 credential 丢失用户显式
创建的 Account。

---

## 24. 如果 empty Account bug 确认存在：只做最小修复

审计的组件：`VaultSnapshot` / `SnapshotBuilder` / `PackageValidator` /
`VaultSnapshotSelector` / `MergePlanner` / `MergePlanApplicator` /
`VaultRepository.applySnapshot`。问题确实发生在“Account only INSERT if a
child has INSERT”的 container semantics（`MergePlanner`）。

**最小修复**（`MergePlanner.plan()`）：源中 `isEmptyAccount`（无 TOTP 且无
Recovery Set）且 destination 缺失时，Account 本身产生 `INSERT_ACCOUNT`，
不依赖是否有 child：

```
val isEmptyAccount = account.totpCredentials.isEmpty() && account.recoveryCodeSets.isEmpty()
...
} else if (hasInsert || isEmptyAccount) { ... INSERT_ACCOUNT ... }
else { DUPLICATE_ACCOUNT }
```

**未**：新增 Provider table / Provider stableId / Room schema v4 / package
schema v2 / redesign MergePlanner 全部 identity / 修改 crypto-envelope /
修改 selectionDigest。只修 empty Account data preservation。

---

## 25. Empty Account merge semantics（锁定）

- A. destination 无该 Account stableId → insert Account even if empty ✅
- B. destination 已有 same exact logical Account → 现有 duplicate semantics（USE_EXISTING）✅
- C. repeated import → idempotent（第二次 `USE_EXISTING`，不建重复 Account）✅
- D. same stableId + existing incompatible Account metadata → 遵循现有 Account
  conflict/divergence contract，不 silent overwrite ✅

未重新设计 Account metadata merge policy。

---

## 26. Provider model 不在本轮重开

Provider = serviceName grouping 不变。未创建 Provider entity/table、不支持
persistent empty Provider、无 Provider stableId、无 logical schema v2、无
Legacy Provider identity rewrite。空 Account 存在时其 serviceName 自然让
Provider grouping 存在；最后一个 Account 删除后 grouping 自然消失——不是 P8
的问题。

---

## 27. Pin state compatibility

P7 已合入：Account Delete Undo 恢复 pinned state（Pinned Account → delete →
Undo → still Pinned，排序正确）。pinned 不是 UI-only transient state；storage
兼容字段 `favorite` 按当前 contract exact restore（`DeletedAccountSnapshot` 含
`favorite`，restore 时精确写回）。

---

## 28. Search compatibility

P7 已合入：delete Account/Developer → Search result 自动消失；Undo → Search
result 自动恢复；Recovery Move → Search result context 自动更新。未创建额外
search mutation path、未缓存 deleted search document、未把 Undo snapshot 塞
SearchIndex。Search 继续由 safe projection + Room/domain Flow 生成。

---

## 29. Legacy data compatibility

Phase 5B migration 后的 Account / Recovery Set / ordinary Developer Entry 与
Native-created data 使用同一 P8 behavior（同一 repository 路径），UI 中不区分
legacy vs native。测试覆盖 Legacy-mapped persisted 对象走 delete+Undo / Move
（同一持久化路径等价）。

---

## 30. No fresh re-auth for Undo

普通 delete + Undo 不要求 fresh biometric re-auth。Sensitive Action re-auth
正式边界不变：reveal/copy long-lived secret、export package、export keystore、
signing credential disclosure。Android Signing Key delete 仍用 confirmation
（非 fresh biometric requirement，除非 docs 已正式要求）。

---

## 31. Accessibility / i18n

en + zh-CN 补齐：Account deleted、Developer entry deleted、Undo、Unable to
restore、Recovery set moved、Move recovery codes、Move to account、No other
accounts available、restore conflict/error、必要 confirmation/Snackbar。
Snackbar action 有明确 accessibility label（`undo_snackbar_action_label`）。
Move destination 的 screen reader 能读 Provider + Account context
（`MoveDestination.label` = "Provider · Account"）。不把 TOTP secret / Recovery
plaintext / Developer secret 放进 contentDescription / Snackbar / error。

---

## 32. UI behavior

- Account delete：操作成功后 hierarchy 立即更新（Room Flow），Snackbar Undo；
  Undo 成功恢复 hierarchy。
- Developer ordinary delete：详情页删除后合理 navigate back → Developer list
  显示 Snackbar Undo；Undo ownership 放 `DeveloperUndoStore`（共享、app-scoped），
  覆盖 post-navigation Snackbar 生命周期，不会因 Detail 被 pop 丢失 token。
- Recovery Move：Account detail / Recovery UI 提供 Move action（卡片 overflow
  menu），不重写整个 Account detail。

---

## 33. Account Undo tests（`P8AccountUndoTest`，12 个）

1. delete Account immediate DB removal ✅
2. （UI Flow 即时移除由 Room Flow 保证，见 §32；repository 级已验证）
3. Undo restores same Account stableId ✅
4. Undo restores account name/serviceName ✅
5. Undo restores pinned state ✅
6. Undo restores notes/order/timestamps per contract ✅
7. Undo restores all TOTP ✅
8. TOTP stableIds preserved ✅
9. TOTP secret/algorithm/digits/period preserved ✅
10. Recovery Sets restored ✅
11. Recovery Set stableIds preserved ✅
12. Recovery Code stableIds preserved ✅
13. USED/UNUSED preserved ✅
14. usedAt preserved ✅
15. atomic restore (no orphans) ✅
16. injected restore failure rolls back (conflict blocks) ✅
17. conflicting destination state → safe failure/no overwrite ✅
18. second Undo cannot consume same token twice ✅
19. expired/dismissed Undo cannot restore（token 不存在则 no-op/block）✅
20. session lock clears pending Account Undo（lock 后 repository 拒绝）✅
21. process recreation does not restore secret Undo snapshot ✅

---

## 34. Developer Undo tests（`P8DeveloperUndoTest`，11 个）

22. API Credential delete + Undo exact stableId/payload ✅
23. SSH Key delete + Undo exact stableId/payload ✅
24. Env Var Set delete + Undo exact ordering/values ✅
25. Generic Secret delete + Undo exact fields ✅
26. createdAt preserved ✅
27. secret values unchanged ✅
28. failed restore rolls back（stableId 冲突 → Blocked，不覆盖）✅
29. session lock clears pending Developer Undo（lock 后 repository 拒绝）✅
30. process recreation does not restore Undo ✅
31. Snackbar/toString/contentDescription contain no fixture secret（safeLabel）✅
32. Android Signing Key still uses confirmation（deleteWithSnapshot 返回 null）✅
33. Android Signing Key has no P8 Undo action ✅

---

## 35. Existing Undo regression tests

34. TOTP delete + Undo still exact（现有 `AuthenticatorViewModelTest` 通过）
35. Recovery Set delete + Undo still exact（现有 `RecoveryViewModelTest` 通过）
36. Recovery USED/usedAt still restored（现有测试通过）
37. no automatic checkpoint files ✅
38. no persistent Undo storage introduced ✅

未复制已有完全等价 test。

---

## 36. Recovery Move tests（`P8RecoveryMoveTest`，10 个）

39. move Recovery Set Account A → B ✅
40. cross-provider move ✅
41. same Account excluded / no-op safe ✅
42. no destination state（dest missing → NotFound）✅
43. Set stableId preserved ✅
44. every Code stableId preserved ✅
45. plaintext values preserved ✅
46. USED/UNUSED preserved ✅
47. usedAt preserved ✅
48. sortOrder preserved ✅
49. same-title destination set does not dedupe ✅
50. failure rolls back ✅
51. no orphan code ✅
52. Native snapshot new parent correct ✅
53. Selected export closure uses new parent ✅
54. package round-trip ownership correct ✅
55. Legacy-imported Recovery Set can move ✅

---

## 37. Empty Account regression tests

`P8EmptyAccountPackageTest`（app，7）+ `MergePlannerTest`（core，3）：

56. Full Vault snapshot contains empty Account ✅
57. Full package encode/decode retains empty Account ✅
58. import empty Account into empty destination creates Account ✅
59. serviceName/provider grouping visible after import ✅
60. account stableId exact ✅
61. account pinned state preserved ✅
62. account metadata preserved ✅
63. repeated import idempotent ✅
64. Selected Items export of empty Account non-empty/valid ✅
65. Selected Items import restores empty Account ✅
66. Authenticator-only round-trip retains empty Account ✅
67. final re-plan still creates empty Account correctly ✅
68. no package format/version change ✅
69. no Room schema change ✅
70. no Provider-model redesign ✅

Focused test 在修复前 FAIL → 确认 bug 真实存在 → 最小修复后 PASS。保留全部
regression tests。

---

## 38. Security tests

通过 architecture/API surface + state tests 保证，不做夸张 reflection hack：

- Undo secret snapshot 不进入 SavedStateHandle/Bundle/DataStore/file/cache/
  clipboard/log（snapshot 是无序列化注解的纯值对象，仅内存持有）。
- session lock clear（各 ViewModel lock 分支清 token）。
- Snackbar 不含 secret（`safeLabel` = metadata only）。
- process recreation 无恢复（无持久化路径，`P8AccountUndoTest` / 
  `P8DeveloperUndoTest` 覆盖）。

---

## 39. Parallel / completed feature boundaries

未修改：P7 Search matcher/security contract、Account-only Pin scope、L2 update
Ed25519 protocol、About/update networking、SensitiveAction security contract、
PortablePackage crypto、selectionDigest、Legacy decoder format、Developer P6
secret disclosure flows。P7/L2 均已 merge，无等待；若未来 rebase 只需最小冲突
解决。

---

## 40. 本轮明确不做

未实现：Provider Delete Undo、Android Signing Key Undo、Account Merge Undo、
global Undo history、persistent Undo queue、checkpoint backup、automatic
backup、clipboard auto-clear、TOTP Pin、Developer Pin、Favorites、Provider
model redesign、package schema v2、Room schema v4、production signing、
update-key provisioning、release publishing、general UI redesign。

---

## 41. Known JVM test flake

首次全量 `:app:testDebugUnitTest` 出现一次 `DeveloperScreenTest >
listShowsMetadataButNeverSecretPlaintext` 失败（**无** `UncaughtExceptionsBeforeTest`
/ `SQLiteConnectionPool closed` 堆栈，疑似 Robolectric 偶发资源竞争）。立即重跑
该测试与全量套件：**全部 PASS**，未复现。未重写 test infrastructure。

---

## 42. Validation 结果

| 命令 | 结果 |
|---|---|
| `./gradlew :core:test` | ✅ BUILD SUCCESSFUL（407 tests，0 failures） |
| `./gradlew :app:testDebugUnitTest` | ✅ BUILD SUCCESSFUL（578 tests，0 failures） |
| `./gradlew :app:lintDebug` | ✅ 0 errors（111 pre-existing warnings） |
| `./gradlew :app:assembleDebug` | ✅ |
| `./gradlew :app:assembleDebugAndroidTest` | ✅ |
| `./gradlew :app:assembleRelease` | ✅（unsigned，未创建 signing key） |

未主动运行 FTL（不在本轮）。

---

## 43. Docs / status

更新：`ROADMAP.md`、`AGENTS.md`、`CHANGELOG.md`、`docs/PHASE4_P8_REPORT.md`。

P8 最终状态明确：

- P1 **CLOSED**，P2 **CLOSED**，P3 **CLOSED**，P4 **CLOSED**，P5 **CLOSED**，
  P6 **CLOSED**，P7 **CLOSED**，P8 **IMPLEMENTED / PR OPEN**。
- P8 完成后：Phase 4 daily-use feature slices = feature implementation
  complete。
- **不宣称 v2.0 RELEASED**：后面仍有 final polish/audit、test stability、
  production Android signing、production Update Ed25519 provisioning、signed
  release smoke、FTL/full regression。

---

## 44. Final report 要求逐项对照

1. existing Undo architecture audit → §1
2. final deletion classification → §2
3. Account Delete + Undo implementation → §8
4. Account exact subtree snapshot contract → §8/§17
5. Account Undo conflict/race behavior → §10
6. ordinary Developer Delete + Undo → §12
7. Android Signing Key exception → §14
8. TOTP/Recovery existing Undo regression → §15/§35
9. Undo memory/security lifecycle → §5/§13/§38
10. session-lock/process-recreation behavior → §6
11. multi-delete/snackbar policy → §7
12. Recovery Set Move implementation → §16
13. Recovery identity/state preservation → §17
14. Recovery Move transaction/rollback → §19
15. Recovery Move package/search compatibility → §20
16. empty Account regression result before fix → §21
17. root cause if bug existed → §24
18. exact minimal production fix if needed → §24
19. Full/Selected/Authenticator-only package results → §21–§23/§37
20. repeated-import/idempotence result → §25/§37
21. Pin/Search integration → §27/§28
22. Legacy data compatibility → §29
23. accessibility/i18n → §31
24. Room schema change status（expected none）→ 无改动
25. package format/schema change status（expected none）→ 无改动
26. changed files → PR diff
27. tests / counts → §33–§37（app +40、core +3）
28. core test result → §42
29. app test result → §42
30. lint/build/release result → §42
31. known JVM flake status → §41
32. FTL expectation → 未运行（不在本轮；release 前 full regression 再跑）
33. remaining release-only work → 见 §43
34. PR URL → PR body
35. branch → PR body
36. commits → PR body
