# opencli-hub 文档索引

## 技术设计

- [完整技术设计方案](technical-design.md)
  - 产品范围与非目标
  - 三模块架构
  - Docker、正式 Chrome、OpenCLI extension 和 daemon
  - Instance 生命周期与 `contextId`
  - Command Catalog、参数安全、黑名单和输出规则
  - 同步执行、路由、persistent affinity、队列和超时
  - 每日资源中心、VNC、日志和前端
  - MySQL/H2、MyBatis Auto Mapper、API、错误码和测试

## 运维迁移

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

## 实施计划

- [实施任务拆分](implementation-plan.md)
  - 任务依赖图
  - 多 Agent 并行建议
  - 每个任务的文件边界、交付物、测试和验收条件
  - 首个单 Instance E2E 里程碑

## 文档使用规则

1. 后续开发以 `technical-design.md` 为设计基线。
2. 单 Agent 或多 Agent 开发时，从 `implementation-plan.md` 选择完整任务项，不拆成没有独立验收面的零散改动。
3. 实现发现设计冲突时，先更新技术设计，再修改代码。
4. OpenCLI 和 Web2API 是只读参考项目，不在其仓库中直接实现 opencli-hub 功能。
5. 所有实现遵循 KISS，不增加当前设计范围外的模块、基础设施和抽象。
