# Phase 4 PA Report — Provider & Account Full Management

> 状态：**IMPLEMENTED / PR OPEN**（Issue #32）。
> 范围：在现有 minimal hierarchy（TOTP + Recovery loop）之上补齐 Provider /
> Account 正式 management 能力。**Room schema / package format 零改动**。
> Provider/Account 的 create/rename/move/merge/delete 全部走共享
> `VaultRepository` 单 mutex + 单 Room transaction。

## 1. current Provider/Account persistence audit

编码前审计结论（`AuthAccountEntity` / `TotpCredentialEntity` /
`RecoveryCodeSetEntity` / `RecoveryCodeEntity` / `AuthAccountDao` /
`VaultRepository` / `AuthenticatorRepository` / `RecoveryCodeRepository` /
`MergePlanApplicator` / `VaultSnapshot` / `MergePlanner` / `Canonicalization`）：

- **Room schema v3**：`auth_account`（含 `serviceName` + `accountName` +
  `stableId`）/ `totp_credential`（FK→account，`stableId`）/ `recovery_code_set`
  （FK→account，`stableId`）/ `recovery_code`（FK→set，`stableId`）。
  本轮 **零 schema 改动**（只加 DAO 查询/更新方法，不动表结构）。
- **Provider 无 Room entity、无 package stableId**：Provider 是
  `serviceName` 分组字段（account 行自带 serviceName parent metadata）。
  portable logical schema（`VaultSnapshot.VaultAccount.serviceName`）中 Provider
  同样只是 serviceName 字符串，没有独立 stableId 实体（与 P5 审计一致）。
  本轮 **不新增** Provider stableId，不把 DB row id 当 portable identity。
- **empty Provider 不是当前正式能力**：`auth_account` 行是最小持久化单元，
  始终携带 `serviceName`；没有独立的空 Provider 表。因此 Create Provider 通过
  创建其首个 Account 来持久化（见 §2）。
- 现有 `VaultRepository.mutate { }` 是统一 transactional mutation 边界
  （单 mutex + 单 Room transaction），已保证跨表 mutation 原子。
- 现有 `Canonicalization.totpFingerprint(secret, algorithm, digits, period)`
  是唯一官方 TOTP semantic fingerprint，Account merge 直接复用。

## 2. Provider logical identity / empty-provider behavior

- **Provider logical identity = `serviceName` 字符串**（trim）。rename 就是更新
  全部 descendant Account 的 `serviceName`。Account / TOTP / Recovery Set/Code
  的 `stableId` 全部保留。
- **Empty Provider 不是当前正式能力**。因为当前 persistence 不允许空 Provider
  （Account 是最小持久化单元），UI 诚实反映：**Create Provider 同时要求并提供
  首个 Account name**（`CreateProviderDialog` 两个字段）。不为支持空 Provider
  改 logical/package model。cross-provider merge 后 source provider 变空时，
  直接保留为空（DB 里没有空 Provider 行可删），Provider 删除是独立 destructive
  action，不自动触发。

## 3. Provider create / rename / delete

- **create**：`createProvider(serviceName, accountName)` 在当前事务内创建该
  Provider 的首个 Account。exact duplicate provider name（任何 account 已占用该
  serviceName）→ `ConflictException`（不静默 reuse）。名称只做外部 whitespace
  trim，不 lowercase、不 case-fold merge、不 alias。
- **rename**：`renameProvider(old, new)` 更新全部 descendant Account 的
  `serviceName`（`updateServiceNameForAll`）。rename-to-existing →
  `ConflictException`（不静默 merge，用户可先改名或走 Account move/merge）。
  无 delete/recreate。
- **delete**：`deleteProvider(serviceName)` 单事务级联删除全部 Account（及其
  TOTP、Recovery Sets+Codes），返回安全 counts。失败整体 rollback，无 orphan。

## 4. Provider rename vs TOTP issuer semantics

- 当前 `TotpCredential` **无独立 issuer 字段**：issuer 由 parent
  `AuthAccount.serviceName` 派生（UI 层 `account.serviceName`）。TOTP 凭据本身
  只有 `secretBase32 / algorithm / digits / period / stableId / accountId`。
- Provider rename 只更新真正属于 hierarchy 的字段（`serviceName`）。它 **不**：
  改 TOTP secret、re-parse otpauth、改 algorithm/digits/period、重建 credential
  stableId。因为 issuer 就是 hierarchy parent 的 serviceName，rename 后 UI 展示的
  issuer 自然跟随新 Provider 名（这是 hierarchy 一致性更新，不是覆写原始
  credential metadata）。

