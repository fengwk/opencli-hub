# opencli-hub

`opencli-hub` 是一个单机部署的 OpenCLI Browser Bridge 管理平台。它管理多套相互隔离的正式 Google Chrome Profile，并把受控、经过验证的 OpenCLI 命令路由到已登录的浏览器 Instance。

## 一句话了解项目

将浏览器登录态集中保存在 Hub 管理的 Chrome Profile 中；业务系统通过 API 请求 Hub，由 Hub 将合法的 OpenCLI 命令在指定 Instance 中执行并返回结果。它适用于内部浏览器能力平台，不是通用浏览器自动化框架，也不替代 OpenCLI、Chrome 或上游 API Gateway。

## 是否适合使用

适合：需要集中维护多个站点登录态，并通过 API 稳定调用 OpenCLI 浏览器命令的单机内部服务。

不适合：需要多节点调度、远程 Agent、Kubernetes、Redis/MQ、WebDriver/Selenium/Playwright，或希望 Hub 自身提供用户认证、授权和会话管理的场景。

## 选择部署方式

| 目标 | 使用方式 | 数据库前置条件 |
|---|---|---|
| 演示、小规模单机或持久化 H2 | `compose.h2.yml` | 无外部数据库；首次启动由 H2 profile 初始化。 |
| 新建独立 MySQL 5.7.44 | `compose.yml` | MySQL 官方 entrypoint 在新 volume 创建数据库和应用账号。 |
| 接入已有/托管 MySQL | 设置 `mysql` profile 与 JDBC 环境变量 | 数据库维护者先手工创建空 `opencli_hub` 数据库；Hub 随后初始化当前表和基础数据。 |

无论选择哪种方式，先准备稳定、受保护的 CRX signing key，然后阅读下方对应的 H2 或 MySQL 启动步骤。完整部署、备份和升级流程见[部署与运维](docs/deployment-and-operations.md)。

## 必须接受的安全边界

- Hub 不提供认证、授权、JWT、Session 或 VNC 密码。生产入口必须通过 SCG Gateway 或等价反向代理提供 TLS、认证、授权、限流和审计。
- 不要把 Hub HTTP 或 VNC TCP 直接暴露给不受信任网络；VNC 仅通过同源 Hub WebSocket 和 Gateway 访问。
- Chrome Profile、Cookie、资源、日志和数据库都可能包含敏感数据，持久卷与备份必须受限访问并加密保护。
- CRX signing key 是部署身份的一部分；它只能通过 BuildKit secret 提供，绝不能进入 Git、Docker context、镜像层、运行时文件系统或日志。

详见[安全说明](docs/security.md)。

## 核心能力与产品边界

- 每个 Instance 独占 Chrome Profile、Xvfb、openbox、x11vnc 和 Browser Bridge `contextId`。
- Hub 共享一个 OpenCLI daemon，并按受控 Command Catalog 校验参数和重建 argv；不接受任意 shell/CLI 透传。
- 单个 Instance 串行执行且有界排队；支持显式 `instanceId` 粘性路由；不自动 failover，也不自动重试写命令。
- 提供 Instance 生命周期、VNC WebSocket、执行历史、资源、日志、命令黑名单/输出规则和浏览器代理设置。
- 支持通过管理端配置 OpenCLI 插件源，并调用官方 `opencli plugin install/update/list` 同步；详见 [插件维护](docs/plugins.md)。
- 支持持久化 H2 单容器与 MySQL 5.7.44。两个 profile 都通过 Spring SQL initialization 幂等应用当前 schema/data；旧 MySQL schema 的结构升级仍须显式迁移。
- 后端 ID 是不透明字符串：新记录使用 UUID，旧正 BIGINT ID 保留为十进制字符串。集成方不得将 ID 转为 JavaScript `Number`。

## 架构

```text
管理端 / 调用方
        |
   Gateway: TLS + authz + rate limit
        |
opencli-hub (Spring Boot + React SPA)
  |-- Instance lifecycle -> Xvfb + openbox + x11vnc + Google Chrome
  |                                      |                 |
  |                                      |            managed CRX
  |-- VNC WebSocket proxy <-------------+                 |
  |-- controlled execution -> shared OpenCLI daemon <-----+
  |-- MySQL 或 H2、资源、日志、Profile 卷
```

