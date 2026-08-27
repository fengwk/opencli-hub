# 部署与运维

本文档面向 `opencli-hub 1.0.0` 的部署维护者。它补充根 README 的快速入口，定义签名密钥、持久数据、升级迁移、备份恢复和故障排查的操作边界。

## 1. 部署前检查

1. 使用 `linux/amd64` Docker 主机。镜像固定的 Google Chrome Debian 包不提供其他目标架构。
2. Docker Engine 必须启用 BuildKit；Docker Compose 必须支持 `build.secrets`。
3. 为 Hub 分配至少 `2gb` shared memory，并验证宿主 seccomp 策略允许 `seccomp=unconfined`。Compose 文件已经声明这两个条件，不要删除。
4. Gateway/反向代理必须提供 TLS、认证、授权、限流，并允许 `/api/instances/{id}/vnc` WebSocket Upgrade 和二进制帧透传。
5. 为 `/data/opencli-hub`、`/var/lib/opencli` 及数据库（PostgreSQL/MySQL）备份准备受限、加密的存储位置；SQLite 备份即 data volume 快照。

Hub 容器和 Chrome 以 UID/GID `1000:1000` 运行。若改用 bind mount，宿主目录必须允许该用户读写。

若 TLS 在 Gateway/反向代理终止而 Gateway 通过内部 HTTP 访问 Hub，必须将浏览器的精确 Origin 注入
`OPENCLI_HUB_VNC_ALLOWED_ORIGINS`，例如：

```text
OPENCLI_HUB_VNC_ALLOWED_ORIGINS=https://opencli-hub.example.com
```

多个 Origin 使用逗号分隔。仅允许 `scheme://host[:port]` 形式的精确 Origin；不要使用 `*`。
该配置仅放开 Hub VNC WebSocket 的 Origin 校验，不替代 Gateway 的认证、授权、限流和审计。

## 2. CRX signing key

Browser Bridge 的 CRX 由构建期的 RSA key 签名。Chrome extension identity 由该 key 的公钥导出，因此它是部署身份的一部分，不是临时构建输入。

### 生成和保存

```bash
umask 077
mkdir -p .secrets
openssl genpkey -algorithm RSA \
  -pkeyopt rsa_keygen_bits:2048 \
  -out .secrets/opencli-extension-signing-key.pem
chmod 600 .secrets/opencli-extension-signing-key.pem
```

默认路径 `.secrets/opencli-extension-signing-key.pem` 同时被 `.gitignore` 和 `.dockerignore` 排除。受管密钥服务、CI secret 文件或只读挂载也可使用：

```bash
export OPENCLI_HUB_EXTENSION_SIGNING_KEY_FILE=/secure/path/opencli-extension-signing-key.pem
docker compose -f compose.yml build
```

Dockerfile 将其作为 BuildKit secret `opencli_extension_signing_key` 挂载到单个 CRX 打包步骤。构建产物只保留 CRX、update manifest、build metadata 和 managed Chrome policy；私钥不应出现在 Git、Docker context、image history、image filesystem、运行时 volume 或日志中。

### 轮换

不要为普通升级重新生成 key。换 key 会改变 extension identity，已有 Chrome Profile 需要重新安装 Browser Bridge，可能改变 context discovery 的结果。必要轮换时：

1. 停止 Hub，验证数据库、Hub data/home volumes 的备份。
2. 在数据副本中用新 key 构建镜像，只启动一个 Instance。
3. 验证 managed extension 安装、网站登录态、`GET /api/instances/{id}` 的 contextId、VNC 和一条只读命令。
4. Hub 仅在观察到唯一的新 contextId 时自动重绑定；遇到 context 冲突或歧义时停止，分析 Profile/extension 状态或恢复备份。
5. 单 Instance 验收后才分批处理其他 Instance。

已经泄露或曾提交到版本库的 key 不得用于 release。

### GitHub 自动构建并发布镜像

推送 `docker` 分支会触发 `.github/workflows/docker-publish.yml`，按数据库矩阵
`postgresql` / `mysql` / `sqlite` 分别构建 `linux/amd64` 镜像并推送以下 Docker Hub tag：

```text
<namespace>/opencli-hub:postgresql      （另有 :latest 与 :docker 别名）
<namespace>/opencli-hub:mysql
<namespace>/opencli-hub:sqlite
<namespace>/opencli-hub:sha-<db>-<short-commit>   # 每个变体各自的短 SHA tag
```

