# 验证记录 · 0.3.0 · 2026-09-05

## 已确认

- 用户已在 Galaxy Z Fold7 上安装 0.2.0，并确认正常运行、登录和读取额度。
- 0.3.0 保留成功使用的 AccountClient、TokenVault 和额度解析接口。
- 原生 RemoteViews 文字、胶囊进度条、矢量图标取代桌面整图缩放。
- 桌面刷新改为显式广播，不启动 Activity、不新建手动 Job；保留默认关闭的自动刷新开关。
- Java17 语法、XML、资源引用和 Manifest 静态检查通过。

## 当前构建

[最终 Android16 构建](https://github.com/MicHrrrD/quotile/actions/runs/33959427790) 全部通过。应用源码提交为 `9a20b7e61cb5e4561ede22921e359cd4f9422865`。

- Android SDK36 编译、资源链接、构建签名通过；包名 `dev.mich.quotile`，versionCode `3`，versionName `0.3.0`。
- 真实广播入口在未登录状态下不启动 MainActivity 或 RefreshActivity、不遗留读取线程、不创建手动定时任务。
- 自动刷新默认关闭、关闭任务取消、主题及间隔不使手动结果失效的检查通过。
- 原生 TextView、ProgressBar 和 24 种尺寸/主题/额度组合渲染通过，包含 1×5 百分比区域确实有文字像素的回归检查。
- 已目检最终紧凑、双栏、窄尺寸截图，修复了单行横向滚动导致右对齐百分比不可见的问题。
- 原始模拟器结果 `INSTRUMENTATION_CODE: -1`；测试没有使用真实账号或请求上游额度接口。
- 取回产物的 SHA-256 与 Actions 一致。使用 Android 官方 apksigner 本地重签后，v2/v3 签名均验证通过，证书与私下保存的固定私钥一致；每个非签名 ZIP 条目的内容校验值与测试 APK 完全一致。

交付 APK：`Quotile-0.3.0.apk`，132541 字节。

```text
SHA-256: 456c9116d8501bd496dd5b6003da6c440d7b0349ac60005300d8291fe394e80b
证书 SHA-256: d2cb32c3211f1d587e5900d646222a7aca046142cb0eb99adb0eb5f7f1f26079
```

本地签名工具已实际运行验证：密钥和密钥库口令使用不同临时文件，避免 apksigner 重用文件读取器导致 EOF；分别验证默认 v3 与指定验证范围下的 v2。私钥及口令不在仓库中。

0.2.0 的临时调试签名无法用于这次升级，需要先卸载旧版；以后应继续使用上述固定签名交付覆盖更新。

## 实机边界

0.3.0 的 One UI 内外屏、缩放、原地刷新体验需用户更新后确认。用户报告的真实额度读取成功来自 0.2.0。数据源仍是 Codex 套餐额度，不能据此推断所有 ChatGPT 模型的独立限额。

0.2.0 [历史构建](https://github.com/MicHrrrD/quotile/actions/runs/33957345128) 已通过编译、签名与模拟器检查。
