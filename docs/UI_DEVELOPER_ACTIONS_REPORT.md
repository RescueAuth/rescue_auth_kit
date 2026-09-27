# Developer 页面统一操作栏与编辑重认证

定稿方向来自 2026-09-26 的页面审阅；实现与最终验证完成于 2026-09-27。

## 页面结果

- 五类 Developer 详情采用 `ProtectedFieldsCard`：短提示收进字段卡片顶栏，点击展开说明。详情页不再显示独立的“详细信息”标题。
- 详情与添加 / 编辑表单共用 `RescueAuthActionBar`：起始端为删除或取消 / 返回，末端为编辑或继续 / 保存。采用细边框、蓝灰色分区、图标与文字，无阴影和渐变。
- 形状、尺寸和颜色统一放在 `CardTokens`。默认主操作较宽；窄屏或大字体均分空间，窄屏大字体时图标置于文字上方，标签有足够宽度。按钮最小高度 52 dp，等高增高。
- 底栏保持可见，正文独立滚动，键盘出现时上移；主 Activity 显式使用 `adjustResize`。安全说明弹层的独立窗口跟随应用明暗主题设置系统栏图标。
- 原生截图验证已覆盖该布局；PNG / WebP、预览网页与索引是本地生成物，提交前清理，不纳入版本库。保留 `SecondaryVisualReviewTest` / `UnifiedActionBarTest` 以便重新验证。

## 编辑验证

`DeveloperFormViewModel.beginEdit` 在访问 repository 和预填既有值之前，调用共享 `SensitiveActionGate` 请求 `EDIT_DEVELOPER_ENTRY`。
目标绑定 `DeveloperEdit(stableId, attemptId)`；attemptId 每次重新生成，仅存于内存。成功授权立即消费，之后只加载原始目标。

完整编辑器会读取敏感字段，因此即使仅计划修改标题等元数据，也先验证。新建空白条目不增加认证。
一次验证用于当前完整编辑流程；再次进入编辑器需要新的验证，不能复用显示、复制或此前编辑的授权。

直接进入和恢复编辑路由使用同一入口。验证未完成时不渲染表单；取消、失败或认证不可用会返回。
离开路由或会话锁定会清除表单、取消所属待处理请求并放弃迟到的加载结果。页面未增加“编辑需要验证”的说明文案。

gate 另外按 prompt generation 丢弃过期回调，校验成功结果与待处理 request 一致，并在执行前复查会话。
生命周期取消会通知等待方 `Cancelled`，避免编辑加载一直等待。
保存完成后立即执行返回回调，修复原有流程等待未挂载 Snackbar 而无法退出的问题。

相关契约已同步至 `PRODUCT.md`、`ROADMAP.md §5.6`、`AGENTS.md`、ADR-0006、ADR-0011 和 UI 卡片约定。

## 验证结果

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
ANDROID_SERIAL=emulator-5582 ./gradlew :app:connectedDebugAndroidTest
```

- core：420 项，0 失败；本轮任务命中有效的 UP-TO-DATE 结果。
- app JVM / Robolectric：755 项，0 失败；包含编辑验证、真实表单路由与保存退出回归。
- Android 15 专用模拟器：53 项，0 失败、0 跳过；包含 6 项数据库测试、导航回归和实际字体 / 键盘 / 滚动检查。
- Debug APK、AndroidTest APK、`lintDebug` 均成功。
- 直接 instrumentation 另外运行 23 项页面与操作栏用例，导出 21 张虚构数据截图；浏览预览选取本轮相关的 15 个状态。

关键新覆盖：五类编辑器验证前不读取 payload；取消 / 失败 / 不可用；其他目标 / 操作结果被拒绝；重复请求；离开路由与锁定；旧授权与页面恢复；保存后退出；320 dp + 1.5 倍字体无裁字；RTL；禁用按钮；键盘上方操作栏；滚动后底栏与签名密钥操作可达。

## 兼容性与验证边界

未改变数据库 schema、portable package 格式、legacy import、加密算法、签名配置或 applicationId。
旧版 / 原生 package 与 merge 的原有回归仍通过。普通删除 Undo、签名密钥删除确认、显示 / 复制 / 导出各自的认证路径保持原有操作语义。

认证测试在真实 ViewModel / repository / route 边界注入 fake prompt；没有把模拟结果当作真实生物识别硬件验证。
本轮没有完成真实设备的指纹 / 人脸 / Device Credential、Keystore 有效期或 production-signed release smoke；既有发布阻塞项仍按 `RELEASE_PROVISIONING.md` 跟踪。
