-- Stop every Hub process and take a verified backup before running this script.
-- MySQL DDL commits implicitly. The script is idempotent for rehearsal and verification runs.
-- Compatible with MySQL 5.7 and 8.4.
--
-- Adds hub_instance.max_concurrency for multi-concurrency execution (default 1, range 1..4).
-- Existing rows receive DEFAULT 1 to preserve legacy single-concurrency behavior.

select table_name, column_name, column_type, is_nullable, column_default
from information_schema.columns
where table_schema = database()
  and table_name = 'hub_instance'
  and column_name in ('max_pending', 'max_concurrency', 'priority')
order by ordinal_position;

set @sql = if(
    exists(
        select 1 from information_schema.columns
        where table_schema = database()
          and table_name = 'hub_instance'
          and column_name = 'max_concurrency'
    ),
    'select ''hub_instance.max_concurrency already present'' as migrate_mysql_instance_concurrency',
    'alter table hub_instance add column max_concurrency int not null default 1 after max_pending'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

-- Existing rows keep DEFAULT 1; no backfill UPDATE required.

select table_name, column_name, column_type, is_nullable, column_default
from information_schema.columns
where table_schema = database()
  and table_name = 'hub_instance'
  and column_name = 'max_concurrency'
order by ordinal_position;

-- Expected: one row, int, NO, default 1.
select count(*) as max_concurrency_column_count
from information_schema.columns
where table_schema = database()
  and table_name = 'hub_instance'
  and column_name = 'max_concurrency';
