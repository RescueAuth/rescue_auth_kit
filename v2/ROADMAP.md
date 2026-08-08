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
| Localization（en + zh-CN） | **KEEP** | daily-use release 前恢复双语（见 §13） |
| Update Check | **KEEP（简化）** | version/about + 检查发布 + 外部打开；无自更新安装（见 §14） |
| Automatic backup / WorkManager backup / cloud | **REMOVE** | 已随 Phase 3 reset 删除，且不再恢复 |
| 全局 BackupKey / 恢复套件 | **REMOVE** | 由 per-export PIN 取代；文档已同步 |

---

## 4. Authenticator 功能范围

### 4.1 Provider / Account / Credential

- **Provider → Account → Credential** 三级层级。
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
- 与 Selective Import 一致：批量导入后走同一 Merge Engine 预览。

---

## 5. Phase Roadmap（Phase 3 之后全部重新规划）

### 5.0 已关闭阶段（保持不动）

- **Phase 0 CLOSED** — 冻结 v1.2.0 + legacy fixtures + 映射文档
- **Phase 1 CLOSED** — Kotlin/Android 工程 + Argon2id/XChaCha20 解密 spike
- **Phase 2 CLOSED** — Room+SQLCipher / VaultKey+Keystore / 安全会话 /
  自动锁 / 遮罩 + FLAG_SECURE / 串行 repository（数据库 instrumented
  6/6 PASS）

### 5.1 工作分类（Roadmap 明确区分）

| 分类 | 内容 | 特征 |
| --- | --- | --- |
| **Foundation work** | package、merge、shared domain、TOTP core、otpauth parser、secure codec、Developer entry domain | 纯逻辑、可 JVM 全量测试、为多个 vertical slice 复用 |
| **Daily-use vertical slices** | TOTP usable loop、Recovery Codes、Developer Vault、Search / Pin、Export/Import loop、Sensitive re-auth | 每个 slice 小步完成：slice → PR → merge → 下一个 |
| **Migration** | legacy import 完整流程（UI 收口）、Developer 数据处理 | 独立于 Native Package Import |
| **Product polish** | i18n、about/update、clipboard polish、动画 | 不阻塞 daily-use |

不再使用“Phase 5 — main UI”这类模糊阶段。以下 Phase/Slice 每个都可
独立 review、独立 merge、有真实用户价值。

### 5.2 Phase 3（进行中）— Package + Merge Foundation

> **注意**：Phase 3A 分支 `auto/phase3a-merge-foundation-a299`（PR #18）
> 基于 main `a3b00b7` 创建。**本 Roadmap PR 不触碰该分支**；Phase 3A
> Agent 回来验收请核对 §8 的 **Phase 3A Review Checklist**。

| Slice | Goal | User-visible result | Deps | Scope |
| --- | --- | --- | --- | --- |
| **3A — Package + Merge Foundation** | 逻辑 package 模型（VaultSnapshot / VaultPackagePayload）+ stableId + semantic fingerprint + canonicalization + 纯 merge planner + schema v1→v2 + 自动备份抽象清理 | 无用户 UI；为 3B/3C/3D 提供纯 JVM 契约与测试基线 | Phase 2 | L |
| **3B — Encrypted Package Codec** | per-export PIN → KDF → PackageKey → AEAD 加密 envelope；wrong PIN/corrupted 安全失败；大小/记录上限 | 纯 JVM 可验证的加密导出/导入字节契约（无 UI） | 3A | M |
| **3C — Transactional Import / Merge** | MergePlan → Room apply（单事务、rollback、幂等）；Native + Legacy 共用 Merge Engine | 命令行/测试层可验证的“导入即合并”，仍无 UI | 3A + 3B | M |
| **3D — Android Export / Import + Package Preview** | SAF 选择/写入、per-export PIN 对话框、import preview（Authenticator/Developer 计数）、Import all | 用户可手动导出/导入 Package；预览 + merge 报告 | 3B + 3C | L |

**3D 验收注意**：import preview 必须能区分 Authenticator（N accounts /
N TOTP / N recovery sets）与 Developer（N signing keys / N API / N SSH /
N env / N generic），支持 **Import all**；Selective Import 作为同阶段/下一
小步（仍走同一 Merge Engine，见 §5.3 P5）。

### 5.3 Phase 4 — Daily-use vertical slices（核心自用路径）

> 目标：尽早让 TOTP 真正可用；每个 slice 独立 PR。

