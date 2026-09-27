# 拾遗坊品牌资源

[shiyifang-logo.svg](shiyifang-logo.svg) 是钥匙标记的唯一可编辑主资产：蓝金回环钥匙、原长度窄柄、平尾与双齿；金边仅沿两块金色内衬。SVG 使用路径和渐变，没有嵌入位图、滤镜或外部资源。中文艺术字为独立透明 PNG，见下文。

## 原生资源

```bash
python3 scripts/generate-brand-vectors.py
python3 scripts/generate-brand-vectors.py --check
python3 scripts/test_brand_vectors.py
```

生成器将 SVG 转为应用内彩色 / 单色标记，以及自适应图标的前景、主题图标和渐变背景，输出到 `app/src/main/res/drawable/`。生成的 XML 随源码提交，常规 Android 构建不需要运行生成器，也不依赖 SVG 解析库。

安装开发依赖 `sharp` 后，可以重新生成各密度 PNG：

```bash
node scripts/generate-brand-assets.cjs
node --test scripts/test-brand-assets.cjs
```

真正用于应用的 PNG 位于 `app/src/main/res/mipmap-*`；额外的正方形导出和圆形 / 圆角预览只写入已忽略的 `build/brand-assets/`，不提交。
`rescueauth-adaptive-*-preview` 使用居中的 66 dp 裁切预览，避免把完整 108 dp 前景画布误当作桌面上的可见范围。

## 图标约定

- 背景为左上暖白 `#FFF7E8` 到右下浅蓝 `#A4C8F0` 的渐变；两端颜色由 SVG 主资产的 `data-launcher-background-start/end` 统一指定。`data-launcher-background-span="66"` 将渐变集中于 108 dp 画布的 `(21,21)` 至 `(87,87)`，避免系统裁切后只剩近似单色的中段；外围延续端点色。Android VectorDrawable 背景和 PNG 使用相同配置。
- 标准图标源仍为完整 1:1 正方形。自适应图标使用未裁切的前景与矩形渐变背景，外形由系统蒙版决定。
- 2026-09-27 真机反馈修正：`data-launcher-scale="0.552"`，相对之前 `0.69` 缩小 20%，中心位置保持一致。彩色与单色桌面前景同步调整；应用内标记及 Banner 底纹的几何不变。
- 只明确命名的 `ic_launcher_round.png` 后备图使用圆形；API 26+ 使用自适应 XML。
- 前景栅格检查覆盖居中的 66 dp 保守安全圆，圆形 / 圆角方形裁切预览均检查。桌面仍可按厂商蒙版和动效继续裁切，预览不等于所有设备的实测。
- `LauncherIconRenderTest` 从已安装 APK 读取真实自适应图标并绘制，检查裁切后背景两端仍有可见色差；本地截图写入 AndroidTest 构建输出。

## 中文艺术字

- 已选定第 1 款「轻行书」，运行时资源为 [`shiyifang_wordmark.png`](../../app/src/main/res/drawable-nodpi/shiyifang_wordmark.png)。来源为内置 imagegen；提示摘要：仅三个准确的汉字「拾遗坊」，克制的轻行书、墨蓝笔画，真正透明的背景，不带底板或额外图形。
- 保留所选原图的 RGBA 像素与 alpha，不重新抠图、不加白底、不另行描摹；放在 `drawable-nodpi` 避免按资源密度放大。原图 2073 × 758，绘制窗口为 `(133,91)` 起的 1847 × 579，窗口外仅为留白和极淡生成噪点，完整原文件仍可复用。
- `RescueAuthWordmark` 共用于三个首页 Banner 和首次开启页：默认高度分别为 28 dp 与 40 dp，随字体比例放大。浅色保留原墨蓝色，深色通过 alpha 蒙版着为主题前景色；不添加不透明背景。
- 中文图像仅有一个本地化读屏名称；英文继续显示原生文字 `RescueAuth`，跟随字体设置与布局约束。该图片不是字体文件，不用于正文或其他任意文案。

Banner 的布局与动效规范见 [UI 卡片约定](../../docs/UI_CARD_CONVENTION.md)。候选图、拟合原稿、历史预览和截图不属于维护源资产，已从当前项目树清理。
