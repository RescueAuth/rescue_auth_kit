# ADR-0010：Legacy v1 import — 确定性 stableId 与 source fingerprint（Phase 5A）

- 状态：**Accepted**（Phase 5A，2026-08-09）
- 关联：`docs/PHASE5A_REPORT.md` §10/§11、`docs/LEGACY_IMPORT.md` §9、
  ADR-0004（stable identity）、ADR-0005（merge planner）

## 背景

Phase 5A 把 legacy `.rakvault` 解码为 shared `VaultSnapshot`，供现有
`MergePlanner` / `VaultRepository.applySnapshot` 复用。核心问题是：

1. legacy 对象有持久化 `id`（schema3 account/credential id、schema1/2
   entry id），但这些 id 在**跨文件**场景下不保证全局唯一 —— 直接把
   legacy id 当 v2 `stableId` 可能在不同 `.rakvault` 之间碰撞。
2. v1 的 `VaultMigrator` 迁移时会 `uuid.v4()` 生成新 id（
   `vault_migrator.dart`），导致**同一份文件重复导入产生不同 id** ——
   违反 ROADMAP “repeated import 不产生新逻辑对象”的要求。
3. `VaultRepository.importLegacy` 现有路径用 `UUID.randomUUID()` 生成
   id/stableId，同样不幂等。

## 决策

### 1. 确定性 stableId（legacy 命名空间）

每条逻辑记录派生：

```
stableId = "legacy:" + b64url(sha256(sourceFingerprint)) + ":" + kind + ":" + b64url(sha256(kind + "\0" + legacyObjectPath))
```

- `sourceFingerprint` = `b64url(sha256(原始加密 .rakvault 字节))`（§2）。
- `kind` = 记录类型（`account` / `totp` / `recovery_set` / `recovery_code` /
  `developer:<legacyType>`）。
- `legacyObjectPath` = 稳定对象路径/身份（如 `account:acc-1`、
  `totp:acc-1/cred-1`、`recovery:set-1/0`），**不是 secret**。

性质：

- **确定性**：同文件同对象 → 同 stableId（重复导入幂等）。
- **命名空间隔离**：不同 `.rakvault` 的相同对象 id 不碰撞。
- **不泄露明文**：仅 SHA-256 摘要，不含 secret / password / decrypted
  payload。
- 通过 shared MergePlanner：第二次导入同一文件 → 全 DUPLICATE / 无新增
  Developer keep-both 噪声。

### 2. Source fingerprint

```
fingerprintOfEncryptedBytes(bytes) = b64url(sha256(bytes))
```

- 基于**原始加密字节**，不含 password、不含 decrypted payload。
- 同字节文件 → 同 fingerprint；不同文件 → 不同。
- 不把 filename / Android Uri 当 identity。
- 供 Phase 5B `ImportRecord.sourceFingerprint` 使用（本轮不接线）。

### 3. 与 ADR-0004 的关系

- ADR-0004 的 stableId 语义（跨设备 lineage）继续适用于 Native package。
- Legacy 导入的 stableId 是**派生自 legacy 源 + 源指纹**的确定性值，不是
  新随机 UUID；这是 legacy 兼容层的**专属**策略，Native `.rakpkg` 不受影响。

## 后果

- **好处**：重复导入幂等；不同旧库互不干扰；可直接复用共享
  PackageValidator / MergePlanner / applySnapshot；删除 legacy 层不影响
  Native package。
- **代价**：stableId 比随机 UUID 长（多 1 个前缀段）；`sourceFingerprint`
  需要调用方传入（Phase 5B 从文件读取原始字节即可计算）。
- **不做**：本轮不写 `ImportRecord`、不做 UI、不修改 Native codec /
  MergePlanner / apply 路径。
