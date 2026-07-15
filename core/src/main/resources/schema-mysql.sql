-- MySQL 5.7 defaults sql_mode='NO_ZERO_DATE,STRICT_TRANS_TABLES,…'; `timestamp not null` without
-- DEFAULT would be rejected at create time. MyBatis INSERT statements set gmt_create/gmt_modified
-- explicitly so the DEFAULT only acts as a strict-mode safety net.

create table if not exists hub_instance (
    id varchar(36) not null,
    code varchar(64) not null,
    display_name varchar(128) not null,
    context_id varchar(128) null,
    state varchar(32) not null,
    websites_json text not null,
    max_pending int not null,
    proxy_mode varchar(16) not null default 'INHERIT',
    proxy_server varchar(512) null,
    last_error_message text null,
    state_changed_at timestamp(3) not null default current_timestamp(3) on update current_timestamp(3),
    gmt_create timestamp(3) not null default current_timestamp(3),
    gmt_modified timestamp(3) not null default current_timestamp(3),
    version bigint not null default 0,
    primary key (id),
    unique key uk_hub_instance_code (code),
    unique key uk_hub_instance_context_id (context_id),
    key idx_hub_instance_state (state)
) engine=InnoDB default charset=utf8mb4 comment='OpenCLI browser instance';

create table if not exists hub_system_settings (
    id int not null,
    proxy_mode varchar(16) not null,
    proxy_server varchar(512) null,
    gmt_create timestamp(3) not null default current_timestamp(3),
    gmt_modified timestamp(3) not null default current_timestamp(3),
    version bigint not null default 0,
    primary key (id)
) engine=InnoDB default charset=utf8mb4 comment='Hub-wide browser settings singleton';

create table if not exists hub_execution (
    id varchar(36) not null,
    instance_id varchar(36) null,
    instance_code varchar(64) null,
    command_key varchar(160) not null,
    site varchar(80) not null,
    site_session varchar(16) not null,
    argv_json text not null,
    reuse_instance tinyint(1) not null default 0,
    status varchar(32) not null,
    exit_code int null,
    stdout_content mediumtext null,
    stdout_truncated tinyint(1) not null default 0,
    stderr_content mediumtext null,
    stderr_truncated tinyint(1) not null default 0,
    error_message text null,
    timeout_millis bigint not null,
    queued_at timestamp(3) not null default current_timestamp(3) on update current_timestamp(3),
    started_at timestamp(3) null,
    finished_at timestamp(3) null,
    gmt_create timestamp(3) not null default current_timestamp(3),
    gmt_modified timestamp(3) not null default current_timestamp(3),
    version bigint not null default 0,
    primary key (id),
    key idx_hub_execution_queued_at_id (queued_at, id),
    key idx_hub_execution_instance_queued_at_id (instance_id, queued_at, id),
    key idx_hub_execution_status (status)
) engine=InnoDB default charset=utf8mb4 comment='OpenCLI execution history';

create table if not exists hub_command_blacklist (
    id varchar(36) not null,
    command_key varchar(160) not null,
    reason varchar(512) null,
    gmt_create timestamp(3) not null default current_timestamp(3),
    gmt_modified timestamp(3) not null default current_timestamp(3),
    version bigint not null default 0,
    primary key (id),
    unique key uk_hub_command_blacklist_command_key (command_key)
) engine=InnoDB default charset=utf8mb4 comment='Globally disabled OpenCLI commands';

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
    unique key uk_hub_command_output_rule_command_key (command_key)
) engine=InnoDB default charset=utf8mb4 comment='Managed OpenCLI resource output rules';
