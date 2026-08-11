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
| 默认生产部署 | `compose.yml`（PostgreSQL 16） | PostgreSQL 官方 entrypoint 在新 volume 创建数据库和应用账号。 |
| 已有 MySQL 或托管 MySQL 8.4 | `compose.mysql.yml` | 官方 entrypoint 在新 volume 创建库和账号；托管库由维护者先创建空 `opencli_hub` 数据库。 |
| 演示、小规模单机 | `compose.sqlite.yml`（SQLite 内嵌） | 无外部数据库；必须是**单 Hub 进程**，data 与 OpenCLI home 使用 named volume。 |

数据库变体是**编译期**选择的（Maven profile `postgresql`/`mysql`/`sqlite` 与 Docker build-arg `OPENCLI_HUB_DATABASE`），不是运行时开关。无论选择哪种方式，先准备稳定、受保护的 CRX signing key，然后阅读下方对应的启动步骤。完整部署、备份和升级流程见[部署与运维](docs/deployment-and-operations.md)。

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
- 支持三种编译期数据库变体：PostgreSQL 16（默认）、MySQL 8.4 LTS、SQLite。变体通过 Spring SQL initialization 幂等应用当前 schema（schema-only，system settings 由应用懒初始化）；旧 MySQL schema 的结构升级仍须显式迁移。
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
  |-- PostgreSQL / MySQL / SQLite、资源、日志、Profile 卷
```

VNC TCP 只监听容器 loopback；客户端只通过同源 WebSocket `/api/instances/{id}/vnc` 访问。Browser Bridge CRX 也只由容器 loopback 更新服务提供。

## 版本与前置条件

| 项目 | Release 基线 |
|---|---|
| Hub | `1.0.0` |
| Java | 17 |
| 前端构建 | Node.js 20 + npm lockfile |
| Google Chrome | `150.0.7871.114-1`，仅 `linux/amd64` |
| OpenCLI | 见 `scripts/docker/opencli-artifact.lock.env`（当前 fork `1.8.7-fengwk.8`） |
| Browser Bridge extension | 见同一 lock（当前 fork `1.0.29`） |
| PostgreSQL（默认） | `16`，`compose.yml` |
| MySQL | `8.4` LTS，`compose.mysql.yml`；迁移脚本兼容 5.7/8.4 |
| SQLite | 内嵌（sqlite-jdbc），`compose.sqlite.yml` |

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
3. **默认 lock 只指向已发布资产**：当前钉住已验证的 `fork-v1.8.7-fengwk.8`；不要提交未发布的本地产物 URL。
4. **后续升级 fork Release 时只改 lock**，当前值为：

```bash
# scripts/docker/opencli-artifact.lock.env
OPENCLI_PACKAGE=@jackwener/opencli
OPENCLI_VERSION=1.8.7-fengwk.8
OPENCLI_CLI_URL=https://github.com/fengwk/OpenCLI/releases/download/fork-v1.8.7-fengwk.8/jackwener-opencli-1.8.7-fengwk.8.tgz
OPENCLI_CLI_SHA256=362c565a46ae7d7e641f8b1d1d912dcf57c020c72118e27513e10156efdd8c1f
OPENCLI_SOURCE_REVISION=fengwk/OpenCLI@6613555ec64ebeada47237f4d0c13476542175c2
EXTENSION_VERSION=1.0.29
OPENCLI_EXTENSION_URL=https://github.com/fengwk/OpenCLI/releases/download/fork-v1.8.7-fengwk.8/opencli-extension-v1.0.29.zip
OPENCLI_EXTENSION_SHA256=c1347a811e3b799b52899043d218d57a431c38bcd00d2aff3f847b7d0e0770c4
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

## 快速开始：PostgreSQL（默认）

完成上面的 key 准备后，在仓库根目录执行：

```bash
export OPENCLI_HUB_POSTGRESQL_PASSWORD="$(openssl rand -hex 32)"
docker compose -f compose.yml up --build -d
curl --fail --show-error http://127.0.0.1:8080/actuator/health
curl --fail --show-error http://127.0.0.1:8080/api/instances
```

