# ROADMAP.md — Rescue Auth Kit v2（正式产品路线图）

> 状态：**正式**（2026-08-07，Issue #17 产品决策落定）。
> 本文是 v2 唯一的路线图 source of truth。Phase 级进度、阶段状态
> 由 `AGENTS.md` 同步维护；**不要**在 PR body / issue / 其他文档中
> 再维护一份平行的阶段表。
>
> 变更规则：修改本文件时同步更新 `PRODUCT.md`（范围）与 `AGENTS.md`
> （阶段勾选）；三处描述不一致视为文档违约。

---

## 0. 产品定义（v2 = Android-only、local-first、加密个人安全库）

Rescue Auth Kit v2 是一个 **Android-only、local-first、encrypted personal
security vault**。它不是单纯的 TOTP Authenticator。完整产品由三个
**正式产品能力**组成：

```
A. Authenticator（服务/账户/TOTP/恢复码）
B. Developer Vault（五类 Developer Entry，完整保留 v1.2.0）
C. Portable Vault Package（backup / migration / selective transfer / vault merge）
```

> ⚠️ **废止的旧假设**：本仓库曾经存在“Developer Vault 在 v2 被
> intentionally removed”的说法（旧 PRODUCT.md / THREAT_MODEL §安全假设）。
> 该假设**已废弃**。Developer Vault 五类条目全部 KEEP（见 §2）。

产品原则：

- **本地优先**：所有数据保存在本机 SQLCipher Vault；无账号/登录后端、
  无云同步。
- **手动导出/导入**：不做 automatic backup；每次导出由用户为这一份
  package 设置独立 Export PIN。
- **Import = merge-first**：导入永远是可合并数据包，不是 replace。
- **跨平台迁移友好**：Portable Package format 保持 platform-neutral，
  不依赖 Android API / Room 表示；但当前 Roadmap **不为其他平台安排
  客户端开发**。

---

## 1. 目标平台与明确不做

### 目标平台

- **Android only**（正式应用平台）。

### 明确不开发（客户端）

- Windows desktop app
- macOS client
- Linux client
- iOS client
- Web client

> Portable Package format 必须保持 platform-neutral，未来若有人写
> CLI / desktop companion 可以直接实现该格式；**但当前 Roadmap 不为此
> 安排开发**。

### 明确不做（能力范围）

- cloud sync / 账号登录后端
- automatic backup（onEveryChange / daily / weekly / monthly / WorkManager /
  background / cloud backup / 自动 checkpoint 文件）
- browser extension / autofill password manager
- SSH agent / SSH generation tool / API execution / DevOps automation
- arbitrary file vault / social sharing
- otpauth-migration export（仅 IMPORT）

> 避免 scope creep。完整清单见 `PRODUCT.md §v1 明确不做` 与下方 §18。

---

## 2. 本地安全模型（保持不变）

Phase 2 已完成并保持：

```
Biometric / Device Credential
    ↓
Android Keystore（不可导出 AES-256-GCM 包装密钥）
    ↓
unwrap VaultKey（256-bit 随机，禁止从用户密码派生）
    ↓
SQLCipher Vault
```

- **不恢复全局 Master Password**。旧 Master Password 只属于 Legacy Import
  兼容层（解密 `.rakvault` 时一次性使用）。
- Keystore 失效 → `KEY_INVALIDATED` → 引导恢复流程，**不得删除数据库**。

### 新增正式能力：Sensitive Action Re-authentication（§5.6）

即使 Vault 当前已 `UNLOCKED`，对高敏感操作仍要求一次 **fresh
Biometric / Device Credential** 认证。这是独立正式能力，**不隐含在普通
unlock 中**。覆盖操作与 Roadmap 安排见 §5.6。

---

## 3. 产品范围决策总表（KEEP / REDESIGN / NEW / REMOVE / DEFER）

| 能力 | 决策 | 说明 |
| --- | --- | --- |
| Authenticator：Provider → Account → Credential 层级 | **KEEP** | 创建/rename/move/merge/delete 全保留；UI 可重设计，能力不丢 |
| Authenticator：TOTP（QR/otpauth paste/manual） | **KEEP** | issuer/accountName/secret/SHA1-256-512/digits/period/countdown/copy/delete |
| TOTP 编辑语义 | **KEEP（immutable）** | 修改即删除重建；不引入复杂编辑语义（见 §4.2） |
| Authenticator：otpauth-migration import | **NEW** | IMPORT ONLY，外部导入适配器，不进入核心 domain（见 §4.3） |
| Recovery Codes | **KEEP + 增强** | batch add / expand-collapse / copy all / edit / delete / move；v2 已有 used/unused |
| Developer Vault 五类条目 | **KEEP** | 完整保留 v1.2.0 五类 Developer Entry（见 §6） |
| Developer Vault 边界 | **KEEP** | secure storage / view / copy / export，不是 DevOps 平台 |
| Global Search | **NEW** | 只搜非敏感元数据，不索引 secret（见 §7.1） |
| Pin / Pinned Items | **NEW** | 简单 Pin/Unpin 置顶；不做 Favorites 体系（见 §7.2） |
| Portable Vault Package | **REDESIGN（已完成 reset）** | per-export PIN、versioned、encrypted、logical、portable、mergeable；manual only |
| Selective Export / Import | **NEW** | Package domain 不得阻碍 partial snapshot；Selective Import 走同一 Merge Engine |
| Merge / Dedupe | **REDESIGN（Phase 3A foundation 覆盖完整 Vault）** | 基础 insert/duplicate/conflict 语义在 portable logical schema 内覆盖 TOTP / Recovery Codes / Developer Entry；每类更复杂的 per-type semantic dedupe 后续增强 |
| Legacy Import（`.rakvault`） | **KEEP（compat only）** | 与 Native Package Import 强制隔离（见 §9） |
| Delete Undo | **NEW** | 普通删除 SnackBar Undo；高破坏性操作保留 confirmation（见 §11） |
| Clipboard auto-clear | **DEFER** | Later security polish，不阻塞 daily use（见 §12） |
| Localization（en + zh-CN） | **KEEP（已完成）** | 完整双语已恢复（见 §13） |
| Update Check | **KEEP（简化）** | version/about + 检查发布 + 外部打开；无自更新安装（见 §14） |
| Automatic backup / WorkManager backup / cloud | **REMOVE** | 已随 Phase 3 reset 删除，且不再恢复 |
| 全局 BackupKey / 恢复套件 | **REMOVE** | 由 per-export PIN 取代；文档已同步 |

