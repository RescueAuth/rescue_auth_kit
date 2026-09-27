# AGENTS.md — 拾遗坊 / RescueAuth v2 维护契约

> 面向所有 AI / 人类维护者的执行规则。违反以下任何一条都属于违约提交。
> 产品范围与路线图：`PRODUCT.md`（范围） / `ROADMAP.md`（路线图） /
> 本文（阶段进度）。三处不一致视为文档违约。

## 项目状态

- 产品中文名为**拾遗坊**、英文名为 **RescueAuth**；仓库名保留 `rescue_auth_kit`，
  v2 仅表示原生重写代际，首个原生版本号为 `1.0.0`。
- v2 是**全新 Android 原生应用**（Kotlin + Jetpack Compose + Room/SQLCipher），
  当前仓库根目录只保留原生应用主体。旧 Flutter 源码冻结在
  `legacy-v1.0.0`–`legacy-v1.2.0` tags；当前树保留 legacy fixtures 与只读
  import compatibility layer。
- **产品定位（2026-08-07，Issue #17 定稿）**：v2 = Android-only、local-first、
  encrypted personal security vault，包含三大正式能力：
  **Authenticator（Provider/Account/TOTP/Recovery Codes）**、
  **Developer Vault（五类 Developer Entry，完整保留 v1.2.0，不再视为 removed）**、
  **Portable Vault Package（manual export、per-export PIN、merge-first import）**。
- v2 代码即仓库根目录（Kotlin + Room/SQLCipher 原生 Android 应用，已从 `v2/` 提升为仓库主体）；`databaseSchemaVersion`（Phase 3A 已升 **2**，Phase 3C 已升 **3**，UI polish 已升 **4**），
  `packageFormatVersion = 1`（PACKAGE_FORMAT.md）。
- **App 身份 / 版本（Release Provisioning，见 `docs/RELEASE_PROVISIONING.md`）**：新 App `applicationId = com.rescueauth.v2`（≠ Legacy `com.xincy.rescue_auth_kit`，可 side-by-side 安装）、`namespace = com.rescueauth.v2`、`versionName = "1.0.0"`、`versionCode = 10000`。**“v2” 是 generation/rewrite 名称，不等于 `versionName`**（V2.0 FEATURE COMPLETE ≠ versionName 2.0.0）。新 App 独立 release sequence，从 `1.0.0` 开始。Production signing 基础设施已建立（`keystore.properties` / env vars；无 debug fallback；无 config 时 release 为 unsigned；`validateReleaseSigning` 显式校验）。**Production Android signing identity = PROVISIONED（2026-08-11）**；公开证书元数据固定于 `release/android-signing-certificate.txt`，private material 仅存于外部保管位置与 CNB Secret Repository。第一份 local production-signed `1.0.0` candidate 已通过 signer/package/version/debuggable/16K alignment 验证。**Build & Release Workflow 已建立（issue #48）**：CNB production release 现在为 **tag-only**，仅由 `rescueauth-vX.Y.Z` release tag 的 `tag_push` 触发（格式与 tag↔versionName 严格校验，FAIL CLOSED）；`main` / feature / fix 分支不可生产发布。日常真机 smoke 用 Debug Pipeline（`web_trigger_debug_apk`，`Build debug RescueAuth`，owner 手动触发，不读取任何 production secret）。secret 仅注入 production signing stage；Debug 流水线不导入 Secret Repo。
- 当前阶段（同步于 2026-09-27）：**Phase 0/1/2/3/4/5 CLOSED**；Phase 6 的 L1/L2 CLOSED，L3
  Clipboard / security polish 明确 DEFER。**DAILY-USE READY = YES**，
  **V2.0 FEATURE COMPLETE = YES**，**V2.0 RELEASED = NO**。
  本轮界面整理和本地回归已完成（代码基线 `ae73494`，见 `docs/UI_POLISH_REPORT.md`），
  当前进入 **1.0.0 发布准备**，工作重点是发布配置、候选包验证与必要缺陷修复。
