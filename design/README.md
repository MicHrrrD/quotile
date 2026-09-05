# 小组件外观

当前 0.3.4 使用原生 Android 布局。当前基准为 `android/app/src/main/java/dev/mich/quotile/WidgetRenderer.java` 与 `android/app/src/main/res/layout/widget.xml`。

最新成品预览位于 [docs/images](../docs/images)，项目首页展示的是这些原生控件图，数据均为示例。

柔白 #F7F7F8、近黑 #181818、9–11dp 胶囊进度条。1×5 与 2×5 指高×宽；桌面控件直接按启动器尺寸布局，不再缩放整张图片。

最新构建会在 Android16 上导出 `android-previews/*.png`，随 Actions 产物提供；它们来自实际原生控件与合成演示数据。

本目录既有 `quotile_design_preview.png`、`quotile_state_preview.png` 和 `render_previews.py` 属于 0.1.0 历史设计，保留用于追溯，不代表当前成品。
