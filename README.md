# opencli-hub

opencli-hub 是一个面向 OpenCLI Browser Bridge 的单机浏览器实例管理与命令路由平台。每个 Instance 使用独立的正式 Google Chrome Profile，并通过独立 `contextId` 接入共享 OpenCLI daemon。

## 当前基线

```text
web -> core -> share
```

- `share`：REST DTO、枚举和稳定错误码；
- `core`：领域模型、MyBatis Repository、本地进程基础设施和后续业务模块；
- `web`：Spring Boot 启动模块及后续 REST/WebSocket 接口；
- `frontend`：后续 React/Vite 管理端，不作为 Maven module。

生产目标是 Docker 单容器部署。Hub 自身不实现认证和授权，访问控制由上游 SCG Gateway 负责。

## 本地构建

需要 JDK 17：

```bash
env JAVA_HOME=$JAVA_HOME_17 mvn clean test
```

## 本地 H2 启动

```bash
env JAVA_HOME=$JAVA_HOME_17 mvn clean package
env JAVA_HOME=$JAVA_HOME_17 \
  $JAVA_HOME_17/bin/java \
  -jar web/target/opencli-hub-web-1.0.0.jar \
  --spring.profiles.active=local-h2
```

`local-h2` 使用内存数据库，并从 `core/src/main/resources/schema-h2.sql` 初始化四张业务表。

## 数据库

- 本地联调：H2，自动执行 `schema-h2.sql` 和 `data-h2.sql`；
- 生产环境：MySQL，部署前手工执行 `schema-mysql.sql` 和 `data-mysql.sql`；
- Service、Repository 和 Mapper 不负责运行时建表。

MyBatis SQL 由 Auto Mapper 在 Maven `compile` 阶段生成到 `target/classes`，不要在源码资源目录提交同路径空 Mapper XML。

## Docker 部署

正式镜像是多阶段构建：先按 `frontend/package-lock.json` 执行 `npm ci && npm run build`，再以 JDK 17 打包 Spring Boot JAR。最终镜像默认以非 root `1000:1000` 启动 Hub，使用持久化 H2 profile；Chrome/OpenCLI 实例由 Hub 生命周期管理，镜像不会自行启动 Chrome 或发布 VNC 端口。

H2 单容器 smoke 部署（Docker 主机须验证可使用 `seccomp=unconfined`，并保留 `2gb` shared memory）：

```bash
docker compose -f compose.h2.yml up --build -d
curl --fail http://127.0.0.1:8080/actuator/health
# 可选：health、API、OpenCLI/CRX 版本 smoke；不会运行 Chrome E2E
scripts/docker/smoke-h2.sh
# 本地短测可复用宿主 npm/~/.m2 缓存，仍产出同一个 final image
OPENCLI_HUB_SMOKE_BUILD_MODE=local scripts/docker/smoke-h2.sh
docker compose -f compose.h2.yml down
```

`opencli-hub-h2-data` 保存 `/data/opencli-hub`（H2 文件数据库、日志、资源和实例目录），`opencli-hub-h2-home` 保存 `/var/lib/opencli`。`application-docker-h2.yml` 以幂等 H2 schema/data 脚本初始化新库；该 profile 仅适合单容器 H2。`OPENCLI_HUB_SMOKE_BUILD_MODE=local` 会先执行 `scripts/docker/build-local.sh`，再用 Compose 启动同一 `opencli-hub:local` 镜像，适合网络不稳定时的本地回归。

MySQL 部署使用固定 `mysql:8.4.5`，并在**新建** `opencli-hub-mysql-data` 卷的首次启动时由 MySQL 官方 entrypoint 执行版本化的 `schema-mysql.sql`、`data-mysql.sql`。Hub 的运行时 `spring.sql.init.mode` 仍为 `never`：不要依赖 Hub 对既有 MySQL 建表。

```bash
export MYSQL_ROOT_PASSWORD='replace-with-a-secret'
export OPENCLI_HUB_MYSQL_PASSWORD='replace-with-a-secret'
docker compose -f compose.yml up --build -d
curl --fail http://127.0.0.1:8080/actuator/health
docker compose -f compose.yml down
```

`compose.yml` 用 `SPRING_PROFILES_ACTIVE=mysql` 覆盖镜像默认 H2 profile，并直接使用基础 `application.yml` 的 MySQL 配置。两个 Compose 文件均只发布 Hub 的 `8080`；VNC 保持容器内部 loopback。不要删除 `shm_size: 2gb` 或 `security_opt: seccomp=unconfined`，否则宿主 Docker 的默认 seccomp 可能阻断 Chrome sandbox。生产环境应由调用方提供上述密码，且应显式管理 named volumes 的备份与保留。

## 文档

- [技术设计](docs/technical-design.md)
- [实施计划](docs/implementation-plan.md)
- [文档索引](docs/README.md)
