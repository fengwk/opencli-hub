-- Stop every Hub process and take a verified database backup before running this script.
-- MySQL DDL commits implicitly. The script is idempotent for rehearsal and verification runs.
-- MySQL 5.7 can scan these ordinary ascending InnoDB B-tree indexes in reverse for DESC queries.

select index_name, seq_in_index, column_name, non_unique, index_type
from information_schema.statistics
where table_schema = database()
  and table_name = 'hub_execution'
order by index_name, seq_in_index;

set @queued_index_matches = exists(
    select index_name
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'hub_execution'
      and index_name = 'idx_hub_execution_queued_at_id'
    group by index_name
    having count(*) = 2
       and group_concat(column_name order by seq_in_index separator ',') = 'queued_at,id'
       and min(non_unique) = 1
       and max(non_unique) = 1
       and min(index_type) = 'BTREE'
       and max(index_type) = 'BTREE'
);
set @sql = if(
    @queued_index_matches = 0
      and exists(
          select 1 from information_schema.statistics
          where table_schema = database()
            and table_name = 'hub_execution'
            and index_name = 'idx_hub_execution_queued_at_id'
      ),
    'alter table hub_execution drop index idx_hub_execution_queued_at_id',
    'select 1'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;
set @sql = if(
    @queued_index_matches = 0,
    'alter table hub_execution add index idx_hub_execution_queued_at_id (queued_at, id)',
    'select 1'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @instance_queued_index_matches = exists(
    select index_name
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'hub_execution'
      and index_name = 'idx_hub_execution_instance_queued_at_id'
    group by index_name
    having count(*) = 3
       and group_concat(column_name order by seq_in_index separator ',') = 'instance_id,queued_at,id'
       and min(non_unique) = 1
       and max(non_unique) = 1
       and min(index_type) = 'BTREE'
       and max(index_type) = 'BTREE'
);
set @sql = if(
    @instance_queued_index_matches = 0
      and exists(
          select 1 from information_schema.statistics
          where table_schema = database()
            and table_name = 'hub_execution'
            and index_name = 'idx_hub_execution_instance_queued_at_id'
      ),
    'alter table hub_execution drop index idx_hub_execution_instance_queued_at_id',
    'select 1'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;
set @sql = if(
    @instance_queued_index_matches = 0,
    'alter table hub_execution add index idx_hub_execution_instance_queued_at_id (instance_id, queued_at, id)',
    'select 1'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if(
    exists(
        select 1 from information_schema.statistics
        where table_schema = database()
          and table_name = 'hub_execution'
          and index_name = 'idx_hub_execution_instance_id'
    ),
    'alter table hub_execution drop index idx_hub_execution_instance_id',
    'select 1'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

set @sql = if(
    exists(
        select 1 from information_schema.statistics
        where table_schema = database()
          and table_name = 'hub_execution'
          and index_name = 'idx_hub_execution_gmt_create'
    ),
    'alter table hub_execution drop index idx_hub_execution_gmt_create',
    'select 1'
);
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

-- Expected: the two named indexes have exactly the listed columns in this order.
select index_name,
       group_concat(column_name order by seq_in_index separator ',') as indexed_columns,
       min(non_unique) as non_unique,
       min(index_type) as index_type
from information_schema.statistics
where table_schema = database()
  and table_name = 'hub_execution'
  and index_name in (
    'idx_hub_execution_queued_at_id',
    'idx_hub_execution_instance_queued_at_id'
  )
group by index_name
order by index_name;

-- Expected: matching_query_index_count=2 and deprecated_index_count=0 before Hub restarts.
select sum(indexed_columns in ('queued_at,id', 'instance_id,queued_at,id'))
           as matching_query_index_count,
       sum(index_name in ('idx_hub_execution_instance_id', 'idx_hub_execution_gmt_create'))
           as deprecated_index_count
from (
    select index_name,
           group_concat(column_name order by seq_in_index separator ',') as indexed_columns
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'hub_execution'
    group by index_name
) execution_indexes;
