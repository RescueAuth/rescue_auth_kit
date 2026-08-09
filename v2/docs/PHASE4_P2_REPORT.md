# PHASE4_P2_REPORT.md — Phase 4 P2: QR Scan + otpauth-migration Import

> 状态：**Implemented（Issue #20）**。
> 本文是 Phase 4 P2（QR 扫码 + Google Authenticator 风格 `otpauth-migration://`
> 批量导入）的实现报告。Scope 严格遵循 Issue #20 的 23 节契约。**IMPORT ONLY**，
> 不实现 migration export。

## 1. 用户闭环（已验证路径）

```
Authenticator
→ Add → Scan QR（首次才请求 CAMERA 权限）
→ camera preview（CameraX + ML Kit，on-device 解码）
→ QR recognized → raw String
→ URI routing
     ├─ otpauth://totp/...       → 复用 P1 OtpauthParser
     └─ otpauth-migration://...  → MigrationPayloadParser（独立纯 Kotlin adapter）
→ preview / result（普通 Compose confirmation state，非 camera overlay）
→ 保存到真实本地 Vault（SQLCipher Room，batch 单事务）
→ 立即在 Authenticator 列表可见
```

## 2. QR scanner architecture

- **成熟 Android stack**：CameraX（`camera-core/camera2/lifecycle/view` 1.4.1）
  + ML Kit `barcode-scanning` 17.3.0（on-device，无需网络）。
- 位置：`app/.../ui/screens/authenticator/QrScannerScreen.kt`。
- 不保存 camera image、不上传任何图像、不写日志；只有 raw scanned string
  通过 `onQrDetected` 离开 composable。
- 避免同一 QR 每帧重复触发：last-value + 2s cooldown guard。
- Torch toggle（CameraX `enableTorch`，无额外权限）。
- 不引入整套不相关视觉 SDK。

## 3. permission / lifecycle behavior

- **CAMERA 权限只在用户点 Scan QR 时请求**（`rememberLauncherForActivityResult`），
  App 启动不请求。
- 状态区分：granted / denied（可重试）/ permanently denied（提示去系统设置）。
- 拒绝后 Paste URI / Manual Entry 仍正常可用。
- CameraX `bindToLifecycle` + `DisposableEffect`：
  - 页面离开 composition → `onDispose` unbind + 关闭 scanner + shutdown executor；
  - App background → CameraX 生命周期自动停止扫描；
  - 返回前台自动恢复。
- `AndroidManifest` 仅声明 `CAMERA` 权限 + `uses-feature camera required=false`。

## 4. otpauth routing

- `app/.../scanner/ScannerResultRouter.kt`（纯 Kotlin，JVM 可测）。
  ```
  raw String
    ├─ otpauth://totp/...       → OtpauthParser（复用 P1，不写第二份 parser）
    ├─ otpauth-migration://...  → MigrationPayloadParser（P2 adapter）
    └─ else                     → NotSupported
  ```
- 路由不抛异常：malformed otpauth → `MalformedOtpauth`，malformed migration →
  `MalformedMigration`，其余 → `NotSupported`，UI 分别显示明确文案。

## 5. migration parser format

- `core/.../migration/MigrationPayloadParser.kt` + `MigrationModels.kt` + `MinimalProtobuf.kt`。
- 纯 Kotlin / 纯 JVM：**不依赖 Camera / Compose / Room / Android Context / legacy models**。
- 解析：`otpauth-migration://offline?data=<base64>`（**仅 `data=`，无 `batch_*`
  query 参数**）
  - `data` → percent-decode → standard Base64 解码 → protobuf wire 解码 → 每条
    `OtpParameters`（secret bytes / name / issuer / algorithm / digits / type /
    counter / id）+ batch metadata（version / batchSize / batchIndex / batchId）。
  - 最终 secret 转 Native v2 Base32 representation（RFC 4648，no padding，
    大写）。
- **adapter 定位**：`MigrationPayloadParser` 是 **Google Authenticator
  migration compatibility adapter**，不是通用 OTP migration parser；只实现
  已经验证的 GA wire contract。未来如需支持其它工具，应新建明确 adapter /
  compatibility decision，而不是无证据放宽当前 parser。
- **wire enum 语义（compatibility CR 修正，已对真实 GA v6.0 export 验证）**：
  `OtpParameters` 的 algorithm / digits / type 是 **protobuf enum**，不是 raw int：
  - `Algorithm`：0=UNSPECIFIED(→SHA1)，1=SHA1，2=SHA256，3=SHA512，4=MD5(→unsupported)；
  - `DigitCount`：0=UNSPECIFIED(→6)，1=SIX(6)，2=EIGHT(8)；其它值 unsupported；
  - `OtpType`：0=UNSPECIFIED(→TOTP)，1=HOTP(→unsupported)，2=TOTP。
