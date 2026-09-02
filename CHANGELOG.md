# 更新记录

本项目遵循语义化版本约定。发布版本的完整构建、部署与升级要求以根 README 和 `docs/` 为准。

## Unreleased

### 新增

- 新增 `scripts/migrate-postgresql-instance-concurrency.sql`、`scripts/migrate-mysql-instance-concurrency.sql`、`scripts/migrate-sqlite-instance-concurrency.sh`：为 PostgreSQL 16、MySQL 5.7/8.4 与 SQLite 旧数据库补齐 `hub_instance.max_concurrency`（默认 1，合法范围 1..4，旧行保持 1 串行行为，均可安全重复执行或无损退出）。
- 新增 `scripts/migrate-mysql-instance-state-changed-at-immutable.sql`：移除 `hub_instance.state_changed_at` 的 `ON UPDATE`（MySQL 5.7/8.4 兼容、可重复执行；既有已漂移值不可恢复，不伪造历史时间）。
- 文档对齐三种编译期数据库变体（PostgreSQL 16 默认 / MySQL 8.4 LTS / SQLite）、三库标准升级停机迁移步骤（停服务→备份→迁移→升级应用→验证）、Canary 灰度与 Chrome/shm 稳定性边界、H2 生产退役指引与发布镜像 tag 矩阵。

### 变更

- Instance 支持配置并发度 `maxConcurrency`（1..4，默认 1，升级后旧行保持 1）与排队容量 `maxPending`（0..50，默认 5，0 表示无排队缓冲，满时立即 429 `INSTANCE_QUEUE_FULL`）；总承载容量为 `maxConcurrency + maxPending`。浏览器命令缺失/空白 `siteSession` 时按 `EPHEMERAL` 处理，可解析为 `EPHEMERAL` 的命令受 `maxConcurrency` 限制并行（业务级冲突由调用者负责），可解析为 `PERSISTENT` 的命令独占串行；非浏览器命令或无法解析的未知 session 元数据 fail-safe 独占。
- 客户端行为变化（详见根 README「客户端行为变化」表）：`POST /api/opencli/execute` 返回 HTTP 202 + PENDING DTO，需轮询 `waitSeconds`（最大 120）或取消；本地文件必须上传后仅用 `/resources/...` 虚拟路径（绝对路径、`~`、`file://`、Windows drive path、显式 traversal 及相对 OpenCLI workdir 实际存在的文件/目录拒绝）；Execution 列表按 `queued_at DESC, id DESC`；cancel/clear-queue 丢弃的任务持久化 CANCELLED；时间戳统一 UTC LocalDateTime（`gmt_*` 列名保留兼容）。
- 生产数据库从 H2/MySQL 5.7 迁移到 PostgreSQL 16（默认）、MySQL 8.4、SQLite 编译期变体；H2 仅保留测试用途。
- 升级到 `convention4j-parent:1.2.3`，使用其默认管理的 AutoMapper `1.0.1`；Instance 创建时间排序由 `@FieldName` 驱动的生成 SQL 取代手写兼容 SQL。
- 成对升级 OpenCLI artifact lock 至已发布的 `fork-v1.8.7-fengwk.9`：CLI `1.8.7-fengwk.9` 与 Browser Bridge extension `1.0.30`，并同步锁定对应 URL、SHA256 和 source revision。

### 修复

- 修复并发提交时多个请求读取相同陈旧路由负载、导致任务在繁忙 Instance 排队而其他可用 Instance 空闲的问题；自动路由按每个 Instance 的 `acceptedNotTerminalCount` 原始总量选择最低负载，不再按 `maxConcurrency` 归一化。
- OpenCLI stdout/stderr 默认捕获上限从 65,535 提升到 1,048,576 字符，并支持 `OPENCLI_HUB_MAX_CAPTURE_CHARS` 覆盖，减少长 JSON 结果因截断而被判定为无效输出。
- Runtime 镜像安装 `ffmpeg`，提供 `ffprobe`，供 `jimeng-agent` 等插件做音视频时长预检。
- VNC 远端剪贴板兼容 x11vnc 通过传统 `ServerCutText` 传输的 UTF-8 文本，避免中文复制到本机后出现 Latin-1 乱码。
- noVNC 固定到包含大发送队列修复的 `1.5.0-g50e4685`，避免长脚本文本跨越 10 KiB 队列后发生传输损坏。

## 1.0.0

### 新增

- 多 Instance 正式 Chrome Runtime：独立 Profile、Xvfb、openbox、loopback x11vnc、VNC WebSocket 和启动恢复。
- 受控 OpenCLI Command Catalog、参数验证、命令黑名单、输出规则、异步执行历史（HTTP 202 + 轮询）与资源归档。
- React 管理端：Instances、Executions、Commands、Resources、Logs、Settings 与 noVNC 控制台。
- 插件维护管理端与 API：配置 GitHub/URL/本地插件源，通过官方 `opencli plugin` 同步并刷新 Command Catalog。
- 三种编译期数据库变体部署：PostgreSQL 16（默认）、MySQL 8.4 LTS、SQLite；UUID/旧 BIGINT ID 兼容、代理设置、Execution 索引、Instance priority 与不可变时间戳迁移脚本。
- 全局与 Instance 级浏览器代理策略，支持 `INHERIT`、`DIRECT`、`CUSTOM`。

### 安全与运维

- Browser Bridge CRX 由构建期 BuildKit secret 签名，私钥不进入仓库或运行时镜像。
- OpenCLI CLI 与 Browser Bridge 由单一 artifact lock 固定 URL、版本、来源提交和 SHA256；当前配套 fork 为 `1.8.7-fengwk.8` / `1.0.29`。
- Chrome 通过 managed policy 和 loopback CRX 更新服务安装 extension；VNC TCP 不对外发布。
- 资源路径/symlink 防护、上传限制、Instance 生命周期串行化、执行 deadline 和安全启动恢复（coordinator 恢复屏障）。
- persistent write session lease 由 daemon capability/CAS 管理：Hub 仅在 cleanup 后按精确 owner 请求 reset recovery；未知 write 结果不重放。

### 升级注意

- 既有 MySQL 依次运行 UUID ID、浏览器代理、Execution 索引、Instance priority、queued_at 不可变、state_changed_at 不可变迁移（兼容 MySQL 5.7/8.4）；MySQL DDL 回滚依赖恢复迁移前备份。
- H2 已退出生产（仅测试），旧 H2 使用者先在旧版本备份/导出，再导入受支持数据库；没有自动 in-place 转换。
- 保持同一 CRX signing key；轮换 key 会改变 extension identity，必须经过停机备份和单 Instance 验证。
