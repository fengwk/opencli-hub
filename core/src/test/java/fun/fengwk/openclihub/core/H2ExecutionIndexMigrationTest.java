package fun.fengwk.openclihub.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Verifies H2 startup DDL installs and upgrades the indexes used by execution pagination. */
class H2ExecutionIndexMigrationTest {

    @Test
    void shouldInitializeExecutionQueryIndexesIdempotently() throws Exception {
        try (Connection connection = newConnection("execution-index-init-")) {
            executeCurrentSchemaTwice(connection);

            assertCurrentExecutionIndexes(connection);
        }
    }

    @Test
    void shouldReplaceLegacyExecutionIndexesIdempotently() throws Exception {
        try (Connection connection = newConnection("execution-index-migration-")) {
            createLegacyExecutionTable(connection);

            executeCurrentSchemaTwice(connection);

            Map<String, List<String>> indexes = executionIndexes(connection);
            assertThat(indexes).doesNotContainKeys(
                "idx_hub_execution_instance_id", "idx_hub_execution_gmt_create");
            assertCurrentExecutionIndexes(connection);
        }
    }

    private static Connection newConnection(String prefix) throws Exception {
        String url = "jdbc:h2:mem:" + prefix + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        return DriverManager.getConnection(url, "SA", "");
    }

    private static void executeCurrentSchemaTwice(Connection connection) throws Exception {
        ClassPathResource schema = new ClassPathResource("schema-h2.sql");
        ScriptUtils.executeSqlScript(connection, schema);
        ScriptUtils.executeSqlScript(connection, schema);
    }

    private static void assertCurrentExecutionIndexes(Connection connection) throws Exception {
        assertThat(executionIndexes(connection))
            .containsEntry("idx_hub_execution_queued_at_id", List.of("queued_at", "id"))
            .containsEntry("idx_hub_execution_instance_queued_at_id",
                List.of("instance_id", "queued_at", "id"))
            .containsEntry("idx_hub_execution_status", List.of("status"));
    }

    private static Map<String, List<String>> executionIndexes(Connection connection)
        throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        Map<String, List<IndexColumn>> columnsByIndex = new LinkedHashMap<>();
        try (ResultSet resultSet = metadata.getIndexInfo(null, null, "hub_execution", false, false)) {
            while (resultSet.next()) {
                String indexName = resultSet.getString("INDEX_NAME");
                String columnName = resultSet.getString("COLUMN_NAME");
                if (indexName == null || columnName == null) {
                    continue;
                }
                columnsByIndex.computeIfAbsent(indexName.toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                    .add(new IndexColumn(
                        resultSet.getInt("ORDINAL_POSITION"),
                        columnName.toLowerCase(Locale.ROOT)));
            }
        }
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        columnsByIndex.forEach((indexName, columns) -> indexes.put(indexName, columns.stream()
            .sorted(Comparator.comparingInt(IndexColumn::position))
            .map(IndexColumn::name)
            .toList()));
        return indexes;
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
            statement.execute("create index idx_hub_execution_instance_id "
                + "on hub_execution (instance_id)");
            statement.execute("create index idx_hub_execution_status on hub_execution (status)");
            statement.execute("create index idx_hub_execution_gmt_create "
                + "on hub_execution (gmt_create)");
        }
    }

    private record IndexColumn(int position, String name) { }

}