- 日志安全：**不输出** raw migration URI、secret、decoded protobuf payload、
  generated Base32 secret。错误只带稳定 reason token。

## 5.1 data decoding rule（严格收敛后冻结 — 仅真实 GA 协议）

处理流程固定为：

```
URL percent-decode → standard RFC 4648 Base64 decode → MigrationPayload protobuf decode
```

1. `data` 值先做 **URL percent-decoding**。真实 Google export 会把 `+` 编码为
   `%2B`、`/` 编码为 `%2F`、`=` 编码为 `%3D`；percent-decode 后的 `+` `/` `=`
   **绝不能**误判为 malformed。
2. percent-decode 后按 **standard RFC 4648 §4 Base64** 解释：`A–Z a–z 0–9 + /`。
   这是真实 Google Authenticator 实际输出的 alphabet。
3. **RFC 4648 `=` padding 必需**（严格 decoder 拒绝任何长度非 4 倍数即缺 padding
   的形式，并拒绝错位 / 超长 `=`）。
4. 任何不符合真实格式的 `data` → **explicit malformed migration payload**：
   - Base64URL alphabet（`-` / `_`）→ `invalid-data-character`；
   - standard/Base64URL 混合 alphabet → `invalid-data-character`（不做
     mixed-alphabet normalization）；
   - 无 padding（no-padding）形式 → `malformed-base64`；
   - 错位 / 超长 padding（`ab=c`、`aGVsbG8====`）→ `malformed-base64`；
   - 其它非 alphabet 字符（`!!!!`）→ `invalid-data-character`。

**不为“兼容第三方工具”扩大 parser contract**：只接受已经验证的 GA wire form。

## 5.1.1 已删除的兼容扩展（严格收敛）

以下为上一轮为兼容非 Google 工具而加入、本轮按产品原则删除的能力：

- ~~Base64URL（`-` / `_`）接受~~ → 拒绝（`invalid-data-character`）；
- ~~自行接受 no-padding~~ → 拒绝（`malformed-base64`）；
- ~~standard/Base64URL 混合 alphabet normalization~~ → 拒绝
  （`invalid-data-character`）；
- ~~`&batch_size=` / `&batch_index=` / `&batch_id=` query 扩展容忍~~ → 拒绝
  （`unknown-query-parameter`，正式 URI 只允许 `data=`）；
- 其它未由真实 GA 协议证明的 fallback → 一律不实现。

## 5.2 independent interoperability fixtures（compatibility CR）

- **A. 真实 Google Authenticator v6.0 export（synthetic test accounts）**：
  `InteropFixtures.GA_*` 是 `krissrex/google-authenticator-exporter`
  `test-assets/test-qr-codes.json`（MIT，`usingAppVersion: "6.0"`）里的真实
  QR URI，只含 disposable synthetic credentials。
- **B. protoc 独立生成 fixture**：`tools/interop-fixture/` 用 `protoc` 3.21.12
  + Python `google.protobuf` 从确认过的 `MigrationPayload` schema 生成，
  **不调用本项目 MinimalProtobuf / ProtoFixture**。其中
  `PROTOC_FIXTURE_URLSAFE_NOPAD` / `ALPHABET_DISTINGUISHING_URLSAFE` 是
  **negative contract**（严格协议下必须被拒绝的 Base64URL / no-padding 形式）。
- `InteropFixtureTest` 断言 external fixture → `MigrationPayloadParser` →
  issuer/name/algorithm/digits/secret Base32/batch metadata 全部得到预期值；
  并对真实 GA multi-QR fixture 做完整 batch assembly 验证。

## 6. protobuf strategy

- 采用**边界严格的最小 protobuf wire decoder**（`MinimalProtobuf.kt`），
  不引入 protobuf 依赖（`:core` 保持零新增依赖、纯 JVM test）。
- 只实现 migration payload 需要的 wire 子集：varint / fixed64 /
  length-delimited / fixed32，未知字段结构性跳过，group 拒绝。
- 所有读取 bounds-checked；varint 长度上限、非 canonical 编码拒绝；
  长度先验证再切片。**不用正则解析、不猜字段、不依赖 undocumented offsets**。

## 7. supported / unsupported OTP semantics

