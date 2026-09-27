# PRODUCT.md — RescueAuth v2

> 状态：**正式**（2026-08-07，Issue #17 产品决策落定；2026-08-27 与当前
> 实现状态同步）。
> 本文定义 v2 的**产品范围**；路线图见 `ROADMAP.md`；阶段进度见
> `AGENTS.md`。三处不一致视为文档违约。

## 产品定位

RescueAuth v2 是 **Android-only、local-first、encrypted personal
security vault**。它不是单纯的 TOTP Authenticator。完整产品包含三个
**正式产品能力**：

```
A. Authenticator          —— Service Provider / Account / TOTP / Recovery Codes
B. Developer Vault        —— 五类 Developer Entry（完整保留 v1.2.0）
C. Portable Vault Package —— backup / migration / selective transfer / vault merge
```

> ⚠️ **已废弃的旧假设**：本仓库曾存在“Developer Vault 在 v2 被
> intentionally removed”的说法。该假设**已废止**：Developer Vault 五类
> 条目全部 KEEP，是正式产品能力。

## 产品目标

1. 打开应用后尽快找到并复制验证码。
2. 用户始终能明确知道数据何时通过手动 Export Package 导出一份。
3. 手机仍在时，无需全局 Master Password 即可迁移到新设备；每份 manual
   Export Package 仍必须设置独立 Export PIN，再通过 merge import 导入。
4. 手机丢失时，用新设备导入最新 Export Package 恢复数据（需要该份包
   的 Export PIN）。
5. 可一次性导入旧 RescueAuthKit（`.rakvault`）数据库。

## 核心流程

- **解锁**：Android 系统生物识别 / 设备凭据（无应用主密码）。
  - 认证器按设备实际可用性动态选择（`resolveAvailableAuthenticators`）；
    `DEVICE_CREDENTIAL` 路径由系统 UI 提供取消，不设 negative button；
    无可用认证器时提示恢复流程，不静默降级。
  - 安全模型：`Biometric/Device Credential → Android Keystore → VaultKey
    → SQLCipher Vault`（Phase 2 已定稿，保持）。
- **验证码**：首页直查 TOTP，倒计时 + 复制。
- **迁移/备份**：**仅手动** Export Package。每次导出为用户选择的这一份
  数据包设置独立 Export PIN（不保存到 Vault、不改变 VaultKey、不参与
  本机解锁）。导入为 **merge-first**（不是覆盖恢复）。
- **恢复**：新导出包导入 / 旧库一次性导入。
- **高敏感操作**：即使 Vault 已解锁，Export、export keystore、reveal
  SSH private key / API secret / signing 密码等操作要求一次 fresh
  Biometric / Device Credential 认证（Sensitive Action Re-authentication）。
  进入五类 Developer Entry 的完整编辑器同样需要独立验证，验证成功后才读取并预填既有敏感值；
  即使本次仅修改标题等元数据也适用。新建空白条目不增加该验证，编辑授权不复用于显示、复制或下一次编辑。

> Phase 3 明确不做：automatic backup、scheduled backup、background
> backup、WorkManager backup、cloud sync、自动上传、自动 checkpoint 文件。

## 首个原生正式版本必须包含

> 这里指新 Android 应用独立 release sequence 的 `versionName=1.0.0`；“v2”
> 是 generation/rewrite 名称，不代表 `versionName=2.0.0`。

TOTP 增删改查 + otpauth URI + 二维码扫描 + otpauth-migration 批量导入；
Provider/Account/Credential 层级与创建/rename/move/merge/delete；恢复码
分组/已用状态/批量添加/展开收起/复制全部/编辑/删除/移动；五类 Developer
Entry（Android Signing Key / API Credential / SSH Key / Env Var Set /
Generic Secret）的存储/查看/复制/导出；**手动**加密导出（per-export PIN
的 Export Package）+ merge-first 导入 + Selective Export/Import；
Global Search + Pin；Delete Undo；Sensitive Action Re-authentication；
生物识别/设备凭据解锁；后台遮罩；中英双语；固定清单式
更新检查 + 外部打开发布页。

> 注意：首个原生正式版本不包含 automatic/scheduled/background backup、保留策略、
> 备份健康状态、恢复套件、云同步、ssh-agent、DevOps 自动化。

## 首个原生正式版本明确不做

- Web / Windows / macOS / Linux / iOS 客户端（Android only）。
- 用户账号、服务器同步、多人协作、云同步。
- automatic / scheduled / background backup（仅 manual export）。
- 依赖运行时 API 的自动更新安装 / APK 静默安装 / 自动下载。
- 未经用户选择的明文导出。
- otpauth-migration export（仅 import）。
- SSH agent / SSH generation tool / API execution / DevOps automation /
  arbitrary file vault / social sharing。
- 恢复全局 Master Password。

## Developer 数据策略（KEEP，五类全保留）

Developer Vault 是正式产品能力，边界为 **secure storage / view / copy /
export**，不是 DevOps automation platform。

- **Android Signing Key**：projectName、packageName、keystore file
  contents、keystore filename、storePassword、keyAlias、keyPassword；
  create/view/edit/delete；reveal/hide；copy fields；export keystore；
  Copy key.properties-style properties。
- **API Credential**：serviceName、accountName、apiKey、apiSecret、
  title/notes；view/edit/delete/copy/reveal。
- **SSH Key**：keyName、publicKey、privateKey、passphrase、title/notes；
  view/edit/delete/copy/reveal。不扩展 ssh-agent / 生成 / 部署。
- **Environment Variable Set**：projectName、多个 KEY=VALUE；
  create/view/edit/delete/copy。
- **Generic Secret**：arbitrary label=value fields、title/notes；
  create/view/edit/delete/copy。

Legacy（`.rakvault`）导入时的旧 Developer 数据**完整迁移**：Legacy v1
Developer Vault 五类（Android Signing Key / API Credential / SSH Key /
Environment Variable Set / Generic Secret）全部正常导入并持久化到 SQLCipher
DB（经 Legacy mapper → shared `VaultSnapshot` → merge/apply），预览显示
数量与五类分项（仅安全 metadata），不降级为只读 secure note、不默认跳过、
禁止静默丢弃、禁止写入日志。P6 只补 Signing Key / Env Var Set 的 Android
CRUD/UI，不是补 migration capability。

## 数据归属

- TOTP/恢复码/恢复密钥/导入数据/Developer Entry → 本地加密数据库
  （SQLCipher）。
- 非敏感 UI 偏好 → Preferences DataStore（当前用于主题色）；敏感数据、搜索
  query、PIN、明文 payload 与 Undo snapshot 不得写入 DataStore。
- 跨设备迁移只通过 **manual Export Package**：每份包由独立 per-export
  PIN 派生 key 保护；PIN 不保存到 Vault、不改变 VaultKey。
- **不存在** BackupKey / 全局 backup password / 永久 master password。

## 非目标（技术约束）

- 不自建服务器、不用自有域名/DNS/CDN。
- 更新源固定为公开 CNB 仓库 `xincy22/rescueauth-updates` 的原始文件地址
  （详见 `docs/UPDATE_PROTOCOL.md`）；不做 self-update 安装。
- Portable Package format 保持 platform-neutral（不依赖 Android API /
  Room 表示），但当前 Roadmap 不为其他平台安排客户端开发。
