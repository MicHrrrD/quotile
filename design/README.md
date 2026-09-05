# 余量 · 设计预览

- `quotile_design_preview.png`：浅色、深色的 1×5 和 2×5 布局，以及缩窄布局。
- `quotile_state_preview.png`：未连接、来源未提供、旧数据及超过重置时间的显示。
- `render_previews.py`：用 Pillow 按 `WidgetRenderer.java` 的 dp 尺寸及断点绘制。

这些图片是设计预览，数字和情景均为演示，不是手机或模拟器截图。中文字体与 Android 系统字体的度量可能不同。`preview_checks.json` 仅记录 Python 预览的文本边界检查，不代表 Android 编译、启动器兼容或设备测试通过。

如需重新生成，使用本机已安装的中文字体：

```sh
QUOTILE_PREVIEW_FONT=/path/to/NotoSansSC.ttf python3 render_previews.py
```

实际小组件支持 110–700 dp 宽、40–300 dp 高；宽至少 250 dp 且高至少 116 dp 时使用详细布局，仅在来源提供 5 小时额度时显示双栏。网格的 1×5、2×5 指高×宽，实际 dp 值由启动器决定。
