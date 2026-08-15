# UPDATE_PROTOCOL.md — 更新协议

> 状态：**Client Contract Final / Release Infrastructure Pending**
> （2026-08-10，Issue #20 Phase 6 L2 本轮实现后定稿）。
> 客户端侧契约已与本仓库生产代码（`core` / `app` 的 update 包）一致并冻结；
> 跨仓发布基础设施（`rescueauth-updates` 仓库、生产 Ed25519 密钥、签名发布
> 流水线）尚未 provision，见 §Release Infrastructure Pending。

## 1. 结论

- 应用运行时**不访问** GitHub/CNB Release API，不搜索 release，不抓取 HTML。
- 更新源为公开 CNB 仓库 `xincy22/rescueauth-updates`（main 分支）的原始文件：
  `https://cnb.cool/xincy22/rescueauth-updates/-/git/raw/main/android/stable/latest.json`
- 签名：同目录 `latest.json.sig`。
- 组织名 / 仓库名 / 默认分支 / 路径属于更新信任契约的一部分，不得删除、改名
  或转私有。
- 应用**只读取**固定 update manifest + signature；固定源必须 HTTPS；不允许用户
  输入 update URL。

## 2. 检查触发：manual only

- 唯一触发：用户在 About 页点击 **Check for Updates**。
- **不做**：app 启动自动检查、unlock 自动检查、periodic check、WorkManager、
  alarm、background service、push、notification polling。
- 本产品是 local-first security vault。更新基础设施故障永远不能影响：
  unlock、TOTP、Recovery、Developer Vault、export/import、offline use。

## 3. Manifest（schema v1，冻结）

```json
{
  "schemaVersion": 1,
  "channel": "stable",
  "versionName": "1.0.1",
  "versionCode": 10001,
  "minSupportedVersionCode": 10000,
  "publishedAt": "ISO-8601",
  "apkUrl": "https://<HTTPS-URL>/rescueauth-1.0.1.apk",
  "apkSizeBytes": 12345678,
  "apkSha256": "hex-64-chars",
  "releaseNotesUrl": "https://cnb.cool/xincy22/rescueauth-updates/-/git/raw/main/android/stable/1.0.1.html",
  "severity": "NORMAL"
}
```

### Strict validation（`core` `UpdateManifestParser`）

- `schemaVersion == 1`，否则 reject（UNSUPPORTED_SCHEMA）。
- `channel == "stable"`，否则 reject。
- `versionName`：非空白。
- `versionCode`：正整数。
- `minSupportedVersionCode`：正整数且 `<= latest versionCode`。
- `publishedAt`：合法 ISO-8601 offset datetime。
- `apkUrl` / `releaseNotesUrl`：必须 `https`（拒绝 `file:` / `content:` /
  `intent:` / `javascript:` / `data:` / `http:`）。
- `apkSizeBytes`：正整数且有界（`<= 5_000_000_000`）。
- `apkSha256`：恰好 64 个小写 hex 字符。
- 未知 additive 字段：忽略（向前兼容）。
- required field 缺失：reject。
- 关键安全字段不做 silent default。

### severity（本轮冻结）

- `NORMAL`：普通新版提示。
- `SECURITY`：更醒目的 "security update available" 提示。
- 即使 `SECURITY`：**不得**锁 Vault、禁止使用 App、自动下载、自动安装 ——
  只是更强推荐。

### minSupportedVersionCode（本轮收敛）

- `currentVersionCode < minSupportedVersionCode` → UI 显示更强的
  unsupported / outdated 警告，强烈建议升级。
- **不得**锁死本地 Vault，不得阻止 unlock，不得阻止查看/导出用户自己的数据。
- 本应用没有 server-side required protocol，update service outage 也不能让
  本地安全屋不可用。

## 4. 版本比较

- **只用** `versionCode`（单调递增整数）判断新旧。
- 不做 lexical compare versionName、不用 semver parser 作为 authority。
- 逻辑：
  ```
  latest.versionCode >  current.versionCode → UPDATE_AVAILABLE
  latest.versionCode <= current.versionCode → UP_TO_DATE
  ```
- `versionName` 只是 display metadata。

## 5. 签名与密钥

