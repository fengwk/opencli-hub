package fun.fengwk.openclihub.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * Verifies the Maven database-variant config contract: {@code application.yml} imports the
 * packaged {@code application-database.yml}, the packaged file is the active profile's source
 * file (driver and SQL init), every variant defines schema-only SQL init (no data SQL), and
 * the UTC/single-connection runtime settings are pinned per variant.
 */
class DatabaseProfileConfigTest {

    private static final List<String> VARIANTS = List.of("postgresql", "mysql", "sqlite");

    private static final Map<String, String> DRIVER_BY_VARIANT = Map.of(
        "postgresql", "org.postgresql.Driver",
        "mysql", "com.mysql.cj.jdbc.Driver",
        "sqlite", "org.sqlite.JDBC");

    @Test
    void shouldImportThePackagedDatabaseConfigFromApplicationYml() {
        Properties applicationYml = yaml(new ClassPathResource("application.yml"));
        assertThat(applicationYml.getProperty("spring.config.import"))
            .as("application.yml must import the variant-independent database config")
            .isEqualTo("classpath:application-database.yml");
        // The base application.yml must no longer hardcode a datasource.
        assertThat(applicationYml.getProperty("spring.datasource.driver-class-name")).isNull();
    }

    @Test
    void shouldPackageTheConfigOfTheActiveVariant() throws Exception {
        String packaged = read(new ClassPathResource("application-database.yml"));
        String variant = variantOf(packaged);
        String source = Files.readString(
            Path.of("src/main/database", variant, "application-database.yml"));
        assertThat(packaged)
            .as("packaged application-database.yml must be the %s source file", variant)
            .isEqualTo(source);
    }

    @Test
    void shouldDefineSchemaOnlySqlInitForEveryVariant() throws Exception {
        for (String variant : VARIANTS) {
            Properties properties = yaml(source(variant));
            assertThat(properties.getProperty("spring.datasource.driver-class-name"))
                .as("%s driver", variant).isEqualTo(DRIVER_BY_VARIANT.get(variant));
            assertThat(properties.getProperty("spring.sql.init.mode"))
                .as("%s SQL init mode", variant).isEqualTo("always");
            assertThat(properties.getProperty("spring.sql.init.schema-locations"))
                .as("%s schema locations", variant)
                .isEqualTo("classpath:schema-database.sql");
            // Schema-only init: no data SQL may be referenced or shipped.
            assertThat(properties.getProperty("spring.sql.init.data-locations"))
                .as("%s data locations", variant).isNull();
        }
    }

    @Test
    void shouldPinUtcAndSqliteRuntimeSettingsPerVariant() throws Exception {
        Properties postgresql = yaml(source("postgresql"));
        assertThat(postgresql.getProperty("spring.datasource.hikari.connection-init-sql"))
            .as("PostgreSQL sessions must run in UTC")
            .isEqualTo("set time zone 'UTC'");

        Properties mysql = yaml(source("mysql"));
        assertThat(mysql.getProperty("spring.datasource.url"))
            .as("MySQL JDBC/session timezone must be UTC")
            .contains("connectionTimeZone=UTC")
            .contains("forceConnectionTimeZoneToSession=true");

        Properties sqlite = yaml(source("sqlite"));
        assertThat(sqlite.getProperty("spring.datasource.url"))
            .as("SQLite must use a single local db file with WAL and a busy timeout")
            .startsWith("jdbc:sqlite:")
            .contains("journal_mode=WAL")
            .contains("busy_timeout=5000");
        assertThat(sqlite.getProperty("spring.datasource.hikari.maximum-pool-size"))
            .as("SQLite must be pinned to a single pooled connection")
            .isEqualTo("1");
    }

    private static String variantOf(String packagedConfig) throws Exception {
        Properties properties = yaml(new org.springframework.core.io.ByteArrayResource(
            packagedConfig.getBytes(StandardCharsets.UTF_8)));
        String driver = properties.getProperty("spring.datasource.driver-class-name");
        return DRIVER_BY_VARIANT.entrySet().stream()
            .filter(entry -> entry.getValue().equals(driver))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(() -> new AssertionError("unknown packaged driver " + driver));
    }

    private static Resource source(String variant) {
        return new FileSystemResource(Path.of("src/main/database", variant, "application-database.yml"));
    }

    private static String read(Resource resource) throws Exception {
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    private static Properties yaml(Resource resource) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(resource);
        Properties properties = yaml.getObject();
        assertThat(properties).as("parsed YAML of %s", resource).isNotNull();
        return properties;
    }
}
