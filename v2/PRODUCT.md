# PRODUCT.md — RescueAuth v2

## 产品目标

RescueAuth v2 是一个 **Android 原生的离线 2FA 与恢复资料库**（Kotlin + Jetpack Compose）。
核心目标：

1. 打开应用后尽快找到并复制验证码。
2. 用户始终能明确知道数据是否已备份。
3. 手机仍在时，可无密码迁移到新设备。
4. 手机丢失时，可用恢复套件解密离线备份。
5. 可一次性导入旧 RescueAuthKit（`.rakvault`）数据库。

## 核心流程

- **解锁**：Android 系统生物识别 / 设备凭据（无应用主密码）。
  - 认证器按设备实际可用性动态选择（`resolveAvailableAuthenticators`）；
    `DEVICE_CREDENTIAL` 路径由系统 UI 提供取消，不设 negative button；
    无可用认证器时提示恢复流程，不静默降级。
- **验证码**：首页直查 TOTP，搜索 + 收藏 + 复制 + 倒计时。
- **备份**：手动/自动（每次变更、每日、每周、每月），备份状态首屏可见。
- **恢复**：新备份格式导入 / 恢复套件 / 旧库一次性导入。

## v1 必须包含

TOTP 增删改查 + otpauth URI + 二维码扫描；恢复码分组/已用状态；
加密备份（手动+自动）+ 保留策略 + 健康状态；新旧格式导入；
生物识别/设备凭据解锁；后台遮罩 + `FLAG_SECURE`；中英双语；
固定清单式更新检查 + APK 更新。

## v1 明确不做

- Developer API Key / SSH 私钥 / 签名文件等开发者密钥管理。
- Web / Windows / iOS 客户端。
- 用户账号、服务器同步、多人协作。
- 依赖 GitHub/CNB Release API 的运行时版本检查。
- 静默安装更新。
- 未经用户选择的明文导出。

## 旧库 Developer 数据策略

导入预览必须显示 Developer 数据数量，提供：
- 推荐：暂不导入，生成未导入报告，保留原始 `.rakvault`。
- 可选：转只读 `Legacy secure note`（不进主导航）。
禁止静默丢弃、禁止写入日志。

## 数据归属

- TOTP/恢复码/恢复密钥/导入数据 → 本地加密数据库（SQLCipher）。
- 非敏感偏好 → DataStore。
- 备份密钥 `BackupKey` 与本地 `VaultKey` 分离。

## 非目标（技术约束）

- 不自建服务器、不用自有域名/DNS/CDN。
- 更新源固定为公开 CNB 仓库 `xincy22/rescueauth-updates` 的原始文件地址。
