-- Stop every Hub process and take a verified backup before running this script.
-- MySQL DDL commits implicitly. Idempotent for rehearsal and verification runs.
-- Compatible with MySQL 5.7 and 8.4.
--
-- Bug: hub_instance.state_changed_at was defined with ON UPDATE CURRENT_TIMESTAMP,
-- so ANY row UPDATE (state transition, profile edit, version bump) silently
-- rewrote the timestamp and broke the state-change sort key.
--
-- Fix: make state_changed_at immutable (DEFAULT only, no ON UPDATE). The
-- application already writes state_changed_at explicitly on every state
-- transition, so after this script runs the column changes only when the
-- application records a real state change.
--
-- Unlike queued_at (migrate-mysql-execution-queued-at-immutable.sql, restorable
-- from gmt_create), there is no surviving column that preserves the original
-- state-transition times: ON UPDATE destroyed them before this script ran.
-- This script deliberately does NOT fabricate historical timestamps; already
-- drifted state_changed_at values are unrecoverable and remain as-is. Only
-- future state transitions are correct after the ALTER.

select table_name, column_name, column_type, column_default, extra
from information_schema.columns
where table_schema = database()
  and table_name = 'hub_instance'
  and column_name = 'state_changed_at';

-- Removing ON UPDATE is a no-op when it is already absent, so re-running the
-- script (rehearsal, verification, rollback checks) is safe.
alter table hub_instance
    modify column state_changed_at timestamp(3) not null default current_timestamp(3);

select table_name, column_name, column_type, column_default, extra
from information_schema.columns
where table_schema = database()
  and table_name = 'hub_instance'
  and column_name = 'state_changed_at';

-- Expected: extra does NOT contain 'on update CURRENT_TIMESTAMP'.

-- Informational: total rows carried over as-is; drifted historical values are
-- not detected by this script and must not be edited by hand.
select count(*) as hub_instance_rows
from hub_instance;
