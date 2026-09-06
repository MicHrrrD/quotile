# 安装包 · 0.3.9

[下载 APK](Quotile-0.3.9.apk?raw=true) · [下载含许可说明的完整 ZIP](Quotile-0.3.9.zip?raw=true) · [使用指南](../docs/USAGE.md)

当前为社区预览版本，适用于 Android 12 及以上。此处 APK 使用维护者固定密钥签名，构建与 Android 16 检查状态：全部通过；仍采用项目的 debug 构建类型，并非应用商店发行版。包名 `dev.mich.quotile`，versionCode `12`，versionName `0.3.9`。

从此前同一维护者签名的 0.3.0–0.3.8 可覆盖安装；其他来源、自行构建或 0.2.0 的签名可能不同。遇到签名冲突先核对来源，卸载会清除应用数据。

本版修复刷新完成后胶囊余量条短暂闪烁的问题，并将展开调整为约 0.9 秒：柔和起步、平滑展开、轻缓收尾，让从无到有的过程更清楚。保留横向胶囊、刷新箭头旋转与一致的按钮尺寸；支持 60／120Hz 显示环境，实际帧率取决于系统和桌面。其余布局、配色和功能保持不变。

APK SHA-256：

```text
11071ad63e85757487bb14b2c26a86c1eeaeee653838f12abab510c4228b139c
```

签名证书 SHA-256：

```text
d2cb32c3211f1d587e5900d646222a7aca046142cb0eb99adb0eb5f7f1f26079
```

完整校验清单见 [SHA256SUMS](SHA256SUMS)。ZIP 同时提供项目许可证、第三方来源与相关许可全文，不包含签名私钥、口令、用户账号或签名工具。

源码与验证证据：[构建运行](https://github.com/MicHrrrD/quotile/actions/runs/34010695205)、[测试源码提交](https://github.com/MicHrrrD/quotile/commit/cebf1b9cdb05126c1912caba578771d469a991fc)、[验证记录](../VALIDATION.md)。

项目代码适用 [Apache-2.0](../LICENSE)，第三方材料见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
