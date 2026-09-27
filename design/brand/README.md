# 拾遗坊品牌资源

[shiyifang-logo.svg](shiyifang-logo.svg) 是唯一可编辑的品牌主资产：蓝金回环钥匙、原长度窄柄、平尾与双齿；金边仅沿两块金色内衬。SVG 使用路径和渐变，没有嵌入位图、滤镜或外部资源。

## 原生资源

```bash
python3 scripts/generate-brand-vectors.py
python3 scripts/generate-brand-vectors.py --check
python3 scripts/test_brand_vectors.py
```

生成器将 SVG 转为应用内彩色 / 单色标记，以及自适应图标的前景和主题图标，输出到 `app/src/main/res/drawable/`。生成的 XML 随源码提交，常规 Android 构建不需要运行生成器，也不依赖 SVG 解析库。

安装开发依赖 `sharp` 后，可以重新生成各密度 PNG：

```bash
node scripts/generate-brand-assets.cjs
node --test scripts/test-brand-assets.cjs
```

真正用于应用的 PNG 位于 `app/src/main/res/mipmap-*`；额外的正方形导出和圆形 / 圆角预览只写入已忽略的 `build/brand-assets/`，不提交。

## 图标约定

- 暖白底，标准图标源是完整 1:1 正方形。自适应图标使用未裁切的前景与矩形背景，外形由系统蒙版决定。
- `data-launcher-scale="0.69"` 保留已选定的 15% 放大比例与居中位置。应用内标记及 Banner 底纹不随之放大。
- 只明确命名的 `ic_launcher_round.png` 后备图使用圆形；API 26+ 使用自适应 XML。
- 当前尺寸超出 66 dp 保守安全区，常规圆形 / 圆角方形预览通过；不能据此推断所有 OEM 蒙版和桌面动效均无裁切。

Banner 的布局与动效规范见 [UI 卡片约定](../../docs/UI_CARD_CONVENTION.md)。候选图、拟合原稿、历史预览和截图不属于维护源资产，已从当前项目树清理。