- **IMPORTABLE**：TOTP（OtpType=2，UNSPECIFIED 视为 TOTP）、SHA1/SHA256/SHA512、
  digits 6/8（Google 定义值；默认 6）、period 30s（Google Authenticator 固定；
  Native 支持 1..120）。
- **UNSUPPORTED**：HOTP（OtpType=1）、MD5/未知 algorithm、Google 未定义的
  digits 值。
- **INVALID**：missing/empty/undecodable secret。
- 每条独立分类：5 条合法 + 1 条 unsupported 不会悄悄丢弃；UI 明确报告
  “5 importable / 1 unsupported”。绝不把 unsupported 静默转成错误 TOTP。

## 7.1 batch metadata authoritative source（严格收敛后冻结）

- **唯一 authoritative source 是 decoded `MigrationPayload` protobuf 字段**
  （`batch_size`=字段3 / `batch_index`=字段4 / `batch_id`=字段5）。
- 正式 URI 只需要 `data=`。**已删除**对自定义 query 参数
  `batch_size` / `batch_index` / `batch_id` 的正式支持：
  - 不再 override protobuf；
  - 不再 fallback 到 query；
  - matching 时不再额外接受。
- 如出现 `batch_*` query 参数 → 按严格 URI contract **拒绝**
  （`unknown-query-parameter`），避免歧义（选择“拒绝未知协议参数”而非
  “忽略”）。
- Parser contract 保持单一、无歧义：

  ```
  URI → data → protobuf → batch metadata
  ```

## 8. multi-entry import

- 一个 migration QR 可携带多个 credentials：
  scan → preview（每项显示 issuer / account / algorithm / digits，**绝不显示
  secret**）→ 批量确认 → 单事务导入。
- 至少支持 import all valid entries；一个无效 entry 不影响其它合法 entry。

## 9. multi-QR batch handling

- `core/.../migration/MigrationBatchSession.kt`（内存态，可独立删除）。
- 支持 batch metadata：batchId / batchIndex / batchSize。
- batchSize > 1 → scanner 进入临时 batch collection session：
  - 同 batchId、合法 index、batchSize 一致；
  - 重复同一 frame（同 index + 同 payload）幂等；
  - 可显示进度（如 `1 / 3`）；
  - 收齐后生成完整 import candidate list。
- 错误明确拒绝：混入其它 batchId / batchSize 不一致 / index 越界 /
  duplicate index different payload。
- 本轮 batch session 只保存在内存；app kill 不恢复半个 batch（符合契约）。

## 10. duplicate behavior

- 复用正式 `Canonicalization.totpFingerprint(secret, algorithm, digits, period)`
  语义，不重新定义 dedupe contract。
- 已有相同 TOTP credential → migration import 识别 duplicate → **不重复插入**。
- 不同 account metadata 但同 seed → 严格按当前正式 TOTP dedupe contract
  （指纹不含 account 元数据 → 判定为重复）。
- 结果 UI 报告：Imported N / Skipped duplicates M / Unsupported K；
  duplicate 不是 error。

## 11. repository batch import

- `AuthenticatorRepository.importTotpBatch(items: List<TotpImportItem>)`：
  **最小必要 TOTP-specific API**，单次 `VaultRepository.mutate`（同一 Mutex +
  withTransaction）内完成：
  - parent Provider/Account 正确复用（`findByServiceAndAccount` +
    会话内 account 缓存）；
  - stableId 正确生成（`UUID.randomUUID()`，Native identity 与 P1 一致）；
  - 不产生重复 Provider/Account；
  - per-entry 校验（secret / algorithm / digits / period）+ fingerprint
    dedupe；返回 `TotpBatchImportEntry(status, credentialId)`。
- **不触碰** Phase 3C MergePlanner / PackageValidator / transactional apply /
  ImportRecord / Developer persistence。

## 12. UI flow

- Add TOTP 菜单：**Scan QR / Paste URI / Manual**（三选 segmented control）。
- Scan QR → 全屏 camera preview（含 torch / close）。
- 扫描完成 → 离开 camera preview → 普通 Compose confirmation state
  （`MigrationImportSheet` ModalBottomSheet）：
  - 单 otpauth TOTP → 该条 preview → confirm → 走 P1 add path；
  - 单 migration QR → 多条目 preview → confirm → batch import；
  - 多 QR batch → 先显示 `collected / total` 进度，收齐后进入完整 preview。
- 结果 sheet：Imported / duplicates / unsupported / invalid 计数 + 关闭。
- 不把几十条 account 塞在 camera overlay。

## 13. security / privacy

