# Phase 4 P5 Report — Selective Export / Import

> 状态：**IMPLEMENTED / PR OPEN**（Issue #20，Phase 4 P5）。
> 范围：把 Phase 3D 已有的 Full Vault Native Package 流程扩展为 Selective
> Export / Import，**继续使用同一个**
> `VaultSnapshot` → `PortablePackageCodec` → `MergePlanner` → transactional apply。
> 不创建第二套 package format，不创建第二套 merge engine。

> **文档性质**：历史实现报告。正文中的 `PR OPEN`、`NOT STARTED` 等状态只
> 代表报告生成时的状态；当前状态以 `../ROADMAP.md`、`../AGENTS.md` 和
> [`README.md`](../README.md) 为准。

## 1. existing SnapshotScope audit

P5 编码前审计结论（`VaultSnapshot` / `SnapshotScope` / `VaultPackagePayload` /
`PackageValidator` / `MergePlanner` / `MergePlanApplicator` / `ExportImportService` /
`ExportImportViewModel` / `ImportPreview`）：

- `SnapshotScope` 已有四个值：`FULL_VAULT` / `AUTHENTICATOR_ONLY` /
  `DEVELOPER_ONLY` / `SELECTED_ITEMS`。logical contract **已完整**，本轮
  不做任何 enum 修改。
- `PortablePackageCodec` 从 3B 起就对所有 scope 一视同仁（同一 encode/decode），
  P5 不需要改动 codec。
- **唯一缺口**：代码中还没有一个“stableId 选择 → 过滤 `VaultSnapshot`”的纯
  logical 实现。UI 的 Selected-Items 选择、Export/Import 的 hierarchy/dependency
  closure 都依赖它。这正是 P5 新增的共享 selection engine（见 §2）。
- **Provider 无 stableId**：portable logical schema 中 Provider 是
  `serviceName` 分组（account 行自带 serviceName parent metadata），没有
  独立 stableId 实体。P5 的 Provider 选择是 UI convenience，扩展为 account
  stableIds 后再提交 selection（committed selection 永远是 stableId-only）。

## 2. final selection model

- **core 纯 Kotlin**：`com.rescueauth.v2.export.VaultSnapshotSelector`
  （object）+ `SelectedItemSet` / `SelectableItems` / `SelectableProvider` /
  `SelectableAccount` / `SelectableItem` / `SelectableRecoverySet` /
  `SelectableDeveloperEntry`。
- **无 Android UI / Room 依赖**：输入/输出是 logical stableIds 和
  `VaultSnapshot`。
- `SelectedItemSet` 只含四组 logical stableId：account / TOTP /
  recovery-code-set / developer-entry。Recovery Set 与 Developer Entry 都是
  **原子**选择单位（没有 per-code / per-field 选择身份）。
- `SelectableItems` 只含 stableIds + 安全 metadata（label / title / counts），
  可在 Compose state 中安全持有（绝不含 secret）。
- `SelectedItemSet.digest()` = **canonical SHA-256**（self-describing
  `<KIND>:<utf8-length>:<stableId>` 记录，canonical byte-sort 后对 UTF-8
  bytes 做 SHA-256，lowercase hex），用作 re-auth 授权与最终导出之间的
  selection 绑定（非敏感）。item kind（ACCOUNT / TOTP / RECOVERY_SET /
  DEVELOPER）进入 identity，与顺序无关、无歧义拼接、不依赖
  `hashCode()`/JVM hash seed（P5 security-boundary CR §1–§3）。

## 3. hierarchy / dependency closure semantics

由 `VaultSnapshotSelector.selected()` 统一实现（Export 与 Import 共用）：

- Provider selected → 该 serviceName 下全部 Account → 每个 Account 的全部
  TOTP + Recovery Sets。
- Account selected → 自身全部 TOTP + Recovery Sets，并保留 account 行
  （携带 Provider parent metadata `serviceName`）。
- single TOTP selected → 只包含该 TOTP + 其 Account（parent metadata）。
- Recovery Code Set selected → 整个 Set 原子（含 USED codes + usedAt）+ 其
  Account；不允许逐条 Recovery Code 选择。
- Developer Entry selected → 整个 entry 原子（五类：Android Signing Key /
  API Credential / SSH Key / Env Var Set / Generic Secret）；不允许逐 field。
- “自动包含 parent”是 **structural dependency closure**，不是把 parent 的
  其它 children 一起导出：只选 Account A 的一个 TOTP → Provider + Account A +
  该 TOTP，**不会**因此导出 Account A 的其它 TOTP/Recovery Sets。

## 4. export scope UX

- `ExportVaultScreen` 增加 scope picker：**Entire Vault / Authenticator /
  Developer / Selected Items…**。
- 每个 scope 都走同一 fresh re-auth（§6）+ 同一 per-export PIN 流程。

## 5. selected export UX

- Selected Items → 独立选择 screen（`ExportState.SelectingItems`）：
  - Authenticator section：Provider → Account → TOTP / Recovery Code Set；
  - Developer section：entry type + safe title/metadata（**不显示 secret**）；
  - Select All / Clear All / section-level select all；
  - 安全 summary：selected providers/accounts、TOTP count、Recovery set count、
    Developer entry count；
  - empty selection 不能 continue。