- `latest.json.sig`：Base64 编码的原始 64-byte Ed25519 签名；ASCII；允许一个
  尾部换行。
- 签名覆盖 `latest.json` 的**确切原始字节** —— 不是 parsed JSON、
  pretty-print 后 JSON、reserialized JSON、canonicalized field map。
- 这样 release pipeline 可以：write exact `latest.json` → sign exact bytes →
  publish same bytes；避免 JSON canonicalization ambiguity。文档与 tests 锁定
  此行为。
- 公钥编码：Base64 编码的原始 32-byte Ed25519 public key（与 BouncyCastle /
  JCA 实现匹配）。
- 公钥不是 secret；私钥是 release infrastructure secret。
- **严禁**：commit private key 到 repo、test private key 用作 production key、
  把 private key 写进 BuildConfig、自动生成 private key 后提交、把 private
  key 输出进 logs/docs。
- 公钥通过 Gradle `UPDATE_PUBLIC_KEY` 属性写入 `BuildConfig.UPDATE_PUBLIC_KEY`
  （release provisioning 时写入）。未配置时 update check 明确返回
  `NOT_CONFIGURED`（"Verification key not configured / Update verification
  unavailable"），仅更新检查失败，整个 Vault 继续正常工作。

## 6. 客户端验证流程（L2）

1. 设置页 → About → 手动 "Check for Updates"（**无** WorkManager 自动检查）。
2. 顺序：
   ```
   fetch manifest raw bytes (HTTPS, bounded ≤ 64 KiB)
   fetch signature (HTTPS, bounded ≤ 4 KiB)
   ↓
   validate sizes
   ↓
   Ed25519 verify EXACT manifest raw bytes
   ↓
   ONLY AFTER verification: parse JSON
   ↓
   validate schema/channel/URLs/version fields
   ↓
   compare versionCode
   ↓
   render UI
   ```
3. 签名必须在解析/信任 manifest 内容之前验证。**禁止**：parse untrusted
   manifest → 使用 URL/title/version → 之后才 verify。
4. 有更新时显示版本信息与 release notes summary（若协议支持），提供
   "Open Release Page"。
5. 用户点击后由系统浏览器/外部打开 `releaseNotesUrl`（经 Android external
   ACTION_VIEW；**不用 WebView**）。**应用不下载 APK、不校验 APK、不调起
   安装器**（不做 self update / silent install / auto-download）。
6. 更新服务故障不锁死离线应用；`severity` 安全更新也只强提示。

## 7. 网络边界

- manifest 中显式声明 `android.permission.INTERNET`（L2 网络成为产品功能，
  不再依赖 ML Kit / transitive manifest 碰巧带来的 INTERNET）。
- 不需要 `ACCESS_NETWORK_STATE`。
- 不增加 storage / install packages / `REQUEST_INSTALL_PACKAGES` /
  notification / background service permission。
- 固定源必须 HTTPS；拒绝 `http://`；不允许用户输入 update URL。
- HTTP client：显式 connect timeout、read timeout、cancellation-friendly、
  bounded response size、无 HTTPS→HTTP downgrade redirect、无 cookie、
  无 auth token、无 telemetry、无 analytics。
- hard upper bounds：
  - manifest ≤ 64 KiB
  - signature ≤ 4 KiB
- **严禁** unbounded readBytes/readText from network。

## 8. 重定向 / URL 安全

- 固定 manifest source 只能是 protocol-defined HTTPS source。
- HTTP client 自动 redirect 不得允许 HTTPS → HTTP downgrade。
- `releaseNotesUrl` / `apkUrl`：在 signature verified 后才可使用；至少要求
  `https` scheme；外部打开前再做一次 scheme validation。
- 不要把 manifest URL 直接显示成 clickable arbitrary deep link。

## 9. UI 状态机

单一清晰状态，不散落 booleans：

```
Idle
Checking
UpToDate(currentVersion)
UpdateAvailable(current, latest, severity, publishedAt, releaseNotesUrl)
UnsupportedCurrentVersion(...)
Error(type)
```

Error taxonomy：
- `NETWORK`
- `TIMEOUT`
- `INVALID_SIGNATURE`
- `INVALID_MANIFEST`
- `UNSUPPORTED_SCHEMA`
- `NOT_CONFIGURED`