| Slice | Goal | User-visible result | Deps | Scope |
| --- | --- | --- | --- | --- |
| **P1 — TOTP usable loop（垂直切片）** | Compose 首页：Vault 列表 + TOTP 显示/倒计时/复制；添加（QR + otpauth paste + manual entry）；删除 + Undo；持久化 | 用户可完成“解锁 → 看码 → 复制 → 添加 → 删除”完整日常循环 | Phase 2 会话层 + TOTP core | L |

> **P1 状态（2026-08-07，Issue #20）**：已实现 otpauth paste + manual
> entry、真实 production storage、倒计时/复制/删除+Undo（见
> `docs/PHASE4_P1_REPORT.md`）。**QR 扫描**与 **otpauth-migration** 尚未
> 实现，按 Issue #20 契约放入后续 slice。
| **P2 — otpauth-migration import** | 解析 `otpauth-migration://` → 内部 logical credential → 批量 merge 预览/导入 | 可从 Google Authenticator 批量迁入 | P1 + 3C | S |
| **P3 — Recovery Codes slice** | 恢复码列表：batch add / expand-collapse / copy all / edit / delete / move；used-unused 标记 + remaining count + used 弱化显示 | 恢复码可完整管理（一等 Vault credential） | P1 | M |
| **P4 — Developer Vault slice（第一批）** | Android Signing Key + API Credential 全 CRUD / reveal-hide / copy / export keystore / key.properties 复制；Sensitive re-auth 接入 | 两种最常见的 Developer Entry 可日常使用 | 3D + re-auth（§5.6） | M |
| **P5 — Selective Export / Import** | Package domain 已支持 partial snapshot；UI：Entire Vault / Authenticator section / Developer section / Selected items；Import 支持 Select items（同一 Merge Engine） | 用户可按需迁移部分数据 | 3D + P3/P4 | M |
| **P6 — Developer Vault slice（第二批）** | SSH Key / Env Var Set / Generic Secret 全 CRUD / copy / reveal | 五类 Developer Entry 全部可日常使用 | P4 | M |
| **P7 — Search + Pin** | 全局搜索（Provider/Account/TOTP display/Developer title + 非敏感 metadata；不索引 secret）+ Pin/Unpin 置顶 | 快速定位常用项 | P1 + P6 | M |
| **P8 — Delete Undo 完善** | 普通删除（TOTP / recovery set / 普通 Developer Entry / account）统一 SnackBar Undo；高破坏性操作（provider 级联、含 keystore 的 signing key、大规模 merge）保留确认 | 误删可恢复，破坏性操作仍受保护 | P1 起步，P3/P6 覆盖全类型 | S |

> **P8 说明**：Undo 优先通过数据库/domain transaction/state mechanism
> 实现；**不要**为了 Undo 恢复 automatic checkpoint backup。

### 5.4 Phase 5 — Migration（legacy import 收口）

| Slice | Goal | User-visible result | Deps | Scope |
| --- | --- | --- | --- | --- |
| **M1 — Legacy Import UI 完整流程** | 选 `.rakvault` → 一次旧密码 → Argon2id/XChaCha20 解密 → 预览 → 单事务写入 → 报告；与“Import Rescue Auth Package”在 UI 上明确区分 | 用户可从旧版 v1.2.0 一次性迁移 | 3C（共享 Merge Engine）+ P1 | M |
| **M2 — Developer 数据处理（legacy）** | 预览显示 Developer 数量；默认“未导入 + 报告”，可选转只读 secure note；禁止静默丢弃 | 旧 Developer 数据有明确去向 | M1 | S |

> Legacy Import 与 Native Package Import 的**强制隔离边界**见 §9。

### 5.5 Phase 6 — Product polish（不阻塞 DAILY-USE READY）

| Slice | Goal | User-visible result | Deps | Scope |
| --- | --- | --- | --- | --- |
| **L1 — Localization（en + zh-CN）** | 建立 i18n 结构；daily-use release 前恢复全部核心页面文案 | 双语 UI | 任意阶段可并行开始 | M |
| **L2 — About / Update Check** | version/about、检查发布、外部打开 release | 用户可看到版本与更新入口（不自动安装） | — | S |
| **L3 — Clipboard / security polish** | clipboard auto-clear（DEFER 项）安全实现（不清除用户后续复制的其他内容）；动画/无障碍 polish | 后期安全打磨 | — | S |

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
  - reveal SSH private key
  - reveal API secret
  - reveal signing storePassword / keyPassword
  - 其他等价的高敏感长期 secret
