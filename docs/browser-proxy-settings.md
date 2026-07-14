# Browser proxy settings

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

## H2 migration

H2 runs `schema-h2.sql` and `data-h2.sql` at startup. The migration is idempotent:

- existing `hub_instance` rows gain `proxy_mode='INHERIT'` and a null `proxy_server`;
- `hub_system_settings` is created if absent;
- the `id=1` global `DIRECT` row is inserted only when absent and never overwrites saved settings.

## MySQL migration

MySQL schema changes are manual because DDL commits implicitly. The stopped-service migration script conditionally adds missing columns/tables and only seeds the singleton when absent, so rerunning it preserves already saved proxy settings.

1. Stop every Hub process.
2. Back up the database and verify the backup can be read.
3. Run:

   ```bash
   mysql --database=opencli_hub < scripts/migrate-mysql-browser-proxy-settings.sql
   ```

4. Verify the final result sets printed by the script:
   - every Instance mode is `INHERIT`, `DIRECT`, or `CUSTOM`;
   - `hub_system_settings` contains exactly `id=1`;
   - the new columns have the expected lengths and nullability.
5. Start the new Hub version and verify `GET /api/settings` plus an existing Instance response.

Rollback is application rollback plus restoration of the pre-migration backup. Do not attempt transactional rollback of the DDL.
