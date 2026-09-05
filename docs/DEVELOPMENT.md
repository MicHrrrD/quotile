# 开发、构建与验证

## 环境

| 组件 | 项目版本 |
| --- | --- |
| Android 最低版本 | Android 12 / API 31 |
| 编译与目标 SDK | 36 |
| Android Build Tools | 35.0.0 |
| JDK | CI 使用 17 |
| Gradle / Android Gradle Plugin | 8.11.1 / 8.10.1 |
| 包名 | `dev.mich.quotile` |

用 Android Studio 打开 `android/`，通过 SDK Manager 安装 SDK Platform 36 和 Build Tools 35.0.0，并自行阅读和接受 SDK 许可。构建脚本不会替你安装 SDK 或接受许可。

在仓库根目录运行：

```sh
python tools/build_android.py --check
python tools/verify_source.py
python tools/build_android.py
```

`--check` 只检查已安装的前置环境。普通在线构建可下载并校验固定版本的官方 Gradle；完整缓存后可用 `--offline`。`--java-home`、`--sdk` 和 `--gradle` 支持显式指定路径。输出为 `dist/Quotile-0.3.6.apk`，这是本机构建工具签出的调试 APK。

## 目录导航

| 路径 | 内容 |
| --- | --- |
| `android/app/src/main/java/dev/mich/quotile/` | 登录、额度解析、本地存储、刷新与原生小组件 |
| `android/app/src/main/res/` | 布局、主题、矢量图标与小组件声明 |
| `android/app/src/androidTest/` | 使用合成数据的 Android instrumentation |
| `tools/` | 构建、源码检查、设备检查和交付签名脚本 |
| `docs/images/` | 当前版本原生控件预览；均为示例数据 |
| `downloads/` | 已验证的 0.3.6 固定签名安装包与校验信息 |
| `bridge/`、`design/` | 历史桥接实现和设计资料；手机版不依赖 bridge |

`AccountClient` 管理有时限的登录和读取；`RateLimitParser` 解析返回的 Codex 额度及可用重置次数；`TokenVault` 存储加密凭据；`QuotaStore` 保存设置和快照；`WidgetRenderer` 布局原生 RemoteViews；`QuotaSync`、`Schedule` 管理手动读取与可关闭的定时任务。

## 重置机会到期信息

可用次数始终取自 `GET /backend-api/wham/usage` 的 `rate_limit_reset_credits.available_count`。只有该次数大于 0 时，才在同次刷新中执行 `GET /backend-api/wham/rate-limit-reset-credits`；附加请求最多使用 4 秒，并受整次读取 25 秒的总预算约束。辅助读取失败不丢弃已成功解析的主要额度，也不会触发额度读取之外的独立后台任务。

最近到期时间必须依据完整、有效的可用明细确定，列表缺失、截断或日期不明确时保留未知状态；不能通过明细列表长度、历史累计次数或部分有效项推算。只持久化用于显示的最近到期时间，不保存原始明细或机会 ID，不调用 `consume` 接口。

详细布局在可用次数大于 0 时，于次数下方显示最近到期时间或「到期时间未提供」。0 或未知次数隐藏该行，紧凑布局不增加到期行。显示时间为北京时间；缓存期限过去后提示旧数据、等待刷新，不自行扣减次数或发起请求。中等高度且宽度足够时，将正常状态的更新时间合并到次数行，为到期信息保留空间；更高的卡片可使用独立更新时间。

## 测试证据

[0.3.6 的构建与 Android 16 模拟器验证](https://github.com/MicHrrrD/quotile/actions/runs/33980902708) 已通过，测试源码提交为 `2a8ac35d5e9a80bafb7f30ac3d743285942a2000`。

- 24 个基本原生渲染用例，61 个文字、次数、到期时间、尺寸和字体缩放布局用例，以及 1 个真实 RemoteViews.reapply 更新回归。
- 额度/重置次数与到期明细解析、辅助失败、取消/登出、未知字段、旧快照兼容与持久化。
- OAuth 回调、PKCE、合成设备码登录、取消/超时和加密存储。
- 默认不自动读取、关闭定时任务、桌面刷新广播不启动 Activity。

本轮只调整详细卡片的上下留白；沿用到期信息及归零/缩小后清除旧行的验证，并增加截图对应比例的原生预览。详细历史见 [VALIDATION.md](../VALIDATION.md)。模拟器通过不能替代所有手机启动器的实机验证；欢迎报告带设备与系统版本的兼容性问题。

设备检查脚本会安装测试 APK，并使用合成凭据、修改应用测试状态，请只在模拟器或专用测试设备上运行：

```sh
gradle --no-daemon -p android :app:assembleDebug :app:assembleDebugAndroidTest
python tools/check_manual_android.py
```

## CI 与签名

[构建工作流](../.github/workflows/build-android.yml) 在 `main` / `mobile-local` 推送时或手动触发，使用 Android 16 模拟器验证。纯文档提交可标注 `[skip ci]`，避免重复构建未改动的应用。

Actions 产物使用 CI 的调试签名；`downloads/` 中的交付 APK 使用维护者单独保管的固定签名。自行构建、CI 调试包和维护者交付包可能签名不同，不能互相覆盖安装。请保管自己的签名密钥，或为开发版本改用不同包名。

维护者重签流程使用 `tools/sign_apk.py`：输入已测试 APK、官方 `apksigner.jar`、仓库外私钥和口令文件；脚本验证签名证书、ZIP CRC 和全部非签名应用内容不变。私钥及口令不进入仓库或 Actions。

## 贡献

界面问题请附尺寸（高×宽）、字体/显示缩放与脱敏截图；额度问题请描述显示状态，不要上传登录数据或完整接口响应。功能建议和普通问题可提交 [Issues](https://github.com/MicHrrrD/quotile/issues)。PR 请说明用户可见的变化和验证范围，保持默认手动刷新行为。
