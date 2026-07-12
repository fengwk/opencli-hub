create table if not exists hub_instance (
    id bigint not null,
    code varchar(64) not null,
    display_name varchar(128) not null,
    context_id varchar(128) null,
    state varchar(32) not null,
    websites_json clob not null,
    max_pending int not null,
    last_error_message clob null,
    state_changed_at timestamp(3) not null,
    gmt_create timestamp(3) not null,
    gmt_modified timestamp(3) not null,
    version bigint not null default 0,
    primary key (id),
    constraint uk_hub_instance_code unique (code),
    constraint uk_hub_instance_context_id unique (context_id)
);

create index if not exists idx_hub_instance_state on hub_instance (state);

create table if not exists hub_execution (
    id bigint not null,
    instance_id bigint null,
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
    queued_at timestamp(3) not null,
    started_at timestamp(3) null,
    finished_at timestamp(3) null,
    gmt_create timestamp(3) not null,
    gmt_modified timestamp(3) not null,
    version bigint not null default 0,
    primary key (id)
);

create index if not exists idx_hub_execution_instance_id on hub_execution (instance_id);
create index if not exists idx_hub_execution_status on hub_execution (status);
create index if not exists idx_hub_execution_gmt_create on hub_execution (gmt_create);

create table if not exists hub_command_blacklist (
    id bigint not null,
    command_key varchar(160) not null,
    reason varchar(512) null,
    gmt_create timestamp(3) not null,
    gmt_modified timestamp(3) not null,
    version bigint not null default 0,
    primary key (id),
    constraint uk_hub_command_blacklist_command_key unique (command_key)
);

create table if not exists hub_command_output_rule (
    id bigint not null,
    command_key varchar(160) not null,
    argument_name varchar(64) not null,
    target_type varchar(32) not null,
    file_name varchar(255) null,
    gmt_create timestamp(3) not null,
    gmt_modified timestamp(3) not null,
    version bigint not null default 0,
    primary key (id),
    constraint uk_hub_command_output_rule_command_key unique (command_key)
);
