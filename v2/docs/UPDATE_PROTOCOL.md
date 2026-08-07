# UPDATE_PROTOCOL.md — 更新协议（草案）

> 状态：**Draft**（Roadmap 阶段 6 / L2 实现前定稿）。
> **2026-08-07（Issue #17 产品决策）**：Update Check 范围收敛为——
> **保留**：app version/about、check releases、open release externally；
> **不做**：self update installer、APK silent install、auto-download
> updater。用户确认后仅**跳转到发布页**，由用户自行安装。

## 结论

- 应用运行时**不访问** GitHub/CNB Release API。
- 更新源为公开 CNB 仓库 `xincy22/rescueauth-updates`（main 分支）的原始文件：
  `https://cnb.cool/xincy22/rescueauth-updates/-/git/raw/main/android/stable/latest.json`
- 该仓库的组织名、仓库名、默认分支、公开状态属于更新协议的一部分，
  不得删除/改名/转私有。

## Manifest（latest.json）

```json
{
  "schemaVersion": 1,
  "channel": "stable",
  "versionName": "1.0.1",
  "versionCode": 10001,
  "minSupportedVersionCode": 10000,
  "publishedAt": "ISO-8601",
  "apkUrl": "https://<CNB-Release-附件实际下载地址>/rescueauth-1.0.1.apk",
  "apkSizeBytes": 12345678,
  "apkSha256": "hex",
  "releaseNotesUrl": "https://cnb.cool/xincy22/rescueauth-updates/-/git/raw/main/android/stable/1.0.1.html",
  "severity": "NORMAL"
}
```

## 签名与密钥

- `latest.json.sig`：Ed25519 对 manifest 签名，公钥编译进应用。
- 私钥放 CI 密钥管理，禁止写入仓库/日志/构建产物。
- CI 检测空/非 HTTPS/无法匿名读取的 `UPDATE_MANIFEST_URL` 时拒绝出稳定版。

## 客户端流程（L2 范围）

1. 设置页手动"检查更新"（**无** WorkManager 自动检查）。
2. 验证 manifest 签名 → 比较 `versionCode`（不比版本字符串）。
3. 有更新时显示版本信息与 release notes，提供"打开发布页"。
4. 用户点击后由系统浏览器/外部打开 `releaseNotesUrl` / `apkUrl`；
   **应用不下载、不校验、不调起安装器**（不做 self update / silent
   install / auto-download）。
5. 更新服务故障不锁死离线应用；`severity` 安全更新也只强提示。

## 发布流水线

1. 格式化/静态检查/单测/截图测试。
2. 构建 release AAB/APK。
3. 长期固定 signing key 签名。
4. 验证签名证书指纹、包名、versionCode。
5. 生成 SHA-256、release notes、`latest.json` + 签名。
6. 上传带版本 APK（禁止覆盖历史 APK）。
7. 从托管地址重新下载并校验。
8. **最后**原子更新 `stable/latest.json`。
9. 保留最近至少 3 个稳定版本。

## 回滚规则

- 旧版可安装回滚（保留历史版本 APK 与对应 manifest 历史）。
- `minSupportedVersionCode` 用于拒绝过旧版本。

## 待定项（阶段 6 / L2）

- 创建 `rescueauth-updates` 仓库并配置跨仓发布权限。
- 确认发布页跳转 URL 形态（release page 或 `releaseNotesUrl`）。
