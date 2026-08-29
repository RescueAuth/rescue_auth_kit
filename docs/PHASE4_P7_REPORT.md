# Phase 4 P7 Report — Global Search + Account Pin/Unpin

> 状态：**IMPLEMENTED / PR OPEN**（Issue #20）。
> 范围：**Global Search（safe metadata only）+ Account Pin/Unpin（Account only）**。
> **Room schema / package format 零改动**（Pin 复用现有 `favorite` 兼容字段）。
> 不做 P8；不做 Favorites/Tags/Folder/Rating；不做通用搜索平台。
> 与并行 **Phase 6 L2（About/Update Check）** 互不等待。

> **文档性质**：历史实现报告。正文中的 `PR OPEN` 等状态只代表报告生成时的
> 状态；当前状态以 `../ROADMAP.md`、`../AGENTS.md` 和 [`README.md`](../README.md) 为准。

## 1. existing Search/Pin foundation audit

编码前审计最新 main 结论：

**Pin foundation 已存在（可直接复用）：**

- `AuthAccountEntity.favorite`（Room storage）✓
- `AuthAccountDao.setFavorite(id, favorite, updatedAt)` ✓
- `VaultRepository.setFavorite(accountId, favorite)`（串行 mutation + session
  check）✓
- `AccountUi.isPinned`（UI model 字段）✓
- `PinIndicator` composable ✓
- `ProviderAccountListItem` 已渲染 pinned badge ✓
- logical `VaultAccount.favorite` ↔ `MergePlanner` / `MergePlanApplicator` /
  `PortablePackageCodec` encode/decode ✓（round-trip 天然支持 pin）

**Search foundation：** 无既有搜索基础设施，本轮全新实现（按 P7 §8 走
in-memory safe projection）。

## 2. final P7 Pin scope = Account only

- 实现：**Account Pin / Unpin / Pinned account ordering**。
- 明确不做：Provider Pin / TOTP Pin / Recovery Set Pin / Developer Pin /
  Favorites page / Favorites folder / favorite category / tag / rating /
  custom pinned collection。
- 理由：Account 是 daily-use 最自然单元；persistence 已有 `account.favorite`；
  避免为 TOTP/Developer 扩 schema；Pin 只是排序能力，不演化成 Favorites
  system。
- 现有通用 `PinIndicator` **继续复用**（修复其 a11y contentDescription 区分
  Pin/Unpin 状态）。

## 3. favorite-storage → pinned-product mapping

- **storage compatibility field = `favorite`**（内部持久化兼容字段，保留）。
- **product/UI semantic = `pinned`**（`AccountUi.isPinned` /
  `VaultRepository.setPinned(...)` / `AuthenticatorRepository.setPinned(...)` /
  UI 菜单 Pin / Unpin）。
- 新增 product-facing repository alias：`setPinned(accountId, pinned)` 内部
  继续调用 existing `setFavorite` storage field。**不做** Room schema
  migration、package schema migration、database column rename、logical model
  rewrite（不为了字段名更漂亮做迁移）。
- technical debt（本报告明确）：`favorite` 仅作为 internal persisted
  compatibility field 保留；v2 product 只暴露 Pin/Unpin。

## 4. Pin ordering semantics

- Pin 是 **ordering attribute**，不是第二份数据。
- Account 仍属于原 Provider（不改 Provider relationship、不改 stableId、不
  复制、不 shadow row、不重新创建 children）。
- UI 默认：每个 Provider 自然分组内 **pinned Accounts first → unpinned**；
  同一 pinned tier 内保留现有稳定排序（accountName）。不重写 Provider
  hierarchy，不创建重复显示的 Favorites collection。

## 5. Global Search architecture

- 个人本地数据集规模很小 → **in-memory safe projection**：
  `Room Flow → domain/repository → 显式 safe SearchDocument → in-memory matching`。
- **不做**：DataStore plaintext index、外部 search DB、files/cache plaintext
  index、Android AppSearch、含 secret 的 FTS table、background indexing
  service。不做 `payloadJson LIKE` 查询。
- 组成：
  - `SearchMatcher`（core，纯 JVM）：case-insensitive contains + multi-token
    AND。
  - `SearchDocument` / `SearchResult`（app，safe 类型化模型）。
  - `SearchIndex`（app，构建 safe projection + deterministic ranking）。
  - `SearchViewModel` / `SearchRoute` / `SearchScreen`（UI + lifecycle）。
  - `DeveloperRepository.observeSearchMetadata()` + `DeveloperMappers.toSearchMetadata()`
    （Developer safe 投影）。

