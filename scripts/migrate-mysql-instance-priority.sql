-- Stop every Hub process and take a verified backup before running this script.
-- MySQL DDL commits implicitly. The script is idempotent for rehearsal and verification runs.
--
-- Adds hub_instance.priority for automatic routing when load is equal (default 0, higher wins).
-- Required before deploying Hub images that read/write the priority column (eb72925+).

select table_name, column_name, column_type, is_nullable, column_default
from information_schema.columns
where table_schema = database()
  and table_name = 'hub_instance'
  and column_name in ('max_pending', 'priority', 'proxy_mode')
order by ordinal_position;

set @sql = if(
    exists(
        select 1 from information_schema.columns
        where table_schema = database()
          and table_name = 'hub_instance'
          and column_name = 'priority'
    ),
    'select ''hub_instance.priority already present'' as migrate_mysql_instance_priority',
    'alter table hub_instance add column priority int not null default 0 after max_pending'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

-- Existing rows keep DEFAULT 0; no backfill UPDATE required.

select table_name, column_name, column_type, is_nullable, column_default
from information_schema.columns
where table_schema = database()
  and table_name = 'hub_instance'
  and column_name = 'priority'
order by ordinal_position;

-- Expected: one row, int, NO, default 0.
select count(*) as priority_column_count
from information_schema.columns
where table_schema = database()
  and table_name = 'hub_instance'
  and column_name = 'priority';