用户文案：`INVALID_SIGNATURE` 必须明确是"无法验证更新信息"，而不是"没有更新"。
不显示 stack trace / raw response。

## 10. 安全契约：update data fail closed / app fail open

- 更新数据验证失败 → 不信任 manifest、不显示其中版本/URL 为可信更新、
  不允许 Open Release Page 使用该 manifest URL。
- 但 App 本体继续完全可用。
- Update Check fails closed. Vault operation fails open/offline.
- update state 不进入 SecureSession state machine。

## 11. 隐私

- 检查更新请求只访问固定公开 CNB URL。
- 不发送 Vault ID、device ID、Android ID、account/provider name、TOTP
  metadata、Developer metadata、install history、analytics IDs、auth token、
  cookies。
- 普通 HTTPS GET 即可。
- User-Agent（如需）：只含 app name + public version，不含 device
  fingerprint。

## 12. About 页面不泄漏内部安全状态

- 可显示：app version、update status、product description。
- 不显示：SQLCipher key、VaultKey state detail、biometric metadata、
  database path、keystore alias、device identifiers、raw exception。
- debug build 也不要默认把这些放 About。

## 13. Offline UX

- 无网络：Check for Updates → 明确、非惊吓式提示：
  "Unable to check for updates. Your vault remains available offline."
- 不把 network failure 当 fatal app error。用户可以 Retry。不要 background
  auto retry。

## 14. Process / lifecycle

- Checking coroutine 由 screen/viewmodel scope 持有；离开屏幕可取消。
- session lock 不要求特殊清理（update data 不来自 Vault），但不要为了
  update check 持有 Activity/Context leak。
- 不用 GlobalScope / standalone unmanaged CoroutineScope / long-lived
  network callback。

## 15. No secret persistence

- Update manifest 是 public metadata，本轮没有必要持久缓存；result in
  memory only。
- 不做 Room table / DataStore update history / cache file / downloaded
  manifest archive。每次手动点击重新检查即可。

## 16. 外部打开策略

- App 自己不下载 APK。About UI 主 action：**Open Release Page**，优先打开
  `releaseNotesUrl`（外部 ACTION_VIEW）。
- 不用 WebView；不把 APK 下载进 app storage；不调 PackageInstaller；不请求
  `REQUEST_INSTALL_PACKAGES`。
- manifest 中的 `apkUrl` 本轮保留并验证（用于 release metadata / future
  publishing verification），但普通用户主按钮不做 "Download APK"。

## 17. 发布流水线（未来；本轮不做）

1. 格式化/静态检查/单测/截图测试。
2. 构建 release AAB/APK。
3. 长期固定 signing key 签名。
4. 验证签名证书指纹、包名、versionCode。
5. 生成 SHA-256、release notes、`latest.json` + 签名。
6. 上传带版本 APK（禁止覆盖历史 APK）。
7. 从托管地址重新下载并校验。
8. **最后**原子更新 `stable/latest.json`。
9. 保留最近至少 3 个稳定版本。

> 本轮 L2 目标是 client-side About + verified manual update check。正式 release
> pipeline（signed APK / upload / generate manifest / sign / publish）留到最终
> release/signing 阶段。**不要**：创建 production APK signing key、创建 update
> private key、配置未知 CNB secrets、自动发布 APK。

## 18. 回滚规则

- 旧版可安装回滚（保留历史版本 APK 与对应 manifest 历史）。
- `minSupportedVersionCode` 用于 UI 更强 unsupported/outdated 警告（不锁本地
  Vault）。

## Release Infrastructure Pending

以下客户端契约已冻结；基础设施待 provision（见 PHASE6_L2_REPORT.md 与
`ROADMAP.md` L2 状态）：

- 创建 `rescueauth-updates` 仓库并配置公开原始文件访问。
- 生成生产 update manifest Ed25519 key（offline）；private key → CI secret only；
  public key → 应用 trusted config（`UPDATE_PUBLIC_KEY`）。
- 确认发布页跳转 URL 形态（release page 或 `releaseNotesUrl`）。
- 跨仓 CI 发布自动化（本轮明确不做）。
