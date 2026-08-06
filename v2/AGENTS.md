# AGENTS.md — RescueAuth v2 维护契约

> 面向所有 AI / 人类维护者的执行规则。违反以下任何一条都属于违约提交。

## 项目状态

- v2 是**全新 Android 原生应用**（Kotlin + Jetpack Compose + Room/SQLCipher），
  与旧 Flutter 项目并行存在。旧项目保留在仓库根目录，冻结于 tag `v1.2.0`。
- v2 代码位于 `v2/` 目录，从 `databaseSchemaVersion = 1` 与
  `backupFormatVersion = 1` 开始。
- 当前阶段：**阶段 0/1/phase1-fix 已完成**，阶段 2 已实现（Room + SQLCipher
  + VaultKey/Keystore + 安全会话 + 串行 repository + 自动锁 + 遮罩 + FLAG_SECURE）
  且 **phase2-blocker-hotfix 已合并**（PR #6：修复 BiometricPrompt 启动崩溃、
  instrumented 测试可 dex 化、真实 FLAG_SECURE/损坏库断言）。
  真机 instrumented 验证（6 用例已可编译）待有设备环境执行。

## 必跑命令（提交前）

```bash
# 生成/验证 legacy fixtures（仅阶段 0 工具，勿随意重跑）
cd tools/legacy_fixtures && dart run bin/generate_fixtures.dart verify

# v2 全量测试 + 构建（core 34 + app JVM/Robolectric 31）
cd v2 && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug

# instrumented 测试 APK 编译（真机验证前的必要前置，需 Android SDK）
cd v2 && ./gradlew :app:assembleDebugAndroidTest
# 有真机/模拟器时：
cd v2 && ./gradlew :app:connectedDebugAndroidTest
```

> 环境要求：JDK 17 + Android SDK（compileSdk 35 / build-tools 35）。
> 本仓库**不提交** `local.properties`；本地构建需自行设置 `sdk.dir`。

任何修改必须通过以上命令后才可提交。

## 禁区（绝对禁止）

1. **数据格式/加密/签名/applicationId**：
   - 不得修改加密算法、签名公钥、applicationId、签名证书配置。
   - 不得在同一 `formatVersion` 下改变新备份格式算法。
   - 不得输出旧 `.rakvault` 格式（legacy 模块只读）。
   - 变更数据 schema、备份协议、导入规则必须先更新文档 + fixture + 测试。
2. **敏感数据**：禁止把 TOTP secret、恢复码、恢复密钥、旧密码、明文 payload
   写入日志、截图 fixture、崩溃报告、analytics 或提交内容。
3. **依赖升级**：不得自动合并加密、存储、Room、SQLCipher、构建签名相关的
   依赖升级。此类升级必须人工评审。
4. **发布**：稳定发布必须通过 CI；不允许本地未验证 APK 覆盖线上文件。
   必须先上传并验证 APK，再更新 `latest.json`。
5. **旧数据**：不得删除旧项目、旧 fixture、旧备份。

## 提交要求

- 每个阶段独立提交，message 前缀 `phaseN:`。
- 功能完成必须提供：测试结果、截图结果（若涉及 UI）、兼容性结果、剩余风险。
- 修改行为前先更新或补充测试（测试未通过不得进入下一阶段）。

## 阶段进度跟踪

- [x] 阶段 0：冻结旧项目（tag `v1.2.0`）+ legacy fixtures + 映射文档
- [x] 阶段 1：最小 Kotlin/Android 工程 + Argon2id/XChaCha20-Poly1305 解密 spike
- [x] phase1-fix：entry-centric 映射 + 非法参数不静默替换 + BC 1.85 官方 XChaCha20
- [x] 阶段 2：数据库 schema v1 + VaultKey/Keystore + 串行 repository + 自动锁
  - [x] ADR-0003（VaultKey/Keystore/会话/备份冲突/16KB）
  - [x] Room + SQLCipher（Zetetic sqlcipher-android 4.17.0）+ schema v1 实体/DAO
  - [x] VaultKey（Keystore 包装/解包）+ 安全会话状态机 + SessionManager（自动锁/后台）
  - [x] 串行 repository（Mutex + withTransaction + 备份快照 sink）+ FLAG_SECURE
  - [x] JVM/Robolectric 测试 **31 个**（并发、锁定、超时、密钥失效、16KB page、真实 FLAG_SECURE）
  - [x] phase2-blocker-hotfix（PR #6）：BiometricPrompt 崩溃修复 + instrumented 可编译 + 真实断言
  - [ ] instrumented 真机验证（6 用例：SQLCipher 加密路径 + 生物识别 + 截图保护）——已可编译，待设备
- [ ] 阶段 3：新备份协议 + BackupKey + 恢复套件 + 导入导出
- [ ] 阶段 4：旧库导入完整流程 + Developer 数据处理
- [ ] 阶段 5：主要界面 + 设计系统 + 截图测试
- [ ] 阶段 6：托管更新（`rescueauth-updates` 仓库）+ CI + 签名
- [ ] 阶段 7：迁移试用

## 关键决策索引

- `docs/ADRS/ADR-0001-legacy-import-frozen-baseline.md`
- `docs/ADRS/ADR-0002-xchacha20-native-bc185.md`（BC 1.85 原生 XChaCha20-Poly1305，移除自实现 HChaCha20）
- `docs/ADRS/ADR-0003-database-vaultkey-session.md`（数据库加密、VaultKey 分层、安全会话生命周期、16KB）
