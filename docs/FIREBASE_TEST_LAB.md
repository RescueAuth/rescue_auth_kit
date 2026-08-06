# Firebase Test Lab CI 接入文档

> 适用于仓库 `xincy22/rescue_auth_kit` 的 Firebase Test Lab（下称 FTL）接入。
> 本 PR 只接入测试基础设施，不修改任何产品代码。

---

## 1. 整体架构

`main` 分支的 `push` 事件会触发一条独立流水线 `full-cloud-test-loop`，
形成全云端测试闭环：

```text
main push
→ JVM/core tests            (./gradlew :core:test)
→ Robolectric unit tests    (./gradlew :app:testDebugUnitTest)
→ lint                      (./gradlew :app:lintDebug)
→ debug APK                 (./gradlew :app:assembleDebug)
→ androidTest APK           (./gradlew :app:assembleDebugAndroidTest)
→ 变更范围判断              (scripts/check-test-lab-gate.sh)
→ Firebase Test Lab         (scripts/run-firebase-test-lab.sh，单台虚拟设备)
→ 在 CNB 日志中保留测试矩阵结果
```

任何前置命令失败都会让流水线失败，**不会**提交 Test Lab 矩阵。

配置位置：`.cnb.yml` 中的 `main.push` 流水线，构建镜像为固定的
`cimg/android:2026.08`（JDK 17/21 + Android SDK 34/35/36 + build-tools 35.0.0，
版本由镜像 tag 锁定，保证可复现）。

---

## 2. 为什么只能在 `main push` 中读取密钥

FTL 需要 GCP 服务账号凭据。凭据存放在独立的密钥仓库：

```text
https://cnb.cool/xincy22/rescue_auth_kit_secrets/-/blob/main/firebase-test-lab.yml
```

该密钥仓库的访问限制为：

| 维度       | 限制值                              |
|-----------|-----------------------------------|
| repository | `xincy22/rescue_auth_kit`         |
| event     | `push`                            |
| branch    | `main`                            |

因此在 `.cnb.yml` 中，**只有** `main.push` 流水线通过 `imports` 引用该文件：

```yaml
main:
  push:
    - name: full-cloud-test-loop
      imports:
        - https://cnb.cool/xincy22/rescue_auth_kit_secrets/-/blob/main/firebase-test-lab.yml
```

`pull_request`、`issue`、`issue.comment@npc`、`pull_request.comment@npc`、
`tag` 事件以及其他分支、手动且不受保护的触发器都**不得**导入密钥。

---

## 3. PR 分支为什么不能真实运行 Test Lab

PR 分支（如 `auto/xxx`）不属于 `main`，无法满足密钥仓库
`repository + event + branch` 的限制，因此**读不到密钥**。

这带来两条推论：

1. PR 分支上的流水线无法（也不应）提交真实 Test Lab 矩阵。
2. 本次 PR 合并进 `main` 产生的首次 `push`，才是**第一次真实 Test Lab 执行**。

这是一个有意的安全边界：防止任意 PR 提交消耗测试配额或触发云端设备。

---

## 4. 所需环境变量

`scripts/run-firebase-test-lab.sh` 显式读取以下环境变量：

| 变量                            | 说明                                        | 敏感 |
|--------------------------------|---------------------------------------------|------|
| `APP_APK`                      | app debug APK 的确定路径                     | 否   |
| `TEST_APK`                     | androidTest debug APK 的确定路径             | 否   |
| `FIREBASE_PROJECT_ID`          | Firebase 项目 ID                             | 是   |
| `GCP_SERVICE_ACCOUNT_JSON_BASE64` | GCP 服务账号 JSON 的 Base64                  | 是   |
| `FTL_DEVICE_MODEL`             | 虚拟设备型号（如 `Pixel_7`）                  | 否   |
| `FTL_DEVICE_VERSION`           | 虚拟设备 Android API 版本                    | 否   |
| `FTL_DEVICE_LOCALE`            | locale（固定 `en`）                          | 否   |
| `FTL_DEVICE_ORIENTATION`       | 方向（固定 `portrait`）                       | 否   |
| `FTL_TEST_TIMEOUT`             | 矩阵超时（固定 `5m`）                         | 否   |
| `CNB_COMMIT`                   | 当前提交 SHA（用于 matrix/result 标识）       | 否   |