---

## 4. Authenticator 功能范围

### 4.1 Provider / Account / Credential

- **Provider → Account → Credential** 三级层级。
- **Provider 身份 = `serviceName` 字符串（Provider name）**：Provider 是
  Account 行的 `serviceName` 分组字段，**无独立 entity、无独立
  stableId / uuid 标识**（见 §8 portable logical schema / §5.3 PA）。
  Provider rename 即更新全部 descendant Account 的 `serviceName`，
  Account / TOTP / Recovery Set/Code 的 stableId 全部保留。
- 能力（全部保留）：创建 / rename / move / merge / delete。
- 允许重新设计 UI，但功能能力不得无故丢失。
- Provider 级联删除属高破坏性操作：保留确认；可评估 Undo 的边界（§11）。

### 4.2 TOTP

必须支持：

- QR scan（Android camera）
- paste `otpauth://` URI
- manual TOTP entry
- issuer / account name
- Base32 secret
- SHA1 / SHA256 / SHA512
- digits（6..10，冻结 v1 Authenticator 契约；默认 6）
- period（1..120，默认 30）
- code display + countdown
- copy
- delete

**TOTP 编辑规则（明确）**：继续采用 **immutable，修改即删除重建**。

- 理由：与 v1 一致；避免引入“编辑导致 secret 变动”的复杂语义与撤销链；
  TOTP 凭据以 semantic fingerprint（secret+params）为身份，编辑参数等于
  换身份，删除重建最清晰。
- 实现要求：删除重建走**同一 Delete Undo 机制**（§11），用户误删可在
  Undo 窗口内恢复，再手动重建。
- 不引入 in-place edit / partial field edit。

### 4.3 otpauth-migration import（NEW）

- 支持 `otpauth-migration://` URI（Google Authenticator 批量导入）。
- **IMPORT ONLY**：Rescue Auth Kit 不生成 / 不 export otpauth-migration URI。
- 定位：**External Import Adapter**。
  - 解析 → 转换为**内部 logical credential** → 进入正常 validation /
    dedupe / merge 流程。
  - 该格式**不得进入核心 Vault domain**：不落库、不进入 package schema、
    不参与 fingerprint 设计。
- 迁移字段映射（Google 命名 → 内部）：`issuer` → serviceName、`email` →
  accountName、`secret` / `algorithm` / `digits` / `period` → TOTP 参数，
  其余按正常 validation。
- wire 语义（merge 前 interop CR 已冻结，最后收敛后收紧）：
  `algorithm`/`digits`/`type` 是 **protobuf enum**（type=2 TOTP / digits=1 SIX、
  2 EIGHT / algorithm=4 MD5）；`data` 为 percent-encoded **standard Base64**
  （仅标准 alphabet + `=` padding，**不接受 Base64URL / no-padding**），batch
  metadata 只从 decoded payload 读取，**不依赖且拒绝 `&batch_*` query 参数**。
- 与 Selective Import 一致：批量导入后走同一 Merge Engine 预览。

---

## 5. Phase Roadmap（Phase 3 之后全部重新规划）

> 当前结论（同步于 2026-08-27）：Phase 0–5 CLOSED；Phase 6 的 L1/L2 CLOSED、L3 DEFER；
> DAILY-USE READY 与 V2.0 FEATURE COMPLETE 均已达到，但尚未正式发布。

### 5.0 已关闭阶段（保持不动）

- **Phase 0 CLOSED** — 冻结 v1.2.0 + legacy fixtures + 映射文档
- **Phase 1 CLOSED** — Kotlin/Android 工程 + Argon2id/XChaCha20 解密 spike
- **Phase 2 CLOSED** — Room+SQLCipher / VaultKey+Keystore / 安全会话 /
  自动锁 / 后台遮罩 / 串行 repository（数据库 instrumented 6/6 PASS）。
  （不再全局 `FLAG_SECURE`，允许截图 —— Issue #57。）

### 5.1 工作分类（Roadmap 明确区分）

| 分类 | 内容 | 特征 |
| --- | --- | --- |
| **Foundation work** | package、merge、shared domain、TOTP core、otpauth parser、secure codec、Developer entry domain | 纯逻辑、可 JVM 全量测试、为多个 vertical slice 复用 |
| **Daily-use vertical slices** | TOTP usable loop、Recovery Codes、Developer Vault、Search / Pin、Export/Import loop、Sensitive re-auth | 每个 slice 小步完成：slice → PR → merge → 下一个 |
| **Migration** | legacy import 完整流程（UI 收口）、Developer 数据处理 | 独立于 Native Package Import |
| **Product polish** | i18n、about/update、clipboard polish、动画 | 不阻塞 daily-use |

不再使用“Phase 5 — main UI”这类模糊阶段。以下 Phase/Slice 每个都可
独立 review、独立 merge、有真实用户价值。

### 5.2 Phase 3（已关闭）— Package + Merge Foundation

> 3A–3D 均已合并并投入当前实现。历史分支、PR review checklist 与当时的
> “无 UI”描述仅用于审计，具体实现证据见 `docs/PHASE3_REPORT.md`。

