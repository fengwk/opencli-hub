# opencli-hub 文档索引

## Release 设计

- [完整技术设计方案](technical-design.md)
  - 产品范围与非目标
  - 三模块架构
  - Docker、正式 Chrome、OpenCLI extension 和 daemon
  - Instance 生命周期与 `contextId`
  - Command Catalog、参数安全、黑名单和输出规则
  - 同步执行、路由、persistent affinity、队列和超时
  - 资源中心、VNC、日志、代理和前端
  - MySQL/H2、MyBatis Auto Mapper、API、错误码和测试

## 运维与迁移

- [UUID ID 迁移](uuid-id-migration.md)
  - JDK UUID 与旧 BIGINT ID 兼容规则
  - H2 自动迁移和 MySQL 停机手工迁移
  - Profile、Execution 与资源保留边界
- [Execution 查询索引迁移](execution-index-migration.md)
  - `queued_at, id` 稳定分页组合索引
  - H2 自动升级和 MySQL 5.7 停机手工迁移
  - 备份、校验与备份恢复回滚流程
- [浏览器代理设置](browser-proxy-settings.md)
  - 全局默认与 Instance 覆盖语义
  - Docker 代理可达性与 Runtime Chrome 参数
  - H2 自动迁移和 MySQL 停机手工迁移
- [部署与运维](deployment-and-operations.md)
- [安全](security.md)
