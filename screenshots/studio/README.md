# Studio UI 实拍（2026-09-13）

这些图片来自 Android 15 模拟器中运行的真实 Compose 页面。当前品牌标识为中空折带盾牌，
与 `design/brand/rescueauth-symbol.svg` 的路径一致。
`app/src/androidTest/kotlin/com/rescueauth/v2/ui/StudioVisualReviewTest.kt`
通过测试 Activity 注入虚构服务、账户与条目元数据，不打开数据库、不绕过认证。
验证码字段为 `null`（显示圆点占位）；图片不含 TOTP secret、恢复码或 Developer secret。

- `01-welcome.png`：简体中文欢迎页。
- `02-authenticator.png`：6 个服务、12 个虚构账户的目录。
- `03-accounts.png` / `04-credential.png`：账户目录与验证码详情。
- `05-developer.png` / `06-developer-list.png`：五类目录与分类列表。
- `07-settings.png` / `08-transfer.png`：设置与独立的传输中心。
- `09-dark.png`：深色分类目录。
- `10-large-text.png`：英文 1.5 倍字体下的欢迎页，已验证操作可滚动到达。
- `11-removed-account.png`：2026-09-14 新增的条目不可用状态，保留返回操作。

配色已统一为暖白背景、墨色主面板和淡紫强调色；薄荷绿只用于选定的 Logo。

认证器、Developer、设置的 banner 使用同一个 `StudioVaultHero`：
默认高度 144 dp、内边距 20 dp、图形 84 dp；大字体遵循统一增高规则。
`StudioBannerTest` 验证短标题与两行标题高度一致。

运行方式：先编译 Debug / AndroidTest APK，再安装到已解锁的专用测试模拟器。
直接执行 `StudioVisualReviewTest` 后，图片位于测试宿主的
`getExternalFilesDir(null)/ui-review/`。使用直接 instrumentation 运行可在卸载 APK 前导出文件；
Gradle connected 测试结束时可能清理安装包和其目录。

本轮行为回归还覆盖：添加菜单区分凭据与服务、分类进入与返回、搜索类型筛选、
欢迎页开启回调，以及原有数据库和加密兼容测试。视觉实拍不替代真机生物认证验收。
