package fun.fengwk.openclihub.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Executes the packaged SQLite schema against a real SQLite database (sqlite-jdbc is only on
 * the test classpath in the sqlite build variant, which is what this test verifies end to end).
 *
 * <p>Coverage intent: the SQLite DDL is the only variant that can run without a database
 * server, so this test proves the schema parses, is idempotent (Spring SQL init runs it on
 * every boot), creates every table with the expected unique keys and query indexes, and
 * round-trips the boolean/LocalDateTime values the mappers persist.
 */
class SqliteSchemaExecutableTest {

    @Test
    void shouldCreateAndRoundTripAllTablesOnRealSqlite() throws Exception {
        Assumptions.assumeTrue(isSqliteDriverPresent(),
            "sqlite-jdbc is only on the classpath in the sqlite build variant");
        Path dbFile = Files.createTempFile("opencli-hub-schema-", ".db");
        String url = "jdbc:sqlite:" + dbFile + "?journal_mode=WAL&busy_timeout=5000";
        try (Connection connection = DriverManager.getConnection(url)) {
            ClassPathResource schema = new ClassPathResource("schema-database.sql");
            // Spring SQL init re-runs the schema on every boot; it must be idempotent.
            ScriptUtils.executeSqlScript(connection, schema);
            ScriptUtils.executeSqlScript(connection, schema);

            assertThat(tables(connection)).containsExactlyInAnyOrder(
                "hub_instance", "hub_system_settings", "hub_execution",
                "hub_command_blacklist", "hub_command_output_rule", "hub_plugin_source");
            assertThat(indexNames(connection, "hub_instance")).contains(
                "uk_hub_instance_code", "uk_hub_instance_context_id", "idx_hub_instance_state");
            assertThat(indexColumns(connection, "uk_hub_instance_code")).containsExactly("code");
            assertThat(indexColumns(connection, "idx_hub_execution_queued_at_id"))
                .containsExactly("queued_at", "id");
            assertThat(indexColumns(connection, "idx_hub_execution_instance_queued_at_id"))
                .containsExactly("instance_id", "queued_at", "id");
            assertThat(indexColumns(connection, "idx_hub_execution_status"))
                .containsExactly("status");
            assertThat(indexNames(connection, "hub_plugin_source")).contains(
                "uk_hub_plugin_source_name", "idx_hub_plugin_source_enabled");

            roundTripExecutionRow(connection);
            assertDuplicateUniqueKeyRejected(connection);
        } finally {
            Files.deleteIfExists(dbFile);
            Files.deleteIfExists(Path.of(dbFile + "-wal"));
            Files.deleteIfExists(Path.of(dbFile + "-shm"));
        }
    }

    private static void roundTripExecutionRow(Connection connection) throws Exception {
        LocalDateTime queuedAt = LocalDateTime.of(2026, 8, 12, 10, 30, 0);
        try (var statement = connection.prepareStatement("""
            insert into hub_execution (
                id, instance_id, instance_code, command_key, site, site_session, argv_json,
                reuse_instance, status, timeout_millis, queued_at, gmt_create, gmt_modified, version
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, "exec-1");
            statement.setString(2, "inst-1");
            statement.setString(3, "bilibili-a");
            statement.setString(4, "bilibili/hot");
            statement.setString(5, "bilibili");
            statement.setString(6, "EPHEMERAL");
            statement.setString(7, "[\"bilibili\", \"hot\"]");
            statement.setBoolean(8, true);
            statement.setString(9, "PENDING");
            statement.setLong(10, 600_000L);
            statement.setObject(11, queuedAt);
            statement.setObject(12, queuedAt);
            statement.setObject(13, queuedAt);
            statement.setLong(14, 0L);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        try (var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                 "select reuse_instance, queued_at from hub_execution where id = 'exec-1'")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getBoolean(1)).isTrue();
            assertThat(resultSet.getObject(2, LocalDateTime.class)).isEqualTo(queuedAt);
        }
    }

    private static void assertDuplicateUniqueKeyRejected(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                insert into hub_instance (
                    id, code, display_name, state, websites_json, max_pending,
                    state_changed_at, gmt_create, gmt_modified, version
                ) values (
                    'inst-1', 'bilibili-a', 'Bilibili A', 'STOPPED', '[]', 5,
                    current_timestamp, current_timestamp, current_timestamp, 0
                )
                """);
            try (ResultSet rs = statement.executeQuery("select max_concurrency from hub_instance where id = 'inst-1'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            assertThatThrownBy(() -> statement.executeUpdate("""
                insert into hub_instance (
                    id, code, display_name, state, websites_json, max_pending,
                    state_changed_at, gmt_create, gmt_modified, version
                ) values (
                    'inst-2', 'bilibili-a', 'Bilibili B', 'STOPPED', '[]', 5,
                    current_timestamp, current_timestamp, current_timestamp, 0
                )
                """)).isInstanceOf(SQLException.class);
        }
    }

    private static List<String> tables(Connection connection) throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                 "select name from sqlite_master where type = 'table' "
                     + "and name not like 'sqlite_%' order by name")) {
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
        }
        return names;
    }

    private static Map<String, List<String>> indexes(Connection connection, String table)
        throws Exception {
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet indexList = statement.executeQuery("PRAGMA index_list('" + table + "')")) {
            List<String> indexNames = new ArrayList<>();
            while (indexList.next()) {
                String indexName = indexList.getString("name");
                if (indexName != null && !indexName.startsWith("sqlite_autoindex")) {
                    indexNames.add(indexName);
                }
            }
            for (String indexName : indexNames) {
                indexes.put(indexName, indexColumns(connection, indexName));
            }
        }
        return indexes;
    }

    private static List<String> indexNames(Connection connection, String table) throws Exception {
        return new ArrayList<>(indexes(connection, table).keySet());
    }

    private static List<String> indexColumns(Connection connection, String indexName)
        throws Exception {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                 "PRAGMA index_info('" + indexName + "')")) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("name"));
            }
        }
        return columns;
    }

    private static boolean isSqliteDriverPresent() {
        try {
            Class.forName("org.sqlite.JDBC");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
