-- SQLite fresh schema, idempotent for Spring SQL init (schema only; no data SQL).
--
-- SQLite has no native timestamp type; timestamp(3) columns use NUMERIC affinity and
-- sqlite-jdbc round-trips LocalDateTime values. CURRENT_TIMESTAMP defaults are evaluated
-- in UTC by SQLite, matching the gmt_create/gmt_modified/state_changed_at UTC contract.
--
-- state_changed_at must NOT auto-update: state transitions are written by the application
-- with explicit timestamps, and DB-side auto-update would silently rewrite the sort key.
-- (SQLite has no ON UPDATE clause; the application owns every state_changed_at write.)

create table if not exists hub_instance (
    id varchar(36) not null primary key,
    code varchar(64) not null,
    display_name varchar(128) not null,
    context_id varchar(128) null,
    state varchar(32) not null,
    websites_json text not null,
    max_pending int not null,
    priority int not null default 0,
    proxy_mode varchar(16) not null default 'INHERIT',
    proxy_server varchar(512) null,
    last_error_message text null,
    state_changed_at timestamp(3) not null default current_timestamp,
    gmt_create timestamp(3) not null default current_timestamp,
    gmt_modified timestamp(3) not null default current_timestamp,
    version bigint not null default 0
);

create unique index if not exists uk_hub_instance_code on hub_instance (code);
create unique index if not exists uk_hub_instance_context_id on hub_instance (context_id);
create index if not exists idx_hub_instance_state on hub_instance (state);

create table if not exists hub_system_settings (
    id int not null primary key,
    proxy_mode varchar(16) not null,
    proxy_server varchar(512) null,
    gmt_create timestamp(3) not null default current_timestamp,
    gmt_modified timestamp(3) not null default current_timestamp,
    version bigint not null default 0
);

create table if not exists hub_execution (
    id varchar(36) not null primary key,
    instance_id varchar(36) null,
    instance_code varchar(64) null,
    command_key varchar(160) not null,
    site varchar(80) not null,
    site_session varchar(16) not null,
    argv_json text not null,
    reuse_instance boolean not null default 0,
    status varchar(32) not null,
    exit_code int null,
    stdout_content text null,
    stdout_truncated boolean not null default 0,
    stderr_content text null,
    stderr_truncated boolean not null default 0,
    error_message text null,
    timeout_millis bigint not null,
    -- queued_at is immutable enqueue time; the application never rewrites it.
    queued_at timestamp(3) not null default current_timestamp,
    started_at timestamp(3) null,
    finished_at timestamp(3) null,
    gmt_create timestamp(3) not null default current_timestamp,
    gmt_modified timestamp(3) not null default current_timestamp,
    version bigint not null default 0
);

create index if not exists idx_hub_execution_queued_at_id on hub_execution (queued_at, id);
create index if not exists idx_hub_execution_instance_queued_at_id
    on hub_execution (instance_id, queued_at, id);
create index if not exists idx_hub_execution_status on hub_execution (status);

create table if not exists hub_command_blacklist (
    id varchar(36) not null primary key,
    command_key varchar(160) not null,
    reason varchar(512) null,
    gmt_create timestamp(3) not null default current_timestamp,
    gmt_modified timestamp(3) not null default current_timestamp,
    version bigint not null default 0
);

create unique index if not exists uk_hub_command_blacklist_command_key
    on hub_command_blacklist (command_key);

create table if not exists hub_command_output_rule (
    id varchar(36) not null primary key,
    command_key varchar(160) not null,
    argument_name varchar(64) not null,
    target_type varchar(32) not null,
    file_name varchar(255) null,
    gmt_create timestamp(3) not null default current_timestamp,
    gmt_modified timestamp(3) not null default current_timestamp,
    version bigint not null default 0
);

create unique index if not exists uk_hub_command_output_rule_command_key
    on hub_command_output_rule (command_key);

create table if not exists hub_plugin_source (
    id varchar(36) not null primary key,
    name varchar(128) not null,
    source varchar(1024) not null,
    desired_plugins_json text not null,
    enabled boolean not null default 1,
    auto_update boolean not null default 0,
    last_status varchar(32) not null,
    last_error text null,
    last_synced_at timestamp(3) null,
    last_result_json text null,
    gmt_create timestamp(3) not null default current_timestamp,
    gmt_modified timestamp(3) not null default current_timestamp,
    version bigint not null default 0
);

create unique index if not exists uk_hub_plugin_source_name on hub_plugin_source (name);
create index if not exists idx_hub_plugin_source_enabled on hub_plugin_source (enabled);
