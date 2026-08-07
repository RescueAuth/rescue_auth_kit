# 阶段 2 实现报告 — 数据库加密与安全会话

> 关联执行规划 §4、§12 阶段 2。本文记录阶段 2 的实现、依赖、ADR 决策、
> 测试结果、未完成的真机验证与剩余风险。
>
> **更新（2026-08-07，PR #15 合并后 / Phase 2 closure）**：本文已同步
> SQLCipher native 加载修复（PR #15）与 **Firebase Test Lab 数据库
> instrumented 验证 6/6 PASS**（真实 Android 虚拟设备执行），并将验证
> 层级严格分类（见 §3 / §4）。

## 1. 实现内容

### 1.1 数据库层（Room + SQLCipher）

- 依赖：**Zetetic `net.zetetic:sqlcipher-android:4.17.0`**（当前仍维护的官方
  Android artifact，2026-07 发布；不使用已过时的 `android-database-sqlcipher`）。
- 集成：Room `SupportOpenHelperFactory` → SQLCipher `SupportOpenHelperFactory`。
- Schema v1 实体/DAO（`app/src/main/kotlin/com/rescueauth/v2/database/`）：
  - `AuthAccountEntity` / `TotpCredentialEntity` / `RecoveryCodeSetEntity` /
    `RecoveryCodeEntity` / `ImportRecordEntity` / `BackupRecordEntity`
  - 6 个 DAO（含级联删除、Flow 观察、恢复码状态流转）
- **16KB page size**（Android 15+ 兼容）：
  - `jniLibs.useLegacyPackaging = false`（AGP 8.7+ 默认，zipalign 16KB）
  - `PageSize16KTest` 解析 APK 中每个 `.so` 的 ELF PT_LOAD p_align，
    校验满足 `p_align <= 4096 || p_align % 16384 == 0`
  - 实测：SQLCipher 4.17 与 DataStore native 库均满足 16KB 规则

### 1.2 VaultKey 与 Android Keystore

- `VaultKeyManager`（`security/`）：256-bit `SecureRandom` VaultKey，
  由 Keystore 中不可导出 AES-256-GCM 密钥包装（`setUserAuthenticationRequired`）。
- `VaultKeyCrypto` 抽象：生产 = `AndroidKeystoreVaultKeyCrypto`；
  测试 = JVM fake（Robolectric 无 AndroidKeyStore）。
- Keystore 失效 → `KeyInvalidatedException` → 状态机 `KEY_INVALIDATED` →
  引导恢复流程，**不删除数据库**。

### 1.3 安全会话

- `SecureSessionStateMachine`：`LOCKED → AUTHENTICATING → UNLOCKED`，
  Keystore 失效 → `KEY_INVALIDATED`。
- `SessionManager`：unlock（BiometricPrompt 后 unwrap + 打开加密 DB）、
  lock（关闭 DB + 清零密钥）、后台超时自动锁（可注入 0/30s/1m/5m）、
  前台取消定时器。
- `MainActivity`：`FLAG_SECURE` + 后台遮罩层 + BiometricPrompt 解锁。
  - **phase2-blocker-hotfix 后**：认证器按设备实际可用性动态解析
    （`resolveAvailableAuthenticators`），`DEVICE_CREDENTIAL` 路径不设 negative
    button（否则 `PromptInfo.build()` 抛 `IllegalArgumentException`）；
    认证仅在 Activity **RESUMED** 后触发（`onCreate` 只记录需求），
    并用 `AtomicBoolean` 防止取消/失败/重复 onResume 造成认证循环。
  - **AppCompat 主题**：`Theme.RescueAuth`（`Theme.AppCompat.Light.NoActionBar`）
    替代平台 Material 主题，避免 `AppCompatDelegate` 在 `setContentView` 抛异常。

### 1.4 串行 repository

- `VaultRepository`：所有 mutation 通过 `Mutex` 串行 + Room `withTransaction`。
- 每个 mutation 前检查 `session.isUnlocked()`，锁定后抛 `SessionLockedException`。
- **后台备份冲突方案**：`BackupSnapshotSink` 接收已加密快照；WorkManager 只
  复制快照，**不绕过认证解锁数据库**（ADR-0003 §2）。
- 旧库导入：PRE_IMPORT checkpoint 失败即中止（不碰数据库），成功后单事务写入。

## 2. ADR 决策

- `docs/ADRS/ADR-0003-database-vaultkey-session.md`：数据库加密、VaultKey 分层、
  认证有效期、锁定/进程死亡/失效恢复、后台备份冲突、16KB page、测试策略。

## 3. 测试结果

### JVM / Robolectric（本环境可运行，phase2-blocker-hotfix 后复验）

