package fun.fengwk.openclihub.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Verifies that the active Maven profile's AutoMapper dialect is what actually gets compiled
 * into the generated Mapper XML: the packaged (Maven-filtered) {@code auto-mapper.config} and
 * every generated {@code *Mapper.xml} must agree on the dialect, and the dialect-specific
 * identifier quoting must match the profile.
 *
 * <p>MySQL quotes identifiers with backticks ({@code `id`}) while PostgreSQL and SQLite use
 * double quotes ({@code "id"}), so the generated XML is the authoritative dialect marker.
 * Each {@code @AutoMapper} interface produces one generated XML, so exactly the four mapper
 * resources are checked.
 */
class AutoMapperDialectTest {

    private static final String DB_TYPE_KEY = "fun.fengwk.automapper.annotation.AutoMapper.dbType";

    private static final Map<String, String> TABLE_BY_MAPPER = Map.of(
        "HubInstanceMapper.xml", "hub_instance",
        "HubExecutionMapper.xml", "hub_execution",
        "HubCommandBlacklistMapper.xml", "hub_command_blacklist",
        "HubCommandOutputRuleMapper.xml", "hub_command_output_rule");

    @Test
    void shouldGenerateEveryMapperXmlInTheActiveProfileDialect() throws Exception {
        String dbType = dbTypeFromPackagedConfig();
        List<Resource> mapperXmls = List.of(
            new PathMatchingResourcePatternResolver().getResources(
                "classpath*:fun/fengwk/openclihub/core/**/mapper/*Mapper.xml"));
        assertThat(mapperXmls)
            .as("generated AutoMapper XMLs must exist for all @AutoMapper mappers")
            .hasSize(TABLE_BY_MAPPER.size());

        for (Resource xml : mapperXmls) {
            String content = xml.getContentAsString(StandardCharsets.UTF_8);
            String mapperFile = xml.getFilename();
            String table = TABLE_BY_MAPPER.get(mapperFile);
            assertThat(table).as("unexpected generated mapper %s", mapperFile).isNotNull();
            switch (dbType) {
                case "MYSQL" -> assertThat(content)
                    .as("%s must quote identifiers with backticks in the MySQL dialect", mapperFile)
                    .contains("`" + table + "`");
                case "POSTGRESQL", "SQLITE" -> assertThat(content)
                    .as("%s must quote identifiers with double quotes in the %s dialect", mapperFile, dbType)
                    .contains("\"" + table + "\"")
                    .doesNotContain("`" + table + "`");
                default -> throw new IllegalStateException("unexpected dbType " + dbType);
            }
        }
    }

    @Test
    void shouldKeepTheFilteredConfigIdenticalExceptForDbType() throws Exception {
        Properties properties = loadPackagedConfig();
        assertThat(properties.getProperty(DB_TYPE_KEY)).isIn("POSTGRESQL", "MYSQL", "SQLITE");
        // The non-dbType contract lines must survive Maven filtering untouched.
        assertThat(properties.getProperty("fun.fengwk.automapper.annotation.AutoMapper.mapperSuffix"))
            .isEqualTo("Mapper");
        assertThat(properties.getProperty(
            "fun.fengwk.automapper.annotation.AutoMapper.tableNamingStyle"))
            .isEqualTo("LOWER_UNDER_SCORE_CASE");
        assertThat(properties.getProperty(
            "fun.fengwk.automapper.annotation.AutoMapper.fieldNamingStyle"))
            .isEqualTo("LOWER_UNDER_SCORE_CASE");
        assertThat(properties.getProperty(
            "fun.fengwk.automapper.annotation.AutoMapper.tableNamePrefix")).isEqualTo("");
        assertThat(properties.getProperty(
            "fun.fengwk.automapper.annotation.AutoMapper.tableNameSuffix")).isEqualTo("");
    }

    @Test
    void shouldKeepPortableLimitOffsetPaginationInTheExecutionMapper() throws Exception {
        Resource executionXml = new PathMatchingResourcePatternResolver()
            .getResources("classpath*:**/execution/**/HubExecutionMapper.xml")[0];
        String content = executionXml.getContentAsString(StandardCharsets.UTF_8);
        // LIMIT/OFFSET pagination is valid SQL in MySQL, PostgreSQL and SQLite alike; a
        // dialect-specific rewrite would indicate a dialect leak in the generated SQL.
        assertThat(content).contains("limit #{limit} offset #{offset}");
    }

    @Test
    void shouldResolveInstanceOrderByThroughFieldName() throws Exception {
        Resource instanceXml = new PathMatchingResourcePatternResolver()
            .getResources("classpath*:**/instance/**/HubInstanceMapper.xml")[0];
        String content = instanceXml.getContentAsString(StandardCharsets.UTF_8);
        String dbType = dbTypeFromPackagedConfig();
        String quotedId = switch (dbType) {
            case "MYSQL" -> "`id`";
            case "POSTGRESQL", "SQLITE" -> "\"id\"";
            default -> throw new IllegalStateException("unexpected dbType " + dbType);
        };
        // createTime is mapped to the legacy physical column gmt_create via @FieldName.
        assertThat(content)
            .contains("<select id=\"findAllOrderByCreateTimeAndId\"")
            .contains("order by gmt_create, " + quotedId)
            .doesNotContain("order by create_time");
    }

    private static String dbTypeFromPackagedConfig() throws IOException {
        return loadPackagedConfig().getProperty(DB_TYPE_KEY);
    }

    private static Properties loadPackagedConfig() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = AutoMapperDialectTest.class.getResourceAsStream("/auto-mapper.config")) {
            assertThat(in).as("packaged auto-mapper.config must be on the test classpath").isNotNull();
            properties.load(in);
        }
        return properties;
    }
}