VNC TCP 只监听容器 loopback；客户端只通过同源 WebSocket `/api/instances/{id}/vnc` 访问。Browser Bridge CRX 也只由容器 loopback 更新服务提供。

## 版本与前置条件

| 项目 | Release 基线 |
|---|---|
| Hub | `1.0.0` |
| Java | 17 |
| 前端构建 | Node.js 20 + npm lockfile |
| Google Chrome | `150.0.7871.114-1`，仅 `linux/amd64` |
| OpenCLI | 见 `scripts/docker/opencli-artifact.lock.env`（当前 fork `1.8.7-fengwk.3`） |
| Browser Bridge extension | 见同一 lock（当前 fork `1.0.24`） |
| MySQL | `5.7.44`（已 EOL） |

Docker 部署要求 Docker Engine 支持 BuildKit 和 Compose `build.secrets`。Compose 固定 `shm_size: 2gb` 与 `seccomp=unconfined`；上线前必须验证宿主 Docker/seccomp 策略允许 Chrome sandbox 正常运行。

### OpenCLI artifact lock

Hub 镜像构建通过单一锁文件固定 CLI 与 Browser Bridge extension 产物：

```text
scripts/docker/opencli-artifact.lock.env
```

`docker build`、Compose、`scripts/docker/build-local.sh` 和 CI 默认都读取该文件，因此日常升级只需编辑这一处并重新构建。锁文件同时记录 package/version、CLI tarball URL/SHA256、source revision、extension version/URL/SHA256。镜像内会生成可读的
`/opt/opencli/artifact-build-info.json`，smoke 用其中的 CLI 版本与 `opencli --version` 对照，不再硬编码版本号。

运行时镜像固定 `OPENCLI_DISABLE_UPDATE_CHECK=1`：官方 baseline 与未来 fork CLI 都只通过编辑
`scripts/docker/opencli-artifact.lock.env` 并重建镜像升级，不依赖 OpenCLI 运行时 updater 提示或联网版本检查。

升级规则：

1. **成对升级**：CLI 与 extension 必须来自同一 OpenCLI Release，不要只改一侧。
2. **校验和必填**：任何远程 CLI tarball / extension zip 都必须写入对应 SHA256；构建会先校验再安装。
3. **默认 lock 只指向已发布资产**：当前钉住已验证的 `fork-v1.8.7-fengwk.3`；不要提交未发布的本地产物 URL。
4. **后续升级 fork Release 时只改 lock**，当前值为：

```bash
# scripts/docker/opencli-artifact.lock.env
OPENCLI_PACKAGE=@jackwener/opencli
OPENCLI_VERSION=1.8.7-fengwk.3
OPENCLI_CLI_URL=https://github.com/fengwk/OpenCLI/releases/download/fork-v1.8.7-fengwk.3/jackwener-opencli-1.8.7-fengwk.3.tgz
OPENCLI_CLI_SHA256=05c5ec2bcfea81bb44d1cdae6958ff6c83019ed4aeed86b22d8c8c0d2856dc2c
OPENCLI_SOURCE_REVISION=fengwk/OpenCLI@df693d324e97a1201cebb7d246c34009b49eaa27
EXTENSION_VERSION=1.0.24
OPENCLI_EXTENSION_URL=https://github.com/fengwk/OpenCLI/releases/download/fork-v1.8.7-fengwk.3/opencli-extension-v1.0.24.zip
OPENCLI_EXTENSION_SHA256=f3b65ff894fe4679dd6a8723dd463ca035c8cf5bc0ee36dd402dc90fc0c7f844
```

可选 build-arg 覆盖范围（仅当前构建生效，不改仓库默认 pin）：

