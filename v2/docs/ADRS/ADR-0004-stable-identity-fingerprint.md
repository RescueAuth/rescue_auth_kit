# ADR-0004：Stable record identity 与 semantic fingerprint

- 状态：**Accepted**（Phase 3A）
- 日期：2026-08-07
- 关联：PACKAGE_FORMAT.md §Identity、PHASE3_REPORT.md §3

## 背景

Phase 3 需要跨设备迁移 / 合并 Vault。审计发现 Room primary key 是
per-install 随机 UUID，不是跨 Vault 稳定 identity：两台设备独立扫描同一
TOTP QR 得到不同的 `id`；Export→Import→Export 若不处理会生成新 ID。

## 决策

### 1. 两层概念

- **Stable record identity（lineage）**：每条记录有一个 `stableId`，
  首次创建时生成并永久保持，随 Export Package 跨设备流转。
- **Semantic fingerprint（dedupe）**：识别“逻辑上同一条 credential”，
  用于两台设备独立录入的重复检测。

### 2. Schema

- 5 张业务表各加 `stableId TEXT NOT NULL` + UNIQUE index（schema v2）。
- pre-Phase-3A 行 migration 回填 `stableId = id`（Room primary key 对既有
  数据就是最自然的稳定 ID）。
- **不为“看起来更现代”新增 UUID 字段**：新写入的行由业务层在创建时
  生成 stableId（当前实现：= 主键 id，写入时分配）。

### 3. Fingerprint 规则

- TOTP：`SHA-256("totp" + canonical(secret, algorithm, digits, period))`。
  issuer / accountName 不参与 —— 重命名不影响 credential 身份。
- Recovery set：`SHA-256(title + code values)`；status/usedAt 不参与（但
  used/unused 差异由 MergePlanner 作为显式 state divergence 处理，见
  ADR-0005）。
- Account：`SHA-256(serviceName + accountName)`，仅用于 merge 分组。
- **Developer Entry（本轮修正为 FULL LOGICAL PAYLOAD）**：`SHA-256(FULL
  LOGICAL PAYLOAD)` 分类型定义，覆盖**全部用户语义字段**（不仅是敏感
  payload）：
  - Android Signing Key：title / notes / projectName / packageName /
    keystoreFileName / keystore base64 字节 / storePassword / keyAlias /
    keyPassword；
  - API Credential：title / notes / serviceName / accountName / apiKey /
    apiSecret；
  - SSH Key：title / notes / keyName / publicKey / privateKey / passphrase；
  - Environment Variable Set：title / notes / projectName / 全部
    KEY=VALUE（key 排序后 canonical，variable **names** 参与）；
  - Generic Secret：title / notes / 全部 label=value（key 排序后
    canonical，field **labels** 参与）。
  `createdAt` / `updatedAt` 等纯技术 metadata **不参与**。
  **用途限制**：该 fingerprint **仅用于同 stableId 比较**：FULL LOGICAL
  PAYLOAD 完全一致 → DUPLICATE；**任意 user-meaningful logical field 不同
  （title / notes / projectName / packageName / serviceName / accountName /
  keyName / env variable names / generic field labels 任一不同，不仅限于
  sensitive payload）→ CONFLICT**。**绝不用于跨 stableId dedupe**：相同
  secret / private key / keystore 字节 / env values / generic values 本身
  不能证明两条不同 stableId 的 Developer Entry 是同一条逻辑资产（同一 API
  key 可按不同 service/account 保存为两个用途；同一 SSH key 可对应不同
  server/usage；同一 keystore 可被多个 project/package 使用；Env/Generic
  同 value 不代表 name/label 相同），因此不同 stableId 默认 INSERT / keep
  both，不得静默丢掉一条记录。

### 4. 安全约束

- fingerprint 是 secret-derived 材料：只在 merge/import 时按需计算，
  不落库，不放入 plaintext package header。
- 不同 secret 永不合并（不同 fingerprint）。
- Android keystore 是 binary asset：以 base64 进入 logical schema，只存在
  于 encrypted payload（ROADMAP §8.2）。

## 后果

- 好处：跨设备 lineage + 语义去重分离；merge 语义可 JVM 纯逻辑验证。
- 代价：schema v2 migration（5 列 + 5 唯一索引 + 删 backup_record 表）；
  stableId 需要业务层保证写入时分配。
- 不可逆点：schema v2 一旦发布，后续变更需 migration。