其中 `latest` 与 `docker` 仅对 PostgreSQL（默认变体）发布；`sha-<db>-<short>` 为每个变体各自的
短 SHA tag（如 `sha-mysql-abc1234`）。拉取镜像时按变体使用对应 tag，例如
`<namespace>/opencli-hub:mysql`。`OPENCLI_HUB_DATABASE` build-arg 决定变体，运行时没有 profile 切换。

仓库需要配置：

| 类型 | 名称 | 说明 |
|---|---|---|
| Secret | `DOCKERHUB_USERNAME` | Docker Hub 用户名；兼容旧名称 `DOCKER_USERNAME`。 |
| Secret | `DOCKERHUB_TOKEN` | Docker Hub access token；兼容旧名称 `DOCKER_PASSWORD`。 |
| Secret | `OPENCLI_HUB_EXTENSION_SIGNING_KEY` | 完整 RSA PEM；必须使用受保护且长期稳定、未泄露的 key。 |
| Variable | `DOCKERHUB_NAMESPACE` | 可选镜像命名空间；缺省使用 Docker Hub 用户名。 |

workflow 也支持手工触发。签名 key 通过 BuildKit secret
`opencli_extension_signing_key` 直接提供给 Docker build，不写入 checkout、build context
或镜像。构建会绕过 `opencli-assets` stage 的缓存，确保 key 轮换后重新生成 CRX；其他
stage 仍使用 GitHub Actions cache。

## 3. 三种数据库变体

数据库变体在构建期选择（Maven profile `postgresql` / `mysql` / `sqlite`，Docker build-arg `OPENCLI_HUB_DATABASE`），
JAR 名为 `opencli-hub-web-1.0.0-{postgresql|mysql|sqlite}.jar`，运行时**没有** profile 切换。
每个变体每次启动都会通过 Spring SQL initialization 幂等执行当前变体的 `schema-database.sql`
（schema-only，无 data SQL；system settings 单例由应用首次读取时懒初始化）。

### 3.1 PostgreSQL（默认）

```bash
export OPENCLI_HUB_POSTGRESQL_PASSWORD="$(openssl rand -hex 32)"
docker compose -f compose.yml up --build -d
curl --fail --show-error http://127.0.0.1:8080/actuator/health
docker compose -f compose.yml ps
```

`compose.yml` 固定 `postgres:16`；PostgreSQL 官方 entrypoint 在新 volume 首次启动时创建 `opencli_hub`
数据库和应用账号。环境变量 `OPENCLI_HUB_POSTGRESQL_HOST/PORT/DATABASE/USERNAME/PASSWORD`，其中密码必填。

### 3.2 MySQL 8.4 LTS

```bash
export MYSQL_ROOT_PASSWORD="$(openssl rand -hex 32)"
export OPENCLI_HUB_MYSQL_PASSWORD="$(openssl rand -hex 32)"
docker compose -f compose.mysql.yml up --build -d
curl --fail --show-error http://127.0.0.1:8080/actuator/health
docker compose -f compose.mysql.yml ps
```

`compose.mysql.yml` 固定 `mysql:8.4` LTS。fresh schema 的 DDL 仍使用 MySQL 5.7 兼容语法
（普通升序 B-tree 索引、`utf8mb4`、`timestamp(3)`），同一个 `schema-database.sql` 在 5.7 与 8.4 上都能建表；
但官方 compose 只针对 8.4 LTS，MySQL 5.7 仅是 legacy 库的兼容目标。接入已有/托管 MySQL 时 Hub 不创建数据库，
由维护者先建空库（`utf8mb4` / `utf8mb4_general_ci`）。环境变量 `OPENCLI_HUB_MYSQL_HOST/PORT/DATABASE/USERNAME/PASSWORD`，
密码必填。

### 3.3 SQLite（单进程单机）

```bash
docker compose -f compose.sqlite.yml up --build -d
curl --fail --show-error http://127.0.0.1:8080/actuator/health
docker compose -f compose.sqlite.yml ps
```

SQLite 变体使用内嵌 sqlite-jdbc，运行约束：

- **单 Hub 进程**：只有一个 Hub 进程打开数据库文件；
- JDBC URL 固定 `journal_mode=WAL` 与 `busy_timeout=5000`，Hikari 池钉死单连接（`maximum-pool-size: 1`），
  读写串行化；
- 数据库文件默认 `${OPENCLI_HUB_DATA_DIR}/database/opencli-hub.db`，可用 `OPENCLI_HUB_SQLITE_PATH` 覆盖；
- 两个 named volume：`opencli-hub-sqlite-data`（数据库、日志、资源和 Instance Profile）与
  `opencli-hub-sqlite-home`（OpenCLI home/data，显式命名避免 Dockerfile `VOLUME` 生成匿名卷）。

