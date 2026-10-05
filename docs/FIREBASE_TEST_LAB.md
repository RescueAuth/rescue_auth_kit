# Firebase Test Lab CI 接入文档

> 适用于仓库 `xincy22/rescue_auth_kit` 的 Firebase Test Lab（下称 FTL）接入。
> 本文记录当前手动设备测试入口与凭据边界；最终候选版本的验收状态见
> [发布检查清单](RELEASE_PROVISIONING.md#release-readiness)。

---

## 1. 整体架构（手动触发，owner-only，main only）

**产品决策：任何 `push` / PR merge / `main` update 都不自动运行任何
RescueAuth 测试（含 Firebase Test Lab）。** FTL 只通过页面上的手动按钮
**“Run Firebase device tests”**（`web_trigger_firebase_test`）由仓库 owner 在
`main` 分支上触发。

原 `main: push → full-cloud-test-loop` 自动流水线**已移除且不再恢复**。

手动 FTL 按钮的执行流程：

```text
main 上点击“Run Firebase device tests”
→ debug APK                 (./gradlew :app:assembleDebug)
→ androidTest APK           (./gradlew :app:assembleDebugAndroidTest)
→ 产物校验                   (APP_APK / TEST_APK 存在且非空)
→ Firebase Test Lab         (scripts/run-firebase-test-lab.sh，单台虚拟设备)
→ 在 CNB 日志中保留测试矩阵结果
```

任何前置命令失败都会让流水线失败，**不会**提交 Test Lab 矩阵。

配置位置：`.cnb.yml` 中的 `web_trigger_firebase_test` 流水线（`$` 兜底分支下，
由 `.cnb/web_trigger.yml` 的 `^main$` 按钮触发），构建镜像为固定的
`cimg/android:2026.08`（JDK 17/21 + Android SDK 34/35/36 + build-tools 35.0.0，
版本由镜像 tag 锁定，保证可复现）。

配套的手动全回归按钮 **“Run full RescueAuth test suite”**
（`web_trigger_full_test`，全分支、owner-only、**无密钥**）执行
core/JVM tests、Robolectric、lint、assembleDebug、assembleDebugAndroidTest
与产物校验，**不包含** Firebase Test Lab——云上设备测试单由上面这个手动 FTL 按钮承担。

---

## 2. 为什么只在手动 FTL 流水线中读取密钥

FTL 需要 GCP 服务账号凭据。凭据存放在独立的密钥仓库：

```text
https://cnb.cool/xincy22/rescue_auth_kit_secrets/-/blob/main/firebase-test-lab.yml
```

`web_trigger` 是 CNB 的**可信事件**（不属于官方文档中的“不可信事件”列表），
且按钮已被 `.cnb/web_trigger.yml` 限制为 **owner 角色**并**仅出现在 `main` 分支**。
因此密钥仓库的访问限制可安全配置为：

| 维度       | 限制值                                        |
|-----------|----------------------------------------------|
| repository | `xincy22/rescue_auth_kit`                    |
| event     | `web_trigger_firebase_test`（或 `web_trigger`） |
| branch    | `main`                                       |

因此在 `.cnb.yml` 中，**只有** `web_trigger_firebase_test` 流水线的
`firebase-test-lab` stage 通过 `imports` 引用该文件：

```yaml
$:
  web_trigger_firebase_test:
    - name: run-firebase-device-tests
      stages:
        - name: firebase-test-lab
          imports:
            - https://cnb.cool/xincy22/rescue_auth_kit_secrets/-/blob/main/firebase-test-lab.yml
```

`pull_request`、`issue`、`issue.comment@npc`、`pull_request.comment@npc`、
`tag` 事件以及其他分支、非 owner 的手动触发都**不得**导入密钥。

> **密钥仓库 `firebase-test-lab.yml` 需在 Web 界面补充授权（一次性）**：当前文件
> 的 `allow_events` 若仍为 `push`，请改为包含
> `web_trigger_firebase_test`（或 `web_trigger`），并保持
> `allow_branches: main`。这一步只能由密钥仓库管理员在 Web 界面完成。
> 在完成该授权前，点击“Run Firebase device tests”会在 prepare 阶段因无访问权
> 被拒绝——这是**预期行为**，不是恢复 `main push` 自动测试的理由。
>
> **`imports` 作用域说明（重要）**：`imports` 采用 **Stage 级**作用域（CNB
> 官方 Schema 同时支持 Pipeline 级、Stage 级与 Job 级 `imports`；本配置使用
> Stage 级，且该 stage 未声明 `jobs`，等价于单一 Job，凭据生命周期只覆盖
> `firebase-test-lab` 这一个执行单元）。注入的密钥变量**仅对
> `firebase-test-lab` stage 可见**：build-debug-apk、build-androidtest-apk、
> validate-apks 等前置 stage 均**无法读取** Firebase 凭据。
> “只有手动 FTL 流水线能导入”与“只有 FTL stage 能看到”两个约束同时成立。
>
> **仓库治理要求**：`main` 必须保持为**保护分支**；所有 `.cnb.yml`、
> Gradle 脚本与构建脚本（`scripts/*.sh` 等）的变更都必须经过**人工 diff
> 审查**后才能合并。

---

## 3. 为什么 PR 分支不能运行 Test Lab

PR 分支（如 `auto/xxx`）不属于 `main`，且“Run Firebase device tests”按钮
仅出现在 `main` 分支详情页，无法满足密钥仓库 `repository + event + branch`
的限制，因此**读不到密钥**。

推论：**只有 owner 在 `main` 上手动点击 FTL 按钮**才能真实提交 Test Lab 矩阵；
任何 push / PR 都不会自动触发 FTL。

这是一个有意的安全边界：防止任意 PR 提交或自动 push 消耗测试配额或触发云端设备。

> **Test Lab 只运行 androidTest APK 中的测试**：FTL instrumentation matrix
> 只执行 `--test=<TEST_APK>`（`app-debug-androidTest.apk`）中的用例。
> JVM unit test 与 Robolectric unit test 在本地 stage（`:app:testDebugUnitTest`）
> 中运行，**不会**被 Test Lab 执行。这些是 Robolectric unit test（位于
> `app/src/test/`，`@RunWith(RobolectricTestRunner)`），不是
> androidTest/instrumentation test。
> （注：`SecureScreenFlagTest` 已随 Issue #57 移除——不再全局阻止截图。）

---

## 4. 所需环境变量

`scripts/run-firebase-test-lab.sh` 显式读取以下环境变量:

| 变量                            | 说明                                        | 敏感 |
|--------------------------------|---------------------------------------------|------|
| `APP_APK`                      | app debug APK 的确定路径                     | 否   |
| `TEST_APK`                     | androidTest debug APK 的确定路径             | 否   |
| `FIREBASE_PROJECT_ID`          | Firebase 项目 ID                             | 是   |
| `GCP_SERVICE_ACCOUNT_JSON_BASE64` | GCP 服务账号 JSON 的 Base64                  | 是   |
| `FTL_DEVICE_MODEL`             | 虚拟设备型号(如 `MediumPhone.arm`)            | 否   |
| `FTL_DEVICE_VERSION`           | 虚拟设备 Android API 版本                    | 否   |
| `FTL_DEVICE_LOCALE`            | locale(固定 `en`)                          | 否   |
| `FTL_DEVICE_ORIENTATION`       | 方向(固定 `portrait`)                       | 否   |
| `FTL_TEST_TIMEOUT`             | 矩阵超时(固定 `5m`)                         | 否   |
| `CNB_COMMIT`                   | 当前提交 SHA(用于 matrix/result 标识)       | 否   |

`APP_APK` / `TEST_APK` 使用**确定的 Gradle 输出路径**:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

绝不使用 `find | head -1` 这类不受约束的查找。

凭据的两个变量只做"是否存在"检查,**绝不打印值**;脚本内也不写入
Gradle properties、用户 home 的永久文件或仓库目录。

---

## 5. 设备配置

设备配置通过非敏感环境变量固定:

```text
FTL_DEVICE_MODEL=MediumPhone.arm
FTL_DEVICE_VERSION=33
FTL_DEVICE_LOCALE=en
FTL_DEVICE_ORIENTATION=portrait
FTL_TEST_TIMEOUT=5m
```

`MediumPhone.arm` 是 Test Lab 目录中的 **VIRTUAL(虚拟/模拟器)** 型号,
API 33 受其支持。**不得改用 `panther`**:`panther` 对应的是 Pixel 7
**实体设备**,不符合本流水线"只使用一台虚拟设备"的约束。

脚本在提交矩阵前会通过已认证的 gcloud 查询 FTL 设备目录:

```bash
gcloud firebase test android models describe MediumPhone.arm
```

校验规则:

1. 配置的 model/version 组合必须存在于目录中;
2. 设备 form 必须为 `virtual`(虚拟设备),**不启用物理设备**;
3. 若配置不可用,报告 `CONFIGURATION FAILURE` 并失败,**绝不静默换成随机设备**;
4. 只提交**一个**矩阵(单台虚拟设备),不创建多设备矩阵;
5. **不启用**自动重试或 flaky retry;
6. **不启用** Android Test Orchestrator(仓库此前未配置,接入时保持测试运行模型不变);
7. 目录条目中 `deprecated` 为真的 model **一律拒绝**,报告 `CONFIGURATION FAILURE`;
8. 目录条目中 `reducedStability` 为真的 model **一律拒绝**,报告 `CONFIGURATION FAILURE`;
9. `panther`(Pixel 7 实体设备)**禁止**作为本流水线的设备。

日志中会输出最终使用的 model、API version、locale、orientation、timeout。

---

## 6. Test Lab 官方退出码及分类

`gcloud firebase test android run` 的原始退出码会被**保留并原样返回**,
并按官方语义分类(日志中输出 `FTL_EXIT_CODE` 与 `FTL_STATUS`):

| 退出码 | 官方语义            | 分类(`FTL_STATUS`)                    |
|-------|--------------------|---------------------------------------|
| 0     | 成功               | `TEST_PASSED`                         |
| 1     | 一般/配置错误       | `GENERAL_OR_CONFIGURATION_FAILURE`    |
| 2     | 无效命令或参数       | `INVALID_COMMAND_OR_ARGUMENT`         |
| 10    | 测试失败            | `TEST_FAILED`                         |
| 15    | 无结论/意外错误      | `INCONCLUSIVE`                        |
| 18    | 不支持的测试环境      | `UNSUPPORTED_TEST_ENVIRONMENT`        |
| 19    | 测试被取消          | `TEST_CANCELED`                       |
| 20    | 基础设施错误        | `INFRASTRUCTURE_FAILURE`              |

关键规则:

- **不把所有非零状态都叫产品测试失败**;只有退出码 `10` 才能归类为测试用例失败;
- 退出码 `20` 归类为基础设施失败;
- 配额耗尽**没有假定的固定退出码**;
- 只有当 API 响应或错误文本**明确包含** quota / resource exhaustion 信息时,
  才额外标注 `QUOTA_EXHAUSTED`,同时仍保留真实退出码;
- 测试失败、配置失败、基础设施失败、配额耗尽都会让当前 Test Lab 流水线失败;
- 文档类变更导致主动跳过不算失败。

脚本在 `set -e` 环境下通过 `set +e` 捕获原始退出码,保证分类与清理逻辑不会
因 shell 提前退出而丢失。

---

## 7. Spark 免费额度下为什么默认只跑一台虚拟设备

本项目保持 Firebase **Spark**(免费)套餐。Spark 免费额度通常只包含少量免费
虚拟设备测试分钟数,因此默认只提交**一个矩阵、一台虚拟设备**,以把有限额度
留给真正有价值的回归验证。

流水线**不得**启用 Blaze 或绑定结算账号(见第 14 节)。

---

## 8. 变更门控（gate）现状

`scripts/check-test-lab-gate.sh` 曾用于旧 `main push` 自动流水线，判断本次 push
是否值得提交 FTL 矩阵。**该自动流水线已移除，因此这个 gate 不再被任何流水线
引用**，仅保留脚本与测试作为历史参考。

在手动模式下，是否运行 FTL 完全由 owner 决定：

- “Run full RescueAuth test suite”手动按钮总是运行完整回归（无 FTL）；
- “Run Firebase device tests”手动按钮总是构建并提交一个 FTL 矩阵。

不再存在“自动根据变更范围跳过 FTL”的行为；`main push` 不触发任何 FTL。

---

## 9. 如何从 CNB 日志进入 Firebase 测试矩阵结果

“Run Firebase device tests”手动按钮的 `firebase-test-lab` 阶段日志中会输出:

```text
[ftl] FTL_MATRIX_ID=1234567890123456789
[ftl] FTL_CONSOLE=https://console.firebase.google.com/project/rescue-auth-kit-test/testlab/histories
[ftl] FTL_EXIT_CODE=<gcloud 原始退出码>
[ftl] FTL_STATUS=<分类>
[ftl] FTL_FINAL=<最终分类>
```

打开 CNB 构建日志页面(`云原生构建 → 对应构建记录 → firebase-test-lab 阶段`),
复制 `FTL_MATRIX_ID` 或点击 `FTL_CONSOLE` 链接进入 Firebase 控制台
`Test Lab → 测试记录 (Histories)` 查看矩阵结果。

`FTL_MATRIX_ID` 与当前提交 SHA 关联(`--results-dir=ftl-<CNB_COMMIT>`),
便于把一次构建与一次矩阵一一对应。

---

## 10. 如何区分各类失败

| 现象(日志特征)                                  | 判定                                            |
|------------------------------------------------|------------------------------------------------|
| `FTL_STATUS=TEST_FAILED`,退出码 10             | **产品测试失败**:测试用例断言不通过             |
| 前置阶段 `assembleDebugAndroidTest` 失败         | **测试 APK 构建失败**:代码/依赖/AGP 问题        |
| `CONFIGURATION FAILURE` + 缺变量/缺 APK          | **配置错误**:环境变量缺失、APK 缺失或非法        |
| `CONFIGURATION FAILURE` + 设备 model/version     | **设备组合不可用**:目录中不存在或非虚拟设备      |
| `FTL_STATUS=INFRASTRUCTURE_FAILURE`,退出码 20   | **基础设施错误**:Test Lab 服务侧问题             |
| `FTL_STATUS=QUOTA_EXHAUSTED`(保留真实退出码)     | **配额耗尽**:Spark 免费额度用尽                 |
| 日志输出 `TEST LAB NOT EXECUTED: documentation-only change` | **文档类跳过**(不算失败)            |

---

## 11. 默认结果保留周期和结果链接用途

Test Lab 结果默认存放在 Firebase 项目绑定的默认 Cloud Storage 桶中
(`gs://test-lab-<...>` 或项目默认桶,以 Firebase 控制台展示为准),结果目录为
`ftl-<CNB_COMMIT>`。结果保留周期遵循 Firebase Test Lab 的默认策略
(通常约 30 天,以 Firebase 控制台展示为准)。结果链接用于:

- 快速回溯某次构建对应的设备测试结果;
- 区分"产品测试失败"与"基础设施/配额问题";
- 归档每次手动 FTL 运行的真实设备回归证据。

结果仅用于**排查与回溯**,不作为长期存储,请勿依赖过期链接。

---

## 12. 服务账号密钥轮换步骤

当需要轮换 FTL 服务账号密钥时:

1. 在 GCP 控制台(IAM 与管理 → 服务账号)找到用于 Test Lab 的服务账号;
2. 选择"密钥"标签 → "添加密钥" → "创建新密钥",下载新的 JSON;
3. 对 JSON 做 `base64 -w0` 得到 `GCP_SERVICE_ACCOUNT_JSON_BASE64`;
4. 将新值写入密钥仓库
   `xincy22/rescue_auth_kit_secrets` 的 `firebase-test-lab.yml`;
5. 在 GCP 删除旧密钥;
6. 在 `main` 分支上点击 **“Run Firebase device tests”** 手动按钮
   验证新凭据可用;
7. 确认矩阵正常后,结束轮换。

> 注意:密钥仓库文件变更本身不会触发本仓库流水线;轮换验证通过手动 FTL 按钮进行
> (一次一个矩阵,不消耗额外的自动 push)。

---

## 13. 发生密钥泄露时的撤销步骤

若服务账号 JSON(或其 Base64)疑似泄露:

1. **立即**在 GCP 控制台删除该服务账号的密钥(IAM 与管理 → 服务账号 →
   该账号 → 密钥 → 删除所有泄露密钥);
2. 若服务账号本身也暴露,删除/停用该服务账号并创建新账号;
3. 生成新的服务账号 JSON 并更新密钥仓库
   `xincy22/rescue_auth_kit_secrets/firebase-test-lab.yml`(Base64);
4. 检查 Firebase 项目是否被异常调用,必要时在 IAM 收紧该账号角色
   (Test Lab 只需要 `Cloud Test Lab 服务账号`/`Firebase Test Lab 管理员`
   之类的最小权限,而不是 Owner);
5. 在 `main` 上点击 **“Run Firebase device tests”** 验证新凭据与最小权限仍能
   完成单矩阵回归;
6. 若泄露发生在本仓库外部,同时建议在密钥仓库侧检查访问审计日志。

---

## 14. 项目保持 Spark,流水线不得启用 Blaze 或绑定结算账号

本项目**明确保持 Firebase Spark(免费)套餐**:

- 流水线**不得**通过任何命令、脚本或配置把项目升级到 Blaze;
- **不得**给 Firebase 项目绑定结算账号;
- 每次手动 FTL 运行最多提交一个 Test Lab 矩阵、一台虚拟设备,以控制免费额度。

若未来确实需要更多设备或更长超时,必须先在团队层面评估并人工变更项目套餐,
本流水线不会自动升级。

---

## 15. 首次真实执行记录(已发生)

Firebase Test Lab 已在本仓库 `main` 上真实执行并成功:

| 项 | 值 |
| --- | --- |
| 提交 | `9561956b`(PR #15 合并) |
| 构建 | `cnb-87g-1jvdnj7iu` |
| 设备 | `MediumPhone.arm`(virtual)/ API 33 |
| matrix | `6707992428877319626` |
| 结果 | `FTL_RAW_GCLOUD_EXIT_CODE=0`、`FTL_EXIT_CODE=0`、`FTL_STATUS=TEST_PASSED`、`FTL_FINAL=TEST_PASSED` |

行为边界(持续有效):

- PR 分支中 **Test Lab 不执行**(按钮仅出现在 `main`,且读不到密钥);
- **只有 owner 在 `main` 上手动点击 “Run Firebase device tests”** 才会提交
  一个矩阵;普通 push / PR merge / main update 都不会自动触发;
- 若某次运行失败,**不得自动修改产品代码或自动重试**,需人工分析
  `FTL_STATUS` 与退出码后决定下一步。

---

## 16. 离线验证

PR 分支上真实执行过以下命令(不消耗任何 Test Lab 配额):

```bash
./gradlew :core:test
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest
bash -n scripts/run-firebase-test-lab.sh
shellcheck scripts/run-firebase-test-lab.sh
bash scripts/test/test-run-firebase-test-lab.sh
bash scripts/test/test-check-test-lab-gate.sh
```

`scripts/test/test-run-firebase-test-lab.sh` 使用 fake `gcloud` 对
失败路径、退出码分类、门控逻辑做了离线验证(88 项全部通过);
`scripts/test/test-check-test-lab-gate.sh` 是变更门控的专项回归套件
(29 项全部通过),覆盖 `app/**`、`core/**`、`gradle/**`、
version catalog、CI/runner 自变更等所有必触发路径,以及 `docs/**`、
`README*` 等纯文档跳过分支,并用 PR #13 的真实路径
(`app/src/androidTest/.../RescueAuthDatabaseInstrumentedTest.kt`)
作为回归样例。fake 仅存在于测试目录,绝不进入生产执行路径。

## GitHub migration (2026-10-05)

The GitHub entry is `.github/workflows/android-firebase.yml`: manual dispatch,
main only, owner actor and triggering actor only. APK build and validation run
in a credential-free job; the separate device-test job receives only Firebase
credentials from environment `firebase-test-lab`. Production signing secrets
are not available to either job. Set `GCP_SERVICE_ACCOUNT_JSON_BASE64` and
`FIREBASE_PROJECT_ID` there; restrict deployment to main and require owner review.
The existing script's `CNB_COMMIT` input receives the GitHub source SHA; it is
only a results label, not a CNB authorization bypass. Same single virtual device
and timeout are retained. No cloud matrix has been executed by this migration.

### Screenshot output in FTL

The first GitHub matrix (`matrix-3jrax66wiy2st`, source `2043b87`) ran 157
tests: 67 passed, 90 failed. The inspected screenshot failure was
`SecondaryVisualReviewTest.capture:96`: missing `additionalTestOutputDir`.
Gradle connected tests inject that argument, whereas the standalone gcloud
command did not. Pass an app-specific external test-output directory explicitly
and collect the same directory with `--directories-to-pull`. Tests and assertions
remain enabled; a corrected matrix must pass before claiming device acceptance.
Only existing synthetic, secret-free visual test data may be captured.

The second matrix (`matrix-29rymw9whn4uj`, source `68e1aed`) improved to
100 passed / 57 failed. The inspected failure was ENOENT writing to the
app-specific external directory. Use `/sdcard/Download/rescueauth-test-output`
with instrumentation-only `no-isolated-storage=true`, following the Android
performance-samples FTL configuration. Pull that same narrow directory.
This affects the disposable cloud instrumentation invocation only; no manifest
permission, production storage policy, or test assertion is changed.
Reference: https://github.com/android/performance-samples/blob/main/.github/workflows/firebase_test_lab.yml

Third matrix (`matrix-29e1hi1ofc3wa`, source `25fe0ac`) reached 138/157
passed. Remaining failures match 16 StudioVisualReviewTest methods calling
enableEdgeToEdge off the main thread, two 48 dp eye-button comparisons affected
by fractional-density subtraction, and RouteFrameTimingTest calling the API 34
UiAutomation.clearCache method on API 33. Fix test setup on the UI thread,
compare the same 48 dp minimum in rounded physical pixels, and gate clearCache
at API 34. Keep all rendering/interaction assertions and all 157 cases. A new
complete FTL matrix is required; these changes are not themselves acceptance.

Fourth matrix (`matrix-2kaeh3uyucxnj`, source `0eedfdc`) reached 156/157
passed. Only RouteFrameTimingTest remained: an accessibility Back action was
rejected. API 34+ already clears the node cache before lookup; older platforms
now reapply the unchanged UiAutomation service info, whose setter clears that
cache in AOSP. The test still requires ACTION_CLICK success and frame reports;
no coordinate-click fallback or assertion skip is added. Full rerun required.
AOSP reference: https://android.googlesource.com/platform/frameworks/base/+/eec68e55cc6661837030c8ecb4386d05b1d31685/core/java/android/app/UiAutomation.java

### Accepted GitHub matrix — 2026-10-05

Source: `a685304739705bde21798e42a61711c1451423f8`.
GitHub run: https://github.com/RescueAuth/rescue_auth_kit/actions/runs/37277312320
Firebase matrix: `4872339796755753964`, MediumPhone.arm / API 33 / en / portrait.
Result: **157 test cases passed**, `FTL_FINAL=TEST_PASSED`, workflow success.
All cases remain enabled, including screenshot and route frame timing cases.
Earlier 67/157, 100/157, 138/157 and 156/157 runs remain failed historical
results; they were not converted to passes or excluded from coverage.

This accepts the current Debug instrumentation regression and GitHub Firebase
credential/approval path. It does not accept a production-signed APK, physical
biometrics/Keystore behavior, or the hosted stable update channel.
