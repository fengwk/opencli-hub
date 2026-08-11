# OpenCLI 插件维护

本文说明 `opencli-hub` 如何通过**官方 OpenCLI plugin 机制**维护第三方/自研命令插件，以及运维边界。

## 1. 设计原则

```text
Hub = 配置 + Web 操作 + Catalog 刷新 + 同步状态
OpenCLI = opencli plugin install/update/list/uninstall
```

Hub **不**自己实现 monorepo clone/symlink/npm install/TS 编译。  
插件最终落在 OpenCLI 官方目录：

```text
$HOME/.opencli/plugins/<name>/
$HOME/.opencli/monorepos/<repo>/     # monorepo 源码缓存
$HOME/.opencli/plugins.lock.json
```

在正式 Docker 运行时：

```text
HOME=/var/lib/opencli
```

因此插件持久化在已有卷：

```text
.../vps-opencli-hub/data/opencli  ->  /var/lib/opencli
```

## 2. 官方目录与 monorepo

### 单插件仓库

```text
github:you/opencli-plugin-weather
```

安装后大致：

```text
~/.opencli/plugins/weather/
```

### monorepo / 多子插件

仓库根 `opencli-plugin.json` 声明：

```json
{
  "plugins": {
    "crm": { "path": "packages/crm" },
    "weather": { "path": "packages/weather" }
  }
}
```

官方行为：

```text
clone -> ~/.opencli/monorepos/<repo>/
plugins/crm -> monorepos/.../packages/crm
plugins/weather -> monorepos/.../packages/weather
```

Hub 配置的 `desiredPlugins` 是**子插件名**（如 `crm`），不是任意磁盘 path。

## 3. 管理 API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/plugins/sources` | 列出配置的插件源 |
| POST | `/api/plugins/sources` | 新增源 |
| PUT | `/api/plugins/sources/{id}` | 更新源 |
| DELETE | `/api/plugins/sources/{id}` | 删除**配置**（默认不卸载已装插件） |
| POST | `/api/plugins/sources/{id}/sync` | 同步该源（空 desiredPlugins 时仅 install；已装会 skip） |
| POST | `/api/plugins/sources/{id}/update-installed` | 仅更新已装插件（`opencli plugin update`） |
| GET | `/api/plugins/installed` | 包装 `opencli plugin list -f json`，仅返回真实插件条目 |
| POST | `/api/plugins/reload-catalog` | 仅刷新 Hub Command Catalog |

管理端页面：`/plugins`。

### 源字段

| 字段 | 说明 |
|---|---|
| `name` | 展示名，唯一 |
| `source` | 官方 source：`github:org/repo`、`github:org/repo/sub`、`https://...`、`file://...` |
| `desiredPlugins` | 子插件名列表；空表示 `opencli plugin install <source>` 的默认集合。非空时仅支持 `github:org/repo` 或规范 GitHub URL `https://github.com/org/repo[.git]`，Hub 会转换为官方 `github:org/repo/sub` 语法。 |
| `enabled` | 是否允许同步 |

### 同步行为

新增或编辑 source 只保存配置。运维人员在管理页执行 source 操作或调用 sync API 时，Hub 才会运行官方 CLI；不存在后台自动安装或自动更新。空 `desiredPlugins` 的 source 只安装默认集合；显式子插件 source 才会先更新已装子插件、再安装缺失项。

```text
desired 为空:
  opencli plugin install <source>

desired 非空:
  对每个 name:
    opencli plugin update <name>   # 已装则更新
    失败则 opencli plugin install github:<org>/<repo>/<name>
      # github: source 直接使用；规范 github.com URL 会先转换为该语法

然后:
  opencli plugin list
  Hub Catalog.reload()
```

同步是全局互斥的（同一时刻只允许一个 sync）。

## 4. 运维注意

1. **新表** `hub_plugin_source`  
   三个数据库变体（PostgreSQL/MySQL/SQLite）都通过 Spring SQL initialization 执行
   `CREATE TABLE IF NOT EXISTS`。
   已有库在 Hub 下次以对应变体启动时会自动建表；**不是**版本化手工迁移脚本场景。

2. **同步耗时**  
   可能包含 git clone + npm install + TS 编译，可达数分钟。  
   管理端对该接口使用更长 HTTP timeout；Gateway 也需覆盖。

3. **Catalog**  
   插件装好后必须刷新 Catalog，新命令才会出现在 `/api/opencli/commands`。  
   sync 成功会自动 reload；也可手动调用 `/api/plugins/reload-catalog`。

4. **Instance websites**  
   browser 类插件站点仍需在 Instance 的 `websites` 中启用后才能 execute。

5. **安全**  
   - 插件代码会在已登录 Chrome 中执行，只装可信源  
   - 删除配置不会自动 `plugin uninstall`（避免误删线上命令）  
   - 当前无认证；插件管理 API 与其它管理 API 一样必须放在 Gateway 后  

6. **私有仓库**  
   MVP 不托管 git 凭据；若需私有源，需容器内已有可用 git credential，或后续扩展。

## 5. 示例

### 单插件

```bash
curl -sS -X POST "$HUB_URL/api/plugins/sources" \
  -H 'Content-Type: application/json' \
  -d '{
    "name":"github-trending",
    "source":"github:ByteYue/opencli-plugin-github-trending",
    "desiredPlugins":[],
    "enabled":true
  }'

curl -sS -X POST "$HUB_URL/api/plugins/sources/<id>/sync"
curl -sS "$HUB_URL/api/opencli/commands"
```

### monorepo 多子插件

```bash
curl -sS -X POST "$HUB_URL/api/plugins/sources" \
  -H 'Content-Type: application/json' \
  -d '{
    "name":"company-plugins",
    "source":"github:your-org/opencli-plugins",
    "desiredPlugins":["crm","weather"],
    "enabled":true
  }'
```

### `my-opencli/chatgpt-agent`

管理页 `/plugins` 中新增源：

```text
名称: my-opencli
Source: https://github.com/fengwk/my-opencli
Desired plugins: chatgpt-agent
Enabled: true
```

保存后点击“安装/更新已选子插件”，首次安装等价于：

```bash
opencli plugin install github:fengwk/my-opencli/chatgpt-agent
```

同步成功会自动 reload Catalog。随后还需确保目标 Instance 的 `websites` 包含
`chatgpt-agent`，并在该 Instance 的 Chrome Profile 中完成 ChatGPT 登录，才能执行
`chatgpt-agent/*` 命令。

仓库已发布 `v0.1.2`，但当前 OpenCLI plugin installer 尚不支持 `#tag` / `#commit`
pin；远程首次安装和后续 update 仍使用仓库默认分支。`plugins.lock.json` 会记录当前
实际安装 commit。

## 6. 故障排查

| 现象 | 检查 |
|---|---|
| sync 失败 | 源 URL、网络、`lastError`、容器内 `opencli plugin list` |
| 命令仍不可见 | 是否 reload Catalog；插件命令是否为 browser command |
| execute 拒绝 website | Instance `websites` 是否包含新 site |
| 前端 sync 超时 | Gateway/浏览器 timeout；后端默认 CLI 超时 300s |
| 容器重建后插件还在 | 确认 `/var/lib/opencli` 卷未丢 |

## 7. 未包含

- 后台定时拉取或自动更新（每次安装/更新均需由运维人员手动触发）
- 删除配置时自动 uninstall
- 私有 Git 凭据管理 UI
- 任意仓库 path 自由映射（仅 monorepo 子插件名）
- 插件签名/审批流