## 6. exact searchable metadata by item type

| 类型 | searchableTokens（safe） |
| --- | --- |
| Provider | serviceName |
| Account | accountName, serviceName |
| TOTP | provider(serviceName), accountName（display context） |
| Recovery Set | set title, provider, accountName |
| Developer · Android Signing Key | title, projectName, packageName, keystoreFileName, keyAlias |
| Developer · API Credential | title, serviceName, accountName |
| Developer · SSH Key | title, keyName |
| Developer · Env Var Set | title, projectName, variable names |
| Developer · Generic Secret | title, field labels |

## 7. exact prohibited secret fields（绝不进入 search）

TOTP `secretBase32`、当前 TOTP code、Recovery plaintext、`apiKey`、
`apiSecret`、SSH `privateKey`、SSH `passphrase`、signing `storePassword` /
`keyPassword`、keystore bytes/base64、`key.properties` snippet、Env `value`、
Generic `value`。SSH `publicKey` 本轮也不索引全文。Developer free-form
`notes` 默认不搜索。**禁止 `SELECT ... WHERE payloadJson LIKE ...`。**

## 8. search matcher / ranking semantics

- **Matcher**：`query.trim()`；empty → empty result / normal home state；
  Unicode-safe case-insensitive `contains`（`Locale.ROOT` fold，杜绝
  Turkish-I 异常）；多 token whitespace split + **AND**；deterministic；无
  fuzzy/Levenshtein/semantic/pinyin/regex/web search。
- **Ranking**：exact title/name → prefix → substring；同 tier pinned Account
  优先 + stable title 排序；按固定 type grouping（Provider→Account→TOTP→
  Recovery→Developer）。不记录 usage/history。

## 9. search navigation behavior

- Provider → 回 Authenticator home（其自然 context）。
- Account → Account detail（`authenticator/account/{id}`）。
- TOTP → owning Account detail。
- Recovery Set → owning Account detail。
- Developer → Developer detail。
- 导航 target 用真实 stable/current IDs（非 title/string key）；目标已删除 →
  safe no-op / user-facing unavailable，不 crash；search query 不塞进持久
  navigation state。

## 10. query / result lifecycle

- query **in-memory only**：不写 Room / DataStore / SavedStateHandle /
  rememberSaveable / Bundle / logs / analytics。
- 离开 Search 页 → 清空；会话锁定 → 清空；进程重建 → 空；新会话 → 空。
- 不提供 search history / recent searches。

## 11. session-lock behavior

- Vault LOCKED 后：清 query、清 result、cancel/release DB collectors、
  不保留 SearchDocument cache、不保留 Developer safe projection。
- unlock 后：从当前 repository Flow 重新建立 projection。不为性能跨 session
  缓存。

## 12. accessibility / i18n

- Search result semantics 不含 secret（模型无 secret 字段，类型保证）。
- Pin：contentDescription 区分 **Pin account** / **Unpin account**（en +
  zh-CN：置顶账户 / 取消置顶账户）。
- 搜索输入有明确 label；clear icon 有 contentDescription；results
  类型/标题/上下文可被 screen reader 理解。
- 新增 en + zh-CN 文案：Search / Search vault / No results / Pin / Unpin /
  Pinned / Accounts / Providers / Authenticator / Developer / Recovery Codes。

## 13. package compatibility

- Account pin（Full Vault 与 Selected Items export/import）→ 通过既有 logical
  `favorite` 字段 round-trip（测试 48-52）。
- `PortablePackageCodec` / package envelope / crypto / SnapshotScope /
  selectionDigest / Legacy format **零改动**。
- existing-target import 对不同 favorite/pinned 状态继续采用现有
  destination-preference / existing-account semantics；不发明 pin merge
  conflict（P7 不 redesign MergePlanner）。

## 14. Room / package schema changes

**expected: none，actual: none。**

- Room schema 不变（`favorite` 复用；`setPinned` 是 DAO `setFavorite` 的
  repository alias）。
- package logical schema / format version 不变；无 ADR 新增。

## 15. changed files

