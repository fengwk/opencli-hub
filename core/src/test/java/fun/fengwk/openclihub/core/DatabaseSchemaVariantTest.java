package fun.fengwk.openclihub.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Verifies the fresh-schema contract across the three database variants: the same tables,
 * columns, primary keys, unique keys and query indexes in every dialect, the packaged
 * {@code schema-database.sql} matches the active Maven profile's source file, and the MySQL
 * variant forbids {@code ON UPDATE} (the application owns every state_changed_at write).
 */
class DatabaseSchemaVariantTest {

    private static final List<String> VARIANTS = List.of("postgresql", "mysql", "sqlite");

    private static final Pattern TABLE_PATTERN =
        Pattern.compile("^create table if not exists (\\w+)");
    private static final Pattern COLUMN_PATTERN = Pattern.compile(
        "^([a-z_]+)\\s+(varchar\\(\\d+\\)|mediumtext|text|int|bigint|tinyint\\(\\d+\\)|boolean|timestamp\\(\\d*\\)?)");
    private static final Pattern PK_PATTERN = Pattern.compile("^primary key \\(([^)]+)\\)");
    private static final Pattern MYSQL_UNIQUE_PATTERN =
        Pattern.compile("^unique key (\\w+) \\(([^)]+)\\)");
    private static final Pattern PG_UNIQUE_PATTERN =
        Pattern.compile("^constraint (\\w+) unique \\(([^)]+)\\)");
    private static final Pattern MYSQL_INDEX_PATTERN = Pattern.compile("^key (\\w+) \\(([^)]+)\\)");
    private static final Pattern SQLITE_UNIQUE_INDEX_PATTERN = Pattern.compile(
        "^create unique index if not exists (\\w+)\\s+on\\s+(\\w+)\\s+\\(([^)]+)\\)", Pattern.DOTALL);
    private static final Pattern SQLITE_INDEX_PATTERN = Pattern.compile(
        "^create index if not exists (\\w+)\\s+on\\s+(\\w+)\\s+\\(([^)]+)\\)", Pattern.DOTALL);

    @Test
    void shouldExposeIdenticalStructureAcrossAllThreeSchemas() throws Exception {
        Map<String, Schema> schemas = new LinkedHashMap<>();
        for (String variant : VARIANTS) {
            schemas.put(variant, parse(source(variant)));
        }

        // Pin the expected inventory so accidental table/constraint drift is caught.
        assertThat(schemas.get("postgresql").tables).containsExactlyInAnyOrder(
            "hub_instance", "hub_system_settings", "hub_execution",
            "hub_command_blacklist", "hub_command_output_rule", "hub_plugin_source");

        Schema baseline = schemas.get("postgresql");
        for (String variant : VARIANTS) {
            Schema candidate = schemas.get(variant);
            assertThat(candidate.tables).as("%s tables", variant)
                .containsExactlyInAnyOrderElementsOf(baseline.tables);
            for (String table : baseline.tables) {
                assertThat(candidate.columns.get(table)).as("%s columns of %s", variant, table)
                    .containsExactlyInAnyOrderElementsOf(baseline.columns.get(table));
                assertThat(candidate.primaryKeys.get(table)).as("%s PK of %s", variant, table)
                    .isEqualTo(baseline.primaryKeys.get(table));
                assertThat(candidate.uniqueKeys.get(table)).as("%s UKs of %s", variant, table)
                    .isEqualTo(baseline.uniqueKeys.get(table));
                assertThat(candidate.indexes.get(table)).as("%s indexes of %s", variant, table)
                    .isEqualTo(baseline.indexes.get(table));
            }
        }
    }

    @Test
    void shouldPackageTheSchemaOfTheActiveVariant() throws Exception {
        String variant = DatabaseVariant.variant();
        String packaged = new ClassPathResource("schema-database.sql")
            .getContentAsString(StandardCharsets.UTF_8);
        assertThat(packaged).as("packaged schema-database.sql must come from the %s source", variant)
            .isEqualTo(source(variant));
    }

    @Test
    void shouldForbidOnUpdateInTheMysqlSchema() throws Exception {
        String mysql = stripComments(source("mysql"));
        // state_changed_at and queued_at must keep their application-written semantics; no
        // column in the fresh MySQL schema may silently rewrite values on UPDATE.
        assertThat(mysql.toLowerCase(Locale.ROOT)).doesNotContain("on update");
    }

