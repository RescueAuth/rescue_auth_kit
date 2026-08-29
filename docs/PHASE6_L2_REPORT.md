# Phase 6 L2 Report — About / Update Check

> 状态：**IMPLEMENTED / PR OPEN**（Issue #20，Phase 6 L2）。
> 范围：正式 About 页 + manual、signature-verified 的 Update Check。
> `docs/UPDATE_PROTOCOL.md` 从 Draft 收口为可执行 contract
> （Client Contract Final / Release Infrastructure Pending）。
> 本轮明确不做 release publishing pipeline / production signing。

> **文档性质**：历史实现报告。正文中的 `PR OPEN` 等状态只代表报告生成时的
> 状态；当前状态以 `../ROADMAP.md`、`../AGENTS.md` 和 [`README.md`](../README.md) 为准。

## 1. current Settings / About audit

- `SettingsScreen.kt`：Settings 页原本在 "About" section 下只有一行
  `Version %versionName`（disabled 行），无正式 About 页。
- `versionName` 由 `MainActivity` 从 `BuildConfig.VERSION_NAME` 传入 shell
  （`RescueAuthApp`）再传给 Settings。
- `RescueAuthRoutes` / `RescueAuthApp` 已有三个顶层 destination
  （Authenticator / Developer / Settings）+ 若干嵌套路由（Export / Import /
  Legacy Import / Developer detail-form）。本轮新增一个嵌套路由
  `RescueAuthRoutes.ABOUT = "about"`，从 Settings 进入。
- Settings 的 Column 原本不可滚动（内容较短）；本轮改为
  `verticalScroll`，因为 About 入口位于列表底部，小屏/测试 viewport 下需要
  滚动可达。

## 2. old v1 UpdateChecker semantics audit（UX / semantic reference）

旧 Flutter `lib/core/update/update_checker.dart` 提供：
- `UpdateCheckResult` 状态：`updateAvailable / upToDate / noReleaseFound /
  cannotCompare`；`UpdateCheckStatus`。
- `UpdateChecker` 从 GitHub Releases API 拉最新 release（`api.github.com`），
  解析 `tag_name`，用 `AppVersion`（semver-like）比较。

**v2 语义差异（本轮收敛）**：
- 旧 v1 访问 GitHub Releases API 并做 semver 比较；v2 改为**固定 CNB 原始
  清单 + Ed25519 验签 + 仅用 versionCode 比较**（UPDATE_PROTOCOL.md）。
- 旧 v1 的 `noReleaseFound` / `cannotCompare` 状态被 v2 的
  `Error(NETWORK)` / `Error(INVALID_SIGNATURE)` / `Error(NOT_CONFIGURED)`
  取代（更明确、fail-closed）。
- 旧 v1 无签名验证；v2 以 Ed25519 验签作为真正 trust boundary。

## 3. final update source

- Manifest：
  `https://cnb.cool/xincy22/rescueauth-updates/-/git/raw/main/android/stable/latest.json`
- Signature：同目录 `latest.json.sig`。
- 组织名 / repo / branch / path 属于 update trust contract（不得删除/改名/
  转私有），固定编译进 `UpdateManifestSource`。
- 运行时**不访问** GitHub/CNB Releases API、不搜索 release、不抓取 HTML。

## 4. network implementation

- `HttpUpdateTransport`（app）：用平台 `HttpURLConnection`（两个小型 GET，
  不引入重量级 network architecture）。
- `BoundedUrlFetcher`：
  - 显式 connect timeout 10s / read timeout 10s。
  - 取消友好：`isActive` 回调在每次 read 前检查，scope 取消即中止。
  - bounded response size（manifest ≤ 64 KiB / signature ≤ 4 KiB），**严禁**
    unbounded readBytes/readText。
  - 拒绝 HTTPS→HTTP downgrade（redirect 后重查 final URL scheme）。
  - 无 cookie、无 auth token、无 telemetry、无 analytics。
  - User-Agent 仅 `RescueAuth-v2`（app name + public version，无 device
    fingerprint）。
- 未增加 `ACCESS_NETWORK_STATE`（现有实现不需要）。

## 5. manifest size bounds

- manifest ≤ **64 KiB**（`UpdateSizeLimits.MAX_MANIFEST_BYTES`）。
- signature ≤ **4 KiB**（`UpdateSizeLimits.MAX_SIGNATURE_BYTES`）。
- 选在建议上限内；reasoning：v1 schema manifest 含 release-notes URL 与描述
  字段远小于 64 KiB；Base64 原始 64-byte Ed25519 签名至多 88 ASCII 字符，
  4 KiB 仍很宽裕。超限在分配前拒绝（oversized rejected before allocation）。

