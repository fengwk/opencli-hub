-- Stop every Hub process and take a verified backup before running this script.
-- MySQL DDL commits implicitly. The script is idempotent for rehearsal and verification runs.

select table_name, column_name, column_type, is_nullable, column_default
from information_schema.columns
where table_schema = database()
  and table_name = 'hub_instance'
  and column_name in ('proxy_mode', 'proxy_server')
order by ordinal_position;

set @sql = if(
    exists(
        select 1 from information_schema.columns
        where table_schema = database()
          and table_name = 'hub_instance'
          and column_name = 'proxy_mode'
    ),
    'select 1',
    'alter table hub_instance add column proxy_mode varchar(16) not null default ''INHERIT'' after max_pending'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if(
    exists(
        select 1 from information_schema.columns
        where table_schema = database()
          and table_name = 'hub_instance'
          and column_name = 'proxy_server'
    ),
    'select 1',
    'alter table hub_instance add column proxy_server varchar(512) null after proxy_mode'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

update hub_instance
set proxy_mode = 'INHERIT', proxy_server = null
where proxy_mode is null or proxy_mode = '';

alter table hub_instance
    modify column proxy_mode varchar(16) not null default 'INHERIT',
    modify column proxy_server varchar(512) null;

create table if not exists hub_system_settings (
    id int not null,
    proxy_mode varchar(16) not null,
    proxy_server varchar(512) null,
    gmt_create timestamp(3) not null default current_timestamp(3),
    gmt_modified timestamp(3) not null default current_timestamp(3),
    version bigint not null default 0,
    primary key (id)
) engine=InnoDB default charset=utf8mb4 comment='Hub-wide browser settings singleton';

insert into hub_system_settings (
    id, proxy_mode, proxy_server, gmt_create, gmt_modified, version
)
select 1, 'DIRECT', null, current_timestamp(3), current_timestamp(3), 0
where not exists (select 1 from hub_system_settings where id = 1);

-- Expected: every Instance is INHERIT, DIRECT, or CUSTOM; existing rows are INHERIT.
select proxy_mode, count(*) as instance_count
from hub_instance
group by proxy_mode
order by proxy_mode;

-- Expected: exactly one row with id=1 and mode DIRECT or CUSTOM.
select id, proxy_mode, proxy_server, version
from hub_system_settings
order by id;

-- Expected: two rows with varchar(16) / varchar(512), and no nullable proxy_mode.
select table_name, column_name, column_type, is_nullable, column_default
from information_schema.columns
where table_schema = database()
  and (
    (table_name = 'hub_instance' and column_name in ('proxy_mode', 'proxy_server'))
    or (table_name = 'hub_system_settings'
        and column_name in ('id', 'proxy_mode', 'proxy_server', 'version'))
  )
order by table_name, ordinal_position;