| Build arg | 作用 |
|---|---|
| `OPENCLI_PACKAGE` / `OPENCLI_VERSION` | 覆盖 CLI package 与期望版本 |
| `OPENCLI_CLI_URL` / `OPENCLI_CLI_SHA256` | 覆盖 CLI tarball 与校验和；二者必须成对覆盖，不可只改一侧 |
| `OPENCLI_SOURCE_REVISION` | 覆盖写入 `artifact-build-info.json` 的来源修订 |
| `EXTENSION_VERSION` | 覆盖 extension 版本（CRX 打包也使用解析后的值） |
| `OPENCLI_EXTENSION_URL` / `OPENCLI_EXTENSION_SHA256` | 覆盖 extension zip 与校验和；二者必须成对覆盖 |

```bash
scripts/docker/validate-opencli-artifact-lock.sh
scripts/docker/test-install-opencli.sh
```

### 生成并保护 CRX signing key

每次 release 构建都需要**受保护且长期稳定**的 RSA 私钥签名 Browser Bridge CRX。该 key 决定 Chrome extension identity：同一部署线升级必须复用同一把 key；随意轮换会改变 extension ID。

仓库、Docker build context、镜像层和运行时镜像都不包含私钥。默认路径是被 Git/Docker 忽略的 `.secrets/opencli-extension-signing-key.pem`；也可通过 `OPENCLI_HUB_EXTENSION_SIGNING_KEY_FILE` 指向受管密钥文件。

```bash
umask 077
mkdir -p .secrets
openssl genpkey -algorithm RSA \
  -pkeyopt rsa_keygen_bits:2048 \
  -out .secrets/opencli-extension-signing-key.pem
chmod 600 .secrets/opencli-extension-signing-key.pem
```

Dockerfile 只在构建期以 BuildKit secret `opencli_extension_signing_key` 挂载该文件；构建完成后 key 不存在于 CRX、运行时文件系统或镜像 layer。缺少、不可读或为空的 key 时本地构建/Smoke 会明确失败。不要使用仓库历史中曾出现过的私钥。

`--enable-unsafe-extension-debugging` 是 Chrome 150 下 Browser Bridge 的正式运行参数，不是可在生产中删除的 unpacked-extension 调试开关；extension 仍完全由 managed policy 和已签名 CRX 安装。

## 快速开始：持久化 H2

H2 适合单容器部署、演示和小规模单机运行；它不是仅供单测的 profile。完成上面的 key 准备后，在仓库根目录执行：

```bash
docker compose -f compose.h2.yml up --build -d
curl --fail --show-error http://127.0.0.1:8080/actuator/health
curl --fail --show-error http://127.0.0.1:8080/api/instances
```

镜像默认发布到 `127.0.0.1:8080`，并使用：

| Volume | 内容 |
|---|---|
| `opencli-hub-h2-data` | H2 文件数据库、日志、资源和 Instance Profile 根目录 |
| `opencli-hub-h2-home` | OpenCLI home/data |

停止但保留数据：

```bash
docker compose -f compose.h2.yml down
```

只有明确要销毁全部 H2 数据、Profile 和登录态时才执行：

```bash
docker compose -f compose.h2.yml down --volumes
```

容器 Smoke 使用独立 Compose project 和默认宿主端口 `18080`，不会创建 Instance，也不宣称 Chrome E2E 覆盖：

```bash
scripts/docker/smoke-h2.sh
# 网络受限且已有宿主 npm/Maven 缓存时：
OPENCLI_HUB_SMOKE_BUILD_MODE=local scripts/docker/smoke-h2.sh
```

## MySQL 新装与既有库升级

`compose.yml` 固定使用 `mysql:5.7.44`。MySQL 官方 entrypoint 在**新建** volume 首次启动时创建
`opencli_hub` 数据库和应用账号；Hub 的 `mysql` profile 随后通过 Spring SQL initialization 在每次
启动时幂等执行 classpath `schema-mysql.sql` 和 `data-mysql.sql`。两个密码没有默认值：

```bash
export MYSQL_ROOT_PASSWORD="$(openssl rand -hex 32)"
export OPENCLI_HUB_MYSQL_PASSWORD="$(openssl rand -hex 32)"
docker compose -f compose.yml up --build -d
curl --fail --show-error http://127.0.0.1:8080/actuator/health
```