## 6. final manifest schema

schema v1（`core` `UpdateManifestParser` strict validation）：

```json
{
  "schemaVersion": 1,
  "channel": "stable",
  "versionName": "...",
  "versionCode": ...,
  "minSupportedVersionCode": ...,
  "publishedAt": "ISO-8601",
  "apkUrl": "https://...",
  "apkSizeBytes": ...,
  "apkSha256": "64-hex",
  "releaseNotesUrl": "https://...",
  "severity": "NORMAL"
}
```

校验：`schemaVersion==1`、`channel==stable`、`versionName` 非空白、
`versionCode` 正整数、`minSupportedVersionCode` 正整数且 `<= latest`、
`publishedAt` 合法 ISO-8601、`apkUrl`/`releaseNotesUrl` HTTPS、
`apkSizeBytes` 正且有界、`apkSha256` 恰好 64 小写 hex、未知 additive 字段
忽略、required 缺失 reject、关键安全字段无 silent default。

## 7. Ed25519 verification architecture

- `UpdateManifestVerifier`（core）：复用 BouncyCastle 1.85
  `org.bouncycastle.math.ec.rfc8032.Ed25519.verify` —— **无新增 crypto 库**。
- 顺序（`UpdateCheckViewModel.runCheck`）：
  ```
  fetch manifest bytes (bounded)
  fetch signature (bounded)
  ↓ validate sizes
  ↓ Ed25519 verify EXACT manifest raw bytes
  ↓ ONLY AFTER: UpdateManifestParser.parse
  ↓ versionCode compare
  ↓ render UI
  ```
- 签名验证失败 → `Error(INVALID_SIGNATURE)`，**不解析不信任** manifest，
  `verifiedOpenUrl()` 返回 null（无 Open Release Page）。

## 8. exact signed bytes contract

- 签名覆盖 `latest.json` 的**确切原始字节** —— 不是 parsed / pretty-print /
  reserialized / canonicalized JSON。
- Release pipeline：write exact `latest.json` → sign exact bytes → publish same
  bytes；避免 JSON canonicalization ambiguity。
- Test 锁定：`reformattingInvalidatesOriginalSignature`（同一逻辑 JSON 重排后
  原签名失效）、`signatureVerifiesExactRawBytes`、`oneByteMutationRejected`。

## 9. signature encoding

- `latest.json.sig` = **Base64 编码的原始 64-byte Ed25519 签名**。
- ASCII；允许一个尾部换行（decode 前 trim）。
- decode 用 strict（非 MIME）Base64，padding 错误 → reject；长度非 64 →
  reject（`INVALID_BASE64`）。

## 10. public-key encoding / configuration

- 公钥编码：**Base64 编码的原始 32-byte Ed25519 public key**（与
  BouncyCastle/JCA 实现匹配），`UpdateTrustConfig`。
- 配置边界：Gradle 属性 `UPDATE_PUBLIC_KEY` → `BuildConfig.UPDATE_PUBLIC_KEY`
  （release provisioning 时写入）。
- 未配置 / 空白 / placeholder → `UpdateTrustConfig.notConfigured()` →
  update check 返回 `NOT_CONFIGURED`（"Verification key not configured /
  Update verification unavailable"），仅更新检查失败，Vault 继续工作。

## 11. production update key provisioning status

**Pending**（本 PR 不创建/不泄漏 production private key）。Release
provisioning item：

```
Before v2.0 release:
1. generate production Update Manifest Ed25519 key offline
2. private key → CI secret only
3. public key → app trusted config (UPDATE_PUBLIC_KEY BuildConfig)
4. create/configure rescueauth-updates
5. publish signed test manifest
6. device smoke verified check
```

> 该 key 与 Android APK signing key 是两个不同 key，不要混用。
> 当前仓库无正式 production update public key（`rescueauth-updates` 仓库也
> 尚不存在，`git ls-remote` 返回 Repository Not Found）。

## 12. version comparison behavior

- 只用 `versionCode`（`UpdateVersionDecision`）：
  - `latest.versionCode >  current.versionCode` → UPDATE_AVAILABLE
  - `==` 或 `<` → UP_TO_DATE
- 不做 lexical compare versionName；`versionName` 只是 display metadata。

## 13. minSupportedVersionCode behavior

- `current.versionCode < minSupportedVersionCode` → `UpdateAvailable` 携带
  `minSupportedExceeded=true`，UI 显示更强的 unsupported/outdated 警告并强烈
  建议升级。
