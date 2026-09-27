# 拾遗坊品牌资源

[shiyifang-logo.svg](shiyifang-logo.svg) 是唯一可编辑的品牌主资产：蓝金回环钥匙、原长度窄柄、平尾与双齿；金边仅沿两块金色内衬。SVG 使用路径和渐变，没有嵌入位图、滤镜或外部资源。

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

- 背景为左上暖白 `#FFFDF7` 到右下冰蓝 `#E1EAF5` 的轻渐变；两端颜色由 SVG 主资产的 `data-launcher-background-start/end` 统一指定，Android 背景和 PNG 使用相同配置。
- 标准图标源仍为完整 1:1 正方形。自适应图标使用未裁切的前景与矩形渐变背景，外形由系统蒙版决定。
- 2026-09-27 真机反馈修正：`data-launcher-scale="0.552"`，相对之前 `0.69` 缩小 20%，中心位置保持一致。彩色与单色桌面前景同步调整；应用内标记及 Banner 底纹的几何不变。
- 只明确命名的 `ic_launcher_round.png` 后备图使用圆形；API 26+ 使用自适应 XML。
- 前景栅格检查覆盖居中的 66 dp 保守安全圆，圆形 / 圆角方形裁切预览均检查。桌面仍可按厂商蒙版和动效继续裁切，预览不等于所有设备的实测。

Banner 的布局与动效规范见 [UI 卡片约定](../../docs/UI_CARD_CONVENTION.md)。候选图、拟合原稿、历史预览和截图不属于维护源资产，已从当前项目树清理。
