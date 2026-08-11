# UUID ID 迁移

opencli-hub 新建的 Instance、Execution、命令黑名单和输出规则使用 JDK `UUID.randomUUID()` 生成 ID。Hub 不再启动 Snowflake 生成器，不需要 `worker-id`、Redis 或其他 ID 协调服务。

现有 Snowflake/BIGINT ID 不会重写为 UUID，而是无损保留为十进制字符串。这样可以继续使用原 Instance API 地址、Profile 目录、`contextId`、历史 Execution 和 execution resource group。

## 兼容规则

- 新 ID：小写规范 UUID，例如 `6f59e726-bdb3-4b32-b5e4-95e070a1e87b`；
- 旧 ID：原正 `BIGINT` 值的十进制字符串，例如 `343020517415976960`；
- Instance 目录不会重命名：`instances/{旧数字 ID}/` 原地保留；
- `code` 仍是可编辑、唯一的人类可读别名，不替代内部 ID；
- 删除 Instance 后仍保留历史 Execution，因此历史 `hub_execution.instance_id` 没有对应 Instance 可以是正常状态。

## 受支持数据库（schema-only 初始化）

三个数据库变体（PostgreSQL 16 默认 / MySQL 8.4 LTS / SQLite）每次启动都通过 Spring SQL initialization
幂等执行各自变体的 `schema-database.sql`（schema-only，无 data SQL），新库直接获得 `VARCHAR(36)` UUID schema。
该初始化**不会**修改既有 MySQL 表结构，也**没有**任何自动 in-place 数据转换；H2 已退出生产（仅测试），
不存在 H2 自动迁移路径。

升级前仍应备份整个 `OPENCLI_HUB_DATA_DIR` 或 Docker volume。升级步骤（新库场景）：

1. 停止 Hub，确认没有 Chrome/OpenCLI Execution 正在运行；
2. 备份数据库与 `instances/`、`resources/`；
3. 使用新镜像启动同一 volume（PostgreSQL/MySQL/SQLite 变体各自建当前 schema）；
4. 检查健康状态、旧数字 Instance、VNC、日志和历史 Execution；
5. 创建一条新记录，确认返回的是 UUID。

## 既有 MySQL（legacy）

既有 MySQL 库的 schema 由当前变体的 `schema-database.sql` 初始化（`CREATE TABLE IF NOT EXISTS` 不能改列），
旧数据库必须在停机窗口手工执行 [`scripts/migrate-mysql-uuid-ids.sql`](../scripts/migrate-mysql-uuid-ids.sql)
（脚本兼容 MySQL 5.7/8.4）。

建议流程：

1. 停止所有连接该数据库的 Hub 进程；
2. 备份数据库，并单独备份 Hub 数据目录；
3. 记录受迁移的四张表的行数和关键 Instance ID；
4. 连接目标 `opencli_hub` 数据库并执行迁移脚本；
5. 确认脚本列出的五个列均为 `varchar(36)`，且 `execution_instance_id_too_long=0`；
6. 对比迁移前后的表行数和关键 ID；
7. 启动新 Hub，验证旧 Instance/Profile、VNC、日志、Execution 和资源；
8. 创建新 Instance 或新 Execution，确认新 ID 为 UUID。

示例：

```bash
mysql --host "$OPENCLI_HUB_MYSQL_HOST" \
  --user "$OPENCLI_HUB_MYSQL_USERNAME" \
  --password --database=opencli_hub < scripts/migrate-mysql-uuid-ids.sql
```

`--password` 会交互式读取密码，避免在命令行或环境变量中传递密码。运行前将 host 和 user 变量设置为目标数据库的连接信息。

MySQL DDL 会隐式提交。迁移后如果已经写入 UUID，不能安全地直接改回 BIGINT；回滚应停止 Hub 并恢复迁移前备份。