| 测试类 | 覆盖 | 结果 |
| --- | --- | --- |
| `SecureSessionStateMachineTest` | 状态机全路径 | 6/6 ✅ |
| `VaultKeyManagerTest` | 生成/解包/失效/损坏 | 5/5 ✅ |
| `SessionManagerTest` | 解锁/锁定/后台超时/前台取消 | 5/5 ✅ |
| `RescueAuthDatabaseTest` | schema/DAO/级联/Flow（内存 Room） | 5/5 ✅ |
| `VaultRepositoryTest` | 并发串行、锁定、导入事务、checkpoint、恢复码状态 | 8/8 ✅ |
| `PageSize16KTest` | APK native 库 16KB 对齐（缺 APK 时 fail） | 1/1 ✅ |
| `SQLCipherNativeLoaderTest` | SQLCipher native 加载幂等/失败告警/并发 publish-after-load 契约 | 4/4 ✅ |
| `SecureScreenFlagTest` | **真实启动 MainActivity** 断言窗口 FLAG_SECURE | 1/1 ✅ |

**合计：35/35 通过**（`./gradlew :app:testDebugUnitTest`，任务依赖 `assembleDebug`
并注入 APK 路径）。
core 模块 phase1-fix 测试 34/34 通过（`./gradlew :core:test`）。
`:app:assembleDebug` 构建成功（APK 内 8 个 `.so`：4 ABI × sqlcipher/datastore）。
`:app:lintDebug` 0 error。

### Instrumented — Firebase Test Lab 真实执行（**已完成，6/6 PASS**）

`app/src/androidTest/.../RescueAuthDatabaseInstrumentedTest.kt` 包含 **6 个用例**，
在 **真实 Android 虚拟设备**（Firebase Test Lab）上执行：

| 设备 | 值 |
| --- | --- |
| model | `MediumPhone.arm`（virtual） |
| API level | 33 |
| locale / orientation | en / portrait |
| matrix | `6707992428877319626`（提交 `9561956b`，构建 `cnb-87g-1jvdnj7iu`） |
| 结果 | `FTL_RAW_GCLOUD_EXIT_CODE=0`、`FTL_EXIT_CODE=0`、`FTL_STATUS=TEST_PASSED`、`FTL_FINAL=TEST_PASSED` |

6 个用例（均走 `RescueAuthDatabase.build`，即生产 SQLCipher 初始化路径，不自行
`loadLibrary`）：

1. 加密 DB 打开/重开持久化（证明 SQLCipher 加密）
2. 错误密钥打开失败（SQLCipher 拒绝）
3. 磁盘文件存在且已加密
4. **损坏数据库文件安全失败**（不崩溃，可引导恢复）——真实断言
5. 进程重启模拟（close→reopen 数据保留）
6. **普通 SQLite header 文件被拒绝**（非加密文件绝不能当加密 vault 打开）

> ⚠️ 边界：**数据库 6/6 PASS ≠ Phase 2 所有 Android 行为均已真机验证**。
> 生物识别 / Keystore 认证有效期 / 截图保护 / 锁屏 / 进程重建等仍为
> **未真机验证**的验证缺口（见 §4；不构成 blocker）。

## 4. 验证层级与剩余风险

### 4.1 已验证（真实设备证据）

**Firebase Test Lab（真实 Android 虚拟设备）**：`RescueAuthDatabaseInstrumentedTest`
6/6 PASS（MediumPhone.arm / API 33，见 §3）。覆盖：

- 加密数据库打开 / 持久化 / 重开
- 错误密钥拒绝
- 磁盘上存在加密文件
- 损坏数据库安全失败（可恢复异常，不崩溃）
- 进程重启 / reopen 模拟
- 有效 SQLite header 但未加密的数据库被拒绝

### 4.2 未真机验证（验证缺口，非 blocker）

1. **生物识别**：`BiometricPrompt` 成功 / 取消 / 失败路径、无认证器提示路径
   未在真实设备上验证（Robolectric 仅覆盖启动与 FLAG_SECURE）。
2. **Keystore 认证有效期**：`setUserAuthenticationRequired(true)` +
   `setUserAuthenticationValidityDurationSeconds(30)` 的真实认证窗口行为
   未真机确认。
3. **截图保护**：`FLAG_SECURE` 实际截图 / Recents / task preview 行为
   未真机验证（`SecureScreenFlagTest` 是 Robolectric unit test，已通过，
   但只证明窗口 flag 设置，不证明系统级截图行为）。
4. **锁屏 / 生命周期**：自动锁超时、后台遮罩、进程重建后的重新认证
   未真机验证。
