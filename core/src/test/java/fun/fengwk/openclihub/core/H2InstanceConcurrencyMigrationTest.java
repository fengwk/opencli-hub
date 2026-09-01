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

/**
 * Verifies that legacy database schemas without {@code max_concurrency} gain the column
 * with {@code DEFAULT 1} idempotently, preserving existing legacy rows with single-concurrency behavior.
 */
class H2InstanceConcurrencyMigrationTest {

    @Test
    void shouldAddMaxConcurrencyColumnIdempotentlyToLegacySchema() throws Exception {
        String url = "jdbc:h2:mem:concurrency-migration-" + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        try (Connection connection = DriverManager.getConnection(url, "SA", "")) {
            createLegacyInstanceTable(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    insert into hub_instance (
                        id, code, display_name, context_id, state, websites_json, max_pending,
                        last_error_message, state_changed_at, gmt_create, gmt_modified, version
                    ) values (
                        'legacy-1', 'bilibili-legacy', 'Legacy Instance', 'ctx-legacy', 'STOPPED',
                        '[\"bilibili\"]', 10, null, current_timestamp, current_timestamp,
                        current_timestamp, 0
                    )
                    """);
            }

            // Run current schema and data scripts twice to prove idempotency
            ClassPathResource schema = new ClassPathResource("schema-h2.sql");
            ScriptUtils.executeSqlScript(connection, schema);
            ScriptUtils.executeSqlScript(connection, schema);

            // Existing legacy rows must receive default max_concurrency = 1
            assertThat(queryInt(connection,
                "select max_concurrency from hub_instance where id='legacy-1'"))
                .isEqualTo(1);
            assertThat(queryInt(connection,
                "select max_pending from hub_instance where id='legacy-1'"))
                .isEqualTo(10);

            // New inserts with explicit maxConcurrency in range 1..4 must be accepted
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    insert into hub_instance (
                        id, code, display_name, context_id, state, websites_json, max_pending,
                        max_concurrency, priority, proxy_mode, proxy_server,
                        last_error_message, state_changed_at, gmt_create, gmt_modified, version
                    ) values (
                        'modern-1', 'bilibili-modern', 'Modern Instance', 'ctx-modern', 'STOPPED',
                        '[\"bilibili\"]', 20, 3, 5, 'INHERIT', null,
                        null, current_timestamp, current_timestamp, current_timestamp, 0
                    )
                    """);
            }

            assertThat(queryInt(connection,
                "select max_concurrency from hub_instance where id='modern-1'"))
                .isEqualTo(3);
            assertThat(queryInt(connection,
                "select priority from hub_instance where id='modern-1'"))
                .isEqualTo(5);
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

    private static int queryInt(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }
}
