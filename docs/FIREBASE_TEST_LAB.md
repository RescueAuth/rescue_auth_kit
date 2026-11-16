# Firebase Test Lab CI 接入文档

> 适用于仓库 `xincy22/rescue_auth_kit` 的 Firebase Test Lab（下称 FTL）接入。
> 本文记录当前手动设备测试入口与凭据边界；最终候选版本的验收状态见
> [发布检查清单](RELEASE_PROVISIONING.md#release-readiness)。

---

## 1. 整体架构（手动触发，owner-only，main only）

**产品决策：任何 `push` / PR merge / `main` update 都不自动运行任何
RescueAuth 测试（含 Firebase Test Lab）。** FTL 只通过 GitHub Actions 页面上的
手动触发 **“Android Firebase device tests”**（`android-firebase.yml`，
`workflow_dispatch`）由仓库 owner 在 `main` 分支上运行。

原 `main: push → full-cloud-test-loop` 自动流水线**已移除且不再恢复**。

手动 FTL 工作流的执行流程：

```text
Actions → Android Firebase device tests → Run workflow（main）
→ debug APK                 (./gradlew :app:assembleDebug)
→ androidTest APK           (./gradlew :app:assembleDebugAndroidTest)
→ 产物校验                   (APP_APK / TEST_APK 存在且非空)
→ Firebase Test Lab         (scripts/run-firebase-test-lab.sh，单台虚拟设备)
→ 在 Actions 运行日志中保留测试矩阵结果
```

任何前置命令失败都会让工作流失败，**不会**提交 Test Lab 矩阵。

配置位置：`.github/workflows/android-firebase.yml`，运行环境为
`ubuntu-24.04`，JDK 为 temurin 17，Android SDK 组件为
`platforms;android-35` 与 `build-tools;35.0.0`，与其它工作流保持一致。

配套的手动全回归工作流 **“Android manual builds”**（`android-manual.yml`，
task 选 `full`，**无密钥**）执行 core/JVM tests、Robolectric、lint、assembleDebug、
assembleDebugAndroidTest 与产物校验，**不包含** Firebase Test Lab——
云上设备测试由上面这个手动 FTL 工作流单独承担。

---

## 2. 为什么只在手动 FTL 流水线中读取密钥

FTL 需要 GCP 服务账号凭据。凭据存放在 GitHub 的 **`firebase-test-lab` 环境**
（Settings → Environments → `firebase-test-lab` → Environment secrets）：

| Secret | 内容 |
|--------|------|
| `FIREBASE_PROJECT_ID` | Firebase 项目 ID（`rescue-auth-kit-test`） |
| `GCP_SERVICE_ACCOUNT_JSON_BASE64` | GCP 服务账号 JSON 的 Base64 |

工作流通过 `environment: firebase-test-lab` 声明该环境，只有该 job 能读到
这两个 secret：

```yaml
jobs:
  device-test:
    needs: build
    environment: firebase-test-lab
    env:
      GCP_SERVICE_ACCOUNT_JSON_BASE64: ${{ secrets.GCP_SERVICE_ACCOUNT_JSON_BASE64 }}
      FIREBASE_PROJECT_ID: ${{ secrets.FIREBASE_PROJECT_ID }}
```

工作流由 `workflow_dispatch` 触发，且 job 级 `if` 要求
`github.ref == 'refs/heads/main'`、`github.actor == 'xincy22'`，
其它分支与非 owner 触发都不会进入该 job。

> **环境访问限制（建议配置）**：在 Settings → Environments →
> `firebase-test-lab` → Deployment branches 中限制为 `main` only，
> 与 job 级 `if` 形成双重约束。
>
> **secret 作用域说明（重要）**：GitHub 的 environment secret 只在该 job
> 声明 `environment:` 时可见。`build` job（构建两个 APK）不声明环境，
> 因此**读不到** Firebase 凭据；凭据只注入 `device-test` job。
> “只有手动 FTL 工作流能读取”与“只有 device-test job 能看到”两个约束同时成立。
>
> **仓库治理要求**：`main` 必须保持为**保护分支**；所有
> `.github/workflows/*.yml`、Gradle 脚本与构建脚本（`scripts/*.sh` 等）
> 的变更都必须经过**人工 diff 审查**后才能合并。

---

## 3. 为什么 PR 分支不能运行 Test Lab

PR 分支（如 `auto/xxx`）不是 `main`，`android-firebase.yml` 的 `build` job
带 `if: github.ref == 'refs/heads/main'` 条件，非 `main` 直接跳过；
即使绕过该条件，`firebase-test-lab` 环境的 Deployment branches 限制
也会让 job 拿不到 secret。

推论：**只有 owner 在 `main` 上手动触发 FTL 工作流**才能真实提交 Test Lab 矩阵；
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
| `FTL_COMMIT`                   | 当前提交 SHA(用于 matrix/result 标识)       | 否   |

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

旧 `main push` 自动流水线曾用 `scripts/check-test-lab-gate.sh` 判断本次 push
是否值得提交 FTL 矩阵。该自动流水线已移除，gate 脚本及其自测套件也随 CNB
退役一并删除；它唯一的调用者是 `.cnb.yml`，GitHub 侧没有替代需求。

在手动模式下，是否运行 FTL 完全由 owner 决定：

- **Android manual builds** 的 `full` 任务总是运行完整回归（无 FTL）；
- **Android Firebase device tests** 工作流总是构建并提交一个 FTL 矩阵。

不再存在“自动根据变更范围跳过 FTL”的行为；`main push` 不触发任何 FTL。

---

## 9. 如何从 Actions 日志进入 Firebase 测试矩阵结果

“Android Firebase device tests”工作流的 `device-test` job 日志中会输出:

```text
[ftl] FTL_MATRIX_ID=1234567890123456789
[ftl] FTL_CONSOLE=https://console.firebase.google.com/project/rescue-auth-kit-test/testlab/histories
[ftl] FTL_EXIT_CODE=<gcloud 原始退出码>
[ftl] FTL_STATUS=<分类>
[ftl] FTL_FINAL=<最终分类>
```

打开 Actions 运行页面(`Actions → Android Firebase device tests → 对应运行 →
device-test job`)，复制 `FTL_MATRIX_ID` 或点击 `FTL_CONSOLE` 链接进入 Firebase
控制台 `Test Lab → 测试记录 (Histories)` 查看矩阵结果。

`FTL_MATRIX_ID` 与当前提交 SHA 关联(`--results-dir=ftl-<FTL_COMMIT>`)，
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
`ftl-<FTL_COMMIT>`。结果保留周期遵循 Firebase Test Lab 的默认策略
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
4. 将新值写入 GitHub 仓库的 `firebase-test-lab` 环境 secrets
   (`GCP_SERVICE_ACCOUNT_JSON_BASE64` 与 `FIREBASE_PROJECT_ID`);
5. 在 GCP 删除旧密钥;
6. 在 `main` 上手动运行 **Android Firebase device tests** 验证新凭据可用;
7. 确认矩阵正常后,结束轮换。

> 注意:修改环境 secrets 本身不会触发本仓库任何工作流;轮换验证通过手动 FTL
> 运行进行(一次一个矩阵,不消耗额外的自动 push)。

---

## 13. 发生密钥泄露时的撤销步骤

若服务账号 JSON(或其 Base64)疑似泄露:

1. **立即**在 GCP 控制台删除该服务账号的密钥(IAM 与管理 → 服务账号 →
   该账号 → 密钥 → 删除所有泄露密钥);
2. 若服务账号本身也暴露,删除/停用该服务账号并创建新账号;
3. 生成新的服务账号 JSON 并更新 GitHub 的 `firebase-test-lab` 环境 secret
   `GCP_SERVICE_ACCOUNT_JSON_BASE64`(Base64);
4. 检查 Firebase 项目是否被异常调用,必要时在 IAM 收紧该账号角色
   (Test Lab 只需要 `Cloud Test Lab 服务账号`/`Firebase Test Lab 管理员`
   之类的最小权限,而不是 Owner);
5. 在 `main` 上手动运行 **Android Firebase device tests** 验证新凭据与最小权限
   仍能完成单矩阵回归;
6. 若泄露发生在本仓库外部,同时建议在 GCP 侧检查该服务账号的访问审计日志。

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
| 构建 | CNB `cnb-87g-1jvdnj7iu`（历史记录，CNB 已废弃） |
| 设备 | `MediumPhone.arm`(virtual)/ API 33 |
| matrix | `6707992428877319626` |
| 结果 | `FTL_RAW_GCLOUD_EXIT_CODE=0`、`FTL_EXIT_CODE=0`、`FTL_STATUS=TEST_PASSED`、`FTL_FINAL=TEST_PASSED` |

行为边界(持续有效):

- PR 分支中 **Test Lab 不执行**(工作流限定 `main`,且读不到密钥);
- **只有 owner 在 `main` 上手动运行 “Android Firebase device tests”** 才会提交
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
```

`scripts/test/test-run-firebase-test-lab.sh` 使用 fake `gcloud` 对
失败路径、退出码分类、设备目录校验与产物校验做了离线验证。
fake 仅存在于测试目录,绝不进入生产执行路径。

变更门控脚本 `scripts/check-test-lab-gate.sh` 及其自测套件已随 CNB 退役删除,
上述离线验证清单不再包含它们。

## GitHub migration (2026-10-05)

The GitHub entry is `.github/workflows/android-firebase.yml`: manual dispatch,
main only, owner actor and triggering actor only. APK build and validation run
in a credential-free job; the separate device-test job receives only Firebase
credentials from environment `firebase-test-lab`. Production signing secrets
are not available to either job. Set `GCP_SERVICE_ACCOUNT_JSON_BASE64` and
`FIREBASE_PROJECT_ID` there; restrict deployment to main and require owner review.
The script's `FTL_COMMIT` input receives the GitHub source SHA; it is only a
results label. Same single virtual device and timeout are retained.

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