## 5. Account create / rename

- **create**：`createAccount(serviceName, accountName)` 目标 Provider 必须已存在
  （`countByServiceName > 0`），否则 `NotFoundException`。provider 内 exact
  duplicate account name → `ConflictException`。不引入新身份字段。
- **rename**：`renameAccount(accountId, newName)` 只更新 `accountName`
  （+`updatedAt`），preserve Account stableId 与全部 children；provider 内
  duplicate → `ConflictException`（用 Merge Account action 而非静默 merge）。

## 6. Account move

`moveAccount(accountId, destinationProvider)`：更新该 Account 的 `serviceName`
parent 关系。Account / TOTP / Recovery Set/Code stableIds 与全部 USED/usedAt
保留，无 secret mutation。目标 provider 已存在才允许；同 provider 安全 no-op。
单事务。UI destination provider picker 不显示当前 provider（或点击当前直接
no-op）。

## 7. Account merge — exact semantics

`mergeAccounts(sourceId, destinationId)`（**Destination 存活**）：

- Destination Account stableId 保留；Source Account merge 成功后删除（单事务）。
- source TOTP 逐个迁移：非 duplicate → move（preserve source TOTP stableId）；
  duplicate（同一官方 fingerprint）→ 不创建第二条，destination existing 存活、
  source duplicate 消解。
- Recovery Code Sets **默认全部 move** 到 destination，preserve set stableId +
  child stableIds + USED/usedAt。Recovery Set title 不是 global identity，
  同 title / 同 code count 不自动 dedupe。若出现真 same-stableId corrupt case，
  安全拒绝事务（不猜谁赢）。
- Source Account metadata 丢弃，只保留 Destination Account metadata。本轮不做
  account aliases/history。
- 失败整体 rollback；重复 merge（source 已删）→ source NotFound，安全失败。

## 8. TOTP duplicate handling

- Account merge 只复用现有官方 `Canonicalization.totpFingerprint`
  （secret + algorithm + digits + period）。不额外 normalize secret case /
  issuer / account label / period / digits / algorithm 之外的字段。
- duplicate 时 destination existing item 存活（用户明确选 Source→Destination，
  Destination 是 authority）。这是**本地 Account merge operation**，不修改
  Package `MergePlanner` 的 source/destination policy（两套语义不同，不混淆）。

## 9. Recovery lineage preservation

Move / Merge 中 Recovery Code Set 与每个 Recovery Code 的
`stableId / value / status(USED/UNUSED) / usedAt / sortOrder` 全部保持。不
delete/recreate、不 normalize codes、不 reset USED、不清 usedAt、不只迁
remaining codes。Recovery Set 视为 atomic child collection。

## 10. delete / cascade semantics

- Provider delete / Account delete 都在**单 transaction** 内级联删除 children。
- 当前 data model 用 Room FK CASCADE（TOTP/Set→Account）+ 显式 Recovery Code
  delete。为清晰与稳健，delete 路径对 TOTP / Recovery Set / Recovery Code 显式
  删除。失败 rollback，无部分提交、无 orphan。

## 11. transaction / rollback behavior

所有 hierarchy mutation 全部走现有 `VaultRepository.mutate { }`（共享单 mutex +
单 Room transaction）。不会出现 ProviderRepository 一个 mutex /
AuthenticatorRepository 另一个 / RecoveryCodeRepository 再一个的跨表不原子问题。
move / merge / provider delete 尤其单事务。已补 rollback failure seam 测试
（merge/delete 在 session lock 边界、missing entity 时安全失败）。

## 12. P5 selection compatibility

- 本轮不修改 `VaultSnapshotSelector` / `SelectedItemSet` / selectionDigest。
- Provider selection 仍是 UI convenience → 展开为 account stableIds；mutation 后
  account stableIds 不变（rename/move 保留），所以 previous stableId selection
  仍解析到同一 Account。
- rename 后 same stableId 仍可被正常 selection；move 后 stableId 不变、parent
  metadata 变化；merge 后 source stableId 变 stale/not found、destination
  stableId 仍有效。package/selection integration 测试覆盖。

## 13. Native package compatibility

- 不修改 `PortablePackageCodec` / package envelope / crypto / `MergePlanner`。
- hierarchy mutation 后 Full Vault snapshot encode/decode 的 logical hierarchy
  正确（无 orphan）。integration 测试覆盖 Provider rename / Account rename /
  move / merge / recovery-state 的 round-trip。

## 14. Legacy-imported data compatibility