| Slice | Goal | User-visible result | Deps | Scope |
| --- | --- | --- | --- | --- |
| **3A — Package + Merge Foundation** | ✅ 已实现并合并（PR #18）逻辑 package 模型（VaultSnapshot / VaultPackagePayload）+ stableId + semantic fingerprint + canonicalization + 纯 merge planner + schema v1→v2 + 自动备份抽象清理 | 纯 JVM 契约与测试基线 | Phase 2 | L |
| **3B — Encrypted Package Codec** | ✅ 已实现并合并（见 `docs/PHASE3_REPORT.md` §9 / ADR-0007）per-export PIN → Argon2id → KEK → wrap PackageKey → AEAD 加密 envelope；wrong PIN/corrupted 安全失败；header-is-untrusted / KDF accepted range / 大小上限；golden fixture | 纯 JVM 可验证的加密导出/导入字节契约 | 3A | M |
| **3C — Transactional Import / Merge** | ✅ 已实现并合并（见 `docs/PHASE3_REPORT.md` §11 / ADR-0008）MergePlan → 单 Room 事务 apply + rollback + 幂等；Developer Vault 五类落库（schema v2→v3）；parent/child identity mapping；CONFLICT / Recovery divergence 保守阻止 | 事务化导入即合并 | 3A + 3B | M |
| **3D — Android Export / Import + Package Preview** | ✅ 已实现（已合并；见 `docs/PHASE3_REPORT.md` §12）SAF 选择/写入（CreateDocument/OpenDocument）、per-export PIN 对话框、import preview（Authenticator/Developer 计数）、Import all（merge-first transactional apply） | 用户可手动导出/导入 Package；预览 + merge 报告 | 3B + 3C | L |

**3D 验收结果**：import preview 已区分 Authenticator（N accounts /
N TOTP / N recovery sets）与 Developer（N signing keys / N API / N SSH /
N env / N generic），并支持 **Import all**。Selective Import 已由 Phase 4
P5 完成，继续复用同一 Merge Engine。

### 5.3 Phase 4（已关闭）— Daily-use vertical slices（核心自用路径）

> 目标：尽早让 TOTP 真正可用；每个 slice 独立 PR。

| Slice | Goal | User-visible result | Deps | Scope |
| --- | --- | --- | --- | --- |
| **P1 — TOTP usable loop（垂直切片）** | ✅ 已实现（见 `docs/PHASE4_P1_REPORT.md`）Compose 首页：Vault 列表 + TOTP 显示/倒计时/复制；添加（QR + otpauth paste + manual entry）；删除 + Undo；持久化 | 用户可完成“解锁 → 看码 → 复制 → 添加 → 删除”完整日常循环 | Phase 2 会话层 + TOTP core | L |
| **P2 — otpauth-migration import** | ✅ 已实现（Issue #20，见 `docs/PHASE4_P2_REPORT.md`）QR 扫描（CameraX + ML Kit）+ `otpauth-migration://` 批量导入（独立纯 Kotlin adapter + 多 QR batch session + repository batch import） | 可从 Google Authenticator 扫码批量迁入 | P1 | S |
| **P3 — Recovery Codes slice** | ✅ 已实现（Issue #1，见 `docs/PHASE4_P3_REPORT.md`）Account detail 恢复码页：batch add（多行粘贴 + preview）/ expand-collapse / reveal-hide / 单条 copy / Copy All + Copy Remaining / mark used-unused（remaining count 实时更新）/ edit（最小 diff 保留 stableId + USED state）/ delete + Undo（恢复 exact stableIds/states） | 恢复码可完整管理（一等 Vault credential），P3 数据自然进入 Full Vault Export/Import round-trip | P1 | M |
| **P4 — Developer Vault slice（第一批）** | ✅ 已实现（Issue #20，见 `docs/PHASE4_P4_REPORT.md`）Sensitive Action Fresh Re-auth Foundation + Developer Vault 第一批（API Credential / SSH Key / Generic Secret 全 CRUD、reveal-hide、copy、re-auth 接入 Full Vault Export） | 三种最常见的 Developer Entry（API Credential / SSH Key / Generic Secret）可日常使用 | 3D + re-auth（§5.6） | M |
| **P5 — Selective Export / Import** | ✅ 已实现（Issue #20，见 `docs/PHASE4_P5_REPORT.md`）：共享纯 Kotlin 选择引擎（`VaultSnapshotSelector` / `SelectedItemSet`，stableId 语义 + hierarchy/dependency closure）；Export 四 scope（Entire Vault / Authenticator / Developer / Selected Items），全部走同一 fresh re-auth（`EXPORT_PACKAGE`，scope+selection digest 绑定）+ 同一 per-export PIN 策略；Selective Import 是 decoded-snapshot 内存过滤（不产生第二套 package/merge），Everything / Authenticator / Developer / Selected Items，仅对选中的 filtered snapshot 运行 MergePlanner；unselected conflict 不阻塞、selected conflict/divergence 按现有规则 BLOCK；Developer 五类完整保留（含 Android Signing Key / Env Var Set） | 用户可按需迁移部分数据 | 3D + P3/P4 | M |
| **PA — Provider & Account Full Management** | ✅ 已实现（Issue #32，见 `docs/PHASE4_PA_REPORT.md`）：正式 Provider（create/rename/delete）与 Account（create/rename/move/merge/delete）management，全部走共享 `VaultRepository` 单 mutex + 单 Room transaction；Provider = `serviceName` 分组（无独立 entity / 无 package stableId）；empty Provider 非当前正式能力（Create Provider 同时创建首个 Account）；Account merge 复用现有官方 TOTP semantic fingerprint，destination 存活、source TOTP 迁移/消解、Recovery Sets 全迁移保留 lineage；跨 Provider merge 支持；**Room schema / package format 零改动** | 完整管理 Provider/Account 层级（对应 §4.1 能力） | P3 + P5 | M |
| **P6 — Developer Vault slice（第二批）** | ✅ 已实现（Issue #20，见 `docs/PHASE4_P6_REPORT.md`）：Android Signing Key / Environment Variable Set 全 CRUD（create/list/detail/edit/delete）+ keystore SAF import（opaque exact-bytes，大小上限从共享 logical/package per-asset contract 推导）/ export（fresh re-auth + SAF CreateDocument）+ Copy key.properties（内存构造、fresh re-auth、不持久化）+ 每字段 reveal/copy 独立 fresh re-auth（SensitiveAction target 绑定 stableId+fieldKey）+ Env Var 动态行、case-sensitive 去重、value 不 normalize；Developer Vault 五类 Android UI 全部完成 | 五类 Developer Entry 全部可日常使用 | P4 | M |
| **P7 — Search + Pin** | ✅ 已实现（Issue #20，见 `docs/PHASE4_P7_REPORT.md`）：全局搜索（Provider/Account/TOTP display/Recovery Set title/Developer title + 非敏感 metadata；只索引 safe metadata，绝不索引 secret）+ Account Pin/Unpin 置顶（复用 `favorite` 兼容字段，无 schema/package 改动） | 快速定位常用项 | P1 + P6 | M |
| **P8 — Delete Undo 完善** | ✅ 已实现（Issue #20，见 `docs/PHASE4_P8_REPORT.md`）：普通删除（TOTP / recovery set / 普通 Developer Entry / account）统一 SnackBar Undo（普通 Developer Entry = API Credential / SSH Key / Env Var Set / Generic Secret；account 删除改为立即移除 + Undo）；高破坏性操作（provider 级联、含 keystore 的 signing key、account merge）保留确认且无 Undo；Recovery Code Set Move 正式能力（跨 Provider，identity/state 全保留，原子 move）；空 Account（无 TOTP / 无 Recovery Set）在 Full/Selected/Authenticator-only 导出导入中被完整保留；Undo snapshot 仅 in-memory、session lock 时清除 | 误删可恢复，破坏性操作仍受保护 | P1 起步，P3/P6 覆盖全类型 | S |

