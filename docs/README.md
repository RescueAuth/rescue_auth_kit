# 拾遗坊 / RescueAuth 文档索引

> 本页是文档导航，不替代产品契约、路线图或安全协议。
> 当前状态同步日期：2026-09-27，进入 1.0.0 发布准备。

## 先读什么

按以下顺序了解项目：

1. [`../README.md`](../README.md) / [`../README.zh-CN.md`](../README.zh-CN.md)：项目入口、构建和发布入口。
2. [`../PRODUCT.md`](../PRODUCT.md)：正式产品范围、非目标和数据归属。
3. [`../ROADMAP.md`](../ROADMAP.md)：阶段、里程碑、依赖和 deferred/removed scope。
4. [`../AGENTS.md`](../AGENTS.md)：维护约束、架构禁区、提交和验证要求。
5. 本页：按主题查找设计契约、实现证据和历史报告。

发布准备从 [RELEASE_PROVISIONING.md §15](RELEASE_PROVISIONING.md#release-readiness)
开始：其中集中列出已验证的代码基线、创建生产 tag 前的待办，以及签名候选包产出后的验收顺序。
最近的界面与本地回归证据见 [UI_POLISH_REPORT.md](UI_POLISH_REPORT.md)；
更新渠道和云端设备操作分别见 [UPDATE_PROTOCOL.md](UPDATE_PROTOCOL.md) 与
[FIREBASE_TEST_LAB.md](FIREBASE_TEST_LAB.md)。

## 单一事实来源

| 问题 | 以哪个文件为准 |
| --- | --- |
| 产品做什么、不做什么 | [`PRODUCT.md`](../PRODUCT.md) |
| 当前阶段和里程碑 | [`ROADMAP.md`](../ROADMAP.md) §5、§10 |
| 维护规则和硬性禁区 | [`AGENTS.md`](../AGENTS.md) |
| Portable Package 字节格式 | [`PACKAGE_FORMAT.md`](PACKAGE_FORMAT.md) |
| Legacy `.rakvault` 兼容格式 | [`LEGACY_IMPORT.md`](LEGACY_IMPORT.md) |
| 安全边界和威胁 | [`THREAT_MODEL.md`](THREAT_MODEL.md) 及相关 ADR |
| 发布签名、流水线、发布前清单与验收证据 | [`RELEASE_PROVISIONING.md`](RELEASE_PROVISIONING.md#release-readiness) |
| 更新清单、签名和客户端状态机 | [`UPDATE_PROTOCOL.md`](UPDATE_PROTOCOL.md) |
| Firebase Test Lab 操作 | [`FIREBASE_TEST_LAB.md`](FIREBASE_TEST_LAB.md) |
| Compose 卡片式 UI 约定 | [`UI_CARD_CONVENTION.md`](UI_CARD_CONVENTION.md) |
| 页面模板、组件层级与复用方式 | [`UI_PAGE_TEMPLATES.md`](UI_PAGE_TEMPLATES.md) |
| Developer 操作栏与编辑验证实现证据 | [`UI_DEVELOPER_ACTIONS_REPORT.md`](UI_DEVELOPER_ACTIONS_REPORT.md) |
| 界面整理、品牌资源与提交清理验证 | [`UI_POLISH_REPORT.md`](UI_POLISH_REPORT.md) |

若实现、阶段报告与上述契约描述不同，先以契约为准，再补充更正文档。不要
在新的 PR、Issue 或报告中维护第二份阶段总表。

## 当前状态摘要

- 当前工作为 **1.0.0 发布准备**；正式产品范围和本轮界面整理已完成。
- Phase 0–5：CLOSED。
- Phase 6：L1 Localization、L2 About/Update Check 已完成；L3 Clipboard / security polish 为 DEFER。
- `DAILY-USE READY = YES`。
- `V2.0 FEATURE COMPLETE = YES`。
- `V2.0 RELEASED = NO`：仍需 production update Ed25519 provisioning、
  `rescueauth-updates` 更新渠道验收、签名密钥独立备份确认、签名 release 的真机 smoke
  和最终 FTL/device regression；以[发布检查清单](RELEASE_PROVISIONING.md#release-readiness)为准。
- 代码基线 `ae73494` 已通过 420 项 Core、769 项 App JVM 和 87 项本地 Android 原生测试。
  Debug / AndroidTest APK 构建成功，lint 无错误但仍有告警。该记录不等于云端回归或签名候选包验收。
- Native app 位于仓库根目录，`applicationId=com.rescueauth.v2`，
  `versionName=1.0.0`、`versionCode=10000`；旧 Flutter 源码只在
  `legacy-v1.0.0`–`legacy-v1.2.0` tags 中。

## 设计与协议

- [`PACKAGE_FORMAT.md`](PACKAGE_FORMAT.md)：Native `.rakpkg` 的 logical schema、容量、KDF、AEAD、header 校验、zeroization、merge 语义。
- [`LEGACY_IMPORT.md`](LEGACY_IMPORT.md)：旧 `.rakvault` 的只读兼容边界、解密、映射、stable ID 和 Android 导入流程。
- [`THREAT_MODEL.md`](THREAT_MODEL.md)：资产、攻击面、信任边界、已接受风险和验证缺口。
- [`UPDATE_PROTOCOL.md`](UPDATE_PROTOCOL.md)：固定 CNB manifest、Ed25519 验签、schema v1、错误分类和 fail-open/fail-closed 语义。
- [`RELEASE_PROVISIONING.md`](RELEASE_PROVISIONING.md)：application identity、production signing、tag-only candidate build、发布检查清单与验收证据。
- [`FIREBASE_TEST_LAB.md`](FIREBASE_TEST_LAB.md)：instrumented APK 构建、测试矩阵、凭据边界和手动触发方式。
- [`UI_CARD_CONVENTION.md`](UI_CARD_CONVENTION.md)：`RescueAuthCard`、`RescueAuthRowCard` 与 `CardTokens` 的使用边界。
- [`UI_PAGE_TEMPLATES.md`](UI_PAGE_TEMPLATES.md)：通用页壳、滚动表单页、公共内容组件，以及 Native / Legacy 流程的展示复用边界。
- [`UI_POLISH_REPORT.md`](UI_POLISH_REPORT.md)：2026-09-27 界面、品牌、输入框、交互和源码清理的验证记录。
- [`UI_REDESIGN_REPORT.md`](UI_REDESIGN_REPORT.md)：2026-08-28 首轮整体 UI 重构的历史记录；后续外观和组件以当前约定及整理报告为准。
- [`UI_FOUNDATION_REPORT.md`](UI_FOUNDATION_REPORT.md)：Compose shell 和设计系统的建立记录。

## ADR 索引

ADR 是不可随意重写的决策记录；后续变更应新增 ADR 或在主契约中明确取代关系。

| ADR | 主题 |
| --- | --- |
| [0001](ADRS/ADR-0001-legacy-import-frozen-baseline.md) | Legacy 冻结基线与只读导入 |
| [0002](ADRS/ADR-0002-xchacha20-native-bc185.md) | Bouncy Castle 1.85 原生 XChaCha20-Poly1305 |
| [0003](ADRS/ADR-0003-database-vaultkey-session.md) | Room/SQLCipher、VaultKey、Keystore 和会话生命周期 |
| [0004](ADRS/ADR-0004-stable-identity-fingerprint.md) | stable identity 与 semantic fingerprint |
| [0005](ADRS/ADR-0005-merge-first-planner.md) | merge-first import 与纯 MergePlanner |
| [0006](ADRS/ADR-0006-sensitive-action-reauth.md) | Sensitive Action re-authentication 总体边界 |
| [0007](ADRS/ADR-0007-portable-package-codec.md) | Portable Package 加密 Codec |
| [0008](ADRS/ADR-0008-phase3c-transactional-apply.md) | Transactional Import / Merge Apply |
| [0009](ADRS/ADR-0009-phase3d-android-export-import.md) | Android SAF Export/Import 与 Package Preview |
| [0010](ADRS/ADR-0010-legacy-stable-identity-source-fingerprint.md) | Legacy durable-id-first stable ID、source fingerprint 与容量边界 |
| [0011](ADRS/ADR-0011-sensitive-action-reauth-oneshot.md) | Sensitive Action one-shot 授权语义 |
| [0012](ADRS/ADR-0012-selective-export-import.md) | Selective Export / Import |
| [0013](ADRS/ADR-0013-developer-vault-completion.md) | Developer Vault keystore size contract 与 env-var identity |

## 实现报告

`PHASE*_REPORT.md` 是对应阶段完成时的实现证据和测试记录，属于**历史快照**。
报告中的 `PR OPEN`、`NOT STARTED`、旧目录路径或“本轮不做”只描述报告生成时的
状态，不代表当前状态；当前状态以本页、`ROADMAP.md` 和 `AGENTS.md` 为准。

| 报告 | 覆盖内容 |
| --- | --- |
| [`PHASE1_REPORT.md`](PHASE1_REPORT.md) | Kotlin/Android、Argon2id、XChaCha20-Poly1305 技术验证 |
| [`PHASE2_REPORT.md`](PHASE2_REPORT.md) | Room/SQLCipher、VaultKey、Keystore、会话、自动锁和 16 KB 验证 |
| [`PHASE3_REPORT.md`](PHASE3_REPORT.md) | 架构重置、Portable Package、Codec、事务合并、Android Export/Import |
| [`PHASE4_P1_REPORT.md`](PHASE4_P1_REPORT.md) | TOTP daily-use loop |
| [`PHASE4_P2_REPORT.md`](PHASE4_P2_REPORT.md) | QR 与 Google `otpauth-migration` import-only adapter |
| [`PHASE4_P3_REPORT.md`](PHASE4_P3_REPORT.md) | Recovery Codes 完整日常流程 |
| [`PHASE4_P4_REPORT.md`](PHASE4_P4_REPORT.md) | Sensitive Action re-auth 与 Developer Vault 首批 |
| [`PHASE4_P5_REPORT.md`](PHASE4_P5_REPORT.md) | Selective Export / Import |
| [`PHASE4_PA_REPORT.md`](PHASE4_PA_REPORT.md) | Provider / Account full management |
| [`PHASE4_P6_REPORT.md`](PHASE4_P6_REPORT.md) | Signing Key、Environment Variable Set 与 Developer completion |
| [`PHASE4_P7_REPORT.md`](PHASE4_P7_REPORT.md) | Global Search 与 Account Pin |
| [`PHASE4_P8_REPORT.md`](PHASE4_P8_REPORT.md) | Delete Undo、Recovery Set Move、empty Account round-trip |
| [`PHASE5A_REPORT.md`](PHASE5A_REPORT.md) | Legacy Core Adapter 与 stable identity |
| [`PHASE5B_REPORT.md`](PHASE5B_REPORT.md) | Legacy Android Import UI 与 transactional migration |
| [`PHASE6_L2_REPORT.md`](PHASE6_L2_REPORT.md) | About / signed manual Update Check |

## 历史与废弃文档

- [`BACKUP_FORMAT.md.obsolete`](BACKUP_FORMAT.md.obsolete) 明确是废弃草案，不能作为当前备份协议依据。
- Phase 报告保留用于审计、回归和追踪，不应删除或改写为当前实现的“实时状态页”。
- 旧 Flutter 源码通过 `legacy-v1.0.0`–`legacy-v1.2.0` tags 读取；当前树中的
  `legacy-fixtures/` 仅用于兼容性测试，Legacy Import 与 Native Package Import
  必须保持独立。

## 文档更新规则

1. 修改产品范围、阶段状态、包格式、导入规则、安全边界或发布规则前，先确定对应的单一事实来源。
2. 修改 `PRODUCT.md`、`ROADMAP.md` 或 `AGENTS.md` 时检查三者交叉引用和当前状态是否一致。
3. 修改数据格式、加密、schema 或导入行为时，同时更新 ADR、fixture 和测试；不得只改说明文字。
4. 新实现报告注明日期、状态、验证命令和剩余风险；完成合并后不必篡改历史报告，只需在当前契约中反映结果。
5. 不在文档、fixture、日志或截图中写入 TOTP secret、恢复码、密码、private key 或明文 payload。