- 不修改 `LegacyRakVaultImporter` / `LegacyVaultSnapshotMapper` / durable-id
  strategy / Legacy UI。
- Legacy-imported Provider/Account 与 Native-created 数据一样可 rename / move /
  merge / delete（都是 shared logical data，`serviceName` + `stableId`）。不在
  management UI 区分 legacy/native。

## 15. UI/UX

- 沿用当前 Authenticator 层级（Authenticator → Provider → Account → Account
  detail）。在首页新增 Provider 分组视图 + Provider 菜单（Rename / Add Account /
  Delete）+ Account 菜单（Rename / Move / Merge / Delete）+ TopAppBar Add Provider。
- 不重写整个 Authenticator UI，不把所有操作塞进一个巨大 modal。按现有
  Compose/Material3 pattern（`DropdownMenu` / `AlertDialog` / `ExposedDropdownMenuBox`）。
- Merge UX 明确 Source（当前 Account）/ Destination（用户选另一个 Account），
  dialog 显示安全 summary（Provider/Account name + counts），确认文案说明
  source 删除 + non-duplicate 迁移 + duplicate 保留 destination。不显示 secret /
  code value。无可选 destination 时 disabled / 提示先创建另一个 Account。

## 16. security/privacy

- management UI 不显示 TOTP secret / Recovery code value；confirmation /
  snackbar / log 只展示安全 metadata 与 counts。
- secret 不进 navigation route / 不打印日志 / 不放 contentDescription。
- Provider/Account rename/move/merge 不需要读取 plaintext secret 到 UI；
  repository 只移动关系/实体。

## 17. changed files

- `app/.../repository/ProviderAccountRepository.kt`（新增）
- `app/.../repository/VaultAccess.kt`（注册 providerAccountRepository）
- `app/.../database/AuthAccountDao.kt`（新增 updateServiceName / ForAll /
  updateAccountName / countByServiceName / listByServiceName）
- `app/.../database/TotpCredentialDao.kt`（新增 updateAccountId）
- `app/.../database/RecoveryCodeSetDao.kt`（新增 updateAccountId）
- `app/.../ui/authenticator/AuthenticatorViewModel.kt`（providers 分组 + management 方法）
- `app/.../ui/authenticator/AuthenticatorRoute.kt`（management dialog 接线）
- `app/.../ui/screens/authenticator/AuthenticatorScreen.kt`（Provider 分组 + 菜单）
- `app/.../ui/screens/authenticator/ProviderAccountManagementDialogs.kt`（新增）
- `app/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`（新增文案）

## 18. tests

- `ProviderAccountRepositoryTest`（25）：Provider create/exact-duplicate/rename/
  delete（含 descendant stableId 保留）、Account create/rename/move/merge/delete、
  TOTP duplicate fingerprint、Recovery lineage 保留、cross-provider merge、
  session-lock 边界。
- `ProviderAccountPackageIntegrationTest`（5）：mutation 后 Full Vault snapshot
  round-trip 正确。
- `ProviderAccountManagementDialogTest`（6）：management dialogs 安全 metadata、
  cancel 不改动、无 secret 泄漏。

## 19. release build

`./gradlew :core:test` / `:app:testDebugUnitTest` / `:app:lintDebug` /
`:app:assembleDebug` / `:app:assembleDebugAndroidTest` / `:app:assembleRelease`
全部通过（release 保持 unsigned）。

## 20. FTL expectation

本 PR 未主动运行 Firebase Test Lab。instrumented 路径（SQLCipher / Room schema）
本 feature 零 schema 改动，仅新增 DAO 方法（instrumented 编译
`:app:assembleDebugAndroidTest` 通过）。

## 21. parallel conflict check with P6

P6（Developer Vault Completion，Issue #20）会修改 Developer screens/repository /
SensitiveAction / SAF keystore flow。本 PR **未修改**：Developer CRUD、Signing Key、
Env Var、SensitiveAction enum；仅新增 management DAO/repository/UI，与 P6 重叠
最小（都不改 Room schema、都不改 package format）。共享的 `VaultAccess` 只追加
一个 `providerAccountRepository()` 方法，不触碰 P6 的 Developer 缓存字段。

## 22. remaining P7/P8 work

- **P8 Delete Undo**：本 PR 的 Provider/Account delete 是 destructive
  confirmation（非 Undo）。P8 再做全类型 Undo。
- 本轮明确不做：Provider merge、Search、Pin/Unpin、P8 Undo、Developer P6、
  package import conflict resolution、cloud、backup automation、new package format、
  Legacy changes、production signing。