`compose.yml` 固定 `postgres:16`，PostgreSQL 官方 entrypoint 在**新建** volume 首次启动时创建
`opencli_hub` 数据库和应用账号。Hub 使用 `postgresql` profile（Maven 默认）构建；`OPENCLI_HUB_POSTGRESQL_PASSWORD`
没有默认值，缺少时 Compose 直接失败。环境变量：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `OPENCLI_HUB_POSTGRESQL_HOST` | `postgresql`（compose 服务名） | PostgreSQL 主机 |
| `OPENCLI_HUB_POSTGRESQL_PORT` | `5432` | 端口 |
| `OPENCLI_HUB_POSTGRESQL_DATABASE` | `opencli_hub` | 数据库名 |
| `OPENCLI_HUB_POSTGRESQL_USERNAME` | `opencli_hub` | 应用账号 |
| `OPENCLI_HUB_POSTGRESQL_PASSWORD` | 无默认 | 应用密码（必填） |
| `OPENCLI_HUB_DATA_DIR` | `/data/opencli-hub` | Hub data 根目录 |

镜像默认发布到 `127.0.0.1:8080`，并使用三个 named volume：`opencli-hub-postgresql-data`（数据库）、
`opencli-hub-postgresql-hub-data`（日志、资源、Instance Profile 根目录）、`opencli-hub-postgresql-home`（OpenCLI home/data）。

停止但保留数据：

```bash
docker compose -f compose.yml down
```

只有明确要销毁全部数据、Profile 和登录态时才执行：

```bash
docker compose -f compose.yml down --volumes
```

### 容器 Smoke

容器 Smoke 使用独立 Compose project 和默认宿主端口 `18080`，不会创建 Instance，也不宣称 Chrome E2E 覆盖。当前仓库提供 SQLite 变体的 smoke 脚本（`scripts/docker/smoke-sqlite.sh`），PostgreSQL/MySQL 变体由 CI 的构建与 Compose 校验覆盖：

```bash
scripts/docker/smoke-sqlite.sh
# 网络受限且已有宿主 npm/Maven 缓存时：
OPENCLI_HUB_SMOKE_BUILD_MODE=local scripts/docker/smoke-sqlite.sh
```

## MySQL 8.4：新装与既有库迁移

`compose.mysql.yml` 固定使用 `mysql:8.4` LTS。MySQL 官方 entrypoint 在**新建** volume 首次启动时创建
`opencli_hub` 数据库和应用账号；Hub 的 `mysql` profile 随后通过 Spring SQL initialization 在每次
启动时幂等执行当前变体的 `schema-database.sql`（只建当前 schema，无 data SQL）。两个密码没有默认值：

```bash
export MYSQL_ROOT_PASSWORD="$(openssl rand -hex 32)"
export OPENCLI_HUB_MYSQL_PASSWORD="$(openssl rand -hex 32)"
docker compose -f compose.mysql.yml up --build -d
curl --fail --show-error http://127.0.0.1:8080/actuator/health
```

接入已有或托管的 MySQL 时，Hub **不会创建数据库本身**。启动 Hub 前，由数据库维护者手工创建空库：

```sql
CREATE DATABASE IF NOT EXISTS opencli_hub
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;
```

Hub 使用的数据库账号还必须具备该库的建表权限。环境变量：`OPENCLI_HUB_MYSQL_HOST`（默认 `mysql`）、
`OPENCLI_HUB_MYSQL_PORT`（默认 `3306`）、`OPENCLI_HUB_MYSQL_DATABASE`（默认 `opencli_hub`）、
`OPENCLI_HUB_MYSQL_USERNAME`（默认 `opencli_hub`）、`OPENCLI_HUB_MYSQL_PASSWORD`（必填）。
持久卷为 `opencli-hub-mysql-data`、`opencli-hub-mysql-hub-data` 与 `opencli-hub-mysql-home`；MySQL 跨主版本
迁移必须 dump/import 到新卷，不要把旧版本 volume 直接挂载给新版本。

fresh schema 的 DDL 仍使用 MySQL 5.7 兼容语法（普通升序 B-tree 索引、`utf8mb4`、`timestamp(3)`），因此
**同一个 `schema-database.sql` 在 5.7 与 8.4 上都能建表**；但官方 compose 固定 8.4 LTS，5.7 只作为 legacy
库的兼容目标。JDBC 连接由 `mysql` 变体的 `application-database.yml` 固定 UTC 会话。

### 既有 MySQL schema 的版本迁移

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
mysql --host "$OPENCLI_HUB_MYSQL_HOST" --user "$OPENCLI_HUB_MYSQL_USERNAME" \
  --password --database=opencli_hub < scripts/migrate-mysql-instance-priority.sql