- **不锁**本地 Vault、不阻止 unlock、不阻止查看/导出用户数据。
- 同步修正了 UPDATE_PROTOCOL.md 的歧义描述。

## 14. severity behavior

- `NORMAL`：普通新版提示。
- `SECURITY`：更醒目 "security update available"（error color）提示。
- 即使 SECURITY：不锁 Vault、不禁止使用、不自动下载、不自动安装 —— 只是
  更强 recommendation。
- `UpdateVersionDecisionTest` 覆盖 SECURITY 不 forced-update。

## 15. URL validation / external-open behavior

- 固定 manifest source 仅 HTTPS；redirect 不允许 HTTPS→HTTP downgrade。
- `releaseNotesUrl` / `apkUrl` 仅在 signature verified 后可用，且至少 https
  scheme。
- `ExternalOpenHelper.openReleasePage`：仅 `ACTION_VIEW`（系统浏览器），
  打开前对 URL 再做一次 scheme validation；不用 WebView、不下载 APK、不调
  PackageInstaller、不请求 REQUEST_INSTALL_PACKAGES。
- `verifiedOpenUrl()` 仅在 verified UpdateAvailable 时返回 HTTPS
  releaseNotesUrl。

## 16. fail-closed update / fail-open Vault behavior

- update 数据验证失败 → 不信任 manifest、不显示版本/URL、不允许 Open
  Release Page（fail closed）。
- App 本体继续完全可用（fail open/offline）。
- update state **不进入** SecureSession state machine。
- 网络/签名/清单故障不会影响 unlock / TOTP / Recovery / Developer /
  export/import / offline。

## 17. offline / privacy behavior

- 无网络 → `Error(NETWORK)`，UI 显示 "Unable to check for updates. Your vault
  remains available offline."；可 Retry，无 background auto retry。
- 请求只访问固定公开 CNB URL；无 Vault ID / device ID / Android ID /
  account/provider / TOTP / Developer / install history / analytics / auth
  token / cookie。
- 无 secret persistence：result in memory only；无 Room / DataStore / cache /
  downloaded manifest archive。

## 18. About UI

- `AboutScreen` + `AboutRoute`：
  - app name、product description。
  - runtime `versionName`（`BuildConfig.VERSION_NAME`）与 `versionCode`
    （`BuildConfig.VERSION_CODE`），注入 AboutRoute —— 不硬编码在
    strings.xml，不写死 "1.0.0"；测试可注入 fake version provider
    （`UpdateCheckViewModel.versionIdentity` lambda）。
  - manual "Check for Updates"。
  - update 状态机渲染（Idle / Checking / UpToDate / UpdateAvailable /
    Error），severity / minSupported 提示。
  - "Open Release Page" 仅在 verified UpdateAvailable 时出现。
  - 不显示 SQLCipher key / VaultKey / biometric / DB path / keystore alias /
    device identifiers / raw exception（debug build 亦然）。

## 19. accessibility / i18n

- 全部用户可见文案走 `strings.xml`（en；zh-CN 待 L1 完整双语时补齐，与
  L2 不冲突）。
- About 返回按钮使用 `R.string.a11y_back`。
- 语义节点不含内部安全状态（AboutScreenTest.noSecretOrInternalVaultMetadata）。

## 20. Android permissions

- 显式新增 `android.permission.INTERNET`（L2 网络成为产品功能，不再依赖
  ML Kit / transitive manifest 碰巧带来的 INTERNET）。
- 未新增：`ACCESS_NETWORK_STATE`、storage、`REQUEST_INSTALL_PACKAGES`、
  notification、background service。
- 最终 merged manifest 只有 INTERNET + 既有的 USE_BIOMETRIC /
  USE_FINGERPRINT / CAMERA。

## 21. changed files

- `core/`：`update/UpdateManifest.kt`、`UpdateManifestVerifier.kt`、
  `UpdateVersionDecision.kt`、`UpdateTrustConfig.kt`、`UrlPolicy.kt`、
  `Iso8601.kt`、`Sha256.kt`、`UpdateSizeLimits.kt`。
- `core/` test：`update/TestEd25519.kt`、`TestManifestJson.kt`、
  `UpdateManifestVerifierTest.kt`、`UpdateVersionDecisionTest.kt`。
- `app/`：`update/UpdateManifestSource.kt`、`UpdateTransport.kt`、
  `HttpUpdateTransport.kt`、`BoundedUrlFetcher.kt`、`UpdateCheckViewModel.kt`、
  `UpdateUiState.kt`、`UpdateTrustProvider.kt`、`ExternalOpenHelper.kt`。