容器默认只将 Hub HTTP 发布到 `127.0.0.1:8080`。外部可见性由 Gateway 提供；VNC TCP 不应额外发布。

## 4. H2 退役说明（legacy）

H2 已从生产退出，**仅保留为测试数据库**（Maven `test` scope）；仓库不再提供 `compose.h2.yml`、
`local-h2`、`docker-h2` 生产指令，也没有自动 in-place 转换工具。旧 H2 使用者迁移到受支持数据库
（推荐 PostgreSQL）时：先在旧版本上停止 Hub 并备份（H2 文件 + 资源/Profile/home 卷），按旧文档导出
业务数据，再在目标库新装当前版本并导入，最后校验登录态、VNC、历史 Execution 与资源。不要期望新版本
读取旧 H2 文件。

## 5. 备份与恢复

停止 Hub 后再取得可恢复快照，避免数据库、资源、Profile 与 OpenCLI home 相互不一致。

### PostgreSQL

备份三个 named volume：

```text
opencli-hub-postgresql-data
opencli-hub-postgresql-hub-data
opencli-hub-postgresql-home
```

并创建可恢复的逻辑数据库备份，例如在维护窗口：

```bash
pg_dump --host "$OPENCLI_HUB_POSTGRESQL_HOST" --username "$OPENCLI_HUB_POSTGRESQL_USERNAME" \
  --dbname opencli_hub > opencli_hub.sql
```

### MySQL

同时备份：

```text
opencli-hub-mysql-data
opencli-hub-mysql-hub-data
opencli-hub-mysql-home
```

并创建可恢复的逻辑数据库备份，例如在维护窗口：

```bash
mysqldump --host "$OPENCLI_HUB_MYSQL_HOST" --user "$OPENCLI_HUB_MYSQL_USERNAME" \
  --password --single-transaction --routines --events opencli_hub > opencli_hub.sql
```

### SQLite

SQLite 变体只有两个 named volume（数据库在 data volume 内）：

```text
opencli-hub-sqlite-data
opencli-hub-sqlite-home
```

先停止 Hub，再复制 data volume（或先用 `sqlite3 .backup` 生成一致快照）；不要在线拷贝 db 文件，
也不要让第二个进程打开同一数据库文件。

对备份执行校验并定期在隔离环境演练恢复。MySQL DDL 隐式提交；发生迁移问题时，回滚方法是停止 Hub 并恢复迁移前备份，不是手写反向 DDL。

## 6. 升级和迁移

### 6.1 同一 schema 的镜像升级

1. 记录现有镜像 digest 和运行配置。
2. 等待或停止所有 Execution，停止 Hub。
3. 备份数据与数据库，保留当前 signing key。
4. 用相同 key 构建新镜像，启动相同 volume。
5. 检查 health、Instance Runtime、VNC、历史 Execution、资源和登录态。

### 6.2 OpenCLI CLI / extension artifact lock

Hub 镜像默认从 `scripts/docker/opencli-artifact.lock.env` 读取 OpenCLI CLI 与 Browser Bridge
extension 固定版本。`Dockerfile`、Compose、`scripts/docker/build-local.sh` 和 GitHub
workflow 共用该文件，因此官方 baseline 与已发布 fork Release 的切换都应优先编辑这一处。

最终镜像设置 `OPENCLI_DISABLE_UPDATE_CHECK=1`，关闭 OpenCLI 运行时 updater 提示与联网版本检查。
无论官方 baseline 还是 fork CLI，升级路径都是改 lock 后重建镜像，而不是容器内自动更新。

当前仓库默认钉住已发布并重新下载验证的 fork Release：

```text
package=@jackwener/opencli
CLI version=1.8.7-fengwk.9
extension version=1.0.30
source revision=fengwk/OpenCLI@d81da8b5f21654aec98716702b25c8449089f838
```

升级或切换 fork 时：

1. **成对升级**：CLI 与 extension 必须来自同一 OpenCLI Release；不要只更新 CLI 或只更新 extension。
2. **校验和强制**：CLI tarball 与 extension zip 都必须写入准确的 SHA256。`install-opencli.sh` /
   `install-extension.sh` 会在安装前校验；URL 模式缺少或错误 SHA 会直接失败。
3. **不要把未发布资产写入默认 lock**：本地试验可用 build-arg；提交到仓库的默认 lock 只能指向
   已公开发布的 npm tarball / GitHub Release 资产。
4. 构建后确认镜像内 `/opt/opencli/artifact-build-info.json` 反映解析结果；smoke 会比较
   `opencli --version` 与该文件中的 `cli.version`。

