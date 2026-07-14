-- Run against the opencli_hub database only after stopping every Hub process and taking a backup.
-- MySQL DDL commits implicitly. Re-running these MODIFY statements is safe and leaves VARCHAR(36)
-- columns unchanged, so the script can be evaluated again during rollback/rehearsal checks.

select table_name, column_name, column_type, is_nullable
from information_schema.columns
where table_schema = database()
  and (table_name, column_name) in (
    ('hub_instance', 'id'),
    ('hub_execution', 'id'),
    ('hub_execution', 'instance_id'),
    ('hub_command_blacklist', 'id'),
    ('hub_command_output_rule', 'id')
  )
order by table_name, ordinal_position;

alter table hub_instance
    modify column id varchar(36) not null;

alter table hub_execution
    modify column id varchar(36) not null,
    modify column instance_id varchar(36) null;

alter table hub_command_blacklist
    modify column id varchar(36) not null;

alter table hub_command_output_rule
    modify column id varchar(36) not null;

-- Expected after migration: all five rows report varchar(36).
select table_name, column_name, column_type, is_nullable
from information_schema.columns
where table_schema = database()
  and (table_name, column_name) in (
    ('hub_instance', 'id'),
    ('hub_execution', 'id'),
    ('hub_execution', 'instance_id'),
    ('hub_command_blacklist', 'id'),
    ('hub_command_output_rule', 'id')
  )
order by table_name, ordinal_position;

-- This count must be zero before Hub is restarted.
select count(*) as execution_instance_id_too_long
from hub_execution
where instance_id is not null and char_length(instance_id) > 36;

-- Informational only: deleted Instances intentionally leave historical Executions behind,
-- so rows reported here can be valid and must not be rewritten by this migration.
select count(*) as historical_execution_without_instance
from hub_execution e
left join hub_instance i on i.id = e.instance_id
where e.instance_id is not null and i.id is null;