- **不覆盖**：TOTP 查看/复制（日常高频，走普通解锁）、普通 metadata 查看。
- **Roadmap 归属**：
  - 基础设施（认证会话复用、`resolveAvailableAuthenticators` 复用 Phase 2）
    → 放在 **Phase 4 P4**（首批 Developer slice）接入；
  - 完整覆盖全部敏感操作 → **P4–P6** 随各自 slice 落实；
  - 作为独立 slice 记录在 **Phase 4 跟踪**（见 AGENTS.md 阶段清单），
    不得隐含在普通 unlock 中。
- **安全约束**：re-auth 窗口应与 Phase 2 认证有效期设置一致；失败不得
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

### 7.1 Global Search（NEW）

- 至少覆盖：Provider name、Account name、TOTP display metadata、
  Developer Entry title、Developer 非敏感 metadata。
- **默认不索引/不搜索**：TOTP secret、API secret、SSH private key、
  passwords、recovery code plaintext、Generic Secret value、Env value。
- 理由：搜索 secret 无明确需求且增加安全复杂度。
- 实现建议：SQLCipher 内 LIKE 查询仅作用于非敏感列，或 DataStore 维护
  非敏感搜索索引；**不得**把 secret 明文加入任何索引。

### 7.2 Pin / Pinned Items（NEW）

- 只做 **Pin / Unpin** 置顶；不做 Favorites 体系。
- 作用范围评估（优先保持简单一致）：
  - **Account**（推荐，最常见）
  - **TOTP credential**（可选，当账户下多凭据时）
  - **Developer Entry**（可选）
- 明确不做：favorites folder、favorite category、rating、tag system。

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

| | Legacy Import（`.rakvault`） | Native Package Import（v2+ Portable Package） |
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

必须包含：

- [ ] biometric unlock（+ device credential fallback）
- [ ] TOTP add（QR / otpauth paste / manual entry）
- [ ] TOTP view / countdown
- [ ] copy
- [ ] delete + Undo
- [ ] persistence（重启不丢数据）
- [ ] manual Export Package
- [ ] Native Package Import
- [ ] merge / dedupe
- [ ] basic Recovery Codes（add / view / copy-all / used-unused / delete）
- [ ] basic Developer Vault（至少 Android Signing Key + API Credential）
- [ ] sensitive-action re-auth（Export 与 reveal 类操作）

**不阻塞 DAILY-USE READY**：

- update check
- clipboard auto-clear
- polish animation
- 完整 i18n（但核心页面至少 en 可用；zh-CN 在 daily-use release 前恢复，
  见 §13）
- SSH / Env / Generic 三类的完整 UI（basic Developer Vault 已覆盖最常用两类）

### 10.1 V2.0 FEATURE COMPLETE 里程碑定义

**V2.0 FEATURE COMPLETE** 是 v2 重构完成的正式里程碑，代表**正式产品
范围全部完成**。**DAILY-USE READY 只是“已经可以迁移过去并开始日常自用”
的中间里程碑**，不能被解释为整个 v2 重构完成。

达到 **V2.0 FEATURE COMPLETE** 必须包含：

- [ ] Authenticator 正式能力（Provider/Account/TOTP 完整能力）
- [ ] Recovery Codes 完整能力
- [ ] Developer Vault 五类完整能力（Android Signing Key / API Credential /
  SSH Key / Env Var Set / Generic Secret）
- [ ] Android Signing Key keystore import/export
- [ ] Portable Package full / selective export / import
- [ ] merge / dedupe / conflict（覆盖全部资产）
- [ ] Legacy migration（`.rakvault` 收口）
- [ ] Search + Pin
- [ ] Delete Undo（全类型覆盖）
- [ ] Sensitive Action Re-auth（全敏感操作覆盖）
- [ ] en + zh-CN（完整双语）
- [ ] About / Update Check

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
- 早期开发阶段先完成结构（L1），但 **daily-use release 前应恢复
  en + zh-CN 所有核心页面文案**。

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
   ├─▶ Phase 3A（进行中）──▶ 3B ──▶ 3C ──▶ 3D
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

## 17. Current Phase 3A Review Requirements（验收清单）

> 供 Phase 3A Agent 回来后对 PR #18 进行自检。以下 10 项必须逐条给出
> 结论（PASS / FAIL / 需补充），作为 Phase 3A 能否关闭的依据。
> 本轮 Roadmap 只做验收定义，不替代 Phase 3A 的实现/修复。

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