> **P8 说明**：Undo 通过 database/domain transaction + short-lived in-memory
> restore snapshot/token 实现；**不要**为了 Undo 恢复 automatic checkpoint
> backup。Undo snapshot 含真正 secret，只允许 in-memory，绝不进入
> SavedStateHandle/Bundle/DataStore/file/cache/clipboard/log；session lock
> 时清除所有 pending Undo，unlock 后不恢复 token。
> **P8 说明（Recovery Set Move）**：Set 是 atomic collection，move 只改变
> parent Account 关系，保留 set/code 全部 stableId 与 USED/UNUSED/usedAt/
> sortOrder；title 不是 identity，不做 dedupe；单 transaction，失败整体回滚。

### 5.4 Phase 5（已关闭）— Migration（legacy import 收口）

> **Phase 5A（2026-08-09，Issue #1）**：Legacy v1 Core Adapter 已实现
> （IMPLEMENTED，已 merge #29）——`LegacyVaultSnapshotMapper` 把解密后的
> `LegacyImportBundle` 映射为 shared `VaultSnapshot`，复用 `PackageValidator` /
> `MergePlanner` / `VaultRepository.applySnapshot`；**durable-id-first** 确定性
> stableId（基于 legacy durable UUID，不依赖 source fingerprint）+ source
> fingerprint（仅作 source identity）+ 五类 Developer 逐字段映射；Legacy
> 防御上限与 Native 16 MiB 解耦（输入 64 MiB）；只走纯 logical validation；
> 独立 Python-provenance fixture + **frozen v1 producer fixture** 锁定幂等与
> actual producer interop。
> 详见 `docs/PHASE5A_REPORT.md`。
>
> **Phase 5B（2026-08-09，Issue #1）**：Legacy v1 Android Import UI 已实现并合并
> （PR #31）——`LegacyImportService` / `LegacyImportViewModel` /
> `LegacyImportRoute` / `LegacyImportScreen`；SAF OpenDocument + 64 MiB bounded
> read（decrypt 前拒绝超限）；Master Password（无 `>=10` 硬编码 gate，与
> Native PIN 独立）；safe preview（无 secret）；MergePlanner / final re-plan /
> transactional apply 完全复用 shared engine；跨备份幂等 UI→DB 贯通；
> ImportRecord `sourceType="LEGACY_RAKVAULT"` + source fingerprint；session /
> plaintext lifecycle（lock/cancel/new-file 清明文）；`VaultRepository` 移除
> 对 `LegacyImportBundle` 的 Phase-1 spike 依赖（架构清债）。
> 详见 `docs/PHASE5B_REPORT.md`。M2（Developer 数据处理）**已并入 Phase 5B**
> （Legacy v1 Developer Vault 五类全部正常迁移并持久化，不降级、不默认跳过）。

| Slice | Goal | User-visible result | Deps | Scope |
| --- | --- | --- | --- | --- |
| **M1 — Legacy Import UI 完整流程** | ✅ 已实现（Phase 5B，见 `docs/PHASE5B_REPORT.md`）选 `.rakvault` → 一次旧密码 → Argon2id/XChaCha20 解密 → 预览 → 单事务写入 → 报告；与“Import Rescue Auth Package”在 UI 上明确区分 | 用户可从旧版 v1.2.0 一次性迁移 | 3C（共享 Merge Engine）+ P1 | M |
| **M2 — Developer 数据处理（legacy）** | ✅ 已实现（Phase 5B §12，见 `docs/PHASE5B_REPORT.md`）：Legacy v1 Developer Vault 五类（Android Signing Key / API Credential / SSH Key / Environment Variable Set / Generic Secret）全部经 Legacy mapper → `VaultSnapshot` → shared merge/apply **正常迁移并持久化**到 SQLCipher DB；不降级为只读 secure note、不默认跳过、不默认“未导入 + 报告”；数据进入 logical snapshot，Native Full Vault Export 不丢失。~~默认“未导入 + 报告”，可选转只读 secure note~~ **已废弃** | 旧 Developer 数据全部保留并可导出 | M1 | S（剩余仅 UI/UX 增强） |

