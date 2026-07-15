insert ignore into hub_system_settings (
    id, proxy_mode, proxy_server, gmt_create, gmt_modified, version
) values (1, 'DIRECT', null, current_timestamp(3), current_timestamp(3), 0);

-- Apply verified output rules only for the OpenCLI version pinned by the Docker image.
