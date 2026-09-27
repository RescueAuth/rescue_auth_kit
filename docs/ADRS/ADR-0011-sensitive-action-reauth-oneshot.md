# ADR-0011：Sensitive Action Re-auth one-shot 语义（Phase 4 P4）

- 状态：**Accepted**（2026-08-09，Issue #20）
- 日期：2026-08-09
- 关联：ADR-0006（Sensitive Action Re-authentication）、ADR-0003（认证有效期）、
  ROADMAP §5.6

## 背景

ADR-0006 定义了“对高敏感操作，即使 Vault 已解锁，也要求一次 fresh
Biometric / Device Credential 认证”。但 ADR-0006 没有定义 re-auth 成功后的
**授权有效期**（freshness window）。

产品上有两种可能：

- A. 短期窗口（例如 5 分钟）：窗口内同一类敏感操作不再要求认证；
- B. one-shot：每次成功 re-auth 只授权恰好一个 pending action，之后立即消费。

Phase 4 P4 之前没有任何 PRODUCT / ADR 正式定义 freshness window。

## 决策

**采用 one-shot 语义（B）**：

1. 每个 high-risk action 明确触发**一次新的** re-auth；
2. 成功 re-auth → 授权**恰好一个** pending sensitive action；
3. 授权在 `SensitiveActionGate.executePending` 中被立即消费，不产生可复用
   token / result；
4. 没有 5 分钟缓存、没有“刚才验证过所以都放行”、没有全局 `authenticated=true`；
5. 若未来 PRODUCT / ADR 正式定义 freshness window，可在 gate 内安全扩展
   （不改变 API 契约）。

**补充决策（2026-08-09 security-boundary CR）：授权必须绑定原始 target**

1. 授权对象不是裸的 `SensitiveAction` enum，而是不可变的
   `SensitiveActionRequest`（`action` + `SensitiveActionTarget`）；
2. `SensitiveActionTarget.DeveloperField(stableId, fieldKey)` 把操作绑定到
   具体 entry 的 stableId 与具体字段；`SensitiveActionTarget.Global` 用于
   Export 等无更细粒度的操作；
3. `executePending` 按完整 request（action + target）值相等才放行，
   因此 prompt 期间 current selection / navigation / field selection 改变或
   相同类型第二个请求到来，都不会把成功结果作用于其它 entry / field；
4. reveal 与 copy 是不同 action，reveal 授权绝不能复用于 copy
   （即使同 entry 同 field），copy 必须独立 fresh re-auth。

**补充决策（2026-09-26）：完整编辑器与回调生命周期**

- 五类 Developer Entry 的完整编辑器使用 `EDIT_DEVELOPER_ENTRY`，目标为 `DeveloperEdit(stableId, attemptId)`；每次进入生成新的内存 attemptId，不存入导航参数、SavedStateHandle 或数据库。
- `DeveloperFormViewModel.beginEdit` 在读取任何编辑内容前请求认证并消费授权；直接进入 / 恢复编辑路由同样验证。取消、失败、认证不可用均不预填表单；离开路由或锁定会话会清除表单并放弃在途加载。
- 验证授权一次完整的编辑流程；新一次进入编辑器必须重新验证。既有 reveal / copy 授权不能复用于编辑，编辑授权也不能复用于它们。新建空白条目不增加验证。
- gate 按 prompt generation 忽略旧 prompt 回调，校验成功结果与待处理 request 完全匹配，并在执行前复查会话。生命周期失效会通知等待方 `Cancelled`，避免编辑加载一直等待。

## 理由

- **最安全**：fresh re-auth 的目的就是限制解锁后短暂窗口内的数据外泄面；
  one-shot 把这个窗口缩到最小。
- **最简单**：无需维护时间戳 / 过期检查 / 类别白名单。
- **最可测**：行为确定（成功→恰好一个 action），race 语义清晰。
- 产品暂无 UX 理由需要 5 分钟窗口；如未来需要，成本是 gate 内部加一个
  `authorizedUntilElapsed` 字段，业务契约不变。

## 后果

- 好处：re-auth 放行面最小；实现与测试简单；错误边界明确
  （cancel/failed/unavailable 一律不执行）；授权绑定原始 target，杜绝
  跨 entry / 跨 field / reveal→copy 的授权混用。
- 代价：连续多个敏感操作会多次弹出系统认证（可接受，符合“每个 high-risk
  action 独立认证”的安全目标）。
- 不可逆点：无。
