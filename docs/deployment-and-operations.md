# 部署与运维

本文档面向 `opencli-hub 1.0.0` 的部署维护者。它补充根 README 的快速入口，定义签名密钥、持久数据、升级迁移、备份恢复和故障排查的操作边界。

## 1. 部署前检查

1. 使用 `linux/amd64` Docker 主机。镜像固定的 Google Chrome Debian 包不提供其他目标架构。
2. Docker Engine 必须启用 BuildKit；Docker Compose 必须支持 `build.secrets`。
3. 为 Hub 分配至少 `2gb` shared memory，并验证宿主 seccomp 策略允许 `seccomp=unconfined`。Compose 文件已经声明这两个条件，不要删除。
4. Gateway/反向代理必须提供 TLS、认证、授权、限流，并允许 `/api/instances/{id}/vnc` WebSocket Upgrade 和二进制帧透传。
5. 为 `/data/opencli-hub`、`/var/lib/opencli` 及 MySQL 备份准备受限、加密的存储位置。

Hub 容器和 Chrome 以 UID/GID `1000:1000` 运行。若改用 bind mount，宿主目录必须允许该用户读写。

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
docker compose -f compose.h2.yml build
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

推送 `docker` 分支会触发 `.github/workflows/docker-publish.yml`，构建
`linux/amd64` 镜像并推送以下 Docker Hub tag：

```text
<namespace>/opencli-hub:docker
<namespace>/opencli-hub:latest
<namespace>/opencli-hub:sha-<commit>
```

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

## 3. H2 单容器运行

H2 profile 为 `docker-h2`，数据库文件位于：

```text
/data/opencli-hub/database/opencli-hub
```

首次启动会幂等执行 `schema-h2.sql` 与 `data-h2.sql`。启动和检查：

```bash
docker compose -f compose.h2.yml up --build -d
curl --fail --show-error http://127.0.0.1:8080/actuator/health
docker compose -f compose.h2.yml ps
```

容器默认只将 Hub HTTP 发布到 `127.0.0.1:8080`。外部可见性由 Gateway 提供；VNC TCP 不应额外发布。

## 4. MySQL 5.7 部署

`compose.yml` 仅为新 MySQL volume 初始化 schema/data。它使用：

```text
mysql:5.7.44
database: opencli_hub
application user: opencli_hub
```

```bash
export MYSQL_ROOT_PASSWORD="$(openssl rand -hex 32)"
export OPENCLI_HUB_MYSQL_PASSWORD="$(openssl rand -hex 32)"
docker compose -f compose.yml up --build -d
curl --fail --show-error http://127.0.0.1:8080/actuator/health
```

MySQL 5.7 已 EOL。该选择仅为了既有兼容性：部署方应将数据库网络隔离、限制账户权限、监控 CVE，并规划受控升级路线。应用使用 `mysql_native_password`；不要修改为 MySQL 8 专属认证或 SQL 特性。

## 5. 备份与恢复

停止 Hub 后再取得可恢复快照，避免数据库、资源、Profile 与 OpenCLI home 相互不一致。

### H2

备份两个 named volume：

```text
opencli-hub-h2-data
opencli-hub-h2-home
```

其中 data volume 包含 H2 文件、Instance Profile、资源和日志；home volume 包含 OpenCLI home/data。恢复时停止 Compose stack，恢复同名 volume 内容，再以相同 signing key 启动。

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

对备份执行校验并定期在隔离环境演练恢复。MySQL DDL 隐式提交；发生迁移问题时，回滚方法是停止 Hub 并恢复迁移前备份，不是手写反向 DDL。

## 6. 升级和迁移

### 6.1 同一 schema 的镜像升级

1. 记录现有镜像 digest 和运行配置。
2. 等待或停止所有 Execution，停止 Hub。
3. 备份数据与数据库，保留当前 signing key。
4. 用相同 key 构建新镜像，启动相同 volume。
5. 检查 health、Instance Runtime、VNC、历史 Execution、资源和登录态。

### 6.2 既有 MySQL schema

对于旧版本数据库，必须在停机窗口按此顺序执行：

1. `scripts/migrate-mysql-uuid-ids.sql`
2. `scripts/migrate-mysql-browser-proxy-settings.sql`
3. `scripts/migrate-mysql-execution-indexes.sql`

每个脚本都有 `information_schema` 校验输出。完整字段、索引和回滚说明在各自的迁移文档中；不要跳过备份，也不要将 MySQL 8 volume 直接降级挂载到 5.7。

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

## 8. 故障处理

| 问题 | 处理顺序 |
|---|---|
| BuildKit 报 secret 缺失 | 检查 key 路径、权限和非空状态；不要用环境变量字符串或提交文件代替 secret。 |
| MySQL Hub 未启动 | 先检查 MySQL service health、密码变量、JDBC host/database，再查看 Hub console log。 |
| Chrome 启动失败 | 查看 Instance 的 CHROME/XVFB/OPENBOX/X11VNC 日志；确认 shm/seccomp、UID 1000 volume 权限和 Chrome binary。 |
| Browser Bridge 不连接 | 检查 managed policy、CRX server、Chrome log；不要添加被 Chrome 拒绝的 unpacked extension flags。 |
| VNC WebSocket 失败 | 查询 VNC status；确认 Gateway 转发 Upgrade；不要映射 x11vnc TCP 端口到外部。 |
| CUSTOM proxy 无法访问 | 从容器网络检查 DNS/路由；确认 URI 无凭据且有端口；bridge 下宿主 loopback 不可直接使用。 |
| 执行长时间等待 | 检查 Instance 排队数、Gateway timeout 和 command timeout；不要用自动重试写命令代替业务幂等。 |

## 9. 发布验证

发布候选至少执行：

```bash
export JAVA_HOME=/path/to/jdk-17
env JAVA_HOME="$JAVA_HOME" mvn -B -ntp clean test
(cd frontend && npm ci && npm test && npm run lint && npm run build)
OPENCLI_HUB_MYSQL_PASSWORD=dummy MYSQL_ROOT_PASSWORD=dummy docker compose -f compose.yml config
docker compose -f compose.h2.yml config
find scripts -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n
git diff --check
```

准备好 signing key 后，再运行 `scripts/docker/smoke-h2.sh`。它验证镜像、health、Hub API、OpenCLI 版本和 CRX loopback health；不会创建 Instance 或覆盖 Chrome 登录态 E2E。
