# 余量 · Quotile

为 Android 桌面制作的个人套餐额度小组件。以三星 Galaxy Z Fold7 为主要适配目标，使用原生 Android 小组件，可在内外屏的不同可用尺寸间切换布局。

**当前交付是源码工程、同步服务、构建工具和设计预览。尚未生成 APK，也未在 Fold7 上完成安装验证。** 当前运行环境没有 Android SDK，依赖下载请求未获网络放行；Java 语法检查并不等同于 Android 编译通过。真实账号也尚未配对。

## 外观与尺寸

所有尺寸名称采用用户指定的 **高 × 宽**。Android 启动器可能按宽 × 高标注，因此系统显示的 5×1 对应这里的 1×5。

| 尺寸 | 信息安排 |
| --- | --- |
| 1×5 | 周剩余额度、大号百分比、细进度条、重置时间和更新状态；右侧刷新 |
| 2×5 | 账号返回五小时额度时使用双栏；没有五小时项时自动使用更宽裕的每周额度布局 |
| 更窄 | 优先保留每周余量，按可用宽度减少辅助文字 |
| 更高或折叠状态变化 | 根据启动器报告的实际 dp 尺寸重新排版 |

两种入口都允许横向、纵向拖动；支持的逻辑范围为宽 110–700dp、高 40–300dp。实际占几格取决于 One UI 桌面网格、屏幕缩放和启动器提供的空间，并非固定的像素尺寸。要放满五列，所在桌面需要有对应网格空间。

配色包括浅色、深色、跟随系统。明确切换并保存立即应用；桌面跟随系统外观在下次小组件刷新时应用。数值与状态有整体无障碍描述，刷新按钮有独立描述。此版本用 Canvas 绘制卡片，文字不等同于原生 TextView 的系统字体缩放行为。

## 如何得到安装包

最直接的方法：用 Android Studio 打开 `android/`，安装工程要求的 Android SDK，然后执行 **Build APK(s)**。所需版本：JDK 17、Gradle 8.11.1、Android Gradle Plugin 8.10.1、Android SDK Platform 36、Build Tools 35.0.0。

也可以在有上述 SDK 的电脑运行：

```bash
python tools/build_android.py --help
python tools/build_android.py
```

构建工具按固定版本下载并校验 Gradle；成功后才会生成并复制 APK。具体选项以 `--help` 为准。工程附有 GitHub Actions 构建工作流（推送 main 或手动触发），运行结果和 APK 下载见仓库的 Actions 页面。

调试 APK 用于个人试装。升级时需要保留原签名；不同电脑或临时云构建产生的调试签名可能不同，届时不能直接覆盖安装。不要把源码 ZIP 改后缀当作 APK。

## 连接真实额度

手机应用不读取 ChatGPT App 私有数据，也不要求 OpenAI 密码、Cookie 或 API Key。它连接你自己持有的额度读取服务。

1. 在一台可访问 ChatGPT、能保持运行的自有电脑或服务器上，按 `bridge/README.md` 配置桥接服务并完成 Codex 官方登录。
2. 服务只读取套餐额度，提供一个 HTTPS 地址和独立配对码。
3. 打开手机上的「余量」，填写服务地址与配对码，选择外观和 15／30／60 分钟更新间隔，保存。
4. 点击「添加 1×5」或「添加 2×5」，或长按桌面空白处 → 小组件 → 余量。
5. 长按已经添加的卡片，拖动边缘改变大小。需要即时查看时，点击右侧刷新。

首次使用时默认显示「待连接」，不会用示例数字冒充账户额度。设置页的外观预览始终注明「示例数据」；只有主动开启演示模式，桌面才显示带「演示」标记的样本。

安卓会根据省电、网络和后台调度情况推迟任务。手动刷新也由系统安排执行，不能承诺精确秒级同步；最近更新时间指向真正读取成功的数据时间。网络不可用时卡片可能保留上一幅显示，恢复连接后再更新。

## 数据与失效处理

- 通过官方 Codex App Server 的 `account/rateLimits/read` 读取数据；只读额度，不发起模型对话或兑换额度重置。
- 用 `windowDurationMins == 10080` 识别周窗口，`== 300` 识别五小时窗口，不猜 primary/secondary 的含义。
- 仅在账号实际返回时展示对应百分比；缺失窗口显示「未提供」。
- 读取失败保留上次成功快照及原更新时间。到达重置时间不会自行填入 100%。
- 同步服务不把 ChatGPT 凭据发送给手机；手机只保存独立配对码，使用 Android Keystore 加密，不备份到云端。
- 手机仅允许 HTTPS 和有效证书，不跟随重定向；请求不带配对码查询参数。

## 源码与验证

```text
android/                  Android Studio 工程
bridge/                   Python 标准库额度读取服务与测试
design/                   设计预览、生成工具与布局校验
tools/                    构建工具和源码结构／语法检查
.github/workflows/        可选择使用的云构建工作流
VALIDATION.md             本次已完成和未完成的验证记录
```

源码检查和桥接服务测试可离线运行：

```bash
python tools/verify_source.py
python -m unittest discover -s bridge/tests -p 'test_*.py' -v
```

上线前仍需做 Android SDK 编译和手机测试，尤其是 One UI 的 1×5／2×5 添加、内外屏切换、拖动缩放、深浅色、休眠后刷新、断网恢复、账号首次登录和重置后的数据一致性。

## 官方依据

- [OpenAI Codex App Server](https://learn.chatgpt.com/docs/app-server)：认证、只读额度方法与字段。
- [Android 响应式小组件布局](https://developer.android.com/develop/ui/views/appwidgets/layouts)：可调尺寸与折叠屏提供的多个实际尺寸。
- [Android JobInfo.Builder](https://developer.android.com/reference/android/app/job/JobInfo.Builder)：后台周期调度。
- [Android Gradle Plugin 8.10 兼容要求](https://developer.android.com/build/releases/agp-8-10-0-release-notes)。

这是个人定制工具，与 OpenAI 或三星没有官方隶属关系。