- 不写日志、无 analytics、无网络上传、不保存 QR bitmap。
- raw migration URI 生命周期短（仅在 route / batch session 内存中短暂存在，
  batch session 内存态，离开即清）。
- decoded secret 只进入 parser → candidate → `TotpBatchImportResult`（不落
  UI model）。
- Phase 2 语义保持：FLAG_SECURE / background privacy mask / session lock 未改。
- 本轮不需要 sensitive-action re-auth（用户已在 unlocked Vault 内主动导入）。

## 14. error UX

明确区分（不再统一“Invalid QR”）：

- Not a supported QR
- malformed otpauth URI
- malformed migration payload
- unsupported HOTP / algorithm / digits
- invalid secret
- incomplete migration batch
- camera permission denied / permanently denied
- batch conflict（batchId / batchSize / index / duplicate-index）
- database/import failure

不显示底层 exception / secret 内容。

## 15. changed files

**core（新增，纯 Kotlin）**
- `core/.../migration/MinimalProtobuf.kt`
- `core/.../migration/MigrationPayloadParser.kt`
- `core/.../migration/MigrationModels.kt`
- `core/.../migration/MigrationBatchSession.kt`
- tests：`MigrationPayloadParserTest` / `MigrationBatchSessionTest` / `ProtoFixture`

**app（新增）**
- `app/.../scanner/ScannerResultRouter.kt`（+ test）
- `app/.../repository/BatchImportModels.kt`
- `app/.../ui/screens/authenticator/QrScannerScreen.kt`
- `app/.../ui/screens/authenticator/MigrationImportSheet.kt`
- tests：`AuthenticatorScanMigrationTest` / `TotpBatchImportRepositoryTest` /
  `AddTotpMenuTest` / `MigrationTestFixtures`

**app（修改）**
- `AuthenticatorRepository.kt`（新增 `importTotpBatch`）
- `AuthenticatorViewModel.kt`（scan/migration state + import）
- `AuthenticatorRoute.kt`（wire scanner + preview）
- `AddTotpSheet.kt`（Scan 模式）
- `AndroidManifest.xml`（CAMERA 权限）
- `build.gradle.kts` / `libs.versions.toml`（CameraX + ML Kit）
- strings en / zh-CN

## 16. tests

```
./gradlew :core:test                 PASS（含 migration parser 19 + batch session 10）
./gradlew :app:testDebugUnitTest     PASS（含 scanner router 6 + ViewModel scan/migration 10
                                       + repository batch import 8 + Add menu 3）
./gradlew :app:lintDebug             PASS（0 error）
./gradlew :app:assembleDebug         PASS
./gradlew :app:assembleDebugAndroidTest PASS
```

覆盖契约：pure core（single/multi TOTP、issuer/name、raw secret→Base32 exact、
SHA1/SHA256/SHA512、supported digits、malformed base64/protobuf、missing secret、
HOTP、unsupported algorithm、mixed）；batch（single、1/3→2/3→3/3、乱序、
重复幂等、duplicate-index-different-payload、wrong batchId、batchSize 不一致、
incomplete）；repository（3→3、Provider/Account 复用、duplicate skip、mixed、
unsupported 不落库、restart/reopen 持久化）；UI/scanner（Add menu Scan/Paste/
Manual、permission denied、otpauth scan、migration preview、multi-QR progress、
import result counts、duplicate、cancel release）。相机硬件不在 JVM test 假装真实拍摄。

## 17. 与 Phase 3C 的冲突检查

- **未修改**：VaultPackagePayload / PortablePackageCodec / PackageEnvelope /
  MergePlanner / PackageValidator package semantics / Phase 3C transactional
  apply / ImportRecord semantics / Developer persistence。
- migration TOTP import 只新增 TOTP-specific repository API
  （`importTotpBatch`），未碰 Native Package Merge。
- 未编辑 PHASE3_REPORT / Phase 3 ADR。
- ROADMAP / CHANGELOG 仅做最小 P2 状态修改。

## 18. remaining P3 scope

- P3 Recovery Codes slice（batch add / copy all / edit / delete / move）。
- P4/P6 Developer Vault slice、P5 Selective Export/Import、P7 Search+Pin、
  P8 Delete Undo 全类型、Sensitive Action Re-auth、clipboard auto-clear、
  i18n 全量补齐。
- 本轮明确不做：migration export、Recovery Codes、Developer Vault UI、
  Search、Pin persistence、Package Export/Import UI、Legacy Import、cloud、
  automatic backup、clipboard auto-clear、full Provider/Account management。