- core：`search/SearchMatcher.kt`（新）、`search/SearchMatcherTest.kt`（新）。
- app/main：
  - `repository/DeveloperRepository.kt`（observeSearchMetadata）
  - `repository/DeveloperMappers.kt`（toSearchMetadata）
  - `repository/DeveloperSearchMetadata.kt`（新）
  - `repository/VaultRepository.kt`（setPinned alias）
  - `repository/AuthenticatorRepository.kt`（setPinned）
  - `search/SearchDocument.kt`（新）
  - `search/SearchResult.kt`（新）
  - `search/SearchIndex.kt`（新）
  - `ui/search/SearchViewModel.kt`（新）
  - `ui/search/SearchRoute.kt`（新）
  - `ui/screens/search/SearchScreen.kt`（新）
  - `ui/navigation/RescueAuthRoutes.kt`（SEARCH route）
  - `ui/RescueAuthApp.kt`（SEARCH 接线 + result 导航）
  - `ui/authenticator/AuthenticatorRoute.kt`（onOpenSearch / onTogglePin）
  - `ui/authenticator/AuthenticatorViewModel.kt`（togglePin + pinned ordering）
  - `ui/screens/authenticator/AuthenticatorScreen.kt`（search 图标 + Pin/Unpin 菜单）
  - `ui/components/PinIndicator.kt`（a11y Pin/Unpin contentDescription）
  - `res/values/strings.xml`、`res/values-zh-rCN/strings.xml`（P7 文案）
- app/test：
  - `search/SearchIndexTest.kt`（新，23 用例）
  - `ui/search/SearchViewModelLifecycleTest.kt`（新，2 用例）
  - `repository/P7PinTest.kt`（新，7 用例）
  - `exportimport/P7PackageCompatibilityTest.kt`（新，5 用例）
- docs：`ROADMAP.md`、`AGENTS.md`、`CHANGELOG.md`、`PHASE4_P7_REPORT.md`（本文件）。

## 16. test count / results

新增 P7 测试全部 PASS：
- core `SearchMatcherTest`：8
- app `SearchIndexTest`：23
- app `SearchViewModelLifecycleTest`：2
- app `P7PinTest`：7
- app `P7PackageCompatibilityTest`：5

合计 **45 个新增测试，0 failure / 0 error**。全套
`:core:test` + `:app:testDebugUnitTest` 通过。

## 17. secret-exclusion tests（P7 §21）

`SearchIndexTest` 显式构造 secret fixture 并断言不可搜索：TOTP secret、
generated code、Recovery plaintext、apiKey、apiSecret、SSH privateKey、
passphrase、signing storePassword/keyPassword、keystore base64、Env value、
Generic value、Developer notes 疑似 secret，以及 `SearchResult/toString()`
不含 fixture secrets。secret 通过**类型设计**保证不进入 model，而非仅过滤
字符串。

## 18. release build status

- `./gradlew :core:test` PASS
- `./gradlew :app:testDebugUnitTest` PASS
- `./gradlew :app:lintDebug` PASS
- `./gradlew :app:assembleDebug` PASS
- `./gradlew :app:assembleDebugAndroidTest` PASS
- `./gradlew :app:assembleRelease` PASS（继续允许 unsigned，未创建 signing key）

未运行 FTL（按要求不主动运行）。

## 19. FTL expectation

本地 unit + assemble 全绿。未运行 Firebase Test Lab / instrumented 全量。
已知 `UncaughtExceptionsBeforeTest` / `SQLiteConnectionPool closed` 这类
Robolectric 偶发如需出现，报告并重跑确认（本轮未出现）。

## 20. parallel conflict check（vs Phase 6 L2 About/Update）

- P7 不触碰 Settings / About route/screen / update client-verifier /
  Manifest INTERNET permission / UPDATE_PROTOCOL docs。
- 双方可能同时修改 `strings.xml` / `values-zh-rCN/strings.xml` /
  `ROADMAP.md` / `AGENTS.md` / `CHANGELOG.md` / app navigation root；P7 只做
  最小接线（新增 SEARCH route + 现有 Authenticator 入口），不覆盖 L2 的
  Settings/About 改动。互不等待。

## 21. known deferred issues

- P8 Delete Undo completion
- Recovery Set Move
- empty Account package regression（本轮发现仅记录，不扩 scope）
- About / Update Check（并行 L2）
- clipboard auto-clear
- Provider model redesign
- TOTP Pin / Developer Pin / Favorites / tag system / sorting customization
- search history / cloud / analytics / production signing

## 22. PR URL

见 PR body 顶部。

## 23. branch

`auto/phase4-p7-search-pin-6c48`

## 24. commits

见 PR commit 列表。
