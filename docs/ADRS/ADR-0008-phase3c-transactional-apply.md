# ADR-0008：Phase 3C 事务化 Import / Merge Apply

- 状态：**Accepted**（Phase 3C）
- 日期：2026-08-08
- 关联：ADR-0005（merge-first planner）、ADR-0004（stable identity）、
  PACKAGE_FORMAT.md §Merge、PHASE3_REPORT.md §11（3C 报告）

## 背景

Phase 3A 提供了纯 deterministic `MergePlanner`，Phase 3B 提供了加密
package codec，但 `MergePlan` 尚未真正写库。Phase 3C 的目标是把
`MergePlan` 以事务方式应用到本地 encrypted Vault，并让 **Developer Vault
五类条目**真正落库（此前 Room 只有 Authenticator 侧的表）。

## 决策

### 1. Apply 边界

```
VaultPackagePayload → PackageValidator → MergePlanner
        → MergePlanApplicator（单 Room 事务）→ local Vault
```

`MergePlanApplicator`（`app/.../repository/`）是唯一执行 `MergePlan` 的
边界。它只接收已校验的 logical snapshot + plan，**不 import legacy 类型、
不 import codec 字节/envelope、不碰 Android SAF/Uri**（ROADMAP §9 隔离，
`LegacyIsolationTest` 在 `:core` source 级锁定 shared 层）。

`VaultRepository.applyMergePlan(payload)` / `applySnapshot(snapshot)` 是
两个入口：前者是 Native Package Import 完整链路；后者是 future
Legacy / otpauth-migration adapter 复用同一 Merge Engine 的 shared
boundary（解析成 logical snapshot 后走同一事务路径）。

### 2. Developer persistence（schema v2 → v3）

- 新增单表 `developer_entry`（**方案 A：一个表 + typed payload**），
  `stableId` UNIQUE，`entryType` 索引，`payloadJson` 保存类型化逻辑载荷
  （JSON，平台中立 sealed serializer）。
- 理由：五类条目仅差 3–6 个类型字段；base table + per-type tables
  （方案 B）需要 5 组 FK/DAO/mapper，对全量 merge 无收益。
- Room 与 portable logical schema 分离：`DeveloperMappers` 是唯一桥梁，
  Room entity 永不进入 shared package/merge core。
- 安全：全部 secret（storePassword / keyPassword / keystoreBase64 /
  apiKey / apiSecret / privateKey / passphrase / env values / generic
  values）只存在于 SQLCipher DB 内；不创建明文 sidecar；不写日志。
- Migration：非 destructive，v2→v3 仅 `CREATE TABLE developer_entry` +
  索引；既有 Authenticator / Recovery / ImportRecord 数据与 stableId
  lineage 全部保留；SQLCipher / VaultKey 模型不变。

### 3. 事务语义

- 整个 apply 在**一个 Room transaction** 内（repository mutex + 
  `db.withTransaction`）。
- 任何中途失败 → 全量 rollback（不留下半个 Provider/Account/TOTP/
  Developer Entry，不留下错误 ImportRecord）。
- 成功 → 所有 INSERT 一次性落库；DUPLICATE 不重复插入；
  destination-only 数据绝不删除。
- 幂等：同一 package 重复 import 第二次全 DUPLICATE / no-op。
  **幂等性依赖 stableId / merge semantics，不依赖 ImportRecord**。

### 4. CONFLICT / divergence 策略（保守，不自作主张）

- 任何 unresolved CONFLICT → **不 apply**，返回 explicit `ImportOutcome.Blocked`。
- 任何 Recovery used/unused state divergence → **不 apply**，返回
  `ImportOutcome.Blocked`（`stateDivergences`）。不静默覆盖 / 不吞 source
  state / 不因 value 相同就 no-op。
- 禁止 source wins / destination wins / last-write-wins / 静默 overwrite。
- Phase 3D preview/UI 负责让用户确认冲突处理。
- 当前 MergePlanner 已定义的可自动 apply 语义（INSERT/DUPLICATE/
  metadata-only DUPLICATE）严格按其 contract 执行。

### 5. Parent / child identity mapping

- `ResolvedProviderMapping`：source account stableId → destination account
  stableId（children 归属的 destination）。
- `ResolvedAccountMapping`：source account stableId → destination Room id
  （实际写入 child 行的 FK）。
- 语义 dedupe 后：package 中 Account A 与 destination B duplicate → A 的
  新 TOTP/Recovery 插到 B 下；**不创建重复 Account A、不用 source Room id、
  不丢 parent relation**。

### 6. stableId 语义

- 真正 INSERT 的新对象保留 package/source stableId（lineage 跨
  export/import 保持），Room `id` 为本地 primary key（≠ portable
  stableId，ADR-0004）。
- 语义 DUPLICATE 用已有 destination 对象，不生成第二个 stableId。
- CONFLICT 不得自动改 stableId 伪装成新数据。

### 7. ImportRecord

- 只在事务成功时写一条 `V2_PACKAGE` record（metadata / source fingerprint
  = packageId / timestamp / counts）。
- rollback 时不留 record；不保存 PIN / plaintext secrets / 整个 decrypted
  payload。

### 8. Failure injection / rollback 测试

- `WriteSeam`（interface + `None`）是 repository/test dependency seam；
  生产无 debug-only failure 开关。
- 测试覆盖：Authenticator 成功后 Developer insert 失败 → 全量 rollback；
  半程失败 → DB 与 import 前完全一致；ImportRecord rollback。

### 9. Concurrency

- 继续遵守 `VaultRepository` 的 mutex 串行访问模型；两个并发 import 串行
  提交，不产生违反唯一约束的半完成状态。单进程 local vault 下
  transaction + repository serialization 即足够，不做 distributed locking。

## 后果

- 好处：Developer Vault 五类首次真实落库；Native / Legacy 共用同一
  transactional merge 路径；rollback / 幂等 / parent mapping 可测试。
- 代价：schema v2→v3 migration（1 表 + 2 索引）；`developer_entry` 的
  typed payload 由 mapper 专有（Developer UI 需要在 P4/P6 提供类型化读写）。
- 不可逆点：schema v3 一旦发布，后续变更需 migration。
