# AGENTS.md — RescueAuth v2 维护契约

> 面向所有 AI / 人类维护者的执行规则。违反以下任何一条都属于违约提交。
> 产品范围与路线图：`PRODUCT.md`（范围） / `ROADMAP.md`（路线图） /
> 本文（阶段进度）。三处不一致视为文档违约。

## 项目状态

- v2 是**全新 Android 原生应用**（Kotlin + Jetpack Compose + Room/SQLCipher），
  与旧 Flutter 项目并行存在。旧项目保留在仓库根目录，冻结于 tag `v1.2.0`。
- **产品定位（2026-08-07，Issue #17 定稿）**：v2 = Android-only、local-first、
  encrypted personal security vault，包含三大正式能力：
  **Authenticator（Provider/Account/TOTP/Recovery Codes）**、
  **Developer Vault（五类 Developer Entry，完整保留 v1.2.0，不再视为 removed）**、
  **Portable Vault Package（manual export、per-export PIN、merge-first import）**。
- v2 代码位于 `v2/` 目录；`databaseSchemaVersion`（Phase 3A 已升 **2**，Phase 3C 已升 **3**），
  `packageFormatVersion = 1`（PACKAGE_FORMAT.md）。
- 当前阶段：**Phase 0/1/2 CLOSED**；**Phase 3 STARTED，3A/3B/3C CLOSED，3D IMPLEMENTED / PR OPEN**；**Phase 4 P1/P2/P3 CLOSED，P4 PR OPEN，P5 IMPLEMENTED / PR OPEN**；**Phase 5A（Legacy v1 Core Adapter）CLOSED（已 merge #29，含 merge 前 CR 修复：durable-id-first stableId / Legacy 防御上限 / frozen v1 producer fixture），M1/M2 NOT STARTED**（见 `docs/PHASE5A_REPORT.md`）
  （Package + Merge Foundation，PR #18；Encrypted Package Codec，PR #22；
  Transactional Import / Merge Apply，见 `docs/PHASE3_REPORT.md` §11 /
  `docs/ADRS/ADR-0008`；Android Export / Import + Package Preview，见
  `docs/PHASE3_REPORT.md` §12 / PR body）。
  Phase 3A 已与 PR #19 最新
  PRODUCT / ROADMAP 对齐（Developer Vault 五类进入 portable logical
  schema + merge foundation；binary keystore base64；selective snapshot
  scope；Recovery used/unused divergence 显式输出）。
  **3B Encrypted Package Codec 已实现**（`core/.../export/codec/`，见
  `docs/PHASE3_REPORT.md` §9 / ADR-0007：per-export PIN → Argon2id → KEK →
  wrap PackageKey → AEAD envelope；header-is-untrusted / KDF accepted range /
  DoS 保护；golden fixture）。merge 前 CR 已修正 3 个 codec contract blocker：
  **cryptoVersion=1 的 `kdfOutputLength` 固定 32**（Argon2id 输出直接作为
  XChaCha20 KEK，旧 16..64 range 删除）、**logical ↔ package 容量一致**
  （`PackageCapacity` 单一来源 + validator 预算 + encode 显式 `PackageTooLarge`）、
  **RUNTIME DECODE RESOURCE POLICY 与 FORMAT HARD LIMIT 分离**（超预算在
  Argon2 前拒绝，默认 19 MiB/2 iter 永远兼容）。
  **Phase 4 P1 已实现**（TOTP Daily-Use Loop，见
  `docs/PHASE4_P1_REPORT.md`；真实 production storage）。
  **Phase 4 P2 已实现**（QR Scan + otpauth-migration Import，见
  `docs/PHASE4_P2_REPORT.md`：CameraX + ML Kit 扫码、独立纯 Kotlin migration
  adapter、多 QR batch session、repository batch import；IMPORT ONLY）。
  **Phase 4 P3 已实现**（Recovery Codes Daily-Use Slice，见
  `docs/PHASE4_P3_REPORT.md`：Account detail 恢复码页（正式 hierarchy）、batch
  add 多行粘贴 + preview、展开/收起、reveal-hide、单条 copy、Copy All /
  Copy Remaining、mark used/unused（remaining count 实时更新）、edit 最小
  diff 保留 stableId + USED state、delete + Undo 恢复 exact stableIds/states；
  Room schema 零改动，P3 数据自然进入 Full Vault Export/Import round-trip）。
  **Phase 4 P4 已实现**（Sensitive Action Fresh Re-auth + Developer Vault
  第一批，见 `docs/PHASE4_P4_REPORT.md`：`SensitiveAction`/`SensitiveActionRequest`/
  `SensitiveActionTarget`/`SensitiveActionGate`/`SensitiveActionResult` 单一
  orchestration path，fresh Biometric/Device Credential one-shot 语义（无
  freshness window / 无全局 authenticated），授权绑定原始 request
  （action + stableId + fieldKey），reveal 与 copy 完全分离、各自独立
  re-auth；Full Vault Export 在 PIN 之前接入 re-auth gate
  （cancel/failed/unavailable 不收集 PIN、不创建 SAF 文档、不构造
  snapshot）；Developer Vault 第一批
  API Credential / SSH Key / Generic Secret 全 CRUD（metadata list 不暴露
  secret、reveal/copy 走 re-auth、edit 保留 stableId、delete destructive
  confirmation）；Room schema 零改动，P4 数据自然进入 Full Vault
  Export/Import round-trip）。
  **Phase 4 P5 已实现**（Selective Export / Import，见 `docs/PHASE4_P5_REPORT.md`：
  共享纯 Kotlin 选择引擎 `VaultSnapshotSelector` / `SelectedItemSet`（stableId
  语义 + hierarchy/dependency closure，无 UI/Room 依赖，Export 与 Import 共用）；
  Export 四 scope —— Entire Vault / Authenticator / Developer / Selected Items，
  全部走同一 fresh re-auth（`SensitiveAction.EXPORT_PACKAGE`，scope+selection
  digest 绑定 + one-shot）+ 同一 per-export PIN Product Policy；Selective Import
  是 decoded-snapshot 内存过滤（不产生第二套 package/merge engine），
  Everything / Authenticator / Developer / Selected Items，仅对选中的 filtered
  snapshot 运行 MergePlanner；unselected conflict 不阻塞、selected
  conflict/divergence 按现有规则 BLOCK、final apply 重新 plan；Developer 五类
  完整保留（含 Android Signing Key / Env Var Set）；package envelope/crypto/
  MergePlanner 语义零改动；Room schema 零改动）。
  **Phase 3C 已实现**（Transactional Import / Merge Apply，独立 PR：
  MergePlan → 单 Room 事务 apply + rollback + 幂等；Developer Vault 五类
  首次真实落库（schema v2→v3 单表 `developer_entry` + typed payload）；
  parent/child identity mapping（ResolvedProvider/ResolvedAccount）；
  CONFLICT / Recovery state divergence 保守阻止 apply；`applyMergePlan` /
  `applySnapshot` 共享事务边界供 Native + future Legacy adapter 复用）。
  **Phase 3D 已实现**（Android Export / Import + Package Preview，独立 PR：
  SAF CreateDocument/OpenDocument、per-export PIN 对话框（输入+确认，
  隐藏/reveal）、bounded untrusted-file reader（`BoundedPackageReader`，
  纯 JVM 可测，16 MiB 上限）、PackageIdentifier（native magic 检查，
  Legacy 隔离）、safe import preview（无 secret 的 summary 模型）、
  confirm 走 Phase 3C `applyMergePlan`（re-plan/preflight/apply 最终 authority）；
  `.rakpkg` 扩展名 + MIME contract 同步 PACKAGE_FORMAT；Sensitive-action
  fresh re-auth 保持为 Phase 4 P4 依赖，本轮不伪造）。
  **Export PIN Product Policy 已锁定**（merge 前收尾）：Export = 纯数字
  6–128 位 + 确认一致，集中在 `PinPolicy`（Product Policy constant，非
  package-format requirement）；Import 只拒绝空 PIN（任意 codec 合法 PIN
  均可解密，历史/第三方包不被 Export UI 策略拒绝）；**流程为 PIN + confirm
  → CreateDocument → encode/write**，PIN cancel 不会创建文件，写失败
  best-effort 清理不 crash；PIN/decoded payload 不进 SavedStateHandle /
  Bundle / rememberSaveable / DataStore / Room，cancel / apply / lock 后
  best-effort zeroize。
  数据库 instrumented 验证已在 Firebase Test Lab 真实执行 6/6 PASS；
  生物识别/Keystore 认证有效期/截图保护等仍为**未真机验证**的验证缺口
  （non-blocking backlog，见 PHASE2_REPORT §C）。