当前 fork Release（tag `fork-v1.8.7-fengwk.9`，CLI `1.8.7-fengwk.9` + extension `1.0.30`）：

```bash
# scripts/docker/opencli-artifact.lock.env
OPENCLI_PACKAGE=@jackwener/opencli
OPENCLI_VERSION=1.8.7-fengwk.9
OPENCLI_CLI_URL=https://github.com/fengwk/OpenCLI/releases/download/fork-v1.8.7-fengwk.9/jackwener-opencli-1.8.7-fengwk.9.tgz
OPENCLI_CLI_SHA256=e0bf515d401da5a265a64de0c1a93d157462c448a3448edcf17fa7d08422f4ca
OPENCLI_SOURCE_REVISION=fengwk/OpenCLI@d81da8b5f21654aec98716702b25c8449089f838
EXTENSION_VERSION=1.0.30
OPENCLI_EXTENSION_URL=https://github.com/fengwk/OpenCLI/releases/download/fork-v1.8.7-fengwk.9/opencli-extension-v1.0.30.zip
OPENCLI_EXTENSION_SHA256=50d117b47ee81fb362d3eb4a58d5a553ce96887fa76642743a286f33f45e0712
```

可选 build-arg 仅覆盖**单次构建**，不改变仓库默认 pin：

| Build arg | 覆盖内容 |
|---|---|
| `OPENCLI_PACKAGE` | npm package 名 |
| `OPENCLI_VERSION` | CLI 期望版本（安装后 `opencli --version` 必须精确匹配） |
| `OPENCLI_CLI_URL` | CLI tarball URL；与 SHA 必须成对覆盖，不可只改一侧 |
| `OPENCLI_CLI_SHA256` | CLI tarball SHA256；与 URL 必须成对覆盖 |
| `OPENCLI_SOURCE_REVISION` | 写入 `artifact-build-info.json` 的来源修订 |
| `EXTENSION_VERSION` | extension 版本；CRX 打包也使用解析后的值 |
| `OPENCLI_EXTENSION_URL` | extension zip URL；与 SHA 必须成对覆盖 |
| `OPENCLI_EXTENSION_SHA256` | extension zip SHA256；与 URL 必须成对覆盖 |

静态校验：

```bash
scripts/docker/validate-opencli-artifact-lock.sh
scripts/docker/test-install-opencli.sh
```

### 6.3 既有 MySQL schema

对于旧版本数据库，必须在停机窗口按此顺序执行（脚本兼容 MySQL 5.7 与 8.4）：

1. `scripts/migrate-mysql-uuid-ids.sql`
2. `scripts/migrate-mysql-browser-proxy-settings.sql`
3. `scripts/migrate-mysql-execution-indexes.sql`
4. `scripts/migrate-mysql-instance-priority.sql`
5. `scripts/migrate-mysql-execution-queued-at-immutable.sql`
6. `scripts/migrate-mysql-instance-state-changed-at-immutable.sql`

每个脚本都有 `information_schema` 校验输出。完整字段、索引和回滚说明在各自的迁移文档中；不要跳过备份，也不要将 MySQL 8 volume 直接降级挂载到 5.7。

`migrate-mysql-instance-priority.sql` 为既有 `hub_instance` 增加 `priority int not null default 0`（自动路由负载相同时更高优先）。新库由当前变体的 `schema-database.sql` 建表即可；既有库必须在部署含该字段的 Hub 镜像前执行此脚本。

`migrate-mysql-execution-queued-at-immutable.sql` 去掉 `hub_execution.queued_at` 的 `ON UPDATE CURRENT_TIMESTAMP`，并使用稳定的 `gmt_create` 回填已被状态更新改写的历史入队时间。脚本结束时 `queued_at_rows_still_drifted` 应为 `0`。

`migrate-mysql-instance-state-changed-at-immutable.sql` 去掉 `hub_instance.state_changed_at` 的 `ON UPDATE CURRENT_TIMESTAMP`（`modify column state_changed_at timestamp(3) not null default current_timestamp(3)`）。与 `queued_at` 不同：`state_changed_at` 没有幸存列能还原被 `ON UPDATE` 覆盖的历史状态变更时间（`gmt_create` 是插入时间，`gmt_modified` 是最近一次任意更新），因此脚本**不伪造历史时间、不做回填**——既有已漂移值不可恢复，只保证此后的状态变更写入正确。脚本结束时信息 schema 中该列 `extra` 不应再包含 `on update CURRENT_TIMESTAMP`。

## 7. 日常检查

```bash
curl --fail --show-error http://127.0.0.1:8080/actuator/health
curl --fail --show-error http://127.0.0.1:8080/api/instances
curl --fail --show-error 'http://127.0.0.1:8080/api/logs/system?lines=200'
```