5. **16KB page size**：已通过 ELF 静态校验（`PageSize16KTest`），未在
   16KB 设备上实际运行。
6. **DataStore / 其他 native 依赖**：DataStore 1.1.1 shared_counter 库已确认
   16KB 对齐；后续升级需复测。

> 这些缺口都是**验证缺口**，不等于存在 bug；属于 non-blocking validation
> backlog，不阻止 Phase 2 closure。

## 5. 提交

- phase1-fix 已由 PR #3（`auto/phase1-fix-6eb6`）承载。
- phase2 独立提交：`auto/phase2-session-*` 分支（PR #5，已合并）。
- phase2-blocker-hotfix：`auto/phase2-blocker-hotfix` 分支（PR #6，已合并），
  两个独立提交：
  1. `0e1ca65` phase2-hotfix: fix biometric prompt startup crash
  2. `84eff6b` phase2-hotfix: make platform tests buildable and truthful

## 6. phase2-blocker-hotfix 修复记录（PR #6）

> 来源：合并后回归审计（只读）发现的 3 个 blocker。本次仅修复，未夹带任何
> phase 3 功能（不引入备份协议/恢复逻辑/BackupKey）。

| # | Blocker | 修复 | 验收 |
| --- | --- | --- | --- |
| B1 | instrumented 测试 APK 无法编译（D8 拒绝带空格的反引号方法名） | 6 个用例改为合法标识符；`corrupted database` 恒真断言重写；新增 plain-SQLite header 拒绝用例 | ✅ `assembleDebugAndroidTest` 编译通过 |
| B2 | BiometricPrompt 配置自相矛盾（negative button + DEVICE_CREDENTIAL 同设）→ 首次启动必崩 | 按设备实际认证器动态解析；`DEVICE_CREDENTIAL` 路径不设 negative button；认证移到 `onResume`（resumed 后才触发）；`AtomicBoolean` 防重复/循环；AppCompat 主题 | ✅ `SecureScreenFlagTest` 真实启动 MainActivity 通过 |
| B3 | 平台证据链断裂（伪断言、APK 缺失静默 pass） | `SecureScreenFlagTest` 改为真实 Activity 启动断言窗口 FLAG_SECURE；`PageSize16KTest` 缺 APK 时 fail 而非静默返回；`testDebugUnitTest` 依赖 `assembleDebug` 并注入 APK 路径 | ✅ 31/31 通过，无恒真/常量断言（后续 PR #13/#14/#15 后为 35/35） |

**测试注入缝**：`SessionManager` 新增可注入 `vaultKeyManagerFactory`，
`MainActivity` 新增 `sessionManagerFactory`，使平台测试可在 Robolectric 下
用 fake 替代 AndroidKeyStore（无 Keystore 环境）。

**hotfix 后遗留（非本次范围，记录备查）**：
- `onConfigurePragmas` 已定义但未被 SQLCipher hook 接线（`PRAGMA cipher_memory_security`/`secure_delete` 未生效）。
- Room `exportSchema=true` 但未配置 `room.schemaLocation`，schema JSON 未导出。
- 未用依赖/DAO/API 清理（datastore、security-crypto、activity-compose、lifecycle-viewmodel 等）。
- `unwrap()` 异常分类过宽（`AEADBadTagException` 也归为密钥失效）。
- `checkUnlocked` 与 `Mutex` 之间、`snapshotSink.onChange` 持锁位置、`lock()/unlock()` 字段同步等边界。

## 7. Phase 2 closure 状态总结（2026-08-07）

### 阶段状态

| Phase | 状态 |
| --- | --- |
| Phase 0 | **DONE**：旧 Flutter 冻结 v1.2.0 + fixtures + LEGACY_IMPORT.md |
| Phase 1 | **DONE**：最小 Kotlin/Android 工程 + Argon2id/XChaCha20 解密 spike（PR #1/#2/#3） |
| Phase 2 | **IMPLEMENTED + 数据库真机验证 6/6 PASS**（见 §3）；生物识别/Keystore/截图保护等仍为未真机验证缺口（§4.2） |
| Phase 3 | **NOT STARTED**（BACKUP_FORMAT.md 仍为 Draft；无 BackupKey/恢复套件/导入导出实现） |

### Phase 2 结论

- **BLOCKERS: NONE**。无已知产品功能错误、无未实现的安全要求、无核心需求缺失。
- 剩余真机验证项（生物识别、Keystore 认证有效期、截图保护、锁屏/生命周期、
  16KB 真机）均为 **non-blocking validation backlog**。
- 建议 **RECOMMEND CLOSE PHASE 2**（关闭前新增的验证项可进入 backlog 追踪）。
