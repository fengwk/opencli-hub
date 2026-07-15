package fun.fengwk.openclihub.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Verifies existing H2 instances gain proxy defaults without losing their identity. */
class H2ProxySettingsMigrationTest {

    @Test
    void shouldAddProxyColumnsAndSettingsTableIdempotently() throws Exception {
        String url = "jdbc:h2:mem:proxy-migration-" + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        try (Connection connection = DriverManager.getConnection(url, "SA", "")) {
            createLegacyInstanceTable(connection);
            createLegacyExecutionTable(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    insert into hub_instance values (
                        '343020517415976960', 'legacy', 'Legacy', '88na2dfs', 'STOPPED',
                        '[\"chatgpt\"]', 5, null, current_timestamp, current_timestamp,
                        current_timestamp, 0
                    )
                    """);
                statement.executeUpdate("""
                    insert into hub_execution values (
                        '343020517415976961', '343020517415976960', 'legacy',
                        'chatgpt/session', 'chatgpt', 'default', '[]', false, 'SUCCEEDED', 0,
                        'historical stdout', false, null, false, null, 1000, current_timestamp,
                        current_timestamp, current_timestamp, current_timestamp, current_timestamp, 0
                    )
                    """);
            }

            ClassPathResource schema = new ClassPathResource("schema-h2.sql");
            ScriptUtils.executeSqlScript(connection, schema);
            ScriptUtils.executeSqlScript(connection, schema);
            ClassPathResource data = new ClassPathResource("data-h2.sql");
            ScriptUtils.executeSqlScript(connection, data);
            ScriptUtils.executeSqlScript(connection, data);

            assertThat(queryString(connection,
                "select proxy_mode from hub_instance where id='343020517415976960'"))
                .isEqualTo("INHERIT");
            assertThat(queryString(connection,
                "select context_id from hub_instance where id='343020517415976960'"))
                .isEqualTo("88na2dfs");
            assertThat(queryLong(connection, "select count(*) from hub_system_settings"))
                .isEqualTo(1L);
            assertThat(queryString(connection,
                "select proxy_mode from hub_system_settings where id=1"))
                .isEqualTo("DIRECT");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    update hub_system_settings
                    set proxy_mode = 'CUSTOM', proxy_server = 'http://saved.example:8080'
                    where id = 1
                    """);
            }
            ScriptUtils.executeSqlScript(connection, data);
            assertThat(queryString(connection,
                "select proxy_server from hub_system_settings where id=1"))
                .isEqualTo("http://saved.example:8080");
            assertThat(queryString(connection,
                "select instance_id from hub_execution where id='343020517415976961'"))
                .isEqualTo("343020517415976960");
            assertThat(queryString(connection,
                "select stdout_content from hub_execution where id='343020517415976961'"))
                .isEqualTo("historical stdout");
            assertThat(columnExists(connection, "hub_instance", "proxy_server")).isTrue();
        }
    }

    private static void createLegacyInstanceTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                create table hub_instance (
                    id varchar(36) not null,
                    code varchar(64) not null,
                    display_name varchar(128) not null,
                    context_id varchar(128) null,
                    state varchar(32) not null,
                    websites_json clob not null,
                    max_pending int not null,
                    last_error_message clob null,
                    state_changed_at timestamp(3) not null,
                    gmt_create timestamp(3) not null,
                    gmt_modified timestamp(3) not null,
                    version bigint not null default 0,
                    primary key (id),
                    constraint uk_hub_instance_code unique (code),
                    constraint uk_hub_instance_context_id unique (context_id)
                )
                """);
        }
    }

    private static void createLegacyExecutionTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                create table hub_execution (
                    id varchar(36) not null,
                    instance_id varchar(36) null,
                    instance_code varchar(64) null,
                    command_key varchar(160) not null,
                    site varchar(80) not null,
                    site_session varchar(16) not null,
                    argv_json clob not null,
                    reuse_instance boolean not null default false,
                    status varchar(32) not null,
                    exit_code int null,
                    stdout_content clob null,
                    stdout_truncated boolean not null default false,
                    stderr_content clob null,
                    stderr_truncated boolean not null default false,
                    error_message clob null,
                    timeout_millis bigint not null,
                    queued_at timestamp(3) not null,
                    started_at timestamp(3) null,
                    finished_at timestamp(3) null,
                    gmt_create timestamp(3) not null,
                    gmt_modified timestamp(3) not null,
                    version bigint not null default 0,
                    primary key (id)
                )
                """);
        }
    }

    private static boolean columnExists(Connection connection, String table, String column)
        throws Exception {
        try (var statement = connection.prepareStatement("""
                select count(*) from information_schema.columns
                where table_name = ? and column_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1) == 1L;
            }
        }
    }

    private static String queryString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static long queryLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

}
