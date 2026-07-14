package fun.fengwk.openclihub.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Verifies the startup schema preserves legacy BIGINT values while changing their SQL type. */
class H2UuidIdMigrationTest {

    @Test
    void shouldMigrateLegacyBigintIdsIdempotently() throws Exception {
        String url = "jdbc:h2:mem:uuid-migration-" + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        try (Connection connection = DriverManager.getConnection(url, "SA", "")) {
            String currentSchema = new ClassPathResource("schema-h2.sql")
                .getContentAsString(StandardCharsets.UTF_8);
            String legacySchema = currentSchema.replace("varchar(36)", "bigint")
                .replaceAll("(?m)^alter table .*;$\\R?", "");
            ScriptUtils.executeSqlScript(connection,
                new ByteArrayResource(legacySchema.getBytes(StandardCharsets.UTF_8)));
            insertLegacyRows(connection);

            ClassPathResource schema = new ClassPathResource("schema-h2.sql");
            ScriptUtils.executeSqlScript(connection, schema);
            ScriptUtils.executeSqlScript(connection, schema);

            assertColumnType(connection, "hub_instance", "id", "CHARACTER VARYING");
            assertColumnType(connection, "hub_execution", "id", "CHARACTER VARYING");
            assertColumnType(connection, "hub_execution", "instance_id", "CHARACTER VARYING");
            assertColumnType(connection, "hub_command_blacklist", "id", "CHARACTER VARYING");
            assertColumnType(connection, "hub_command_output_rule", "id", "CHARACTER VARYING");
            assertThat(queryString(connection, "select id from hub_instance"))
                .isEqualTo("343020517415976960");
            assertThat(queryString(connection, "select id from hub_execution"))
                .isEqualTo("343020517415976961");
            assertThat(queryString(connection, "select instance_id from hub_execution"))
                .isEqualTo("343020517415976960");
            assertThat(queryString(connection, "select id from hub_command_blacklist"))
                .isEqualTo("343020517415976962");
            assertThat(queryString(connection, "select id from hub_command_output_rule"))
                .isEqualTo("343020517415976963");
        }
    }

    private static void insertLegacyRows(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                insert into hub_instance values (
                    343020517415976960, 'legacy', 'Legacy', null, 'STOPPED', '[]', 5, null,
                    current_timestamp, current_timestamp, current_timestamp, 0)
                """);
            statement.executeUpdate("""
                insert into hub_execution values (
                    343020517415976961, 343020517415976960, 'legacy', 'bilibili/hot',
                    'bilibili', 'EPHEMERAL', '[]', false, 'SUCCEEDED', 0, null, false,
                    null, false, null, 1000, current_timestamp, current_timestamp,
                    current_timestamp, current_timestamp, current_timestamp, 0)
                """);
            statement.executeUpdate("""
                insert into hub_command_blacklist values (
                    343020517415976962, 'bilibili/private', null,
                    current_timestamp, current_timestamp, 0)
                """);
            statement.executeUpdate("""
                insert into hub_command_output_rule values (
                    343020517415976963, 'bilibili/hot', 'output', 'FILE', 'out.json',
                    current_timestamp, current_timestamp, 0)
                """);
        }
    }

    private static void assertColumnType(Connection connection, String table, String column,
                                         String expectedType) throws Exception {
        try (var statement = connection.prepareStatement("""
                select data_type, character_maximum_length from information_schema.columns
                where table_name = ? and column_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualToIgnoringCase(expectedType);
                assertThat(resultSet.getLong(2)).isEqualTo(36L);
            }
        }
    }

    private static String queryString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

}