mysql --host "$OPENCLI_HUB_MYSQL_HOST" --user "$OPENCLI_HUB_MYSQL_USERNAME" \
  --password --database=opencli_hub < scripts/migrate-mysql-execution-queued-at-immutable.sql
mysql --host "$OPENCLI_HUB_MYSQL_HOST" --user "$OPENCLI_HUB_MYSQL_USERNAME" \
  --password --database=opencli_hub < scripts/migrate-mysql-instance-state-changed-at-immutable.sql
```

顺序是 **UUID ID -> 浏览器代理 -> Execution 索引 -> Instance priority -> queued_at 不可变 -> state_changed_at 不可变**。
其中两个“不可变时间戳”脚本语义不同：`queued_at` 可从同值列 `gmt_create` 回填修复；`state_changed_at` 没有
幸存列能还原被 `ON UPDATE` 覆盖的历史值，脚本只移除 `ON UPDATE` 并**不伪造历史时间**——既有已漂移值不可恢复，
仅保证此后的写入正确。当前 schema 有 6 张业务表：`hub_instance`、`hub_system_settings`、`hub_execution`、
`hub_command_blacklist`、`hub_command_output_rule`、`hub_plugin_source`。细节见 [UUID ID 迁移](docs/uuid-id-migration.md)、
[浏览器代理设置](docs/browser-proxy-settings.md)、[Execution 查询索引迁移](docs/execution-index-migration.md)
和 [部署与运维](docs/deployment-and-operations.md)。

## SQLite：单进程单机运行

`compose.sqlite.yml` 使用内嵌 SQLite（无外部数据库服务），适合演示和小规模单机：

```bash
export OPENCLI_HUB_SQLITE_PATH=/data/opencli-hub/database/opencli-hub.db  # 可选，默认即此路径
docker compose -f compose.sqlite.yml up --build -d
curl --fail --show-error http://127.0.0.1:8080/actuator/health
```

SQLite 变体的运行约束：

- **单 Hub 进程**：只能有一个 Hub 进程打开该数据库文件，不要多实例、不要与其它进程共享；
- JDBC URL 固定 `journal_mode=WAL` 与 `busy_timeout=5000`，连接池钉死为**单连接**（`maximum-pool-size: 1`），
  读写串行化，避免 `SQLITE_BUSY`；
- 数据库文件默认位于 `${OPENCLI_HUB_DATA_DIR}/database/opencli-hub.db`，可用 `OPENCLI_HUB_SQLITE_PATH` 覆盖；
- 使用两个 named volume：`opencli-hub-sqlite-data`（数据库、日志、资源和 Instance Profile 根目录）与
  `opencli-hub-sqlite-home`（OpenCLI home/data，Dockerfile `VOLUME` 会生成匿名卷，因此 Compose 显式命名）。

备份 SQLite 时停止 Hub 后复制 data volume（或先用 `sqlite3 .backup` 生成一致快照），不要在线拷贝 db 文件。

## H2 生产部署退役（legacy）

H2 已退出生产：**仅保留为测试数据库**（Maven `test` scope），仓库不再提供 `compose.h2.yml`、`local-h2` 或
`docker-h2` 生产指令。**没有自动 in-place 转换工具**。旧 H2 使用者迁移到受支持数据库（推荐 PostgreSQL）时：

1. 先停止旧 Hub，并备份整个 H2 data volume（含 `database/`、`instances/`、`resources/`、`logs/`）与 OpenCLI home volume；
2. 在**旧版本**上按旧文档导出数据（H2 文件复制/`SCRIPT` 导出到 CSV/SQL，以及手动迁移上传资源与 Profile）；
3. 在目标库（PostgreSQL/MySQL/SQLite）新装当前版本，导入第 2 步导出的业务数据，并把资源与 Profile 恢复到对应
   data/home volume；
4. 校验 Instance 登录态、VNC、历史 Execution 与资源后切换。

不要期望新版本读取旧 H2 文件或自动转换旧 schema/data。

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
# submit 返回 HTTP 202 Accepted，body 为 PENDING 状态 DTO（含 executionId）。
curl --fail --show-error --request POST "$HUB_URL/api/opencli/execute" \
  --header 'Content-Type: application/json' \
  --data "{\"instanceId\":\"$INSTANCE_ID\",\"argv\":[\"bilibili\",\"hot\",\"--limit\",\"5\"],\"timeoutMillis\":60000}"
# 用返回的 executionId 轮询终态；waitSeconds 最大 120，服务端 long-poll 至终态或超时。
curl --fail --show-error "$HUB_URL/api/executions/$EXECUTION_ID?waitSeconds=30"
# 排队期间可取消（仅 PENDING 可取消）。
curl --fail --show-error --request POST "$HUB_URL/api/executions/$EXECUTION_ID/cancel"
curl --fail --show-error "$HUB_URL/api/executions?instanceId=$INSTANCE_ID&pageNumber=1&pageSize=20"
```

