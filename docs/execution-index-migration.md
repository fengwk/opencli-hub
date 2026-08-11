# Execution 查询索引迁移

Execution 列表查询固定使用 `ORDER BY queued_at DESC, id DESC`；按 Instance 查询还会增加
`WHERE instance_id = ?`。新建数据库使用以下普通升序 B-tree 组合索引：

- `idx_hub_execution_queued_at_id (queued_at, id)`；
- `idx_hub_execution_instance_queued_at_id (instance_id, queued_at, id)`。

MySQL 5.7/8.4 的 InnoDB 都可以反向扫描普通升序 B-tree，因此不需要 MySQL 8.0 的降序索引语法。
旧 `instance_id` 单列索引已被第二个组合索引的左前缀覆盖，旧 `gmt_create` 索引与实际排序不匹配；
迁移会删除这两个旧索引并保留 `status` 索引。索引变更不修改 Execution 排序、历史记录、审计时间或 API。

## 受支持数据库（schema-only 初始化）

三个数据库变体（PostgreSQL 16 默认 / MySQL 8.4 LTS / SQLite）每次启动都通过 Spring SQL initialization
幂等执行各自变体的 `schema-database.sql`，新库直接创建上述组合索引（PostgreSQL/SQLite 使用
`create index if not exists`），因此新库和已有 PostgreSQL/SQLite 库都不需要单独执行迁移命令。
该初始化不会修改既有 MySQL 表结构；H2 已退出生产（仅测试）。升级前仍应备份数据库和 Hub 数据目录。

## 既有 MySQL（legacy）

既有 MySQL 库的 schema 由当前变体的 `schema-database.sql` 初始化，但
`CREATE TABLE IF NOT EXISTS` 不能修改已有 `hub_execution` 的旧索引。必须安排停机窗口手工执行
[`scripts/migrate-mysql-execution-indexes.sql`](../scripts/migrate-mysql-execution-indexes.sql)
（脚本兼容 MySQL 5.7/8.4）：

1. 停止所有连接目标数据库的 Hub 进程，确认没有 Execution 正在运行；
2. 创建并验证目标数据库的完整备份，记录 `hub_execution` 行数和当前索引；
3. 连接正确的 `opencli_hub` 数据库并执行迁移脚本；
4. 检查脚本末尾校验结果：两个组合索引的列顺序正确，
   `matching_query_index_count=2` 且 `deprecated_index_count=0`；
5. 对比迁移前后的 `hub_execution` 行数，再启动 Hub 并验证全量和按 Instance 的 Execution 列表；
6. 如需回滚，停止 Hub 并恢复迁移前备份。MySQL DDL 会隐式提交，不以反向 DDL 代替备份恢复。

示例：

```bash
mysql --host "$OPENCLI_HUB_MYSQL_HOST" \
  --user "$OPENCLI_HUB_MYSQL_USERNAME" \
  --password --database=opencli_hub < scripts/migrate-mysql-execution-indexes.sql
```

`--password` 会交互式读取密码，避免在命令行或环境变量中传递密码。运行前将 host 和 user 变量设置为目标数据库的连接信息。

脚本通过 `information_schema.statistics` 判断索引是否存在及列顺序，不使用 MySQL 5.7 不支持的
`CREATE INDEX IF NOT EXISTS`，可在演练和校验时重复执行。