- **本轮不做 Search**（P7）。

## 6. re-auth integration

- `SensitiveAction.EXPORT_FULL_VAULT` → **`SensitiveAction.EXPORT_PACKAGE`**
  （最小 rename，语义变准确）。
- 新增 `SensitiveActionTarget.ExportRequest(scopeName, selectionDigest)`：
  - scope A authorization **不能**授权 scope B（test 38）；
  - selected export auth **绑定原始 selection digest**（test 39）；
  - auth 成功 → 只授权本次 pending export request → one-shot consumed，
    不复用给第二次 export；**无 auth cache**。
- **任何 `.rakpkg` export scope 都要求 fresh re-auth**（test 20），继续使用
  现有 `SensitiveActionGate` / `SensitiveActionRequest`，不 new 第二套
  BiometricPrompt。
- **scope 属于 authorization identity**：`AUTHENTICATOR_ONLY` 与
  `DEVELOPER_ONLY` 即使 selected stableId 集碰巧相同/为空也不能共享授权
  （test 39c `different scope with identical selection cannot share
  authorization`）。`ExportRequest(scopeName, selectionDigest)` 的 scopeName
  与 digest 共同构成 target identity（P5 security-boundary CR §3）。

## 7. import subset / selection UX

- decode 后先进入 **ChoosingScope**（safe summary：accounts / TOTP / recovery
  sets / developer counts）：
  - Everything（默认）；
  - Authenticator data（仅当 package 有 Authenticator 时可用）；
  - Developer data（仅当 package 有 Developer 时可用）；
  - Selected items…（独立选择 screen）。
- `AUTHENTICATOR_ONLY` package → Developer option disabled/hidden；
  Everything = 全部 Authenticator 数据。
- `SELECTED_ITEMS` package → 只在它已有的 subset 中进一步选择；绝不可能
  “恢复”package 中不存在的对象（stale stableId 明确失败）。

## 8. filtered snapshot + MergePlanner architecture

```
Export:
  scope/selection (stableId)
  → fresh re-auth (EXPORT_PACKAGE, scope+digest)
  → PIN + confirm
  → SAF CreateDocument
  → buildConsistentExportSnapshot (mutex + transaction) + re-resolve selection
  → selected() filter → VaultPackagePayload(scope)
  → PortablePackageCodec.encode → encrypted write

Import:
  OpenDocument → bounded read → identify → PIN → decode
  → logical validate → safe preview (ChoosingScope)
  → choose scope / items (SelectedItemSet)
  → VaultSnapshotSelector.selected(decoded, filter)  ← in-memory, no second package
  → MergePlanner.plan(destination, filteredSource)
  → preview (filtered counts only)
  → confirm → applySnapshot(filteredSource) → re-plan + preflight + transactional apply
```

- **Selective import 是 decoded snapshot filtering**：不改 ciphertext、不重新
  编码 package、不创建临时 package file。
- MergePlanner **只对最终选中的 filtered snapshot 运行**：unselected item 的
  conflict 不会阻塞（test 31）；selected item 的 conflict / recovery
  divergence 按现有规则 BLOCK（test 32/33）。

## 9. conflict / divergence behavior

- 未选中 item 有 conflict → **不阻塞 selected import**（test 31）。
- 选中 item 有 Developer conflict / Recovery state divergence / 其它现有
  blocking conflict → **按现有规则 BLOCK**（test 32/33）。
- **不引入** source-wins / destination-wins / auto conflict resolution。
- final apply 重新 plan：preview merge plan ≠ final authority；destination
  在 preview 后变化 → 重新 plan 并在新 conflict/divergence 时 BLOCK
  （test 34）。selection 本身也在 confirm 时重新验证 stableIds。

## 10. Developer five-type compatibility

- Selection / package 引擎支持五类：Android Signing Key / API Credential /
  SSH Key / Env Var Set / Generic Secret。
- Selected export/import round-trip 不丢任何 logical field（test 15/18/29；
  keystore base64 exact byte round-trip）。
- 不为 Signing Key / Env Var Set 新增 CRUD UI（P6 未做，selection /
  safe metadata / package preservation 正确即可）。

## 11. Recovery / TOTP preservation

- Recovery Code Set **原子**：stableId / title / code stableIds / values /
  status USED/UNUSED / usedAt / order 全部保留（test 17/28）。
- 不允许“只导出 remaining codes”或“默认去掉 USED codes”；Copy Remaining 是
  UI convenience，不是 export semantics。
- TOTP 完整保留：stableId / secret / algorithm / digits / period /
  account-provider relation；不重新 parse / normalize otpauth（test 16）。

## 12. stale-selection handling

- 最终 export snapshot 在 repository **shared mutex / consistent transaction**
  内重新解析 selection（`buildConsistentExportSnapshot` + `selected()`）。
- selected stableId 已不存在 → `SelectionStaleException` 明确提示
  “selection stale / item changed”，**绝不静默导出另一个对象**（test 23）。