接入已有或托管的 MySQL（包括 NAS 复用的 `vps-mysql`）时，Hub **不会创建数据库本身**。启动 Hub 前，
由数据库维护者手工创建空库；Hub 启动后才会通过 Spring SQL initialization 创建当前表和基础数据：

```sql
CREATE DATABASE IF NOT EXISTS opencli_hub
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;
```

Hub 使用的数据库账号还必须具备该库的建表和写入初始化数据权限。既有 schema 的结构升级不属于初始化职责，
仍须执行下文的版本化迁移。

Hub JDBC 使用 `mysql_native_password`；`mysql` profile 设置 `spring.sql.init.mode=always` 并显式引用这两个
MySQL SQL 资源。它只保证当前 schema/data 的幂等初始化，不能把旧版本表结构升级为当前版本。持久卷为
`opencli-hub-mysql-data`、`opencli-hub-mysql-hub-data` 与 `opencli-hub-mysql-home`；MySQL 8.x volume 不可直接
挂载到 5.7，跨主版本迁移必须 dump/import 到新卷。

对既有 MySQL schema 的版本迁移，DDL 会隐式提交。先停止所有 Hub，验证数据库和 Hub 数据卷备份可恢复，再按
以下顺序执行；每一步读取脚本末尾校验结果后才进入下一步：

```bash
# 命令会提示输入数据库密码；在目标 opencli_hub 库上执行。
mysql --host "$OPENCLI_HUB_MYSQL_HOST" --user "$OPENCLI_HUB_MYSQL_USERNAME" \
  --password --database=opencli_hub < scripts/migrate-mysql-uuid-ids.sql
mysql --host "$OPENCLI_HUB_MYSQL_HOST" --user "$OPENCLI_HUB_MYSQL_USERNAME" \
  --password --database=opencli_hub < scripts/migrate-mysql-browser-proxy-settings.sql
mysql --host "$OPENCLI_HUB_MYSQL_HOST" --user "$OPENCLI_HUB_MYSQL_USERNAME" \
  --password --database=opencli_hub < scripts/migrate-mysql-execution-indexes.sql
```

顺序是 **UUID ID -> 浏览器代理 -> Execution 索引**。当前 schema 有 5 张业务表：`hub_instance`、`hub_system_settings`、`hub_execution`、`hub_command_blacklist`、`hub_command_output_rule`。细节见 [UUID ID 迁移](docs/uuid-id-migration.md)、[浏览器代理设置](docs/browser-proxy-settings.md)、[Execution 查询索引迁移](docs/execution-index-migration.md) 和 [部署与运维](docs/deployment-and-operations.md)。

## 管理 API

普通 JSON API 返回 Convention4j Result envelope，成功结果位于 `data`；资源和日志下载是二进制响应。以下示例假设：

```bash
export HUB_URL=http://127.0.0.1:8080
```

### Instance、Catalog 与执行

先读取真实 Catalog；调用方必须使用它返回的参数定义。Hub 会添加 `--profile <contextId>` 与 `--format json`，请求 `argv` 不得自行传入这两个参数，也不得传入被输出规则托管的参数。

```bash
curl --fail --show-error "$HUB_URL/api/opencli/commands?website=bilibili"
curl --fail --show-error --request POST "$HUB_URL/api/instances" \
  --header 'Content-Type: application/json' \
  --data '{
    "code":"bilibili-primary",
    "displayName":"Bilibili primary",
    "websites":["bilibili"],
    "maxPending":5,
    "proxyMode":"INHERIT"
  }'
# 保存上一步 envelope.data.id；ID 是字符串，不能转为 number。
export INSTANCE_ID='<instance-id>'
curl --fail --show-error "$HUB_URL/api/instances/$INSTANCE_ID/vnc/status"
curl --fail --show-error --request POST "$HUB_URL/api/instances/$INSTANCE_ID/restart"
# 示例命令必须存在于当前 Catalog，且 Instance 已完成网站登录。
curl --fail --show-error --request POST "$HUB_URL/api/opencli/execute" \
  --header 'Content-Type: application/json' \
  --data "{\"instanceId\":\"$INSTANCE_ID\",\"argv\":[\"bilibili\",\"hot\",\"--limit\",\"5\"],\"timeoutMillis\":60000}"
curl --fail --show-error "$HUB_URL/api/executions?instanceId=$INSTANCE_ID&pageNumber=1&pageSize=20"
```

