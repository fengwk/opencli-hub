create table if not exists hub_instance (
    id varchar(36) not null,
    code varchar(64) not null,
    display_name varchar(128) not null,
    context_id varchar(128) null,
    state varchar(32) not null,
    websites_json clob not null,
    max_pending int not null,
    priority int not null default 0,
    proxy_mode varchar(16) not null default 'INHERIT',
    proxy_server varchar(512) null,
    last_error_message clob null,
    state_changed_at timestamp(3) not null default current_timestamp(3) on update current_timestamp(3),
    gmt_create timestamp(3) not null default current_timestamp(3),
    gmt_modified timestamp(3) not null default current_timestamp(3),
    version bigint not null default 0,
    primary key (id),
    constraint uk_hub_instance_code unique (code),
    constraint uk_hub_instance_context_id unique (context_id)
);

alter table hub_instance alter column id varchar(36) not null;
alter table hub_instance add column if not exists proxy_mode varchar(16) default 'INHERIT' not null;
alter table hub_instance add column if not exists proxy_server varchar(512) null;
alter table hub_instance add column if not exists priority int default 0 not null;
create index if not exists idx_hub_instance_state on hub_instance (state);

create table if not exists hub_system_settings (
    id int not null,
    proxy_mode varchar(16) not null,
    proxy_server varchar(512) null,
    gmt_create timestamp(3) not null default current_timestamp(3),
    gmt_modified timestamp(3) not null default current_timestamp(3),
    version bigint not null default 0,
    primary key (id)
);

create table if not exists hub_execution (
    id varchar(36) not null,
    instance_id varchar(36) null,
    instance_code varchar(64) null,
    command_key varchar(160) not null,
    site varchar(80) not null,
    site_session varchar(16) not null,
    argv_json clob not null,
    reuse_instance boolean not null default false,
    status varchar(32) not null,
    exit_code int null,
    stdout_content clob null,
    stdout_truncated boolean not null default false,
    stderr_content clob null,
    stderr_truncated boolean not null default false,
    error_message clob null,
    timeout_millis bigint not null,
    queued_at timestamp(3) not null default current_timestamp(3),
    started_at timestamp(3) null,
    finished_at timestamp(3) null,
    gmt_create timestamp(3) not null default current_timestamp(3),
    gmt_modified timestamp(3) not null default current_timestamp(3),
    version bigint not null default 0,
    primary key (id)
);

alter table hub_execution alter column id varchar(36) not null;
alter table hub_execution alter column instance_id varchar(36) null;
drop index if exists idx_hub_execution_instance_id;
drop index if exists idx_hub_execution_gmt_create;
create index if not exists idx_hub_execution_queued_at_id on hub_execution (queued_at, id);
create index if not exists idx_hub_execution_instance_queued_at_id
    on hub_execution (instance_id, queued_at, id);
create index if not exists idx_hub_execution_status on hub_execution (status);

create table if not exists hub_command_blacklist (
    id varchar(36) not null,
    command_key varchar(160) not null,
    reason varchar(512) null,
    gmt_create timestamp(3) not null default current_timestamp(3),
    gmt_modified timestamp(3) not null default current_timestamp(3),
    version bigint not null default 0,
    primary key (id),
    constraint uk_hub_command_blacklist_command_key unique (command_key)
);

alter table hub_command_blacklist alter column id varchar(36) not null;

create table if not exists hub_command_output_rule (
    id varchar(36) not null,
    command_key varchar(160) not null,
    argument_name varchar(64) not null,
    target_type varchar(32) not null,
    file_name varchar(255) null,
    gmt_create timestamp(3) not null default current_timestamp(3),
    gmt_modified timestamp(3) not null default current_timestamp(3),
    version bigint not null default 0,
    primary key (id),
    constraint uk_hub_command_output_rule_command_key unique (command_key)
);

alter table hub_command_output_rule alter column id varchar(36) not null;

create table if not exists hub_plugin_source (
    id varchar(36) not null,
    name varchar(128) not null,
    source varchar(1024) not null,
    desired_plugins_json clob not null,
    enabled boolean not null default true,
    auto_update boolean not null default false,
    last_status varchar(32) not null,
    last_error clob null,
    last_synced_at timestamp(3) null,
    last_result_json clob null,
    gmt_create timestamp(3) not null default current_timestamp(3),
    gmt_modified timestamp(3) not null default current_timestamp(3),
    version bigint not null default 0,
    primary key (id),
    constraint uk_hub_plugin_source_name unique (name)
);

alter table hub_plugin_source alter column id varchar(36) not null;
create index if not exists idx_hub_plugin_source_enabled on hub_plugin_source (enabled);
