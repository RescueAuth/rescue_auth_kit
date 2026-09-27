# ADR-0006：Sensitive Action Re-authentication

- 状态：**Accepted**（产品决策，2026-08-07，Issue #17）
- 日期：2026-08-07
- 关联：ROADMAP.md §5.6、PRODUCT.md §核心流程、ADR-0003（认证有效期）

## 背景

Vault 解锁（Biometric / Device Credential → Keystore → VaultKey →
SQLCipher）建立了**日常会话**。但若干高敏感操作即使在会话内也属于
"长期 secret 的暴露/输出"——若仅凭一次解锁即可执行，攻击者在用户
解锁后短暂拿到设备的窗口内即可导出全部数据。

产品决策要求：对高敏感操作，即使 Vault 已解锁，也要一次 **fresh
Biometric / Device Credential** 认证。

## 决策

1. **独立正式能力**：Sensitive Action Re-authentication 是产品能力，
   **不隐含在普通 unlock 中**，不作为普通会话解锁的副作用。
2. **覆盖操作（至少）**：
   - Export entire Vault Package
   - Export selected sensitive package（含 keystore / SSH private key /
     API secret 的 section）
   - Export Android keystore file
   - reveal SSH private key
   - reveal API secret
   - reveal signing storePassword / keyPassword
   - 其他等价的高敏感长期 secret（Roadmap 按类型随 Developer slice 落实）
   - 2026-09-26 补充：进入五类 Developer Entry 完整编辑器，必须在读取 / 预填敏感值前独立 fresh re-auth；即使只计划修改元数据也适用。新建空白条目不增加认证。
3. **不覆盖**：TOTP 查看/复制（日常高频）、普通 metadata 查看——这些
   仍走普通解锁会话。
4. **实现要求**：
   - 复用 Phase 2 认证基础设施（`resolveAvailableAuthenticators`、
     `BiometricPrompt`、Keystore 认证有效期）。
   - re-auth 成功后仅放行该次操作，不得隐式延长/扩展整个会话。
   - 失败/取消必须中止操作，不得降级为无认证执行。
5. **Roadmap 归属**：基础设施接入随 Phase 4 P4（首批 Developer slice）
   落实；完整覆盖全部敏感操作随 P4–P6 各 slice 完成；Export 类操作随
   Phase 3D 落实。

## 后果

- 好处：限制解锁后短暂窗口内的数据外泄面；对长期 secret 的 reveal /
  导出建立独立安全门槛。
- 代价：Export / reveal 流程多一次系统认证交互；需要为敏感操作统一
  re-auth gate（可复用组件）。
- 不可逆点：无（属于产品行为契约，不在数据格式/加密层面）。
