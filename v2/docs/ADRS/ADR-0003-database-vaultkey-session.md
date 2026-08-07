# ADR-0003：数据库加密、VaultKey 与安全会话生命周期

- 状态：**Accepted**（phase2）
- 日期：2026-08-06
- 关联：执行规划 §4、§12 阶段 2、用户 phase2 指令
- **更新（2026-08-06，PR #6）**：§2 补充 BiometricPrompt 认证器的
  blocker 修复记录（`DEVICE_CREDENTIAL` 禁止 negative button、认证仅
  resumed 后触发）。

## 背景

v2 需要在 Android 上实现安全会话：
Room + SQLCipher 全库加密、VaultKey 生成/包装/解锁/锁定/失效处理、
串行 repository mutation、自动锁、后台遮罩与 `FLAG_SECURE`。
用户还要求解决"后台备份 vs 生物识别"的冲突，以及 16KB page size 支持。

## 决策

### 1. 数据库层：Room + Zetetic `sqlcipher-android` 4.17.0

- 使用 **Zetetic `net.zetetic:sqlcipher-android:4.17.0`**（当前仍维护的官方
  Android artifact；4.17.0 发布于 2026-07，2027-02 起 Google Play 要求
  16KB page size 兼容）。
- 集成方式：Room `SupportFactory` + SQLCipher 的 `SupportOpenHelperFactory`。
- **不使用**已过时的 `android-database-sqlcipher` 方案（它不提供 Room
  `SupportOpenHelperFactory`，且维护状态差）。
- **16KB page size**：SQLCipher 4.17 的 native 库不依赖系统 page size；
  应用通过 `android:extractNativeLibs` 保持默认并把 `.so` 以 16KB 对齐
  （AGP 8.7+ 默认 `useLegacyPackaging=false` 即按 16KB 对齐 zipalign）。
  数据库本身在首次打开时设置 `PRAGMA page_size=4096`（SQLCipher 默认）
  并在 `VaultKey` 初始化时通过 `SupportOpenHelperFactory` 传入正确参数。
  应用侧同时按 Android 15 16KB 要求声明
  `android:maxSdkVersion`/对齐由构建侧保证。

### 2. 密钥分层

```
VaultKey (256-bit 随机, 数据库主密钥)
  └─ 由 Android Keystore 中不可导出的 AES-256-GCM 密钥包装 (wrap/unwrap)
BackupKey (256-bit 随机, 阶段 3)
  └─ 设备内由 VaultKey 保护；恢复套件离线持有
```

- **VaultKey 生成**：`SecureRandom`（Android `java.security.SecureRandom`）
  一次性生成 32 字节；**禁止从用户密码派生**（v2 无日常主密码）。
- **Keystore 包装**：`AndroidKeyStore` provider 的
  `KeyGenerator`/`KeyStore` 生成不可导出 AES 密钥（`setUserAuthenticationRequired`）。
  包装后的 VaultKey 密文存于应用私有目录（`filesDir`），密钥别名固定为
  `vault_key_wrap`。
- **认证有效期**：`setUserAuthenticationValidityDurationSeconds` 可配
  （默认 30 秒；锁屏策略允许后由用户设置 0=每次、30、60、300）。
- **数据库打开/关闭**：
  - 解锁时：Keystore unwrap VaultKey → `SupportOpenHelperFactory` 用
    VaultKey 打开 SQLCipher → 状态机进入 `UNLOCKED`。
  - 锁定时：关闭 Room 数据库实例、**清零内存中的 VaultKey 副本**
    （ByteArray.fill(0)），状态机进入 `LOCKED`。
- **进程死亡**：进程被杀后，Room 连接随进程释放；Keystore 密钥不受影响；
  下次启动需重新生物识别解锁。
- **Keystore 失效**（设备重置/锁屏变化/系统异常）：
  **不得删除数据库**。检测到 unwrap 失败（`KeyPermanentlyInvalidatedException`
  等）→ 状态机进入 `KEY_INVALIDATED` → UI 引导用户用恢复套件/备份恢复。
- **后台备份冲突**：WorkManager **不得**绕过用户认证解锁数据库。方案：
  应用在已解锁并成功提交事务后，立即生成**加密备份快照**（内存/临时文件），
  WorkManager 只负责把已加密快照复制到用户选择的目录。这样后台任务
  不需要数据库访问，也不触碰 VaultKey。

### 3. 串行 repository mutation

- 所有数据库写操作通过**唯一** repository 入口（`VaultRepository`）。
- 内部用 `Mutex`/单线程 dispatcher 串行化 mutation；mutation 采用
  Room `withTransaction`。
- 只读查询可以并行，但任何写操作前检查状态机为 `UNLOCKED`。

### 4. 安全会话状态机

```
LOCKED ──authenticate()──▶ AUTHENTICATING ──success──▶ UNLOCKED
   ▲                                                  │
   └──────────────lock()/timeout/background───────────┘
UNLOCKED ──keystore invalidated──▶ KEY_INVALIDATED (需恢复流程)
```

- 状态用 `StateFlow<SessionState>` 暴露给 UI。
- 自动锁：应用进入后台立即遮罩（不锁定数据，仅遮 UI）；按用户设置
  （立即/30s/1m/5m）后台超时后调用 `lock()`。
- 后台遮罩：`MainActivity.onStop` 显示遮罩层；`onStart` 判断是否需要
  重新认证。
- `FLAG_SECURE`：敏感页面 Window 设置 `FLAG_SECURE`，阻止截图/最近任务预览。

### 5. 测试策略

- **JVM 单元测试**：状态机、VaultKey 包装/解包逻辑（纯逻辑层）、
  串行 repository（用 fake DAO）、损坏数据库安全失败（模拟）、
  16KB page 配置断言。
- **Robolectric 测试**：Room + SQLCipher 生命周期、锁定后数据库关闭、
  后台超时锁定、截图保护配置、Keystore 失效（mock）。
- **Instrumented 测试**：真机/模拟器验证（本环境无设备，标记未完成）。
- **Android 16KB page size**：构建产物校验（zipalign -c 16）在 CI 中执行。

### 6. BiometricPrompt 认证器决策（phase2-blocker-hotfix 补充）

回归审计发现 `PromptInfo.Builder` 同时设置 `DEVICE_CREDENTIAL` 与 negative
button 会在 `build()` 抛 `IllegalArgumentException`，导致首次启动即崩。
补充决策：

- 认证器按设备实际可用性动态解析（`resolveAvailableAuthenticators`），
  不再写死 `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`。
- `DEVICE_CREDENTIAL` 被允许时**不得**设置 negative button（系统 UI 提供取消）；
  biometric-only 提示保留 "Cancel" negative button。
- `authenticate()` 只在 Activity **RESUMED** 后触发（`onCreate` 仅记录需求），
  并用 `AtomicBoolean` 防止取消/失败/重复 `onResume` 造成认证循环。
- 无可用认证器时明确提示恢复流程，不静默降级（`BIOMETRIC_ERROR_NONE_ENROLLED` 等）。
- `MainActivity` 使用 AppCompat 主题（`Theme.RescueAuth`）——`AppCompatActivity`
  要求 AppCompat 主题，否则 `setContentView` 抛 `IllegalStateException`。

## 后果

- 好处：全库加密、密钥不可导出、后台任务不触碰密钥、串行 mutation 防并发丢失。
- 代价：引入 SQLCipher native 库（APK 增大 ~10MB 各 ABI）；需要 Robolectric
  配置；真机验证延迟到有设备的环境。
- 不可逆点：数据库 schema v1、VaultKey 包装结构一旦发布，变更需 migration。
