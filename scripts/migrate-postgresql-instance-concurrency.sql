-- Stop every Hub process and take a verified backup before running this script.
-- PostgreSQL executes DDL inside a transaction block safely.
-- Compatible with PostgreSQL 16 (and PostgreSQL 9.6+).
--
-- Adds hub_instance.max_concurrency for multi-concurrency execution (default 1, range 1..4).
-- Existing rows receive DEFAULT 1 to preserve legacy single-concurrency behavior.

\set ON_ERROR_STOP on

select table_name, column_name, data_type, is_nullable, column_default
from information_schema.columns
where table_schema = current_schema()
  and table_name = 'hub_instance'
  and column_name in ('max_pending', 'max_concurrency', 'priority')
order by ordinal_position;

begin;

alter table hub_instance
    add column if not exists max_concurrency int not null default 1;

commit;

select table_name, column_name, data_type, is_nullable, column_default
from information_schema.columns
where table_schema = current_schema()
  and table_name = 'hub_instance'
  and column_name in ('max_pending', 'max_concurrency', 'priority')
order by ordinal_position;

-- Expected: exactly one row for max_concurrency with integer, NO, and default '1'.
select count(*) as max_concurrency_column_count
from information_schema.columns
where table_schema = current_schema()
  and table_name = 'hub_instance'
  and column_name = 'max_concurrency';
