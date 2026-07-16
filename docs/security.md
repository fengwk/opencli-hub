# 安全说明

`opencli-hub` 的安全模型以“受保护的内网控制面 + 独立浏览器登录态”为前提。本文件说明产品已实现的边界，以及部署方必须承担的责任。

## 1. 信任边界

Hub 不实现认证、授权、JWT、Session、Bearer Token 或 VNC 密码。所有到 Hub 的入口都应位于 Gateway/反向代理之后，由部署方完成：

- TLS 终止与证书管理；
- 用户、服务身份与权限校验；
- API 与 VNC WebSocket 的访问控制、限流、审计和网络隔离；
- 对同步执行接口设置符合业务的请求 timeout。

Hub 自身默认只把 HTTP 发布到宿主 `127.0.0.1`。不要把容器 `8080` 或 VNC 端口直接公开到不受信任网络。

## 2. Browser Bridge 与 signing key

正式 Chrome 通过 Linux managed policy 从容器 loopback 更新服务安装已签名 CRX；Hub 不依赖 `--load-extension` 或 `--disable-extensions-except`。CRX signing key 必须：

- 由部署方生成、加密保存并长期稳定使用；
- 仅经 BuildKit secret `opencli_extension_signing_key` 在构建期提供；
- 不进入 Git、Docker context、image layer、最终镜像、日志或持久 volume；
- 泄露时视为 extension identity 泄露，按受控 key rotation 流程处理。

`--enable-unsafe-extension-debugging` 是 Browser Bridge 与 Chrome 150 的正式兼容参数。它不允许通过命令行加载 unpacked extension，也不取代 managed policy。正式 Chrome 不以 `--no-sandbox` 运行；Compose 的 seccomp 配置是为 Chrome sandbox 兼容而保留。

## 3. 网络与 VNC

- x11vnc 仅监听容器 `127.0.0.1`，没有外部 TCP VNC 发布。
- 客户端通过 Hub 的 `/api/instances/{id}/vnc` WebSocket 访问 VNC；Gateway 必须限制该 Upgrade 请求。
- CRX 更新服务也仅监听容器 loopback，且只服务固定 CRX、manifest、metadata 和 health 路径。
- Browser proxy 仅作用于 Chrome 网站流量，不代理 Hub HTTP、OpenCLI daemon 或 loopback control flow。

## 4. 命令、数据与路径

- 调用方只能执行当前 OpenCLI Catalog 中公开、未被黑名单禁用的命令；Hub 校验类型、必填项和 choices，并自行组装 profile/JSON output 参数。
- 单个 Instance 串行执行。Hub 不会自动对写命令 failover 或重试，避免重复副作用。
- 资源服务拒绝 traversal、分隔符、控制字符和已存在 symlink，使用 virtual path 暴露文件；调用方应使用 API 返回的 URL，而非自行拼接路径。
- Instance Profile、Cookie、extension storage、资源、执行输出和日志可能包含登录态或业务数据。`/data/opencli-hub`、`/var/lib/opencli`、MySQL 数据卷及其备份必须按敏感数据保护。

资源路径检查无法消除同权限恶意本地进程在检查和文件操作之间替换目录项的极窄 TOCTOU 窗口。容器和宿主隔离、卷权限和最小化本地访问仍是部署方责任。

## 5. 代理与日志

`CUSTOM` proxy 不支持用户名/密码，防止凭据进入数据库和 Chrome 命令行。只允许带显式端口的 `http`、`https`、`socks4`、`socks5` URI；使用需要认证的代理时，应在 Hub 外部完成身份处理。

系统日志、Chrome/Xvfb/openbox/x11vnc 日志和执行输出可能含 URL、页面内容或错误上下文。将日志 API 和下载端点视为敏感接口，并配置存储保留、访问审计和备份加密。

## 6. 已知边界

- Java `ProcessHandle` 无法可靠找回已被 reparent 的后台后代。Hub 保证执行 deadline、输出 capture 和请求返回有界，但不能替代容器级进程隔离。
- MySQL 5.7.44 已 EOL。若必须使用，应通过网络、最小权限、补偿控制和升级规划降低风险。
- Hub 没有高可用、多节点调度、跨 Instance 自动重试或用户认证能力；不要将这些能力假设为已实现。

漏洞报告应使用部署组织既有的私密安全通道处理，避免在日志、Issue 或聊天中披露密钥、登录态、Profile 或可复现攻击数据。
