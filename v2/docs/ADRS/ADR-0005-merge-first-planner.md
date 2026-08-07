# ADR-0005：Merge-first import 与纯 merge planner

- 状态：**Accepted**（Phase 3A）
- 日期：2026-08-07
- 关联：PACKAGE_FORMAT.md §Merge、PHASE3_REPORT.md §5

## 背景

旧模型隐含“restore = 覆盖当前 Vault”。Phase 3 reset 后：
Export Package 是可合并数据包，Import 默认语义必须是
decrypt → parse → validate → deduplicate → detect conflicts →
**transactionally merge**。Restore 只是 empty Vault + package → merge。

## 决策

### 1. 统一数据路径

Backup / Restore / Migration / Merge 不发展成四套独立逻辑，全部收敛到：

```
Export Package → Import Package → Merge Engine
```

### 2. 纯 merge planner（Phase 3A 实现）

`MergePlanner` 是纯 deterministic 函数：

```
plan(destination: VaultSnapshot, source: VaultSnapshot): MergePlan
```

- 不触碰数据库、不写盘、无随机、无时钟依赖。
- 匹配顺序：stableId 优先，fingerprint 次之（account 用 fingerprint 分组）。
- 输出 `MergePlan`（account/totp/recovery-set/developer 级别决策 + 结构化
  摘要），Phase 3C 在单个 Room 事务内执行；Phase 3D 用作 import 预览。
- **Developer Entry（本轮纳入）**：每类至少具备保守的 insert / duplicate /
  conflict 基础语义（同 stableId + FULL LOGICAL PAYLOAD 完全一致 → dup；同
  stableId + 任意 user-meaningful logical field 不同 → conflict；**不同
  stableId → insert / keep both**；不做跨 stableId 指纹 dedupe）。FULL
  LOGICAL PAYLOAD 覆盖全部用户语义字段（title / notes / projectName /
  packageName / serviceName / accountName / keyName / env variable names /
  generic field labels 等），不仅是敏感 payload；`createdAt` / `updatedAt`
  等纯技术 metadata 排除。
- **Recovery used/unused divergence（本轮新增）**：set 级决策不变，但
  per-code 的 used/unused 差异以 `RecoveryCodeStateDivergence` 显式输出、
  `MergeSummary.stateDivergences` 计数，绝不静默保留/覆盖（用户状态，
  不是纯 metadata）。

### 3. 决策规则

- INSERT：source-only。
- DUPLICATE：同 stableId+同内容，或（仅限 TOTP / recovery set）不同
  stableId+同 fingerprint；metadata 差异不构成冲突（保留 destination）。
  **Developer Entry 不做跨 stableId 指纹 dedupe**。
- CONFLICT：同 stableId 但 secret / TOTP 参数 / recovery values /
  **Developer 任一用户语义字段（FULL LOGICAL PAYLOAD）**不同。
- UNCHANGED：destination-only，永不删除。
- stateDivergence（额外信号）：同 recovery set 内 used/unused 差异 →
  显式报告，不改变 set 决策。

### 4. 强制不变式

1. destination-only 数据绝不因 source 缺少而删除；
2. 同一 package 重复 import → 第二次尽可能 no-op；
3. idempotent、deterministic；
4. 无法判断 → 宁可 conflict / keep-both，不误删；
5. parent/child 关系保持；
6. 写库必须 transactional（Phase 3C，rollback-on-failure）。

### 5. 验证

上述 12 条强制语义 + 14 个 merge planner 测试在 `:core` 纯 JVM 锁定，
不依赖 Android / Room。

## 后果

- 好处：merge 语义可在 JVM 完整验证；3C/3D 只需执行已有计划。
- 代价：planner 与 Room apply 分离，需额外 mapping（Phase 3C）。
