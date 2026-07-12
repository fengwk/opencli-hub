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

## 文档

- [技术设计](docs/technical-design.md)
- [实施计划](docs/implementation-plan.md)
- [文档索引](docs/README.md)