> Legacy Import 与 Native Package Import 的**强制隔离边界**见 §9。

### 5.5 Phase 6（L1/L2 已关闭，L3 DEFER）— Product polish

| Slice | Goal | User-visible result | Deps | Scope |
| --- | --- | --- | --- | --- |
| **L1 — Localization（en + zh-CN）** | ✅ 已实现（完整双语 key 与 format 对齐） | 双语 UI | 任意阶段可并行开始 | M |
| **L2 — About / Update Check** | ✅ 已实现（见 `docs/PHASE6_L2_REPORT.md`）version/about、签名校验的手动更新检查、外部打开 release | 用户可看到版本与更新入口（不自动安装） | — | S |

> **L2 当前状态**：已实现并合并。About 页
> （runtime versionName/versionCode、product description、Check for Updates、
> update 状态机、severity、minSupported 更强警告、Open Release Page）已接入
> Settings → About；固定 CNB 清单 + Ed25519 验签（BouncyCastle）已实现，
> 签名覆盖 exact raw bytes，`.sig` 为 Base64 原始 64-byte；schema v1 strict
> validation；manual only（无 WorkManager/后台）；update data fail closed /
> app fail open；INTERNET 权限显式声明；UI 状态机 + error taxonomy 冻结。
> 发布基础设施（`rescueauth-updates` 仓库、生产 update 公钥 provisioning）
> 未完成 —— 见 `docs/UPDATE_PROTOCOL.md` §Release Infrastructure Pending。
| **L3 — Clipboard / security polish** | DEFER：clipboard auto-clear、动画/无障碍 polish | 后期安全打磨 | — | S |

> Update Check 具体实现可复用旧版 UpdateChecker 语义 + 新
> UPDATE_PROTOCOL.md（CNB 固定清单 + Ed25519 验签）。**不做** self
> update installer / APK 静默安装 / 自动下载。

### 5.6 Sensitive Action Re-authentication（正式能力）

- **定义**：对高敏感操作，即使 Vault 已解锁，仍要求一次 fresh
  Biometric / Device Credential 认证。
- **覆盖操作（至少）**：
  - Export entire Vault Package
  - Export selected sensitive package（含 keystore / SSH private key /
    API secret 的 section）
  - Export Android keystore file
  - reveal / copy SSH private key、SSH passphrase、API secret / apiKey、
    Generic Secret field value
  - reveal signing storePassword / keyPassword
  - 其他等价的高敏感长期 secret
- **不覆盖**：TOTP 查看/复制（日常高频，走普通解锁）、普通 metadata 查看。
- **授权语义（Phase 4 P4 + security-boundary CR）**：one-shot + 绑定原始
  target。成功 re-auth 只授权恰好一个 `SensitiveActionRequest`
  （`action` + `stableId` + `fieldKey`），并立即消费；reveal 授权绝不复用于
  copy；prompt 期间 selection / navigation / 第二个同类型请求都不会把成功
  结果作用于其它 entry / field（ADR-0011）。
- **Roadmap 归属**：
  - 基础设施（认证会话复用、`resolveAvailableAuthenticators` 复用 Phase 2）
    → 放在 **Phase 4 P4**（首批 Developer slice）接入；
  - 完整覆盖全部敏感操作 → **P4–P6** 随各自 slice 落实；
  - 作为独立 slice 记录在 **Phase 4 跟踪**（见 AGENTS.md 阶段清单），
    不得隐含在普通 unlock 中。
- **安全约束**：re-auth 采用一次性授权，不设置 freshness window；失败不得
  降级为无认证操作。

---

## 6. Developer Vault（五类全部 KEEP）

边界：**secure storage / view / copy / export**，不是 DevOps automation
platform。

| 类型 | 至少保留能力 | 说明 |
| --- | --- | --- |
| **Android Signing Key** | projectName、packageName、keystore file contents、keystore filename、storePassword、keyAlias、keyPassword；create/view/edit/delete；reveal/hide secrets；copy fields；export keystore；Copy key.properties-style properties | keystore 文件内容以 binary asset 形式进入 Package（§8.2） |
| **API Credential** | serviceName、accountName、apiKey、apiSecret、title/notes；view/edit/delete/copy/reveal | |
| **SSH Key** | keyName、publicKey、privateKey、passphrase、title/notes；view/edit/delete/copy/reveal | **不扩展** ssh-agent / SSH generation / deployment |
| **Environment Variable Set** | projectName、多个 KEY=VALUE entries；create/view/edit/delete/copy | |
| **Generic Secret** | arbitrary label=value fields、title/notes；create/view/edit/delete/copy | |

---

## 7. Search + Pin

### 7.1 Global Search（已实现，Phase 4 P7，见 `docs/PHASE4_P7_REPORT.md`）

- 至少覆盖：Provider name、Account name、TOTP display metadata、
  Recovery Set title + provider/account context、Developer Entry title 与
  Developer 非敏感 metadata（signing: projectName/packageName/
  keystoreFileName/keyAlias；API: serviceName/accountName；SSH: keyName；
  Env: projectName + variable names；Generic: field labels）。
- **默认不索引/不搜索**：TOTP secret、当前 TOTP code、Recovery plaintext、
  API key/secret、SSH private key/passphrase、signing storePassword/
  keyPassword、keystore bytes/base64、Env value、Generic value、SSH
  publicKey、Developer free-form notes。
- **禁止** `payloadJson LIKE` 查询。
- **实现**：个人本地数据规模小 → 优先 **in-memory safe projection**（Room
  Flow → domain → 显式 safe `SearchDocument` → in-memory matching）。不做
  DataStore/外部 DB/文件/缓存/FTS/AppSearch/后台索引。