`POST /api/opencli/execute` 是同步接口。客户端或 Gateway 断开连接不会取消已被 Hub 接受的任务；Gateway timeout 应覆盖业务所需的排队和执行时间。

### VNC、日志、资源与代理

管理端通过 noVNC 使用同源 WebSocket：

```text
ws(s)://<hub-host>/api/instances/{id}/vnc
```

若 HTTPS 由 Gateway/反向代理终止而 Hub 使用内部 HTTP，部署必须将浏览器的精确 Origin 注入
`OPENCLI_HUB_VNC_ALLOWED_ORIGINS`，例如：

```text
OPENCLI_HUB_VNC_ALLOWED_ORIGINS=https://opencli-hub.example.com
```

多个 Origin 使用逗号分隔。值必须是 `scheme://host[:port]`，不包含路径或末尾 `/`，且不得使用 `*`。
未配置时 Hub 保持严格同源校验并拒绝该类经 HTTPS 代理的 VNC Upgrade。

不要直接暴露或连接 `5900-5999`；运行时 VNC TCP 只绑定容器 loopback。日志 API 示例：

```bash
curl --fail --show-error "$HUB_URL/api/logs/system?lines=200"
curl --fail --show-error "$HUB_URL/api/instances/$INSTANCE_ID/logs?source=CHROME&lines=200"
curl --fail --show-error --output chrome.log \
  "$HUB_URL/api/instances/$INSTANCE_ID/logs/download?source=CHROME"
```

Instance 日志 source 为 `CHROME`、`XVFB`、`OPENBOX` 或 `X11VNC`；系统日志使用 `/api/logs/system`。资源单文件/单请求上限默认 100 MiB / 500 MiB：

```bash
curl --fail --show-error --request POST "$HUB_URL/api/resources/uploads" \
  --form 'date=2026-07-16' \
  --form 'files=@./input.json'
curl --fail --show-error "$HUB_URL/api/resources/dates"
curl --fail --show-error "$HUB_URL/api/resources?date=2026-07-16&source=UPLOAD&page=0&pageSize=100"
```

上传响应 `data` 给出 `date`、`group` 及每个资源的 `contentUrl`、`downloadUrl`。读取/删除/下载应使用返回 URL 或字段，不要自行拼接或解码含空格、Unicode、`#`、`?` 的路径。

全局 `GET`/`PUT /api/settings` 只接受 `DIRECT` 或 `CUSTOM`；Instance 的 `proxyMode` 可为 `INHERIT`、`DIRECT`、`CUSTOM`。`CUSTOM` 只接受带显式端口且不含凭据、path、query、fragment 的 `http`、`https`、`socks4`、`socks5` URL：

```bash
curl --fail --show-error --request PUT "$HUB_URL/api/settings" \
  --header 'Content-Type: application/json' \
  --data '{"proxyMode":"CUSTOM","proxyServer":"socks5://proxy.example:1080"}'
```

保存代理设置不会中断运行中的 Chrome；对使用它的 Instance stop/start 或 restart 后生效。代理从容器内部解析，bridge 网络中的 `127.0.0.1` 不是 Docker 宿主机；代理只影响 Chrome 网站流量。

## 数据、升级与排障

`/data/opencli-hub` 与 `/var/lib/opencli` 包含数据库、Profile、Cookie/登录态、资源与日志，必须按敏感数据加密备份、限制读取者，并进行恢复演练。升级时：

1. 确认没有执行中的任务，停止 Hub。
2. 备份数据库和两个 Hub volumes；MySQL 同时保存可恢复 `mysqldump`。
3. 对既有 MySQL 执行所需迁移并查看每步校验。
4. 使用**同一稳定 signing key**构建新镜像，启动相同 volumes。
5. 检查 health、旧 numeric ID Instance、登录态、VNC、日志、Execution 和资源。

