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
- 输出 `MergePlan`（account/totp/recovery-set 级别决策 + 结构化摘要），
  Phase 3C 在单个 Room 事务内执行；Phase 3D 用作 import 预览。

### 3. 决策规则

- INSERT：source-only。
- DUPLICATE：同 stableId+同内容，或不同 stableId+同 fingerprint；
  metadata 差异不构成冲突（保留 destination）。
- CONFLICT：同 stableId 但 secret / TOTP 参数 / recovery values 不同。
- UNCHANGED：destination-only，永不删除。

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
