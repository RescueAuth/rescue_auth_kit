# 阶段 2 实现报告 — 数据库加密与安全会话

> 关联执行规划 §4、§12 阶段 2。本文记录阶段 2 的实现、依赖、ADR 决策、
> 测试结果、未完成的真机验证与剩余风险。

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

### JVM / Robolectric（本环境可运行）

| 测试类 | 覆盖 | 结果 |
| --- | --- | --- |
| `SecureSessionStateMachineTest` | 状态机全路径 | 6/6 ✅ |
| `VaultKeyManagerTest` | 生成/解包/失效/损坏 | 5/5 ✅ |
| `SessionManagerTest` | 解锁/锁定/后台超时/前台取消 | 5/5 ✅ |
| `RescueAuthDatabaseTest` | schema/DAO/级联/Flow（内存 Room） | 5/5 ✅ |
| `VaultRepositoryTest` | 并发串行、锁定、导入事务、checkpoint | 6/6 ✅ |
| `PageSize16KTest` | APK native 库 16KB 对齐 | 1/1 ✅ |
| `SecureScreenFlagTest` | FLAG_SECURE | 2/2 ✅ |

**合计：30/30 通过**（`./gradlew :app:testDebugUnitTest`）。
core 模块 phase1-fix 测试 34/34 通过（`./gradlew :core:test`）。
`:app:assembleDebug` 构建成功（18 MB APK）。

### Instrumented（需真机/模拟器，**本环境未完成**）

`app/src/androidTest/.../RescueAuthDatabaseInstrumentedTest.kt` 已编写 5 个用例：

1. 加密 DB 打开/重开持久化（证明 SQLCipher 加密）
2. 错误密钥打开失败（SQLCipher 拒绝）
3. 磁盘文件存在且已加密
4. **损坏数据库文件安全失败**（不崩溃，可引导恢复）
5. 进程重启模拟（close→reopen 数据保留）

> ⚠️ 本环境无 Android 设备/模拟器（无 KVM），以上 5 个用例**未执行**。
> 真机验证属于阶段 2 验收项，不能被 APK 编译成功替代。

## 4. 剩余风险

1. **SQLCipher 真机路径未验证**：native 加载、密钥错误处理、损坏文件行为
   都只能在设备/模拟器上确认（Robolectric 无法加载 SQLCipher JNI）。
2. **生物识别未真机验证**：`BiometricPrompt` 解锁流程、Keystore
   `setUserAuthenticationRequired` 的认证有效期窗口需真机确认。
3. **16KB page size 已通过 ELF 静态校验**，但未在 16KB 设备上实际运行。
4. **DataStore/其他 native 依赖**：DataStore 1.1.1 的 shared_counter 库
   已确认 16KB 对齐；后续升级需复测。
5. **截图保护**：`SecureScreenFlagTest`（**Robolectric unit test**，在
   `:app:testDebugUnitTest` 中运行）已通过，真机截图验证待完成。它属于
   unit test，不是 androidTest/instrumentation 测试。

## 5. 提交

- phase1-fix 已由 PR #3（`auto/phase1-fix-6eb6`）承载。
- phase2 独立提交：`auto/phase2-session-*` 分支。
