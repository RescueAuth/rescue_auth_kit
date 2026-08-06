# BACKUP_FORMAT.md — 新备份格式 v1（草案）

> 状态：**Draft**（阶段 3 实现前必须定稿并生成固定测试向量）。
> 一旦以 `formatVersion = 1` 发布，算法不得在同一版本下改变。

## 设计原则

- 本地数据库：高效查询与状态管理（SQLCipher）。
- 备份文件：稳定、版本化、可验证、可跨版本导入的逻辑快照。
- 备份文件**不是** SQLite 主文件的拷贝；不暴露 Room schema/WAL。

## Envelope

```json
{
  "magic": "RescueAuthBackup",
  "formatVersion": 1,
  "keyId": "uuid",
  "createdAt": "ISO-8601",
  "kdf": "HKDF-SHA256",
  "cipher": "AES-256-GCM",
  "saltB64": "...",
  "nonceB64": "...",
  "ciphertextB64": "..."
}
```

## 密钥派生

- `BackupKey`（256-bit 随机，用户离线持有恢复套件）为根密钥。
- 每个备份文件：随机 `salt` → HKDF-SHA256(BackupKey, salt) → 文件内容密钥。
- 每个备份使用新随机 `nonce`（AES-256-GCM）。

## Payload（export schema v1）

- 独立 export schema，不是 Room entity 直接 JSON 序列化。
- 结构：账户/服务/TOTP/恢复码的逻辑快照（字段映射见 LEGACY_IMPORT.md 反向）。
- 解密必须先完成 AEAD 校验，再解析 payload。
- Header 与 payload 设置大小、记录数量、字符串长度上限。
- 导入未来版本时明确提示"不支持的新版本"，不得猜测解析。

## 待定项（阶段 3 前完成）

- export schema v1 字段定义与固定测试向量。
- 恢复套件内容（恢复二维码 + 恢复密钥文件）与 mnemonic 表示（可选）。
- 保留策略与备份健康状态定义。

## 约束

- 算法一旦发布，不得在同一 `formatVersion` 下改变。
- 修改必须更新本文档 + 生成新测试向量 + 兼容性测试。
