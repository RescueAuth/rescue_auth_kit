# 拾遗坊界面整理与提交清理

## 最终行为

- 首页与导航使用“账户 / Accounts”；服务、账户、验证码和恢复码的原有数据结构不变。
- 外观支持浅色、深色与跟随系统。紧凑 Dock 跟随主题，选中底块平滑移动，去除叠加的按压加深效果。
- 三个首页使用低开销的品牌底纹，仅账户页显示账户总数。关于与欢迎页共用蓝金钥匙画面；检查更新固定在关于页底部。
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

2026-09-27 提交前验证（JDK 17、Android 15 / API 35 专用模拟器）：

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

## 兼容性与边界

数据库 schema、包格式、加密算法、签名公钥、applicationId 和版本号均未改变。
原生 / 旧版导入仍使用各自 parser、密码规则与容量限制；共享合并引擎和既有回归保持有效。
模拟器及 fake prompt 验证不替代真机生物识别、Keystore 有效期或 production-signed release smoke。
本次提交没有创建生产发布 tag，发布前置项仍以 `RELEASE_PROVISIONING.md` 为准。