轮换 signing key 前必须在隔离数据副本和单个 Instance 验证。新 key 产生新的 extension identity；Hub 仅在发现唯一新 `contextId` 时自动重绑定。多个新 context 或登录态异常时，应停止升级并从备份恢复。

| 现象 | 首先检查 |
|---|---|
| Docker build 提示 missing/unreadable signing key | `OPENCLI_HUB_EXTENSION_SIGNING_KEY_FILE` 是否指向可读非空私钥；不要把 key 放入仓库或 image context。 |
| health 不为 `UP` | `docker compose logs hub`、`/api/logs/system`、MySQL health、profile 与密码变量。 |
| Instance 启动失败 | `lastErrorMessage`、Chrome/Xvfb/openbox/x11vnc 日志、`2gb` shm、seccomp、Chrome/OpenCLI binaries。 |
| VNC 不可用 | `GET /api/instances/{id}/vnc/status`；Gateway 是否允许 WebSocket Upgrade，而不是开放 VNC TCP。 |
| extension/contextId 超时 | CRX loopback server、managed policy、Chrome 日志和 Profile；不要添加 `--load-extension`、`--disable-extensions-except` 或 `--no-sandbox`。 |
| CUSTOM proxy 无法连通 | 从容器网络检查代理地址；`127.0.0.1` 指向容器自身，认证代理不受支持。 |
| MySQL 迁移失败 | 停止 Hub 并恢复迁移前备份；不要以反向 DDL 回滚隐式提交。 |

完整 runbook 见 [部署与运维](docs/deployment-and-operations.md)，安全边界见 [安全说明](docs/security.md)。

## 本地开发与验证

Java 模块依赖方向为 `web -> core -> share`；`frontend/` 不属于 Maven module：

```bash
export JAVA_HOME=/path/to/jdk-17
env JAVA_HOME="$JAVA_HOME" mvn -B -ntp clean test
(cd frontend && npm ci && npm test && npm run lint && npm run build)
OPENCLI_HUB_MYSQL_PASSWORD=dummy MYSQL_ROOT_PASSWORD=dummy docker compose -f compose.yml config
docker compose -f compose.h2.yml config
find scripts -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n
scripts/docker/validate-opencli-artifact-lock.sh
scripts/docker/test-install-opencli.sh
git diff --check
```

`local-h2` 会启动完整 Spring Boot/Runtime 恢复链，不是测试专用模式；本机必须具备 OpenCLI、正式 Chrome、Xvfb、openbox 和 x11vnc：

```bash
export JAVA_HOME=/path/to/jdk-17
env JAVA_HOME="$JAVA_HOME" mvn -B -ntp clean package
env JAVA_HOME="$JAVA_HOME" "$JAVA_HOME/bin/java" \
  -jar web/target/opencli-hub-web-1.0.0.jar \
  --spring.profiles.active=local-h2
```

## 文档与目录

```text
share/      REST DTO、枚举、错误码与 ID 工具
core/       领域服务、持久化、Runtime、OpenCLI 与资源逻辑
web/        Spring Boot、REST Controller、SPA 与 VNC WebSocket
frontend/   React/Vite 管理端
scripts/    MySQL 手工迁移与 Docker 构建/Smoke 工具
docs/       设计、迁移、安全与运维文档
```

- [技术设计](docs/technical-design.md)
- [部署与运维](docs/deployment-and-operations.md)
- [安全说明](docs/security.md)
- [UUID ID 迁移](docs/uuid-id-migration.md)
- [Execution 查询索引迁移](docs/execution-index-migration.md)
- [浏览器代理设置](docs/browser-proxy-settings.md)
- [更新记录](CHANGELOG.md)

## 已知限制

- MySQL 5.7.44 已 EOL；该版本是兼容性目标而非安全支持承诺。
- Java `ProcessHandle` 无法保证找到已被 reparent 的后台后代。Hub 保证调用与输出 capture 有界，但不能替代容器级进程隔离。
- 文件系统防护拒绝 symlink 和非法路径，但同权限恶意进程仍可在检查与操作之间制造极窄 TOCTOU 窗口。
- Hub 不提供 HA、跨 Instance 自动重试或写命令 failover；业务方必须决定失败处理与幂等性。
