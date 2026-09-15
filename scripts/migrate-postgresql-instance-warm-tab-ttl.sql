-- Stop every Hub process and take a verified backup before running this script.
-- PostgreSQL executes DDL inside a transaction block safely.
-- Compatible with PostgreSQL 16 (and PostgreSQL 9.6+).
--
-- Adds hub_instance.warm_tab_ttl_seconds for per-instance warm-tab TTL (default 1800).
-- Existing rows adopt the new DEFAULT 1800 reclamation policy.

\set ON_ERROR_STOP on

select table_name, column_name, data_type, is_nullable, column_default
from information_schema.columns
where table_schema = current_schema()
  and table_name = 'hub_instance'
  and column_name in ('priority', 'warm_tab_ttl_seconds', 'proxy_mode')
order by ordinal_position;

begin;

alter table hub_instance
    add column if not exists warm_tab_ttl_seconds int not null default 1800;

commit;

select table_name, column_name, data_type, is_nullable, column_default
from information_schema.columns
where table_schema = current_schema()
  and table_name = 'hub_instance'
  and column_name in ('priority', 'warm_tab_ttl_seconds', 'proxy_mode')
order by ordinal_position;

-- Expected: exactly one row for warm_tab_ttl_seconds with integer, NO, and default '1800'.
select count(*) as warm_tab_ttl_seconds_column_count
from information_schema.columns
where table_schema = current_schema()
  and table_name = 'hub_instance'
  and column_name = 'warm_tab_ttl_seconds';