- import filter 中 stale stableId → 同样明确失败（只能窄化，不能恢复）。

## 13. decoded plaintext lifecycle

- Decoded plaintext package **只存在内存**（`ExportImportService` 的
  `ImportSession`）。
- 不进 Room temp table / files/cache plaintext / SavedStateHandle / Bundle /
  DataStore（test 43/44）。
- Cancel / successful import / session lock / process recreation / switch to
  another package / fatal error → 丢弃（test 37/40/41）。
- process recreation → 重新开始 import flow。

## 14. ImportRecord behavior

- 继续沿用 Phase 3C/3D success semantics：只在 **successful transactional
  apply 后**记录（test 36）。
- Selective import 的 ImportRecord 记录 package/source identity（packageId）
  与 existing model 支持的 metadata；**不改 Room schema**，不额外持久化
  selection details（ImportRecord 不是 audit log 产品）。

## 15. Native / Legacy isolation

- Native package feature 不 import `com.rescueauth.v2.legacy`（grep 验证无
  引用）。
- 不修改 `LegacyRakVaultImporter` / `LegacyVaultSnapshotMapper` / legacy
  UI / `.rakvault` / frozen-v1 fixtures / LegacyImportRoute（Phase 5B 并行）。
- 共享只发生在 `VaultSnapshot` / selection engine / MergePlan safe preview /
  merge-apply。

## 16. changed files

```
core/src/main/kotlin/com/rescueauth/v2/export/VaultSnapshotSelector.kt   (new)
core/src/test/kotlin/com/rescueauth/v2/export/VaultSnapshotSelectorTest.kt (new)
app/.../exportimport/ExportImportService.kt      (scope export + filtered import)
app/.../exportimport/ExportImportViewModel.kt    (scope/selection/choosing-scope states)
app/.../exportimport/ImportPreview.kt            (filtered-source preview)
app/.../security/SensitiveAction.kt              (EXPORT_PACKAGE rename)
app/.../security/SensitiveActionRequest.kt       (ExportRequest target)
app/.../ui/screens/exportimport/ExportImportScreens.kt (scope pickers + selection UI)
app/.../ui/screens/exportimport/ExportImportRoute.kt    (wiring)
app/.../res/values/strings.xml + values-zh-rCN/strings.xml (new strings)
app/src/test/.../SelectiveExportImportServiceTest.kt     (new)
app/src/test/.../SelectiveExportImportViewModelTest.kt   (new)
app/src/test/.../ui/exportimport/SelectiveExportImportUiTest.kt (new)
test updates: ExportImportViewModelTest / SensitiveActionGateTest /
  FakeSensitiveActionPrompt (open) / DeveloperPackageIntegrationTest (no change)
docs: PHASE4_P5_REPORT.md (this) / ADR-0012 / ROADMAP.md / AGENTS.md / CHANGELOG.md
```

## 17. tests

- core selection（13 项 + extras，`VaultSnapshotSelectorTest`）。
- export integration（service，§14–24 大部分，§20–22 在 ViewModel 层）。
- import integration（service，§25–37）。
- security（ViewModel，§38–44 + §20–23）。
- UI（Compose Robolectric，§23 大部分）。
- 全量：`:core:test`（360 tests）+ `:app:testDebugUnitTest`（366 tests）PASS。

## 18. release build status

- `:app:lintDebug` PASS（0 Error/Fatal）。
- `:app:assembleDebug` PASS。
- `:app:assembleDebugAndroidTest` PASS。
- `:app:assembleRelease` PASS → 产出 **unsigned** `app-release-unsigned.apk`
  （未创建/提交 signing key）。

## 19. FTL expectation

本轮修改 Native package product flow，merge main 后 FTL gate 预计触发。
**未主动运行 FTL**（按 Issue #20 §29）。

## 20. parallel conflict check with Phase 5B

- Phase 5B（Legacy v1 Android Import UI，#1 并行）将实现
  `LegacyRakVaultImporter` / Legacy UI / `.rakvault` / frozen-v1 等。
  本 P5 PR **不触碰**这些文件。
- 双方可能都会碰：Settings import/export entry、RescueAuthRoutes、
  RescueAuthApp、strings、shared preview component。P5 继续使用现有
  Native `ExportImportRoute`/`ViewModel`，未重构为 GenericImporter UI；
  新增 selection UI 放在 native package feature 下；未创建
  `GenericImportCoordinator`。

## 21. remaining P6/P7/P8 scope

- **P6** Developer Vault 第二批（Android Signing Key / Env Var Set CRUD）—
  NOT STARTED。
- **P7** Search + Pin — NOT STARTED（本 PR 不做 Search）。
- **P8** Delete Undo 完善 — NOT STARTED。

状态：P1 CLOSED / P2 CLOSED / P3 CLOSED / P4 CLOSED /
**P5 IMPLEMENTED / PR OPEN** / P6 NOT STARTED / P7 NOT STARTED / P8 NOT
STARTED。Phase 5A CLOSED；Phase 5B parallel / NOT part of this PR。