- `app/` UI：`ui/screens/about/AboutScreen.kt`、`AboutRoute.kt`；
  `ui/navigation/RescueAuthRoutes.kt`（+ABOUT）；`ui/RescueAuthApp.kt`
  （+ABOUT route）；`ui/screens/settings/SettingsScreen.kt`（+About 入口 +
  scroll）。
- `app/` resources：`res/values/strings.xml`（About 文案）；
  `AndroidManifest.xml`（INTERNET）。
- `app/` test：`update/FakeUpdateTransport.kt`、`TestEd25519.kt`、
  `TestManifestJson.kt`、`UpdateCheckViewModelTest.kt`；
  `ui/about/AboutScreenTest.kt`；`ui/RescueAuthAppNavigationTest.kt`。
- `app/build.gradle.kts`（UPDATE_PUBLIC_KEY BuildConfig + test bcprov）。
- Docs：`docs/UPDATE_PROTOCOL.md`、`docs/PHASE6_L2_REPORT.md`、`ROADMAP.md`、
  `AGENTS.md`、`CHANGELOG.md`。

## 22. tests

- core protocol（18+）：valid sig / one-byte mutation / wrong key / malformed
  sig / wrong-length / invalid Base64 / exact raw bytes / reformat invalidates /
  trailing newline / oversized sig / schemaVersion!=1 / channel!=stable /
  missing field / invalid ISO / non-HTTPS apkUrl / non-HTTPS releaseNotesUrl /
  invalid apkSha256 / invalid versionCode / minSupported>latest / additive
  field tolerated。
- core version decision（7）：latest>current / == / < / versionName 不控制 /
  minSupported / SECURITY / SECURITY 不 forced-update。
- app/network（11）：NETWORK / TIMEOUT / oversized manifest / oversized
  signature / invalid sig 无 URL / malformed verified / NOT_CONFIGURED /
  retry / scope cancel / no auth data / update available + verified URL。
- UI（11 About + 1 nav）：Settings→About 导航、runtime versionName/Code、
  Checking / UpToDate / UpdateAvailable / SECURITY / network / signature /
  invalid manifest 无 open link、open release only verified、无 secret 泄漏。
- 均为 fake transport / 本地合成 manifest；unit tests 不访问真实 CNB。

## 23. release build status

- 见 §Validation：`:core:test`、`:app:testDebugUnitTest`、`:app:lintDebug`、
  `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:assembleRelease`
  通过（release 允许继续 unsigned）。未创建 Android signing key，未创建
  update private key，未主动运行 FTL。

## 24. UPDATE_PROTOCOL final status

`docs/UPDATE_PROTOCOL.md` 已从 Draft 收口为可执行 contract，冻结：manifest
source、signature source、raw-byte signing、`.sig` encoding、public-key
encoding、schema v1、severity enum、minSupported 行为、manual-only、
external-open 策略、fail-closed update / fail-open app、offline app 可用。
状态标记：**Client Contract Final / Release Infrastructure Pending**（生产
公钥 / `rescueauth-updates` 仓库未 provision）。

## 25. FTL expectation

本 PR 不主动运行 Firebase Test Lab（按 Issue #20 §32 约束）。关于更新的
instrumented UI 走 Robolectric host unit tests；如需 FTL 冒烟，可在合并后由
`main` 推送流程的 guarded FTL 覆盖（无新增 FTL 矩阵）。

## 26. parallel conflict check with P7

- P7（Global Search + Account Pin/Unpin）并行，主改 Authenticator UI/ViewModel、
  Search、pinned state。
- 本 PR（L2）只加 `RescueAuthRoutes.ABOUT`、About 页、Settings About 入口、
  `strings.xml` About 文案、`ROADMAP.md`/`AGENTS.md`/`CHANGELOG.md` 的 L2
  状态段。
- 共同可能碰：navigation（本 PR 只加 ABOUT 路由，不碰 Authenticator/Developer
  语义）、strings（只加 About 组）、ROADMAP/AGENTS/CHANGELOG（只写 L2，不虚报
  P7/P8）。
- 不做 Authenticator 产品语义 / favorite / pinned / Search / Developer CRUD /
  package / MergePlanner 改动。互不等待。

## 27. remaining release-provisioning work

- 创建 `rescueauth-updates` 仓库并配置公开原始文件访问。
- 生成生产 update manifest Ed25519 key（offline）；private key → CI secret；
  public key → `UPDATE_PUBLIC_KEY`。
- 发布 signed test manifest 并在设备冒烟验证。
- 跨仓 CI 发布自动化（最终 release/signing 阶段）。

## 28–30. PR / branch / commits

见本 PR：branch `auto/about-update-*`；commits 见 PR commit list；
`Ref #20`。
