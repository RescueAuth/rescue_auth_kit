# PRODUCT.md — 拾遗坊 / RescueAuth v2

> 状态：**正式范围已完成，进入 1.0.0 发布准备**（2026-09-27 核对；
> 产品范围沿用 2026-08-07 Issue #17 决策）。尚未正式发布。
> 本文定义 v2 的**产品范围**；路线图见 `ROADMAP.md`；阶段进度见
> `AGENTS.md`。三处不一致视为文档违约。

## 产品定位

产品中文名为**拾遗坊**，英文名为 **RescueAuth**；仓库名仍为 `rescue_auth_kit`。
v2 表示原生重写代际，首个原生版本为 `1.0.0`。产品是
**Android-only、local-first、encrypted personal security vault**，完整产品包含三个
**正式产品能力**：

```
A. Authenticator          —— Service Provider / Account / TOTP / Recovery Codes
B. Developer Vault        —— 五类 Developer Entry（完整保留 v1.2.0）
C. Portable Vault Package —— backup / migration / selective transfer / vault merge
```

界面的入口名称为“账户 / Accounts”“开发者 / Developer”和“设置 / Settings”。
Authenticator 仍作为内部领域名称。发布准备侧重生产更新配置、候选包验证与必要缺陷修复；
门槛和证据统一见 [发布检查清单](docs/RELEASE_PROVISIONING.md#release-readiness)。

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
  create/view/edit/delete/copy。可用字段保存用户名 / 密码；当前不包含系统自动填充或 Passkey 管理。

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
- 非敏感 UI 偏好 → Preferences DataStore（主题色，以及浅色 / 深色 / 跟随系统）；敏感数据、搜索
  query、PIN、明文 payload 与 Undo snapshot 不得写入 DataStore。
- 跨设备迁移只通过 **manual Export Package**：每份包由独立 per-export
  PIN 派生 key 保护；PIN 不保存到 Vault、不改变 VaultKey。
- **不存在** BackupKey / 全局 backup password / 永久 master password。

## 非目标（技术约束）

- 不自建服务器、不用自有域名/DNS/CDN。
- 更新源固定为 GitHub `RescueAuth/rescue_auth_kit` 的 `android-stable` Release assets
  （详见 `docs/UPDATE_PROTOCOL.md`）；不做 self-update 安装。
- Portable Package format 保持 platform-neutral（不依赖 Android API /
  Room 表示），但当前 Roadmap 不为其他平台安排客户端开发。

## 2026-10-05：GitHub 迁移与版本边界

代码主仓库已迁至 `RescueAuth/rescue_auth_kit`，主分支迁移基线为
`01f007040b705c80f79a5a7f4a2caa77ea04a043`；legacy 标签完整保留。
`1.0.0` 仅发布现有 Android 功能，不包含 MCP。用户已报告 Debug 真机验证，
设备信息与生产签名候选包验收尚未记录，不能据此宣布正式发布。

`1.1.0` 在 `1.0.0` 发布后开发 Android 审批端与独立桌面 Bridge：
配对客户端及账户授权、单次 TOTP 请求与手机 fresh re-auth、当前验证码
及有效期返回；另支持 Agent 提交新增内容，手机预览确认后按现有校验、
去重规则写入（账户、TOTP、恢复码、五类 Developer 条目）。默认不开放
修改、删除、批量导出或无人值守授权。请求中的凭据不写日志；MCP 客户端
可能保留工具输入输出，必须在产品设计中明确此边界。
Bridge 不复制完整保险库，不构成桌面保险库客户端或云同步。
此前 Android-only / 不安排 companion 开发的描述限定于 `1.0.0`；
本节是已批准的 `1.1.0` 后续范围，尚未实现。

用户于 2026-10-05 明确批准更换首次原生发布的 Android 生产签名密钥。
新公开指纹见 `release/android-signing-certificate.txt`，私钥仅保存在
仓库外及 GitHub `production` 环境 Secrets。应用 ID、版本、加密与备份格式
保持不变；历史签名包不保证覆盖升级，旧私钥保留。

2026-10-05 补充授权：更新源迁至 GitHub `RescueAuth/rescue_auth_kit` 的
`android-stable` Release assets，APK/说明使用 `rescueauth-vX.Y.Z` Release。
独立 Ed25519 更新密钥已 provision；公开 pin 在 `release/update-public-key.txt`。
该状态表示配置完成，端点上线、签名包和更新端到端验收仍未完成；旧 CNB
资源保留。用户同时授权本任务后续 PR 由 Agent 自行审查、测试并合并，
覆盖此前工作流人工审阅约束，不豁免发布验收与凭据保护。

2026-10-05 验收更新：GitHub 全量回归 420 core + 844 app 通过；
`a685304` 的 Firebase API 33 完整矩阵 **157/157 PASS**，见
`docs/RELEASE_PROVISIONING.md §19`。该结果是 Debug instrumentation 验收，
生产签名包真机验收、密钥独立备份确认及稳定更新渠道上线仍待完成；
`V2.0 RELEASED = NO`，MCP/Bridge 仍安排在 1.0.0 发布后的 1.1.0。
