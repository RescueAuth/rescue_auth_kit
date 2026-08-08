# ADR-0009：Phase 3D Android Export/Import Adapter（SAF + preview + PIN UX）

- 状态：**Accepted**（Phase 3D）
- 日期：2026-08-08
- 关联：ADR-0007（codec）、ADR-0008（transactional apply）、ADR-0005
  （merge planner）、ROADMAP §5.2（3D）、PACKAGE_FORMAT.md（.rakpkg /
  MIME contract）、Issue #1（Phase 3D 验收）

## 背景

3A/3B/3C 已交付逻辑 package 模型、加密 codec、transactional apply，但用户
无法在 Android 上真正导出 / 导入 Package。Phase 3D 把这些能力接成
SAF 文件 + per-export PIN 对话框 + import preview + confirm transactional
apply 的闭环，并严格保持架构边界（UI 不直接访问 DAO、Composable 不直接
调用 codec / Room）。

## 决策

### 1. 分层（strict boundary）

```
Compose → ExportImportViewModel（coordinator） → ExportImportService（use-case）
        → VaultRepository / PortablePackageCodec
```

- 复用 3A/3B/3C，不重新实现 crypto / format / serialization / merge /
  transactional apply。
- 不创建 GenericImporter；Native 与 Legacy import 强制隔离（ROADMAP §9）。

### 2. SAF only

- Export：`ActivityResultContracts.CreateDocument`，扩展名 `.rakpkg`，
  MIME hint `application/vnd.rescueauth.v2-package`（见 PACKAGE_FORMAT.md
  §扩展名 / MIME contract）。
- Import：`ActivityResultContracts.OpenDocument`（`*/*`），由实际 bytes +
  magic + codec 决定类型。
- 禁止直接写 /sdcard、MANAGE_EXTERNAL_STORAGE、
  READ/WRITE_EXTERNAL_STORAGE、固定 Downloads path、自建文件浏览器。

### 3. bounded untrusted-file reading

- `BoundedPackageReader`（`:core`，纯 Kotlin）：流式增量读取，最多
  `MAX_PACKAGE_SIZE + 1` 字节，超限立即以 `MalformedPackage`（package too
  large）拒绝；不预分配攻击者声称的巨大 size；不 OOM。
- SAF adapter 只把 ContentResolver stream 桥接到该 reader，保持很薄。
- `OpenableColumns.SIZE` 只作为 UX hint，不是 security gate。

### 4. per-export PIN UX / policy

- Export：PIN 输入 + 确认，两次一致才继续；Import：输入一次。
- 默认隐藏、可短暂 reveal；不写日志、不进 SavedStateHandle / Bundle /
  rememberSaveable / 持久化；优先 CharArray，用后 best-effort 清理。
- charset / min length 未在 PACKAGE_FORMAT 正式定义 → 不在 codec 写死；
  Android UI 层 `PinPolicy`：6–128 位纯数字（Product Policy constant）。

### 5. Export snapshot consistency

- `VaultRepository.buildConsistentExportSnapshot`：在 repository mutex +
  单 Room 事务内构造一致 FULL_VAULT snapshot（复用 buildDestinationSnapshot，
  不复制 ExportSnapshotBuilder）。

### 6. Import preview + confirm（preview ≠ apply authority）

- preview：decode → validate → plan 对当前 destination，输出**无 secret**
  的 `ImportPreview`（package metadata + 内容计数 + merge summary）。
- confirm：调用 Phase 3C `applyMergePlan`，由它基于当前真实 destination
  重新 validate / plan / preflight / apply。preview 的 plan 从不直接写库。
- preview 有 CONFLICT / recovery state divergence → blocked：用户只能
  Cancel / Back，不能强推 source/destination wins / overwrite all。

### 7. Import session 敏感生命周期

- decode 后的 `VaultPackagePayload`（plaintext secret）只存在
  `ExportImportService` 内存 session 中；cancel / apply / lock → 清除。
- 不持久化；process/state recreation 不恢复 plaintext；auto-lock 后要求
  重新执行完整 decode 流程。

### 8. Sensitive-action re-auth boundary

- 本轮不实现完整 re-auth subsystem；Export orchestration 保留单一入口，
  Phase 4 P4 在 build snapshot / encode / write 前插入 fresh
  Biometric/Device-credential gate。不创建假的 no-op security abstraction。

## 后果

- 好处：Native v2 Package 的 encode → file → file → decode → preview →
  merge → transactional apply 真正闭环；SAF input 安全（bounded + 不信任
  metadata）；preview 不泄漏 secret；confirm 永远走 3C 最终 authority。
- 代价：`.rakpkg` / MIME 为新 contract，需在 PACKAGE_FORMAT 维护；SAF 真实
  文件 picker 只能靠 FTL / 真机验证（本地 JVM 用 fake adapter）。
- 不可逆点：`.rakpkg` 扩展名 / MIME hint 在发布后如需修改需文档同步；
  `PinPolicy` 是 UI 层 policy，非 package-format requirement，可单独调整。