- 当前 release blockers：production Update Ed25519 provisioning、
  `rescueauth-updates` 更新渠道验收、签名密钥独立备份确认、signed release real-device smoke
  与最终 FTL / device regression；顺序和验收证据集中维护于
  [RELEASE_PROVISIONING.md §15](docs/RELEASE_PROVISIONING.md#release-readiness)。
  历史数据库 Firebase Test Lab 6/6 PASS 与当前本地模拟器 87/87 PASS 均不替代
  候选版本的最终设备回归；生物识别、Keystore 有效期和锁屏仍需真机验证。
- 阶段与 slice 定义见 `ROADMAP.md §5`，详细实现证据见对应 `docs/PHASE*_REPORT.md`
  与 ADR；文档导航及时效规则见 `docs/README.md`。**不要再新增平行的阶段表**。

## 必跑命令（提交前）

```bash
# v2 全量测试 + 构建（core + app JVM/Robolectric）
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug

# instrumented 测试 APK 编译（FTL/真机执行前的必要前置，需 Android SDK）
./gradlew :app:assembleDebugAndroidTest
# 本地有真机/模拟器时：
./gradlew :app:connectedDebugAndroidTest
# Firebase Test Lab（仅 main 手动触发，见 docs/FIREBASE_TEST_LAB.md）
```

> 环境要求：JDK 17 + Android SDK（compileSdk 35 / build-tools 35）。
> 本仓库**不提交** `local.properties`；本地构建需自行设置 `sdk.dir`。

任何修改必须通过以上命令后才可提交。

## 禁区（绝对禁止）

1. **数据格式/加密/签名/applicationId**：
   - 不得修改加密算法、签名公钥、applicationId、签名证书配置。
   - 不得在同一 `formatVersion` 下改变新备份格式算法。
   - 不得输出旧 `.rakvault` 格式（legacy 模块只读）。
   - 变更数据 schema、备份协议、导入规则必须先更新文档 + fixture + 测试。
2. **敏感数据**：禁止把 TOTP secret、恢复码、恢复密钥、旧密码、明文 payload
   写入日志、截图 fixture、崩溃报告、analytics 或提交内容。
3. **依赖升级**：不得自动合并加密、存储、Room、SQLCipher、构建签名相关的
   依赖升级。此类升级必须人工评审。
4. **发布**：稳定发布必须通过 CI；不允许本地未验证 APK 覆盖线上文件。
   必须先上传并验证 APK，再更新 `latest.json`。
5. **旧数据**：不得删除 legacy tags、旧 fixture、旧备份或只读兼容能力。

## 架构边界（强制）

1. **Legacy Import 与 Native Package Import 强制隔离**（独立 parser /
   crypto / errors / use case；仅共享 `LogicalVaultSnapshot` / validation /
   Merge Engine / MergeResult）。未来删除 Legacy Import 不得要求重构
   Native Package Import。UI 必须区分 “Import Rescue Auth Package” 与
   “Import from Legacy Rescue Auth”（`ROADMAP.md §9`）。
2. **otpauth-migration 是 Google Authenticator migration compatibility
   adapter，IMPORT ONLY**：`MigrationPayloadParser` 只实现已经验证的 GA
   wire contract（standard Base64 + `=` padding、protobuf enum semantics、
   batch metadata 只来自 decoded protobuf），**不是通用 OTP migration
   parser**；解析后转内部 logical credential 进入正常
   validation/dedupe/merge；该格式**不得进入核心 Vault domain**
   （`ROADMAP.md §4.3`）。不为“兼容第三方工具”扩大 parser contract——未来
   如需支持其它工具，应新建明确 adapter / compatibility decision。
3. **Package schema 独立于 Room**：portable package 使用 platform-neutral
   逻辑 schema（`VaultSnapshot`），**不得**用 Room entity 直接序列化；
   必须覆盖完整 Vault（Authenticator + Developer 五类，含 binary keystore）。
4. **不做 automatic backup**：manual export only；不得恢复 automatic /
   scheduled / background / WorkManager / cloud backup。
5. **不恢复全局 Master Password**：Master Password 仅存在于 legacy
   `.rakvault` 兼容解密。
6. **Sensitive Action Re-authentication**：高敏感操作（Export、export
   keystore、reveal 长期 secret、进入五类 Developer 完整编辑器等）即使 Vault 已解锁也要求 fresh
   Biometric / Device Credential；不得隐含在普通 unlock 中。
7. **删除 Undo**：普通删除走 SnackBar Undo；**不要**为了 Undo 恢复
   automatic checkpoint backup。
8. **卡片式 UI（Card-First）**：全项目 UI 必须采用卡片式容器（见
   `docs/UI_CARD_CONVENTION.md`）。列表行/条目、分组内容块、表单分区必须用
   `RescueAuthCard` / `RescueAuthRowCard` 或统一 token `CardTokens`（颜色/圆角/内边距
   单一来源），禁止硬编码 `surfaceContainer*` / `RoundedCornerShape` / 卡片间距。
   `ModalBottomSheet` / `AlertDialog` / 全屏相机取景框本身除外，但其内部字段/列表仍须卡片化。
9. **认证器 ViewModel 提升到 App Shell**：`AuthenticatorViewModel` 由
   `RescueAuthApp`（shell）持有并注入 `AuthenticatorRoute`，切换底部 Tab / 进入详情页时
   ViewModel 与 Room collection 不被销毁，避免每次重新进入认证器都重新加载 + 重算 TOTP
   （Issue #70 加载慢）；`AuthenticatorRoute` 保留本地回退创建（`viewModel` 参数为 null 时）
   以兼容测试/独立 preview。

## 提交要求

- 每个阶段/slice 独立提交，message 前缀 `phaseN:` 或 `sliceN:`。
- 功能完成必须提供：测试结果、截图结果（若涉及 UI）、兼容性结果、剩余风险。
- 修改行为前先更新或补充测试（测试未通过不得进入下一阶段）。

## 阶段进度跟踪（source of truth: `ROADMAP.md §5`）

- [x] 阶段 0：冻结旧项目（tag `legacy-v1.2.0`）+ legacy fixtures + 映射文档
- [x] 阶段 1：最小 Kotlin/Android 工程 + Argon2id/XChaCha20-Poly1305 解密 spike
- [x] phase1-fix：entry-centric 映射 + 非法参数不静默替换 + BC 1.85 官方 XChaCha20
- [x] 阶段 2：数据库 schema v1 + VaultKey/Keystore + 串行 repository + 自动锁
  - [x] ADR-0003（VaultKey/Keystore/会话/16KB；§2 备份部分已被 Phase 3 reset 取代）
  - [x] Room + SQLCipher（Zetetic sqlcipher-android 4.17.0）+ schema v1 实体/DAO
  - [x] VaultKey（Keystore 包装/解包）+ 安全会话状态机 + SessionManager（自动锁/后台）
  - [x] 串行 repository（Mutex + withTransaction）+ 后台遮罩
  - [x] JVM/Robolectric 测试（并发、锁定、超时、密钥失效、16KB page、SQLCipher native loader 并发契约）
  - [x] phase2-blocker-hotfix（PR #6）：BiometricPrompt 崩溃修复 + instrumented 可编译 + 真实断言
  - [x] SQLCipher native 加载（PR #15）：`SQLCipherNativeLoader` 在数据库唯一入口加载
  - [x] **instrumented 真机验证（数据库 6 用例）**：6/6 PASS（Firebase Test Lab）
  - [ ] 生物识别 / Keystore 认证有效期 / 锁屏行为等**仍待真机验证**（non-blocking）（不再全局截图保护，允许截图 —— Issue #57）
- [x] 阶段 3：Package + Merge（**CLOSED**）
  - [x] **3A Package + Merge Foundation**（architecture reset + 逻辑 package 模型 + stableId + semantic fingerprint + 纯 merge planner + schema v1→v2 + 自动备份抽象清理，PR #18）
  - [x] **3B Encrypted Package Codec**（per-export PIN → Argon2id → KEK → wrap PackageKey → AEAD envelope；header-is-untrusted / KDF accepted range / DoS 保护；wrong PIN/corrupted 安全失败；golden fixture；merge 前 CR 已修：kdfOutputLength==32、capacity 一致、runtime policy 与 format limit 分离）
  - [x] **3C Transactional Import / Merge Apply**（MergePlan → 单 Room 事务 apply + rollback + 幂等；Developer Vault 五类落库（schema v2→v3）；parent/child identity mapping；CONFLICT / Recovery state divergence 保守阻止；`applyMergePlan` / `applySnapshot` 共享事务边界；见 ADR-0008 / PHASE3_REPORT §11）
  - [x] **3D Android Export / Import + Package Preview**（SAF CreateDocument/OpenDocument、per-export PIN 对话框、bounded untrusted-file reader、import preview（Authenticator/Developer 计数 + safe summary）、confirm 走 3C transactional apply；`.rakpkg` 扩展名/MIME contract；见 PHASE3_REPORT §12）
- [x] 阶段 4：Daily-use vertical slices（**CLOSED**）
  - [x] **P1 TOTP usable loop**（otpauth paste / manual / countdown / copy / delete+Undo，真实 production storage，PR 见 docs/PHASE4_P1_REPORT.md；QR 后续补）
  - [x] **P2 otpauth-migration import**（IMPORT ONLY，External Import Adapter，见 docs/PHASE4_P2_REPORT.md）
  - [x] **P3 Recovery Codes slice**（Account detail 恢复码页：batch add / expand-collapse / reveal-hide / 单条 copy / Copy All + Copy Remaining / mark used-unused / edit（最小 diff 保留 stableId + USED state）/ delete + Undo；见 docs/PHASE4_P3_REPORT.md）
  - [x] **P4 Developer Vault 第一批 + Sensitive re-auth**（Sensitive Action Fresh Re-auth Foundation：`SensitiveAction`/`SensitiveActionGate`/`SensitiveActionResult` 单一 orchestration path + BiometricPrompt/Device Credential one-shot 语义 + Full Vault Export 接入；Developer Vault 第一批：API Credential / SSH Key / Generic Secret 全 CRUD / reveal-hide / copy / delete（destructive confirm）/ stableId-preserving edit；见 docs/PHASE4_P4_REPORT.md）
  - [x] **P5 Selective Export / Import**（同一 Merge Engine；见 `docs/PHASE4_P5_REPORT.md`：共享纯 Kotlin selection engine / Export 四 scope / decoded-snapshot 内存过滤 import / selected conflict 语义）
  - [x] **P6 Developer Vault 第二批（Developer Vault Completion）**（Android Signing Key / Environment Variable Set 全 CRUD + keystore SAF import/export + Copy key.properties + Env Var 动态行 + 每字段独立 fresh re-auth；见 `docs/PHASE4_P6_REPORT.md`）
  - [x] **P7 Search + Pin**（Global Search safe metadata only + Account Pin/Unpin；见 `docs/PHASE4_P7_REPORT.md`）
  - [x] **P8 Delete Undo 完善**（普通删除 TOTP/recovery set/account/普通 Developer Entry 统一 SnackBar Undo；高破坏性操作保留确认；Recovery Code Set Move 正式能力；空 Account 全 scope 导出导入保留；Undo snapshot 仅 in-memory、session lock 清除；见 `docs/PHASE4_P8_REPORT.md`）
  - [x] **PA Provider & Account Full Management**（Provider create/rename/delete；Account create/rename/move/merge/delete；见 `docs/PHASE4_PA_REPORT.md`）
  - [x] **DAILY-USE READY 里程碑**（定义见 ROADMAP.md §10；中间里程碑：可迁移并开始日常自用）
  - [x] **V2.0 FEATURE COMPLETE 里程碑**（定义见 ROADMAP.md §10.1；正式产品范围全部完成，不等于 DAILY-USE READY）
    - **V2.0 FEATURE COMPLETE = YES**（最终 release-readiness 审计确认，见 ROADMAP.md §10.1）；**V2.0 RELEASED = NO**（Android production signing identity 已 provision；仍待 Update Ed25519 provisioning / rescueauth-updates 基础设施 / signed release real-device smoke / FTL，见 `docs/RELEASE_PROVISIONING.md`）
- [x] 阶段 5：Migration（legacy import 收口，**CLOSED**）
  - [x] **5A Legacy v1 Core Adapter**（`LegacyVaultSnapshotMapper`：解密后 `LegacyImportBundle` → shared `VaultSnapshot`；**durable-id-first** 确定性 stableId（`legacy:<kind>:<sha256(legacy\0kind\0durablePath)>`，基于 legacy durable UUID，不依赖 source fingerprint）+ source fingerprint（原始加密字节 sha256，base64url，仅作 source identity）；五类 Developer 逐字段映射（keystore exact byte round-trip）；复用 `PackageValidator.validateSnapshot`（纯 logical）/ `MergePlanner` / `applySnapshot`；Legacy 防御上限与 Native 16 MiB 解耦（输入 64 MiB）；独立 Python-provenance fixture + frozen v1 producer fixture；跨备份幂等 + 隔离 + logical/capacity 边界测试；见 docs/PHASE5A_REPORT.md；已 merge #29）
  - [x] **5B Legacy v1 Android Import UI**（`LegacyImportService` / `LegacyImportViewModel` / `LegacyImportRoute` / `LegacyImportScreen`；SAF OpenDocument + 64 MiB bounded read（decrypt 前拒绝）；Master Password（无 `>=10` 硬编码 gate，与 Native PIN 独立）；safe preview（无 secret）；MergePlanner / final re-plan / transactional apply 复用 shared engine；跨备份幂等 UI→DB 贯通；ImportRecord `sourceType="LEGACY_RAKVAULT"` + source fingerprint；session/plaintext lifecycle；`VaultRepository` 移除 `LegacyImportBundle` spike 依赖；见 docs/PHASE5B_REPORT.md；已 merge #31）
  - [x] **M2 Developer 数据处理（legacy）**（已并入 Phase 5B §12：Legacy v1
    Developer Vault 五类全部正常迁移并持久化，不降级为只读 secure note、不默认
    跳过；P6 只补 Signing Key / Env Var Set 的 Android CRUD/UI，不是补
    migration capability）
- [ ] 阶段 6：Product polish（L1/L2 CLOSED；L3 DEFER）
  - [x] L1 Localization（en + zh-CN；完整双语为 V2.0 FEATURE COMPLETE 门槛，最终审计已确认 en/zh 全量 key + format 对齐）
  - [x] **L2 About / Update Check（V2.0 FEATURE COMPLETE 门槛）**（Issue #20 Phase 6 L2，已 merge #36：About 页 + Settings→About 导航 + 固定 CNB 清单 + Ed25519 验签（BouncyCastle，签名覆盖 exact raw bytes，`.sig` Base64 原始 64-byte）+ schema v1 strict validation + severity/minSupported 收敛 + manual only + update data fail closed / app fail open + INTERNET 显式声明 + UI 状态机/error taxonomy 冻结 + core/app/UI tests；发布基础设施 `rescueauth-updates` 仓库 + 生产 update 公钥 provisioning 未完成，见 docs/UPDATE_PROTOCOL.md §Release Infrastructure Pending 与 docs/PHASE6_L2_REPORT.md）
  - [ ] L3 Clipboard / security polish（含 DEFER 项 clipboard auto-clear；不阻塞 DAILY-USE READY 与 V2.0 FEATURE COMPLETE）

## 关键决策索引

- `docs/ADRS/ADR-0001-legacy-import-frozen-baseline.md`
- `docs/ADRS/ADR-0002-xchacha20-native-bc185.md`（BC 1.85 原生 XChaCha20-Poly1305，移除自实现 HChaCha20）
- `docs/ADRS/ADR-0003-database-vaultkey-session.md`（数据库加密、VaultKey 分层、安全会话生命周期、16KB；§2 备份部分已被 Phase 3 reset 取代）
- `docs/ADRS/ADR-0004-stable-identity-fingerprint.md`（stable record identity + semantic fingerprint，Phase 3A）
- `docs/ADRS/ADR-0005-merge-first-planner.md`（merge-first import + 纯 merge planner，Phase 3A）
- `docs/ADRS/ADR-0006-sensitive-action-reauth.md`（Sensitive Action Re-authentication，正式产品能力）
- `docs/ADRS/ADR-0007-portable-package-codec.md`（v2 Portable Package 加密 Codec，Phase 3B：per-export PIN → Argon2id → KEK → wrap PackageKey → AEAD envelope；header-is-untrusted / KDF accepted range / AAD / 错误分类 / best-effort zeroization）
- `docs/ADRS/ADR-0008-phase3c-transactional-apply.md`（Phase 3C：MergePlan → 单 Room 事务 apply + rollback + 幂等；Developer persistence schema v2→v3；parent/child identity mapping；CONFLICT / Recovery divergence 保守阻止；applyMergePlan / applySnapshot 共享事务边界）
- `docs/ADRS/ADR-0009-phase3d-android-export-import.md`（Phase 3D：Android SAF Export/Import、per-export PIN Product Policy、bounded untrusted-file reader、import preview/confirm、`.rakpkg` contract）
- `docs/ADRS/ADR-0010-legacy-stable-identity-source-fingerprint.md`（Phase 5A CR：durable-id-first stableId（基于 legacy durable UUID，不依赖 source fingerprint）+ source fingerprint 仅作 source identity + Legacy 防御上限（64 MiB，与 Native 16 MiB 解耦）+ logical validator 边界 + frozen v1 producer fixture）
- `docs/ADRS/ADR-0011-sensitive-action-reauth-oneshot.md`（Phase 4 P4：Sensitive Action Re-auth one-shot 语义——成功 re-auth 只授权恰好一个 pending action 并立即消费；无 freshness window / 无全局 authenticated）
- `docs/ADRS/ADR-0012-selective-export-import.md`（Phase 4 P5：Selective Export / Import —— 共享纯 Kotlin selection engine + Export 四 scope + decoded-snapshot 内存过滤 import + selected conflict 语义；package envelope/crypto/MergePlanner 语义零改动）
- `docs/ADRS/ADR-0013-developer-vault-completion.md`（Phase 4 P6：keystore size boundary 由共享 logical/package per-asset contract 推导为 raw bytes（`MAX_KEYSTORE_BASE64_LENGTH/4*3`），不定义第三套规则；env-var fresh re-auth target 绑定 stableId + 不可变 field key `var:<name>`，无 package-format 改动）
- **Phase 4 P7（Search + Pin）**：Global Search 用 **in-memory safe projection**（Room Flow → domain → 显式 safe `SearchDocument` → in-memory matching；无 plaintext index / FTS / AppSearch / payloadJson LIKE）；Matcher = Unicode-safe case-insensitive contains + multi-token AND；Pin scope = **Account only**，复用 `favorite` 兼容字段（`setPinned` → `setFavorite`），storage=`favorite` / product=`pinned`，无 Room/package schema 改动（见 `docs/PHASE4_P7_REPORT.md`）