`APP_APK` / `TEST_APK` 使用**确定的 Gradle 输出路径**：

```text
v2/app/build/outputs/apk/debug/app-debug.apk
v2/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

绝不使用 `find | head -1` 这类不受约束的查找。

凭据的两个变量只做"是否存在"检查，**绝不打印值**；脚本内也不写入
Gradle properties、用户 home 的永久文件或仓库目录。

---

## 5. 设备配置

设备配置通过非敏感环境变量固定：

```text
FTL_DEVICE_MODEL=Pixel_7
FTL_DEVICE_VERSION=33
FTL_DEVICE_LOCALE=en
FTL_DEVICE_ORIENTATION=portrait
FTL_TEST_TIMEOUT=5m
```

脚本在提交矩阵前会通过已认证的 gcloud 查询 FTL 设备目录：

```bash
gcloud firebase test android models describe Pixel_7
```

校验规则：

1. 配置的 model/version 组合必须存在于目录中；
2. 设备 form 必须为 `virtual`（虚拟设备），**不启用物理设备**；
3. 若配置不可用，报告 `CONFIGURATION FAILURE` 并失败，**绝不静默换成随机设备**；
4. 只提交**一个**矩阵（单台虚拟设备），不创建多设备矩阵；
5. **不启用**自动重试或 flaky retry；
6. **不启用** Android Test Orchestrator（仓库此前未配置，接入时保持测试运行模型不变）。

日志中会输出最终使用的 model、API version、locale、orientation、timeout。

---

## 6. Test Lab 官方退出码及分类

`gcloud firebase test android run` 的原始退出码会被**保留并原样返回**，
并按官方语义分类（日志中输出 `FTL_EXIT_CODE` 与 `FTL_STATUS`）：

| 退出码 | 官方语义            | 分类（`FTL_STATUS`）                    |
|-------|--------------------|---------------------------------------|
| 0     | 成功               | `TEST_PASSED`                         |
| 1     | 一般/配置错误       | `GENERAL_OR_CONFIGURATION_FAILURE`    |
| 2     | 无效命令或参数       | `INVALID_COMMAND_OR_ARGUMENT`         |
| 10    | 测试失败            | `TEST_FAILED`                         |
| 15    | 无结论/意外错误      | `INCONCLUSIVE`                        |
| 18    | 不支持的测试环境      | `UNSUPPORTED_TEST_ENVIRONMENT`        |
| 19    | 测试被取消          | `TEST_CANCELED`                       |
| 20    | 基础设施错误        | `INFRASTRUCTURE_FAILURE`              |

关键规则：

- **不把所有非零状态都叫产品测试失败**；只有退出码 `10` 才能归类为测试用例失败；
- 退出码 `20` 归类为基础设施失败；
- 配额耗尽**没有假定的固定退出码**；
- 只有当 API 响应或错误文本**明确包含** quota / resource exhaustion 信息时，
  才额外标注 `QUOTA_EXHAUSTED`，同时仍保留真实退出码；
- 测试失败、配置失败、基础设施失败、配额耗尽都会让当前 Test Lab 流水线失败；
- 文档类变更导致主动跳过不算失败。

脚本在 `set -e` 环境下通过 `set +e` 捕获原始退出码，保证分类与清理逻辑不会
因 shell 提前退出而丢失。

---

## 7. Spark 免费额度下为什么默认只跑一台虚拟设备

本项目保持 Firebase **Spark**（免费）套餐。Spark 免费额度通常只包含少量免费
虚拟设备测试分钟数，因此默认只提交**一个矩阵、一台虚拟设备**，以把有限额度
留给真正有价值的回归验证。

流水线**不得**启用 Blaze 或绑定结算账号（见第 14 节）。

---

## 8. 文档类变更为什么跳过 Test Lab

`scripts/check-test-lab-gate.sh` 会计算本次 push 的完整变更文件范围
`CNB_BEFORE_SHA..CNB_COMMIT`（覆盖多 commit 一次 push 的完整范围，不是只看
最后一个 commit）。

只有以下路径发生变化时才运行 Test Lab：

```text
app/**
core/**
gradle/**
build.gradle
build.gradle.kts
settings.gradle
settings.gradle.kts
gradle.properties
libs.versions.toml
.cnb.yml
scripts/run-firebase-test-lab.sh
scripts/check-test-lab-gate.sh
```

如果变更只涉及文档类内容（`docs/**`、`README*`、`LICENSE*`、`*.md` 等），
会在本地测试全部通过后**跳过** Test Lab，并明确输出：

```text
TEST LAB NOT EXECUTED: documentation-only change
```

门控规则：

1. `CNB_BEFORE_SHA` 为空、无效或全零时，**默认运行** Test Lab（保守策略）；
2. 多个 commit 一次 push 时，必须比较完整范围；
3. 跳过时流水线仍然成功，但必须明确记录原因；
4. **不得**通过 commit message 中的随意字符串关闭安全测试。

---

## 9. 如何从 CNB 日志进入 Firebase 测试矩阵结果

`main push` 流水线的 `firebase-test-lab` 阶段日志中会输出：

```text
[ftl] FTL_MATRIX_ID=1234567890123456789
[ftl] FTL_CONSOLE=https://console.firebase.google.com/project/rescue-auth-kit-test/testlab/histories
[ftl] FTL_EXIT_CODE=<gcloud 原始退出码>
[ftl] FTL_STATUS=<分类>
[ftl] FTL_FINAL=<最终分类>
```

打开 CNB 构建日志页面（`云原生构建 → 对应构建记录 → firebase-test-lab 阶段`），
复制 `FTL_MATRIX_ID` 或点击 `FTL_CONSOLE` 链接进入 Firebase 控制台
`Test Lab → 测试记录 (Histories)` 查看矩阵结果。

`FTL_MATRIX_ID` 与当前提交 SHA 关联（`--results-dir=ftl-<CNB_COMMIT>`），
便于把一次构建与一次矩阵一一对应。

---

## 10. 如何区分各类失败

| 现象（日志特征）                                  | 判定                                            |
|------------------------------------------------|------------------------------------------------|
| `FTL_STATUS=TEST_FAILED`，退出码 10             | **产品测试失败**：测试用例断言不通过             |
| 前置阶段 `assembleDebugAndroidTest` 失败         | **测试 APK 构建失败**：代码/依赖/AGP 问题        |
| `CONFIGURATION FAILURE` + 缺变量/缺 APK          | **配置错误**：环境变量缺失、APK 缺失或非法        |
| `CONFIGURATION FAILURE` + 设备 model/version     | **设备组合不可用**：目录中不存在或非虚拟设备      |
| `FTL_STATUS=INFRASTRUCTURE_FAILURE`，退出码 20   | **基础设施错误**：Test Lab 服务侧问题             |
| `FTL_STATUS=QUOTA_EXHAUSTED`（保留真实退出码）     | **配额耗尽**：Spark 免费额度用尽                 |
| 日志输出 `TEST LAB NOT EXECUTED: documentation-only change` | **文档类跳过**（不算失败）            |

---

## 11. 默认结果保留周期和结果链接用途

Test Lab 结果默认存放在 Firebase 项目绑定的默认 Cloud Storage 桶中
（`gs://test-lab-<...>` 或项目默认桶，以 Firebase 控制台展示为准），结果目录为
`ftl-<CNB_COMMIT>`。结果保留周期遵循 Firebase Test Lab 的默认策略
（通常约 30 天，以 Firebase 控制台展示为准）。结果链接用于：

- 快速回溯某次构建对应的设备测试结果；
- 区分"产品测试失败"与"基础设施/配额问题"；
- 归档每次 `main push` 的真实设备回归证据。

结果仅用于**排查与回溯**，不作为长期存储，请勿依赖过期链接。

---

## 12. 服务账号密钥轮换步骤

当需要轮换 FTL 服务账号密钥时：

1. 在 GCP 控制台（IAM 与管理 → 服务账号）找到用于 Test Lab 的服务账号；
2. 选择"密钥"标签 → "添加密钥" → "创建新密钥"，下载新的 JSON；
3. 对 JSON 做 `base64 -w0` 得到 `GCP_SERVICE_ACCOUNT_JSON_BASE64`；
4. 将新值写入密钥仓库
   `xincy22/rescue_auth_kit_secrets` 的 `firebase-test-lab.yml`；
5. 在 GCP 删除旧密钥；
6. 合并一个仅含 `docs/` 变更（或触发 `.cnb.yml`）的提交推送到 `main`，
   触发流水线验证新凭据可用；
7. 确认矩阵正常后，结束轮换。

> 注意：密钥仓库文件变更本身不会触发本仓库流水线，轮换验证需要推送
> 一次 `main`（文档变更即可，不消耗矩阵）。

---

## 13. 发生密钥泄露时的撤销步骤

若服务账号 JSON（或其 Base64）疑似泄露：

1. **立即**在 GCP 控制台删除该服务账号的密钥（IAM 与管理 → 服务账号 →
   该账号 → 密钥 → 删除所有泄露密钥）；
2. 若服务账号本身也暴露，删除/停用该服务账号并创建新账号；
3. 生成新的服务账号 JSON 并更新密钥仓库
   `xincy22/rescue_auth_kit_secrets/firebase-test-lab.yml`（Base64）；
4. 检查 Firebase 项目是否被异常调用，必要时在 IAM 收紧该账号角色
   （Test Lab 只需要 `Cloud Test Lab 服务账号`/`Firebase Test Lab 管理员`
   之类的最小权限，而不是 Owner）；
5. 推送一次 `main` 验证新凭据与最小权限仍能完成单矩阵回归；
6. 若泄露发生在本仓库外部，同时建议在密钥仓库侧检查访问审计日志。

---

## 14. 项目保持 Spark，流水线不得启用 Blaze 或绑定结算账号

本项目**明确保持 Firebase Spark（免费）套餐**：

- 流水线**不得**通过任何命令、脚本或配置把项目升级到 Blaze；
- **不得**给 Firebase 项目绑定结算账号；
- 每次 `main push` 最多提交一个 Test Lab 矩阵、一台虚拟设备，以控制免费额度。

若未来确实需要更多设备或更长超时，必须先在团队层面评估并人工变更项目套餐，
本流水线不会自动升级。

---

## 15. PR 合并后的首次运行说明

本次 PR 的行为边界：

- PR 分支中 **Test Lab 未执行**（读不到密钥）；
- `androidTest APK` 已构建但**未在设备上运行**；
- 本 PR 合并到 `main` 产生的 push 将是**第一次真实 Test Lab 执行**；
- 该首次运行预计只消耗**一次虚拟设备测试**；
- 若首次运行失败，**不得自动修改产品代码或自动重试**，需人工分析
  `FTL_STATUS` 与退出码后决定下一步。

---

## 16. 离线验证

PR 分支上真实执行过以下命令（不消耗任何 Test Lab 配额）：

```bash
./gradlew :core:test
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest
bash -n scripts/run-firebase-test-lab.sh
shellcheck scripts/run-firebase-test-lab.sh
bash scripts/test/test-run-firebase-test-lab.sh
```

`scripts/test/test-run-firebase-test-lab.sh` 使用 fake `gcloud` 对
失败路径、退出码分类、门控逻辑做了离线验证（54 项全部通过），fake
仅存在于测试目录，绝不进入生产执行路径。