- 阶段与 slice 定义见 `ROADMAP.md §5`；**不要再新增平行的阶段表**。

## 必跑命令（提交前）

```bash
# 生成/验证 legacy fixtures（仅阶段 0 工具，勿随意重跑）
cd tools/legacy_fixtures && dart run bin/generate_fixtures.dart verify

# v2 全量测试 + 构建（core + app JVM/Robolectric）
cd v2 && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug

# instrumented 测试 APK 编译（FTL/真机执行前的必要前置，需 Android SDK）
cd v2 && ./gradlew :app:assembleDebugAndroidTest
# 本地有真机/模拟器时：
cd v2 && ./gradlew :app:connectedDebugAndroidTest
# Firebase Test Lab（仅 main push，见 docs/FIREBASE_TEST_LAB.md）
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
5. **旧数据**：不得删除旧项目、旧 fixture、旧备份。

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
   keystore、reveal 长期 secret 等）即使 Vault 已解锁也要求 fresh
   Biometric / Device Credential；不得隐含在普通 unlock 中。
7. **删除 Undo**：普通删除走 SnackBar Undo；**不要**为了 Undo 恢复
   automatic checkpoint backup。

## 提交要求

- 每个阶段/slice 独立提交，message 前缀 `phaseN:` 或 `sliceN:`。
- 功能完成必须提供：测试结果、截图结果（若涉及 UI）、兼容性结果、剩余风险。
- 修改行为前先更新或补充测试（测试未通过不得进入下一阶段）。

## 阶段进度跟踪（source of truth: `ROADMAP.md §5`）

- [x] 阶段 0：冻结旧项目（tag `v1.2.0`）+ legacy fixtures + 映射文档
- [x] 阶段 1：最小 Kotlin/Android 工程 + Argon2id/XChaCha20-Poly1305 解密 spike
- [x] phase1-fix：entry-centric 映射 + 非法参数不静默替换 + BC 1.85 官方 XChaCha20
- [x] 阶段 2：数据库 schema v1 + VaultKey/Keystore + 串行 repository + 自动锁
  - [x] ADR-0003（VaultKey/Keystore/会话/16KB；§2 备份部分已被 Phase 3 reset 取代）
  - [x] Room + SQLCipher（Zetetic sqlcipher-android 4.17.0）+ schema v1 实体/DAO
  - [x] VaultKey（Keystore 包装/解包）+ 安全会话状态机 + SessionManager（自动锁/后台）
  - [x] 串行 repository（Mutex + withTransaction）+ FLAG_SECURE
  - [x] JVM/Robolectric 测试（并发、锁定、超时、密钥失效、16KB page、真实 FLAG_SECURE、SQLCipher native loader 并发契约）
  - [x] phase2-blocker-hotfix（PR #6）：BiometricPrompt 崩溃修复 + instrumented 可编译 + 真实断言
  - [x] SQLCipher native 加载（PR #15）：`SQLCipherNativeLoader` 在数据库唯一入口加载
  - [x] **instrumented 真机验证（数据库 6 用例）**：6/6 PASS（Firebase Test Lab）
  - [ ] 生物识别 / Keystore 认证有效期 / 截图保护 / 锁屏行为等**仍待真机验证**（non-blocking）
- [ ] 阶段 3：Package + Merge（**STARTED**）
  - [x] **3A Package + Merge Foundation**（architecture reset + 逻辑 package 模型 + stableId + semantic fingerprint + 纯 merge planner + schema v1→v2 + 自动备份抽象清理，PR #18）
  - [x] **3B Encrypted Package Codec**（per-export PIN → Argon2id → KEK → wrap PackageKey → AEAD envelope；header-is-untrusted / KDF accepted range / DoS 保护；wrong PIN/corrupted 安全失败；golden fixture；Phase 3B PR OPEN；merge 前 CR 已修：kdfOutputLength==32、capacity 一致、runtime policy 与 format limit 分离）
  - [x] **3C Transactional Import / Merge Apply**（MergePlan → 单 Room 事务 apply + rollback + 幂等；Developer Vault 五类落库（schema v2→v3）；parent/child identity mapping；CONFLICT / Recovery state divergence 保守阻止；`applyMergePlan` / `applySnapshot` 共享事务边界；见 ADR-0008 / PHASE3_REPORT §11）
  - [x] **3D Android Export / Import + Package Preview**（SAF CreateDocument/OpenDocument、per-export PIN 对话框、bounded untrusted-file reader、import preview（Authenticator/Developer 计数 + safe summary）、confirm 走 3C transactional apply；`.rakpkg` 扩展名/MIME contract；见 PHASE3_REPORT §12）
- [ ] 阶段 4：Daily-use vertical slices
  - [x] **P1 TOTP usable loop**（otpauth paste / manual / countdown / copy / delete+Undo，真实 production storage，PR 见 docs/PHASE4_P1_REPORT.md；QR 后续补）
  - [x] **P2 otpauth-migration import**（IMPORT ONLY，External Import Adapter，见 docs/PHASE4_P2_REPORT.md）
  - [x] **P3 Recovery Codes slice**（Account detail 恢复码页：batch add / expand-collapse / reveal-hide / 单条 copy / Copy All + Copy Remaining / mark used-unused / edit（最小 diff 保留 stableId + USED state）/ delete + Undo；见 docs/PHASE4_P3_REPORT.md）
  - [x] **P4 Developer Vault 第一批 + Sensitive re-auth**（Sensitive Action Fresh Re-auth Foundation：`SensitiveAction`/`SensitiveActionGate`/`SensitiveActionResult` 单一 orchestration path + BiometricPrompt/Device Credential one-shot 语义 + Full Vault Export 接入；Developer Vault 第一批：API Credential / SSH Key / Generic Secret 全 CRUD / reveal-hide / copy / delete（destructive confirm）/ stableId-preserving edit；见 docs/PHASE4_P4_REPORT.md）
  - [x] **P5 Selective Export / Import**（同一 Merge Engine；见 `docs/PHASE4_P5_REPORT.md`：共享纯 Kotlin selection engine / Export 四 scope / decoded-snapshot 内存过滤 import / selected conflict 语义）
  - [ ] P6 Developer Vault 第二批（Android Signing Key / Env Var Set + Developer completion）
  - [ ] P7 Search + Pin
  - [ ] P8 Delete Undo 完善
  - [ ] **DAILY-USE READY 里程碑**（定义见 ROADMAP.md §10；中间里程碑：可迁移并开始日常自用）
  - [ ] **V2.0 FEATURE COMPLETE 里程碑**（定义见 ROADMAP.md §10.1；正式产品范围全部完成，不等于 DAILY-USE READY）
- [ ] 阶段 5：Migration（legacy import 收口）
  - [x] **5A Legacy v1 Core Adapter**（`LegacyVaultSnapshotMapper`：解密后 `LegacyImportBundle` → shared `VaultSnapshot`；**durable-id-first** 确定性 stableId（`legacy:<kind>:<sha256(legacy\0kind\0durablePath)>`，基于 legacy durable UUID，不依赖 source fingerprint）+ source fingerprint（原始加密字节 sha256，base64url，仅作 source identity）；五类 Developer 逐字段映射（keystore exact byte round-trip）；复用 `PackageValidator.validateSnapshot`（纯 logical）/ `MergePlanner` / `applySnapshot`；Legacy 防御上限与 Native 16 MiB 解耦（输入 64 MiB）；独立 Python-provenance fixture + frozen v1 producer fixture；跨备份幂等 + 隔离 + logical/capacity 边界测试；见 docs/PHASE5A_REPORT.md；PR OPEN，未 merge）
  - [ ] M1 Legacy Import UI 完整流程（与 Native Package Import UI 区分）
  - [ ] M2 Developer 数据处理（legacy）
- [ ] 阶段 6：Product polish
  - [ ] L1 Localization（en + zh-CN；完整双语为 V2.0 FEATURE COMPLETE 门槛）
  - [ ] L2 About / Update Check（V2.0 FEATURE COMPLETE 门槛）
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

