# ADR-0001：旧 `.rakvault` 只读导入 + 冻结基线

- 状态：**Accepted**（阶段 0）
- 日期：2026-08-06
- 关联：执行规划 §7、§12 阶段 0

## 背景

旧 Flutter 应用（RescueAuthKit）v1.2.0 的 `.rakvault` 使用
Argon2id(19 MiB/2/1/32B) + XChaCha20-Poly1305，payload 为 JSON，
schema 版本 1/2/3。v2 是全新 Android 原生应用，必须能一次性导入旧库。

## 决策

1. 旧格式**只读导入**：新代码不输出 `.rakvault`，不依赖旧 payload 结构。
2. 冻结基线：旧项目打不可变 tag `v1.2.0`；fixture 的 SHA-256 固定并纳入文档。
3. 导入流程：选文件 → header 校验 → 一次旧密码 → 后台 Argon2id+解密 →
   解析旧 schema → 统一 `LegacyImportBundle` → 预览 → checkpoint → 单事务写入 →
   导入后新格式备份 → 记录 `ImportRecord` → 清理密钥材料。
4. KDF 参数必须先校验上限再执行（实测 8 GiB 参数直接 abort 进程）。

## 后果

- 好处：v2 的备份格式完全独立、可跨版本演进；旧库任何损坏都不会影响新库。
- 代价：需要维护 9 个 fixture + 3 套 schema parser + 1 个 mapper；
  旧 schema 不再演进（被冻结）。
- 不可逆点：`v1.2.0` tag、fixture SHA-256、KDF 安全上限一旦发布不可变更。
