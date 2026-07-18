# 更新记录

本项目遵循语义化版本约定。发布版本的完整构建、部署与升级要求以根 README 和 `docs/` 为准。

## 1.0.0

### 新增

- 多 Instance 正式 Chrome Runtime：独立 Profile、Xvfb、openbox、loopback x11vnc、VNC WebSocket 和启动恢复。
- 受控 OpenCLI Command Catalog、参数验证、命令黑名单、输出规则、同步执行历史与资源归档。
- React 管理端：Instances、Executions、Commands、Resources、Logs、Settings 与 noVNC 控制台。
- 插件维护管理端与 API：配置 GitHub/URL/本地插件源，通过官方 `opencli plugin` 同步并刷新 Command Catalog。
- H2 单容器持久化和 MySQL 5.7.44 部署；UUID/旧 BIGINT ID 兼容、代理设置与 Execution 索引迁移脚本。
- 全局与 Instance 级浏览器代理策略，支持 `INHERIT`、`DIRECT`、`CUSTOM`。

### 安全与运维

- Browser Bridge CRX 由构建期 BuildKit secret 签名，私钥不进入仓库或运行时镜像。
- OpenCLI CLI 与 Browser Bridge 由单一 artifact lock 固定 URL、版本、来源提交和 SHA256；当前配套 fork 为 `1.8.7-fengwk.2` / `1.0.24`。
- Chrome 通过 managed policy 和 loopback CRX 更新服务安装 extension；VNC TCP 不对外发布。
- 资源路径/symlink 防护、上传限制、Instance 生命周期串行化、执行 deadline 和安全启动恢复。

### 升级注意

- 既有 MySQL 依次运行 UUID ID、浏览器代理、Execution 索引迁移；MySQL DDL 回滚依赖恢复迁移前备份。
- 保持同一 CRX signing key；轮换 key 会改变 extension identity，必须经过停机备份和单 Instance 验证。
