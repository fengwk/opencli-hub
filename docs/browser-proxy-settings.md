# 浏览器代理设置

## Scope

OpenCLI Hub supports a global browser proxy policy and an Instance-level override:

- Global: `DIRECT` or `CUSTOM`.
- Instance: `INHERIT`, `DIRECT`, or `CUSTOM`.
- `INHERIT` resolves the current global policy when the Instance starts.
- Changes do not restart running Instances; stop/start or restart the Instance to apply them.
- The setting controls Chrome website traffic only. Hub HTTP, the shared OpenCLI daemon, and loopback CRX/bridge traffic are not proxied.

A custom proxy must be an unauthenticated `http`, `https`, `socks4`, or `socks5` URI with an explicit port and a maximum length of 512 characters, for example:

```text
http://proxy.example:8080
socks5://proxy.example:1080
```

Credentials are intentionally unsupported in the first version so secrets are not stored in plaintext or exposed in Chrome process arguments. User information, paths, query strings, and fragments are rejected. Accepted values are canonicalized (trimmed with lower-case scheme and host) before persistence and use in Chrome arguments.

The management API is `GET`/`PUT /api/settings`; Instance create/update payloads and responses expose `proxyMode` and `proxyServer`. A global `INHERIT` mode is rejected, while an omitted Instance `proxyMode` from an older client is treated as `INHERIT`.

Every runtime Chrome also uses `--disable-gpu`. Software rendering remains enabled for servers without GPU devices.

## Docker reachability

The proxy address is resolved from inside the Hub container. In bridge networking, `127.0.0.1` refers to the container itself, not the Docker host. Use a proxy endpoint reachable from the container, or a deployment-specific host-network configuration when the proxy is intentionally bound to host loopback.

## Supported database initialization

The three database variants (PostgreSQL 16 default / MySQL 8.4 LTS / SQLite) run their own
`schema-database.sql` at startup (schema-only, no data SQL); H2 is retired from production and
exists only in tests. The `id=1` global `DIRECT` row is **not** seeded by SQL: the application
lazily inserts it on first read (`HubSystemSettingsServiceImpl`), so a fresh or existing supported
database converges to `DIRECT` without a data migration and saved settings are never overwritten.

## MySQL migration (legacy)

MySQL schema changes are manual because DDL commits implicitly. The stopped-service migration script
conditionally adds missing columns/tables and only seeds the singleton when absent, so rerunning it
preserves already saved proxy settings. The script is compatible with MySQL 5.7 and 8.4.

1. Stop every Hub process.
2. Back up the database and verify the backup can be read.
3. Run:

   ```bash
   mysql --host "$OPENCLI_HUB_MYSQL_HOST" \
     --user "$OPENCLI_HUB_MYSQL_USERNAME" \
     --password --database=opencli_hub < scripts/migrate-mysql-browser-proxy-settings.sql
   ```

   `--password` 会交互式读取密码。运行前将 host 和 user 变量设置为目标数据库的连接信息。

4. Verify the final result sets printed by the script:
   - every Instance mode is `INHERIT`, `DIRECT`, or `CUSTOM`;
   - `hub_system_settings` contains exactly `id=1`;
   - the new columns have the expected lengths and nullability.
5. Start the new Hub version and verify `GET /api/settings` plus an existing Instance response.

Rollback is application rollback plus restoration of the pre-migration backup. Do not attempt transactional rollback of the DDL.