- **Matcher**：Unicode-safe case-insensitive contains；多 token 用
  whitespace split + AND；deterministic；无 fuzzy/Levenshtein/semantic/
  pinyin/regex。
- **Ranking**：exact → prefix → substring；同 tier pinned Account 优先 +
  stable title 排序；不记录 usage/history。
- **Lifecycle**：query 仅 in-memory（不写 Room/DataStore/SavedStateHandle/
  rememberSaveable/Bundle/logs/analytics）；离开搜索页/会话锁定/进程重建
  即清空。

### 7.2 Pin / Pinned Items（已实现，Phase 4 P7）

- 只做 **Pin / Unpin** 置顶；不做 Favorites 体系。
- **P7 v2.0 正式 scope = Account only**。不做 Provider/TOTP/Recovery Set/
  Developer Pin、不做 Favorites page/folder/category/tag/rating/custom
  pinned collection。
- Pin 是 **ordering attribute**，不是第二份数据：Account 仍属于原
  Provider；只影响显示顺序（同 Provider 内 pinned 在前，稳定排序保留）；
  不复制/不 shadow row/不改 stableId/不改 Provider 关系。
- **storage compatibility field = `favorite`**（Room `AuthAccountEntity.favorite`
  ↔ logical `VaultAccount.favorite` ↔ package encode/decode）；product/UI
  semantic = **pinned**（`AccountUi.isPinned` / `setPinned(...)`）。
  无 Room/package schema migration、无 column rename、无 logical model
  rewrite。

---

## 8. Portable Vault Package（Phase 3 契约 + 后续扩展）

### 8.1 产品语义

- **取消 automatic backup**；只做 **Manual Export / Import**。
- 每次 Export：用户为这一份 package 独立设置 **Export PIN**。
  - 不存在永久 backup password；Export PIN 与本机 VaultKey 完全独立；
    PIN 不落盘、不保存到 Vault。
- Package：**versioned / encrypted / logical / portable / mergeable**。
- Import 默认：decrypt → validate → preview → dedupe → detect conflict →
  merge into current Vault。**不是 replace**。
- **Restore = empty Vault + package → merge**。

### 8.2 Package 必须覆盖完整 Vault（Phase 3A 验收）

Developer Vault 五类是**正式 v2 核心资产**，不是未来 optional extension；
`LogicalVaultSnapshot / VaultPackagePayload` **不得只覆盖 TOTP + Recovery
Codes**，必须现在即可表达：

```
Authenticator:
  Provider / Account / TOTP / Recovery Codes
Developer:
  Android Signing Key / API Credential / SSH Key / Environment Variable Set / Generic Secret
```

- **Android keystore 是 binary asset**：Package format 必须能安全携带
  （base64 或等价编码 + 大小上限）。
- **不要用 Room entity 作为 package schema**：package 使用独立的逻辑
  schema（platform-neutral）。
- **partial / selective snapshot 必须可表达**（§8.4）。

### 8.3 Merge / Dedupe（覆盖全部资产）

- 所有 Native Package Import 采用 **merge-first**。必须支持：
  - stable identity（stableId，已实现）
  - semantic duplicate detection（fingerprint：TOTP/Recovery 已实现；
    Developer Entry 基础语义纳入 Phase 3A foundation，per-type 精确 dedupe 后续增强）
  - conflict detection（已实现）
  - no silent overwrite（已实现）
  - deterministic result（已实现）
  - idempotent repeated import（已实现）
  - transaction rollback（Phase 3C 实现）
- **Merge 覆盖完整 Vault 资产**：TOTP、Recovery Codes、Developer Entry
  全部在 Phase 3A 的 portable logical schema / merge foundation 范围内。
- **Developer Entry 已纳入 Phase 3A foundation**：每类至少具备**保守、
  可扩展的 insert / duplicate / conflict 基础语义**（例如：**不能因为
  title 相同就自动认为两份 SSH private key 相同**）；更复杂的 per-type
  semantic dedupe 可后续增强，但不能等后续才把 Developer Entry 加入
  portable logical schema。
- 安全原则：**宁可 keep both / conflict，不要错误 dedupe**。

### 8.4 Selective Export / Import

- Export 支持：Entire Vault / Authenticator section / Developer Vault
  section / Selected items（第一版 UI 可逐步实现，但 **Package domain /
  format 不得阻碍 partial snapshot**）。
- Import 支持 **Package Preview** + **Import all**；**Selective Import**
  未来/同阶段实现，且**走同一个 Merge Engine**，不产生第二套 import
  implementation。

---

## 9. Legacy Import 与 Native Package Import 强制隔离

这是**强制架构边界**。

| | Legacy Import（`.rakvault`） | Native Package Import（v2+ Portable Package，`.rakpkg`） |
| --- | --- | --- |
| 来源 | Flutter / v1 | v2+ Export Package |
| 特点 | old format、Master Password、old crypto/schema、**compatibility only** | per-export PIN、new package format、**long-term supported** |
| parser | 独立 | 独立 |
| crypto | 独立（Argon2id+XChaCha20 legacy，Phase 1） | 独立（per-export PIN codec，Phase 3B） |
| errors | 独立 | 独立 |
| use case / entry point | 独立 | 独立 |

允许共享：`LogicalVaultSnapshot`、validation、**Merge Engine**、MergeResult。

- 未来删除 Legacy Import ⇒ 主要表现为删除 legacy compatibility layer，
  **不得要求重构 Native Package Import**。
- 产品 UI 必须区分：“**Import Rescue Auth Package**”与“**Import from
  Legacy Rescue Auth**”。

---

## 10. DAILY-USE READY 里程碑定义

