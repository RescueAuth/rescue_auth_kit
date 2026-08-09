# ADR-0012 — Selective Export / Import（Phase 4 P5）

状态：**Accepted**（2026-08-09，Issue #20 Phase 4 P5）。

## 背景

Phase 3A 起 `SnapshotScope` 已支持 `AUTHENTICATOR_ONLY` / `DEVELOPER_ONLY` /
`SELECTED_ITEMS`，3B codec 对所有 scope 一视同仁，3C/3D 提供 transactional
apply 与 Full Vault Export/Import。P5 的目标是把 Full Vault 流程扩展为
Selective Export / Import，**继续使用同一套**
`VaultSnapshot` → `PortablePackageCodec` → `MergePlanner` → transactional
apply，不创建第二套 package format / merge engine。

## 决策

### 1. 共享纯 Kotlin selection engine（core）

新增 `VaultSnapshotSelector` / `SelectedItemSet` / `SelectableItems`：
stableId 语义 + hierarchy/dependency closure，Export 与 Import 共用同一套
逻辑，避免“Export 写一套 parent closure、Import 再复制另一套”。

- 无 Android UI / Room entity 依赖。
- 输入/输出是 logical stableIds / `VaultSnapshot`。
- Provider 没有独立 stableId（portable schema 中 Provider 是 serviceName
  分组）；Provider 选择是 UI convenience，扩展为 account stableIds 后提交。

### 2. selection identity = stableId

不依赖 list index / title / account name / sort / Room row id。最终 export
snapshot 在 repository shared mutex + transaction 内重新解析 selection；
stale stableId → 明确失败，绝不静默导出/导入另一个对象。

### 3. hierarchy / dependency closure

- Provider → 全部 Account → 全部 TOTP/Recovery Sets；
- Account → 自身 + Provider parent metadata；
- single TOTP / Recovery Set → 自身 + Account + Provider；
- Recovery Set 与 Developer Entry 原子；
- “自动包含 parent”是 structural dependency closure，不附带 parent 的其它
  children。

### 4. 所有 package export 都要求 fresh re-auth

`SensitiveAction.EXPORT_FULL_VAULT` 最小 rename 为
`SensitiveAction.EXPORT_PACKAGE`；新增
`SensitiveActionTarget.ExportRequest(scopeName, selectionDigest)` 把授权绑定
到具体 scope + selection。auth 成功 → 只授权本次 pending export（one-shot，
不复用），无 auth cache。不 new 第二套 BiometricPrompt。

### 5. Selective import = decoded snapshot filtering

不改 ciphertext、不重新编码 package、不创建临时 package file；只在内存中对
decoded `VaultSnapshot` 做 `selected()` 过滤，然后只对 filtered snapshot 运行
MergePlanner。

- 未选中 item 的 conflict 不阻塞；
- 选中 item 的 conflict / recovery divergence 按现有规则 BLOCK；
- 不引入 source-wins / destination-wins / auto conflict resolution；
- final apply 重新 plan（stale preview plan 永不直接 apply）。

### 6. 其它

- 导出 PIN Product Policy 不变（ASCII digits 6–128 双次确认），不写入 package
  format；Import 仍只要求非空 PIN。
- ImportRecord 只在 successful apply 后记录，不改 Room schema。
- Native/Legacy 隔离保持：Native code 不引用 `com.rescueauth.v2.legacy`。

## 备选方案

- 为 selective import 设计第二个 package / 第二套 merge —— **拒绝**（违反
  ROADMAP §8.4，不产生第二套实现）。
- Export 与 Import 各自写一套 selection —— **拒绝**（重复、易漂移）。
- 为 selective import 做临时 package 文件 —— **拒绝**（plaintext 生命周期）。
- 引入 auto conflict resolution —— **拒绝**（冲突解决不在本轮）。

## 影响

- package envelope / formatVersion / cryptoVersion / Argon2id / XChaCha20 /
  PackageKey / 16 MiB / PIN 语义 / MergePlanner 语义 **零改动**。
- 旧 Full Vault package 继续正常 import（Everything 默认即全量）。
- `databaseSchemaVersion` 不变（3）；`packageFormatVersion` 不变（1）。
