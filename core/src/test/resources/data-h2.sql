insert into hub_system_settings (
    id, proxy_mode, proxy_server, gmt_create, gmt_modified, version
)
select 1, 'DIRECT', null, current_timestamp, current_timestamp, 0
where not exists (select 1 from hub_system_settings where id = 1);