**DAILY-USE READY** 是 v2 的正式里程碑。达到该里程碑 = 新 APK 可从空
Vault 开始替代旧版作为日常 Authenticator + 个人安全库使用。

必须包含（当前全部已实现）：

- [x] biometric unlock（+ device credential fallback）
- [x] TOTP add（QR / otpauth paste / manual entry）
- [x] TOTP view / countdown
- [x] copy
- [x] delete + Undo
- [x] persistence（重启不丢数据）
- [x] manual Export Package
- [x] Native Package Import
- [x] merge / dedupe
- [x] basic Recovery Codes（add / view / copy-all / used-unused / delete）
- [x] basic Developer Vault（至少 Android Signing Key + API Credential）
- [x] sensitive-action re-auth（Export 与 reveal 类操作）

**不阻塞 DAILY-USE READY**：

- update check
- clipboard auto-clear
- polish animation
- 完整 i18n（已完成，见 §5.5 / §13）
- SSH / Env / Generic 三类的完整 UI（已完成；属于 V2.0 FEATURE COMPLETE）

> **DAILY-USE READY = YES**。以上清单描述里程碑门槛，不是未完成任务；设备级
> 生物识别和 Keystore 行为仍需按发布门禁继续做真机验证。

### 10.1 V2.0 FEATURE COMPLETE 里程碑定义

**V2.0 FEATURE COMPLETE** 是 v2 重构完成的正式里程碑，代表**正式产品
范围全部完成**。**DAILY-USE READY 只是“已经可以迁移过去并开始日常自用”
的中间里程碑**，不能被解释为整个 v2 重构完成。

达到 **V2.0 FEATURE COMPLETE** 必须包含：

- [x] Authenticator 正式能力（Provider/Account/TOTP 完整能力）
- [x] Recovery Codes 完整能力
- [x] Developer Vault 五类完整能力（Android Signing Key / API Credential /
  SSH Key / Env Var Set / Generic Secret）
- [x] Android Signing Key keystore import/export
- [x] Portable Package full / selective export / import
- [x] merge / dedupe / conflict（覆盖全部资产）
- [x] Legacy migration（`.rakvault` 收口）
- [x] Search + Pin
- [x] Delete Undo（全类型覆盖）
- [x] Sensitive Action Re-auth（全敏感操作覆盖）
- [x] en + zh-CN（完整双语）
- [x] About / Update Check

> **V2.0 FEATURE COMPLETE = YES**（经最终 release-readiness 审计确认，全部
> v2.0 正式产品能力已实现并有测试覆盖）。
>
> **V2.0 RELEASED = NO**：FEATURE COMPLETE 不等于已发布。Android production
> signing identity 已 provision；仍待 production Update Ed25519 provisioning、
> `rescueauth-updates` 基础设施、signed release real-device smoke、FTL / final
> device regression。
>
> **Release Provisioning Step 1 = MERGED；Step 2 = PROVISIONED**（见
> `docs/RELEASE_PROVISIONING.md`）：新 App `applicationId=com.rescueauth.v2`
>（≠ Legacy `com.xincy.rescue_auth_kit`，可 side-by-side）、`versionName="1.0.0"`、
> `versionCode=10000` 已冻结；production signing 基础设施已建立（无 debug
> fallback；无 config 时 release 为 unsigned；`validateReleaseSigning` 显式校验）。
> Production Android signing identity 的公开证书元数据固定于
> `release/android-signing-certificate.txt`；private material 不进入本仓库。

**不阻塞 V2.0 FEATURE COMPLETE**（按 Roadmap §18 deferred policy 处理）：

- clipboard auto-clear
- 动画 / 无障碍 polish

---

## 11. 删除 UX：Undo（NEW）

- 普通删除操作：
  ```
  delete → immediate UI removal → SnackBar / equivalent → Undo within short window
  ```
- 适合 Undo 的操作：
  - TOTP
  - Recovery Code set
  - ordinary Developer Entry
  - account
- 高破坏性操作**保留 confirmation**（可评估是否再叠加 Undo）：
  - provider cascading delete
  - Android signing key 含 keystore
  - 大规模 merge / destructive operations
- **不要为了 Undo 恢复 automatic checkpoint backup**；Undo 优先通过
  数据库/domain transaction/state mechanism 实现。

---

## 12. Clipboard Auto-clear（DEFER）

- 当前产品决定：**DEFER**。不是 v2 initial daily-use blocker。
- 记录为：**Later security polish**。
- 不得阻塞：TOTP copy、recovery-code copy、Developer secret copy。
- 若未来实现：确保不会错误清除用户后来复制的其他 clipboard 内容。
- 现在**不安排到 P0 / initial release**。

---

## 13. Localization（KEEP）

- 旧版有 English + Simplified Chinese；**完整保留双语能力**。
- v2 不得因 Native rewrite 丢失 i18n。
- **当前状态：L1 已完成**，en + zh-CN key 与 format 已对齐；后续新增 UI
  必须同步维护两种语言。

---

## 14. Update Check（KEEP，简化）

- 保留：app version / about、check releases、open release externally。
- 不做：self update installer、APK silent install、auto-download updater。
- 后期小功能，不在核心自用 critical path 前面（L2）。

---

## 15. 依赖与顺序总览

```
Phase 2（已关闭）
   │
   ├─▶ Phase 3A（已关闭）──▶ 3B（已关闭）──▶ 3C（已实现）──▶ 3D
   │                                        │
   │                    ┌───────────────────┘
   ▼                    ▼
Phase 4 P1（TOTP loop） ◀──── 3C（merge）可用于 P2
   ├─▶ P2（otpauth-migration）——依赖 3C + P1
   ├─▶ P3（Recovery Codes）——依赖 P1
   ├─▶ P4（Developer Vault 第一批 + re-auth）——依赖 3D + re-auth
   ├─▶ P5（Selective Export/Import）——依赖 3D + P3/P4
   ├─▶ P6（Developer Vault 第二批）——依赖 P4
   ├─▶ P7（Search + Pin）——依赖 P1 + P6
   └─▶ P8（Delete Undo 完善）——P1 起步，P3/P6 覆盖全类型
Phase 5 M1/M2（legacy import 收口）——依赖 3C + P1
Phase 6 L1/L2/L3（polish）——不阻塞 daily-use
```

