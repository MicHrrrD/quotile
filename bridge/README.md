# Quotile 额度桥接

给手机上的 Quotile 提供只读额度快照。桥接运行在你自己的电脑或云主机上，使用该主机的 Codex CLI 登录状态；手机只保存独立的桥接配对码。

实现调用官方 App Server 的 `account/rateLimits/read`。它在初始化连接后读取额度，不创建 thread、不开始 turn，也不发送模型任务。官方返回中可能存在多个额度桶，当前默认选择 `codex`。[官方 App Server 文档](https://learn.chatgpt.com/docs/app-server)

显示范围由实际返回决定：只有窗口长度明确为 **300 分钟**或 **10080 分钟**时，才分别显示 5 小时和每周额度；其他长度、缺失或异常数据显示为未知。`planType` 可能只返回 `pro`，本程序不会据此补成“Pro 5x”。这个接口不保证覆盖 ChatGPT 所有模型、工具和订阅额度。

## 连接手机

1. 在你自己的主机准备 **Python 3.10 或更新版本**和官方 **Codex CLI**。桥接本身只使用 Python 标准库，无需 `pip install`。Codex CLI 安装方法见[官方仓库说明](https://github.com/openai/codex#installing-and-running-codex-cli)。

2. 由你本人在该主机运行以下命令，按照官方流程选择 **Login with ChatGPT / Sign in with ChatGPT**。登录用户必须与之后运行桥接的系统用户一致。

   ```bash
   codex login
   ```

   不需要把 ChatGPT 密码、Cookie、访问令牌或 Codex 凭据文件交给任何人，也不要把它们填进手机的配对码栏。桥接源码不直接读取或导出这些凭据；登录和认证由本机 Codex CLI 处理。

3. 进入解压后的 `quotile/bridge` 目录，生成配置：

   ```bash
   python3 quotile_bridge.py --init
   ```

   默认保存到 `~/.config/quotile/config.json`；在 Linux/macOS 上，新文件权限为 `0600`，仅当前用户可读写。命令**只输出文件路径，不输出配对码**，已有配置不会被覆盖。你可以在自己的主机上用文本编辑器打开这个文件，其中的 `token` 值就是手机需要的配对码，请只填入自己的 Quotile。

4. 启动桥接：

   ```bash
   python3 quotile_bridge.py
   ```

   默认只监听 `127.0.0.1:8765`，然后按下方示例添加 HTTPS。使用自选配置路径时，初始化和启动都传同一个参数：

   ```bash
   python3 quotile_bridge.py --config /你的绝对路径/config.json --init
   python3 quotile_bridge.py --config /你的绝对路径/config.json
   ```

5. 在手机 Quotile 中填写桥接的 **HTTPS 地址**与上述 **配对码**，保存并刷新。例如填写 `https://quota.example.com`；应用会请求 `/v1/quota`。这里的域名必须换成你自己的真实域名。

## HTTPS 示例

以下方案使用同一台主机上的 Caddy 反向代理。先把你自己的域名指向主机，并准备好 Caddy 所需的 80/443 端口；桥接仍保持 `127.0.0.1:8765`，不开放 8765 公网访问。Caddy 可为符合条件的域名自动管理 HTTPS 证书。[Caddy 官方反向代理说明](https://caddyserver.com/docs/quick-starts/reverse-proxy)

将下面的 `quota.example.com` **替换成你自己的域名**，加入 Caddyfile：

```caddyfile
quota.example.com {
    log {
        output discard
    }
    reverse_proxy 127.0.0.1:8765
}
```

此示例丢弃该站点的访问日志。不要开启 `debug`、`log_credentials` 或添加记录请求头的自定义日志，避免把 `Authorization` 中的配对码写进日志。桥接自身也不记录请求头、请求 URL 或上游错误原文。[Caddy 官方日志说明](https://caddyserver.com/docs/caddyfile/directives/log)

手机只接受 HTTPS，并检查证书有效性；应使用手机信任的证书和匹配的域名。也可在桥接配置中同时设置 `tls_cert` 与 `tls_key`，直接提供 TLS；监听非回环地址时，这两个配置项必须同时提供。

## 配置项

| 字段 | 默认值 | 含义 |
| --- | --- | --- |
| `token` | 初始化时随机生成 | 独立的桥接配对码；32–256 个英文字母、数字、`_` 或 `-` |
| `listen` | `127.0.0.1` | 明确的 IPv4/IPv6 地址；反向代理场景保持默认 |
| `port` | `8765` | 监听端口，1–65535 |
| `limit_id` | `codex` | 选择官方返回的额度桶，不能用它推断不存在的额度 |
| `codex_binary` | `codex` | Codex 可执行文件名或绝对路径 |
| `rpc_timeout` | `4.0` | 单次上游读取总超时，0.1–5 秒 |
| `tls_cert` | `null` | 直接提供 TLS 时使用的证书链文件路径 |
| `tls_key` | `null` | 与证书对应的私钥文件路径 |

在 Linux/macOS 上，配置文件必须由运行桥接的用户拥有，其他用户不能有读取、写入或执行权限；通常使用 `chmod 600 ~/.config/quotile/config.json`。修改配置后重启桥接。更换 `token` 后，手机也需要更新配对码。

## 接口与数据

`GET /healthz` 返回 `{"ok":true}`，无需配对码，仅用于确认桥接 HTTP 服务可达，不能证明 Codex 已登录或额度读取成功。

`GET /v1/quota` 要求以下请求头，不支持把配对码放在 URL 中：

```http
Authorization: Bearer <你的桥接配对码>
```

桥接按需读取数据并缓存 **30 秒**；这段时间内的请求共用快照，不会每次请求上游。上游读取超时或失败时保留旧快照和原 `updatedAt`，设置 `stale: true`，不会把旧数字标成刚更新。首次读取就失败时，额度为 `null`、`updatedAt` 为 `0`。

若上游成功响应，但没有指定额度桶或两个窗口都不可识别，会返回相应错误和 `null` 字段。只识别到一个窗口时，正常返回该窗口，另一个为 `null`，`error` 为 `null`。此时 `updatedAt` 表示本次成功收到响应的时间，不代表未知额度已经可用。快照中的重置时间已过去时，也会标记 `stale: true`；程序不会自动把剩余额度重置成 100%。

| 响应字段 | 类型 | 含义 |
| --- | --- | --- |
| `schemaVersion` | 整数 | 当前为 `1` |
| `plan` | 字符串或 `null` | 上游额度桶返回的 `planType` 原值 |
| `fiveHour` | 对象或 `null` | 官方明确返回的 300 分钟窗口 |
| `weekly` | 对象或 `null` | 官方明确返回的 10080 分钟窗口 |
| `*.remainingPercent` | 数字 | `100 - usedPercent`，范围 0–100；未知不是 0 |
| `*.resetsAt` | 整数 | 对应窗口的官方重置时间，Unix 秒 |
| `updatedAt` | 整数 | 最近成功获取快照的时间，Unix 秒；从未成功时为 0 |
| `stale` | 布尔值 | 数据是否需要重新确认 |
| `error` | 字符串或 `null` | 状态码；无错误时为 `null` |

| HTTP 状态 | `error` | 含义与处理 |
| --- | --- | --- |
| `200` | `null` | 已取得可识别的额度窗口，仍需查看 `stale` |
| `200` | `upstream_timeout` | Codex 读取超时；保留旧快照，稍后刷新 |
| `200` | `upstream_unavailable` | Codex 无法启动、未能读取或响应异常；检查 CLI 路径、本人登录状态和主机连接 |
| `200` | `bucket_unavailable` | 官方没有返回配置指定的额度桶 |
| `200` | `quota_window_unavailable` | 两个窗口都无法识别，原因可能是缺失、长度不符、重复或数值异常 |
| `401` | `unauthorized` | 缺少配对码、配对码不符或认证头无效 |
| `404` | `not_found` | 路径不支持；确认请求 `/v1/quota` |
| `429` | `rate_limited` | 请求过密；响应包含 `Retry-After: 5` |
| `400` / `501` 等 | `invalid_request` | HTTP 请求格式或方法不支持 |

请求有并发上限与按来源地址的速率限制；使用反向代理时，桥接看到的来源通常都是本机代理，因此各客户端会共用这一限制。上游读取失败也返回 `200`，便于手机显示旧值与过期状态；请同时检查 JSON 中的 `error` 和 `stale`。

## 云主机长期运行（可选）

以下为 Linux 的 systemd **用户服务**模板。假定项目位于 `~/quotile`，且已经由当前用户完成 Codex 登录与桥接初始化。路径不同时请先调整。将模板保存为 `~/.config/systemd/user/quotile-bridge.service`：

```ini
[Unit]
Description=Quotile quota bridge

[Service]
Type=simple
WorkingDirectory=%h/quotile/bridge
ExecStart=/usr/bin/python3 %h/quotile/bridge/quotile_bridge.py --config %h/.config/quotile/config.json
Restart=on-failure
RestartSec=5
UMask=0077
NoNewPrivileges=true

[Install]
WantedBy=default.target
```

确认 `/usr/bin/python3` 满足版本要求。systemd 的 `PATH` 可能与你的交互终端不同：将配置里的 `codex_binary` 改为实际绝对路径，并确保 Codex 需要的运行环境（例如 Node.js）在用户服务的 `PATH` 中。

```bash
systemctl --user daemon-reload
systemctl --user enable --now quotile-bridge.service
systemctl --user status quotile-bridge.service
```

如需退出 SSH 后继续运行，可让主机管理员执行 `loginctl enable-linger 用户名`，把“用户名”换成实际运行用户。HTTPS 代理也需要保持运行。停止桥接可执行 `systemctl --user stop quotile-bridge.service`；这不会自动退出 Codex 账号。

## 本地测试

在 `quotile/bridge` 目录运行：

```bash
python -m unittest discover -s tests -v
```

确保这里的 `python` 指向 Python 3.10 或更新版本，也可替换为 `python3`。测试使用模拟上游数据，无需登录 ChatGPT；测试通过只验证代码行为，真实额度是否可用仍由本人主机上的官方接口响应决定。
