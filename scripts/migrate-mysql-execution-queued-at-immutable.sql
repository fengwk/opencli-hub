-- Stop Hub and take a verified backup before running this script.
-- MySQL DDL commits implicitly. Idempotent for rehearsal runs.
--
-- Bug: hub_execution.queued_at was defined with ON UPDATE CURRENT_TIMESTAMP, so
-- markRunning / terminal status updates rewrote the enqueue timestamp and broke
-- list ordering (no longer newest-first by real activity time).
--
-- Fix: make queued_at immutable (DEFAULT only, no ON UPDATE). Application list
-- queries also order by coalesce(finished_at, started_at, queued_at) DESC.

select table_name, column_name, column_type, column_default, extra
from information_schema.columns
where table_schema = database()
  and table_name = 'hub_execution'
  and column_name = 'queued_at';

alter table hub_execution
    modify column queued_at timestamp(3) not null default current_timestamp(3);

select table_name, column_name, column_type, column_default, extra
from information_schema.columns
where table_schema = database()
  and table_name = 'hub_execution'
  and column_name = 'queued_at';

-- Expected: extra does NOT contain 'on update CURRENT_TIMESTAMP'.