---

## 16. 里程碑检查表（对照）

| 能力 | 归属 | DAILY-USE READY | V2.0 FEATURE COMPLETE |
| --- | --- | --- | --- |
| biometric unlock | Phase 2 | 必需 | 必需 |
| TOTP add/view/countdown/copy | P1 | 必需 | 必需（正式能力） |
| delete + Undo | P1（起步）→ P8 完善 | 必需 | 必需（全类型覆盖） |
| persistence | Phase 2 + P1 | 必需 | 必需 |
| QR / otpauth paste / manual entry | P1 | 必需 | 必需 |
| manual Export Package | 3B + 3D | 必需 | 必需 |
| Native Package Import | 3C + 3D | 必需 | 必需 |
| merge / dedupe / conflict | 3A + 3C | 必需（dedupe 基础） | 必需（含 conflict，全资产覆盖） |
| basic Recovery Codes | P3 | 必需（basic） | 必需（完整能力） |
| basic Developer Vault | P4 | 必需（首批两类） | 必需（五类完整） |
| Android Signing Key keystore import/export | P4 | 可选（增强） | 必需 |
| sensitive-action re-auth | P4 | 必需（Export / reveal 类） | 必需（全敏感操作覆盖） |
| otpauth-migration import | P2 | 可选（增强） | 可选（增强） |
| Selective Export / Import | P5 | 可选（增强） | 必需 |
| Search / Pin | P7 | 可选（增强） | 必需 |
| legacy import 收口 | M1/M2 | 兼容性，不阻塞新用户 | 必需（Legacy migration 完成） |
| en + zh-CN | L1 | 不阻塞（核心页面 en 可用） | 必需（完整双语） |
| About / Update Check | L2 | 不阻塞 | 必需 |
| clipboard / 动画 / 无障碍 polish | L3 | 不阻塞 | 不阻塞（deferred policy） |

---

## 17. Phase 3A Review Requirements（历史验收清单）

> Phase 3A 已关闭。以下 10 项保留为历史验收基线和后续回归约束，不代表
> 当前仍有待处理的 PR 或未完成验收。

1. **Package 是否覆盖完整 Authenticator + Developer Vault**
   —— `LogicalVaultSnapshot / VaultPackagePayload` 不得只覆盖 TOTP +
   Recovery Codes；必须能表达 Provider / Account / TOTP / Recovery Codes /
   Developer 五类（Android Signing Key / API Credential / SSH Key /
   Env Var Set / Generic Secret）。
2. **Developer binary keystore 是否可表示**
   —— Android keystore 是 binary asset，Package schema 必须能安全携带
   （base64 或等价编码 + 大小上限），不得因 schema 限制丢弃或截断。
3. **partial / selective snapshot 是否可表达**
   —— 逻辑模型必须支持部分快照（Entire Vault / section / selected
   items），不得阻碍 Selective Export / Import。
4. **Merge 是否不是 TOTP-only**
   —— Merge Engine / portable logical schema 必须已纳入 Developer Entry：
   每类至少具备保守、可扩展的 insert / duplicate / conflict 基础语义；
   更复杂的 per-type semantic dedupe 可后续增强，但不能架构上只能处理
   TOTP，也不能等后续才把 Developer Entry 加入 portable logical schema。
5. **Legacy / Native Import 是否隔离**
   —— 两者有独立 parser / crypto / errors / use case；只共享
   LogicalVaultSnapshot / validation / Merge Engine / MergeResult。
6. **Native package 是否 per-export PIN**
   —— 每次导出独立 PIN → KDF → PackageKey → AEAD；不存在永久 backup
   password；PIN 不落盘、不改变 VaultKey。
7. **是否误保留 automatic-backup assumptions**
   —— 不得残留 automatic / scheduled / background / WorkManager backup /
   BackupRecord 表 / PRE_IMPORT checkpoint / BackupSnapshotSink 等。
8. **是否误用旧 PRODUCT 中 Developer removed 的假设**
   —— 文档/代码不得再假设“Developer Vault 在 v2 被 intentionally
   removed”；Developer 五类全部 KEEP。
9. **是否支持 future selective import**
   —— Package domain / import 路径不得阻塞“Select items to import”；
   Selective Import 必须走同一 Merge Engine，不产生第二套实现。
10. **是否没有把 Room schema 直接等价成 portable schema**
    —— package 使用独立逻辑 schema（platform-neutral），不得直接序列化
    Room entity；migration/identity 处理不依赖 Room 表示。

---

## 18. Removed Scope / Deferred Scope（摘要）

### Removed（正式不做）

- Windows / macOS / Linux / iOS / Web 客户端
- cloud sync / 账号登录后端 / 多人协作
- automatic backup（onEveryChange / daily / weekly / monthly / WorkManager /
  background / cloud backup / 自动 checkpoint 文件）
- 全局 BackupKey / 恢复套件 / 永久 backup password / 全局 Master Password
- browser extension / autofill password manager
- SSH agent / SSH generation tool / API execution / DevOps automation
- arbitrary file vault / social sharing
- otpauth-migration export
- self update installer / APK silent install / auto-download updater

### Deferred（暂缓，后续 polish）

- clipboard auto-clear（Later security polish，不阻塞 daily use）
- 动画 / 无障碍 polish
- 完整 i18n 之外的文案打磨（i18n 结构可在早期开始，文案在 daily-use
  release 前完成）
- Search/Pin、Selective Import 的 UI 细节（可在 daily-use 后增强）