    private static Schema parse(String sql) {
        // Strip comment lines (including indented ones) first: they may contain semicolons
        // (e.g. sql_mode notes) that must not split DDL statements.
        String noComments = stripComments(sql);
        List<String> statements = Arrays.stream(noComments.split(";"))
            .map(String::trim)
            .filter(statement -> !statement.isEmpty())
            .toList();

        Schema schema = new Schema();
        for (String statement : statements) {
            Matcher tableMatcher = TABLE_PATTERN.matcher(statement);
            if (tableMatcher.find()) {
                parseTable(schema, tableMatcher.group(1), tableBody(statement));
                continue;
            }
            Matcher uniqueIndex = SQLITE_UNIQUE_INDEX_PATTERN.matcher(statement);
            if (uniqueIndex.find()) {
                schema.uniqueKeys.computeIfAbsent(uniqueIndex.group(2), key -> new LinkedHashMap<>())
                    .put(uniqueIndex.group(1), columns(uniqueIndex.group(3)));
                continue;
            }
            Matcher indexMatcher = SQLITE_INDEX_PATTERN.matcher(statement);
            if (indexMatcher.find()) {
                schema.indexes.computeIfAbsent(indexMatcher.group(2), key -> new LinkedHashMap<>())
                    .put(indexMatcher.group(1), columns(indexMatcher.group(3)));
            }
        }
        return schema;
    }

    private static void parseTable(Schema schema, String table, String body) {
        schema.tables.add(table);
        Set<String> columns = new LinkedHashSet<>();
        List<String> primaryKey = new ArrayList<>();
        Map<String, List<String>> uniqueKeys = new LinkedHashMap<>();
        Map<String, List<String>> indexes = new LinkedHashMap<>();

        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher pk = PK_PATTERN.matcher(trimmed);
            if (pk.find()) {
                primaryKey.addAll(columns(pk.group(1)));
                continue;
            }
            Matcher mysqlUnique = MYSQL_UNIQUE_PATTERN.matcher(trimmed);
            if (mysqlUnique.find()) {
                uniqueKeys.put(mysqlUnique.group(1), columns(mysqlUnique.group(2)));
                continue;
            }
            Matcher pgUnique = PG_UNIQUE_PATTERN.matcher(trimmed);
            if (pgUnique.find()) {
                uniqueKeys.put(pgUnique.group(1), columns(pgUnique.group(2)));
                continue;
            }
            Matcher mysqlIndex = MYSQL_INDEX_PATTERN.matcher(trimmed);
            if (mysqlIndex.find()) {
                indexes.put(mysqlIndex.group(1), columns(mysqlIndex.group(2)));
                continue;
            }
            Matcher column = COLUMN_PATTERN.matcher(trimmed);
            if (column.find()) {
                columns.add(column.group(1));
                if (trimmed.contains("primary key")) {
                    // SQLite declares the PK inline on the id column.
                    primaryKey.add(column.group(1));
                }
            }
        }

        schema.columns.put(table, columns);
        schema.primaryKeys.put(table, primaryKey);
        schema.uniqueKeys.put(table, uniqueKeys);
        schema.indexes.put(table, indexes);
    }

    private static String tableBody(String statement) {
        int open = statement.indexOf('(');
        int close = statement.lastIndexOf(')');
        return statement.substring(open + 1, close);
    }

    private static List<String> columns(String columnList) {
        return Arrays.stream(columnList.split("[,\\s]+"))
            .map(token -> token.toLowerCase(Locale.ROOT))
            .filter(token -> !token.isEmpty())
            .toList();
    }

    private static String source(String variant) throws IOException {
        return Files.readString(Path.of("src/main/database", variant, "schema-database.sql"));
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?m)^\\s*--.*$", "");
    }

    /** Parsed structural contract: table -> columns, primary key, unique keys, indexes. */
    private static final class Schema {
        private final Set<String> tables = new LinkedHashSet<>();
        private final Map<String, Set<String>> columns = new LinkedHashMap<>();
        private final Map<String, List<String>> primaryKeys = new LinkedHashMap<>();
        private final Map<String, Map<String, List<String>>> uniqueKeys = new LinkedHashMap<>();
        private final Map<String, Map<String, List<String>>> indexes = new LinkedHashMap<>();
    }
}
