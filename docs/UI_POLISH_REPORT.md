# 拾遗坊界面整理与提交清理

## 最终行为

- 首页与导航使用“账户 / Accounts”；服务、账户、验证码和恢复码的原有数据结构不变。
- 外观支持浅色、深色与跟随系统。紧凑 Dock 跟随主题，选中底块平滑移动，去除叠加的按压加深效果。
- 页面采用切入 / 切出：详情进入 220 ms、返回 200 ms；三个首页按 Dock 顺序做 220 ms 横向推移，Dock 保持固定，指示块直接跟随页面的同一移动进度。文字不做整页淡入淡出，转场中不改变页面高度。
- 三个首页使用低开销的品牌底纹，仅账户页显示账户总数。Banner 文字首帧完整显示，180 ms 淡入仅作用于装饰底纹。关于与欢迎页共用蓝金钥匙画面；检查更新固定在关于页底部。
- 输入框保留白底 / 深色 surface、大圆角与现有描边，标签在空白时位于框内，聚焦或有内容时浮到边框。删除和显隐位于输入槽尾部，名称 / 内容等宽，并验证普通与大字体对齐。
- 验证码表单直接展开，字段可滚动，操作栏位于键盘上方。页面、表单、摘要卡及底部操作使用公共组件；Native 与 Legacy 导入仅复用展示层。
- 五类 Developer 完整编辑器在读取敏感值前执行独立 one-shot 重认证，离开、锁定和过期回调不会继续加载或复用授权。

布局约定见 [UI_CARD_CONVENTION.md](UI_CARD_CONVENTION.md)，模板边界见
[UI_PAGE_TEMPLATES.md](UI_PAGE_TEMPLATES.md)，编辑认证见
[UI_DEVELOPER_ACTIONS_REPORT.md](UI_DEVELOPER_ACTIONS_REPORT.md)。

## 保留与清理

保留应用代码、回归测试、`design/brand/shiyifang-logo.svg` 主资产，以及 Android 实际使用的 VectorDrawable 和各密度图标。
资源生成器与对应检查可重新生成、核对运行时资源；额外预览导出写入已忽略的 `build/brand-assets/`。

候选图、被替换的品牌稿、PNG 拟合原稿、截图 / 录屏、预览 HTML、临时验证 JSON、浏览器控制服务及本地演示数据初始化器均已清理。
清理范围为本地设计与审阅产物，不涉及 legacy fixtures、备份兼容能力或签名材料。
`screenshots/` 与设计草稿目录加入忽略规则；生成图片不再随普通源码提交进入版本库。

## 验证

原始界面整理基线 `331605d` 的 2026-09-27 提交前验证（JDK 17、Android 15 / API 35 专用模拟器）：

- Core JVM：420 / 420；App JVM / Robolectric：767 / 767；原生 instrumented：78 / 78，均无失败、错误或跳过。
- Debug APK 与 instrumented 测试 APK 构建成功。
- Android lint：0 error、212 warning、1 information。告警包括现有依赖检查、资源 / 命名建议以及有意保留的完整正方形图标；未为消除告警修改加密依赖或系统裁切方案。
- SVG / VectorDrawable 一致性检查通过；6 项矢量与 3 项图标资源测试通过。清理和调整生成器输出位置前后，应用使用的 XML / PNG 字节一致。
- Dock 的英文长标签测试显式固定测试语言，避免受设备语言影响；窄屏、大字体、点击、键盘操作与动效检查通过。

执行命令：

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
ANDROID_SERIAL=<专用测试模拟器> ./gradlew :app:connectedDebugAndroidTest
python3 scripts/generate-brand-vectors.py --check
python3 scripts/test_brand_vectors.py
node scripts/generate-brand-assets.cjs
node --test scripts/test-brand-assets.cjs
```

原生截图检查使用虚构元数据和空秘密字段；覆盖浅深色、英文 / 中文、大字体、输入法、滚动、浮动标签与尾部图标对齐。
图片作为临时输出人工检查，不纳入源码；相关 instrumented 用例保留以便重新生成。

## 同日页面转场修复

真机 Debug 试用反馈指出进出页面显得拖沓。原先 `NavHost` 没有显式转场配置，
使用项目所依赖的 Navigation Compose 2.8.2 默认 700 ms 淡入淡出；Banner 又将整张卡片淡入。
现已使用上述切入 / 切出与首页共用进度方案，动画和几何约定见
[UI_CARD_CONVENTION.md](UI_CARD_CONVENTION.md)。

- 账户 / 开发者 / 设置由一个保留的 `HorizontalPager` 承载；详情仍使用 NavHost。
  共享 ViewModel 继续归 App Shell，首页滚动不会重建 Vault 会话或重启数据订阅。
- Dock 指示块在布局放置阶段读取页面位置；不以每帧进度重组整个 App Shell，
  也不另启一套位移动画。连续点击取消前一个页面滚动，详情返回恢复原先首页。
- 新增 9 项原生动态检查，以及顶层启动路由和保存状态恢复的 2 项 JVM 回归。覆盖中间帧同步、跨两个标签、
  正反向 / RTL、连续点击、系统返回、进入 / 返回详情的稳定尺寸、系统关闭动画和首帧文字对比度。
- 保留旧首页路由 ID / 入口，避免恢复先前保存的返回栈时找不到目的地；详情页恢复后返回仍选中此前首页。
- 修复后全量命令通过：Core **420/420**、App JVM **769/769**、原生 **87/87**；
  无失败、错误或跳过。Debug / AndroidTest APK 构建成功；lint **0 error、254 warning、1 information**。
- 原生中间帧截图已检查：首页与指示块共同推进，详情以前景完整覆盖背景，
  文本保持清晰。截图写入忽略的 AndroidTest 构建输出，不进入版本库。

这些检查验证导航行为、位置同步与时间预算，不是用户真机的帧率测量；
实际设备体验仍属于发布候选包 smoke 的验收内容。

## 桌面图标留白与背景修正（2026-09-27）

- 根据真机桌面裁切反馈，启动器前景由 `0.69` 调为 `0.552`，等比缩小 20%，
  彩色和主题单色图标保持居中。前景栅格检查通过居中的 66 dp 保守安全圆。
- 桌面背景改为左上暖白 `#FFFDF7` 到右下冰蓝 `#E1EAF5` 的浅渐变。
  SVG 主资产统一指定色值，生成 Android 矩形渐变背景与各密度 PNG；圆角由系统裁切。
- 应用内彩色 / 单色 Logo 的 VectorDrawable 与调整前逐字节一致。
  圆形、圆角方形预览已检查，预览额外模拟中心 66 dp 裁切，文件只写入忽略的构建目录。
- 6 项矢量测试与 4 项图标测试通过；Core 420/420、App JVM 769/769、
  原生 87/87 通过，无失败或跳过；Debug / AndroidTest APK 构建通过，lint 无错误。
- 模拟器冷启动曾遇到用户未解锁、System UI 无响应弹窗，分别影响数据库访问和窗口焦点；
  恢复解锁与正常焦点后完整重跑通过。未因此修改应用行为或放宽测试断言。

## 兼容性与边界

数据库 schema、包格式、加密算法、签名公钥、applicationId 和版本号均未改变。
原生 / 旧版导入仍使用各自 parser、密码规则与容量限制；共享合并引擎和既有回归保持有效。
模拟器及 fake prompt 验证不替代真机生物识别、Keystore 有效期或 production-signed release smoke。
本次提交没有创建生产发布 tag，发布前置项仍以 `RELEASE_PROVISIONING.md` 为准。