检查以下状态：

- `health` 为 `UP`；
- Instance `state`、`lastErrorMessage`、`runtime.activeCount`、`runtime.pendingCount` 合理；
- `GET /api/instances/{id}/vnc/status` 的 `vncAvailable` 与实际状态一致；
- CRX loopback server 仅容器内部可用；
- 日志、资源、Profile 和数据库备份按保留策略执行。

### 7.1 插件维护

Hub 可通过管理页 `/plugins` 或 `/api/plugins/*` 配置插件源，并调用官方：

```bash
opencli plugin install|update|list
```

要点：

- 插件文件落在 `/var/lib/opencli/.opencli/`，随 opencli 数据卷持久化；
- 同步成功后会自动刷新 Command Catalog；也可 `POST /api/plugins/reload-catalog`；
- 同步可能耗时数分钟（clone/npm/transpile），Gateway timeout 需覆盖；
- 新增/编辑 source 只保存配置；只有运维人员手动触发 source 操作才会运行官方 CLI；
- 删除插件源配置默认**不会** uninstall 已安装插件。

细节见 [OpenCLI 插件维护](plugins.md)。

## 8. 故障处理

| 问题 | 处理顺序 |
|---|---|
| BuildKit 报 secret 缺失 | 检查 key 路径、权限和非空状态；不要用环境变量字符串或提交文件代替 secret。 |
| 数据库变体 Hub 未启动 | PostgreSQL/MySQL：先检查 DB service health、密码变量、JDBC host/database；SQLite：检查 `OPENCLI_HUB_DATA_DIR` 磁盘、WAL 文件权限和**单进程**约束；再看 Hub console log。 |
| Chrome 启动失败 | 查看 Instance 的 CHROME/XVFB/OPENBOX/X11VNC 日志；确认 shm/seccomp、UID 1000 volume 权限和 Chrome binary。 |
| Browser Bridge 不连接 | 检查 managed policy、CRX server、Chrome log；不要添加被 Chrome 拒绝的 unpacked extension flags。 |
| VNC WebSocket 失败 | 查询 VNC status；确认 Gateway 转发 Upgrade；不要映射 x11vnc TCP 端口到外部。 |
| CUSTOM proxy 无法访问 | 从容器网络检查 DNS/路由；确认 URI 无凭据且有端口；bridge 下宿主 loopback 不可直接使用。 |
| 执行长时间等待 | 检查 Instance 排队数、Gateway timeout 和 command timeout；execute 是 202 异步契约，客户端必须轮询，不要用自动重试写命令代替业务幂等。 |
| 插件 sync 失败 | 查看源 `lastError`、容器网络/git 可达性、`opencli plugin list` 与 `/var/lib/opencli/.opencli` 权限。 |
| 插件已装但命令不可见 | 调用 `POST /api/plugins/reload-catalog`；确认命令为 public 且 Instance websites 已启用对应 site。 |

## 9. 发布验证

发布候选至少执行：

```bash
export JAVA_HOME=/path/to/jdk-17
env JAVA_HOME="$JAVA_HOME" mvn -B -ntp clean test
(cd frontend && npm ci && npm test && npm run lint && npm run build)
# 三个数据库变体构建 + JDBC driver 隔离校验（CI verify.yml 的 java job 等价检查）
env JAVA_HOME="$JAVA_HOME" mvn -B -ntp -Ppostgresql clean package
env JAVA_HOME="$JAVA_HOME" mvn -B -ntp -Pmysql clean package
env JAVA_HOME="$JAVA_HOME" mvn -B -ntp -Psqlite clean package
# 三套 Compose 静态校验（密码占位）
OPENCLI_HUB_POSTGRESQL_PASSWORD=dummy docker compose -f compose.yml config
OPENCLI_HUB_MYSQL_PASSWORD=dummy MYSQL_ROOT_PASSWORD=dummy docker compose -f compose.mysql.yml config
docker compose -f compose.sqlite.yml config
find scripts -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n
scripts/docker/validate-opencli-artifact-lock.sh
scripts/docker/test-install-opencli.sh
git diff --check
```

准备好 signing key 后，再运行 `scripts/docker/smoke-sqlite.sh`。它验证镜像、health、Hub API、OpenCLI 版本（对照
`/opt/opencli/artifact-build-info.json`）和 CRX loopback health；不会创建 Instance 或覆盖 Chrome 登录态 E2E。
PostgreSQL/MySQL 变体由 CI 的矩阵构建与三套 Compose 校验覆盖。