`POST /api/opencli/execute` 是异步接口：立即返回 HTTP 202 Accepted，body 为 PENDING 状态的 Execution DTO（含 `executionId`），客户端必须轮询 `GET /api/executions/{id}?waitSeconds=N`（N 最大 120，long-poll 直到终态或超时）获取终态结果，或在排队期间调用 `POST /api/executions/{id}/cancel` 取消（仅 PENDING 可取消，RUNNING 返回 `EXECUTION_NOT_CANCELLABLE`）。客户端或 Gateway 断开连接不会取消已被 Hub 接受的任务；任务会继续到完成或 deadline。HTTP 客户端需把 202 视为提交成功并继续轮询（`curl --fail` 对 2xx 均视为成功），Gateway timeout 应覆盖业务所需的排队和执行时间。

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

## 客户端行为变化（相对 1.0.0 基线）

| 领域 | 行为 |
|---|---|
| execute 契约 | `POST /api/opencli/execute` 由同步返回终态改为 **HTTP 202 Accepted + PENDING DTO**（含 `executionId`）。客户端必须轮询 `GET /api/executions/{id}?waitSeconds=N`（N 最大 120，long-poll 直到终态或超时）或调用 `POST /api/executions/{id}/cancel`（仅 PENDING 可取消，RUNNING 返回 `EXECUTION_NOT_CANCELLABLE`）。客户端/Gateway 断开不取消已接受任务。 |
| 本地文件引用 | `/resources/...` 是唯一支持的文件引用协议：argv 中**必须先上传**，再使用上传响应返回的虚拟路径。独立 argv 值若为绝对路径、Windows drive path、`~` 展开、`file://` URI、含显式 `.`/`..` traversal 段，或相对 OpenCLI workdir **实际存在**的文件/目录，一律以 `OPENCLI_LOCAL_PATH_NOT_ALLOWED` 拒绝；http/https 等 URL、普通自然语言 prompt 和仅含斜杠的文本（日期、句子）不视为路径，原样通过。 |
| 列表排序 | `GET /api/executions` 固定 `queued_at DESC, id DESC`（按 Instance 过滤时加 `instance_id` 等值条件）。`queued_at` 不可变：由应用写入，数据库不再 `ON UPDATE`。 |
| 取消/清队列持久化 | `POST /api/executions/{id}/cancel`、`POST /api/instances/{id}/clear-queue` 与强制 shutdown 丢弃的 PENDING 任务一律持久化为 **CANCELLED**（CAS PENDING→CANCELLED），不会残留 DB PENDING 行。 |
| 时间语义 | 所有时间戳（`queued_at`/`started_at`/`finished_at`/`state_changed_at`/`gmt_create`/`gmt_modified`）均为 **UTC LocalDateTime**，API 直接返回 UTC 值；`gmt_*` 列名保留以兼容旧客户端。应用使用 `Clock.systemUTC()`，各数据库连接被强制 UTC 会话。 |
| 数据库 | 生产默认 PostgreSQL 16；MySQL 8.4 与 SQLite 为编译期变体；**H2 退出生产**（仅测试）。镜像按 `OPENCLI_HUB_DATABASE` 构建，JAR 名为 `opencli-hub-web-1.0.0-{postgresql|mysql|sqlite}.jar`。 |
| Output Rule | 保存/upsert 时基于**当前 Catalog metadata** 校验：命令必须是公开 browser command，参数必须是具名、接受值的输出参数（positional 与布尔 flag 拒绝）；`targetType` 为 `DIRECTORY`/`FILE`，`FILE` 必填安全文件名。校验通过后规则以不可变快照原子生效；命令 DTO 返回真实规则 metadata（argumentName/targetType/fileName）。执行时按 commandKey 直接使用已保存规则注入托管输出参数，不逐次重验 catalog；调用方传入被托管参数返回 `OPENCLI_RESOURCE_OUTPUT_ARGUMENT_MANAGED`。 |
| startup recovery barrier | 启动时 `ApplicationRunner` 先声明恢复屏障再调度恢复 sweep；期间 API `create`/`start`/`restart` 有界等待（默认 60s，`OPENCLI_HUB_START_COORDINATION_TIMEOUT_MILLIS`），超时返回 `INSTANCE_START_RECOVERY_IN_PROGRESS`。 |
| 部署 | 三套 Compose（`compose.yml`/`compose.mysql.yml`/`compose.sqlite.yml`）与三套发布 tag（`postgresql`/`mysql`/`sqlite`，PostgreSQL 另有 `latest`/`docker`，均含 `sha-<db>-<short>`）。 |

