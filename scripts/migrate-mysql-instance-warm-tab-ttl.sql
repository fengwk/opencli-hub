-- Stop every Hub process and take a verified backup before running this script.
-- MySQL DDL commits implicitly. The script is idempotent for rehearsal and verification runs.
-- Compatible with MySQL 5.7 and 8.4.
--
-- Adds hub_instance.warm_tab_ttl_seconds for per-instance warm-tab TTL (default 1800).
-- Existing rows adopt the new DEFAULT 1800 reclamation policy.

select table_name, column_name, column_type, is_nullable, column_default
from information_schema.columns
where table_schema = database()
  and table_name = 'hub_instance'
  and column_name in ('priority', 'warm_tab_ttl_seconds', 'proxy_mode')
order by ordinal_position;

set @sql = if(
    exists(
        select 1 from information_schema.columns
        where table_schema = database()
          and table_name = 'hub_instance'
          and column_name = 'warm_tab_ttl_seconds'
    ),
    'select ''hub_instance.warm_tab_ttl_seconds already present'' as migrate_mysql_instance_warm_tab_ttl',
    'alter table hub_instance add column warm_tab_ttl_seconds int not null default 1800 after priority'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

-- Existing rows keep DEFAULT 1800; no backfill UPDATE required.

select table_name, column_name, column_type, is_nullable, column_default
from information_schema.columns
where table_schema = database()
  and table_name = 'hub_instance'
  and column_name = 'warm_tab_ttl_seconds'
order by ordinal_position;

-- Expected: one row, int, NO, default 1800.
select count(*) as warm_tab_ttl_seconds_column_count
from information_schema.columns
where table_schema = database()
  and table_name = 'hub_instance'
  and column_name = 'warm_tab_ttl_seconds';