## 数据、升级与排障

`/data/opencli-hub` 与 `/var/lib/opencli` 包含数据库、Profile、Cookie/登录态、资源与日志，必须按敏感数据加密备份、限制读取者，并进行恢复演练。升级时：

1. 确认没有执行中的任务，停止 Hub。
2. 备份数据库和 Hub data/home volumes；PostgreSQL 用 `pg_dump`、MySQL 用 `mysqldump` 保存可恢复逻辑备份（SQLite 在停止后复制 data volume 或 `sqlite3 .backup`）。
3. 对既有 MySQL schema 执行所需迁移并查看每步校验。
4. 使用**同一稳定 signing key**构建新镜像，启动相同 volumes。
5. 检查 health、旧 numeric ID Instance、登录态、VNC、日志、Execution 和资源。

轮换 signing key 前必须在隔离数据副本和单个 Instance 验证。新 key 产生新的 extension identity；Hub 仅在发现唯一新 `contextId` 时自动重绑定。多个新 context 或登录态异常时，应停止升级并从备份恢复。

| 现象 | 首先检查 |
|---|---|
| Docker build 提示 missing/unreadable signing key | `OPENCLI_HUB_EXTENSION_SIGNING_KEY_FILE` 是否指向可读非空私钥；不要把 key 放入仓库或 image context。 |
| health 不为 `UP` | `docker compose logs hub`、`/api/logs/system`、数据库（PostgreSQL/MySQL）health、profile 与密码变量；SQLite 检查磁盘与单进程约束。 |
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
# 三个数据库变体分别构建（默认 postgresql）并校验各自 JAR：
env JAVA_HOME="$JAVA_HOME" mvn -B -ntp -Ppostgresql clean package
env JAVA_HOME="$JAVA_HOME" mvn -B -ntp -Pmysql clean package
env JAVA_HOME="$JAVA_HOME" mvn -B -ntp -Psqlite clean package
# 三套 Compose 静态校验（密码占位）：
OPENCLI_HUB_POSTGRESQL_PASSWORD=dummy docker compose -f compose.yml config
OPENCLI_HUB_MYSQL_PASSWORD=dummy MYSQL_ROOT_PASSWORD=dummy docker compose -f compose.mysql.yml config
docker compose -f compose.sqlite.yml config
find scripts -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n
scripts/docker/validate-opencli-artifact-lock.sh
scripts/docker/test-install-opencli.sh
git diff --check
```

H2 仅作为测试数据库（Maven `test` scope）存在；生产/本地运行不再提供 `local-h2`、`docker-h2` 或 H2 JAR 指令。本地跑完整 Spring Boot/Runtime 恢复链时按数据库变体选择启动方式，例如默认 PostgreSQL 变体（本机必须具备 PostgreSQL、OpenCLI、正式 Chrome、Xvfb、openbox 和 x11vnc）：

```bash
export JAVA_HOME=/path/to/jdk-17
env JAVA_HOME="$JAVA_HOME" mvn -B -ntp -Ppostgresql clean package
env JAVA_HOME="$JAVA_HOME" "$JAVA_HOME/bin/java" \
  -jar web/target/opencli-hub-web-1.0.0-postgresql.jar
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

- MySQL 5.7 已 EOL：既有库的迁移脚本仍兼容 5.7/8.4，但官方 compose 固定 MySQL 8.4 LTS；5.7 只是 legacy 兼容目标而非安全支持承诺。
- SQLite 变体只能单 Hub 进程访问（单连接 + WAL + busy_timeout），不适合多进程或多节点。
- Java `ProcessHandle` 无法保证找到已被 reparent 的后台后代。Hub 保证调用与输出 capture 有界，但不能替代容器级进程隔离。
- 文件系统防护拒绝 symlink 和非法路径，但同权限恶意进程仍可在检查与操作之间制造极窄 TOCTOU 窗口。
- Hub 不提供 HA、跨 Instance 自动重试或写命令 failover；业务方必须决定失败处理与幂等性。
