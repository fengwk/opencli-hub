package fun.fengwk.openclihub.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Validates the presence, formatting, and key DDL contents of all three database migration scripts
 * for instance concurrency (PostgreSQL, MySQL, SQLite).
 */
class MigrationScriptsValidationTest {

    private static Path resolveScript(String filename) {
        Path direct = Path.of("scripts", filename);
        if (Files.exists(direct)) {
            return direct.toAbsolutePath();
        }
        Path parent = Path.of("..", "scripts", filename);
        if (Files.exists(parent)) {
            return parent.toAbsolutePath();
        }
        throw new IllegalStateException("Cannot find script: " + filename);
    }

    @Test
    void shouldValidatePostgresqlMigrationScript() throws Exception {
        Path scriptPath = resolveScript("migrate-postgresql-instance-concurrency.sql");
        assertThat(Files.isRegularFile(scriptPath)).isTrue();

        String content = Files.readString(scriptPath, StandardCharsets.UTF_8);
        assertThat(content)
            .contains("alter table hub_instance")
            .contains("add column if not exists max_concurrency int not null default 1")
            .contains("\\set ON_ERROR_STOP on")
            .contains("begin;")
            .contains("commit;")
            .contains("information_schema.columns")
            .contains("max_concurrency_column_count");
    }

    @Test
    void shouldValidateMysqlMigrationScript() throws Exception {
        Path scriptPath = resolveScript("migrate-mysql-instance-concurrency.sql");
        assertThat(Files.isRegularFile(scriptPath)).isTrue();

        String content = Files.readString(scriptPath, StandardCharsets.UTF_8);
        assertThat(content)
            .contains("alter table hub_instance add column max_concurrency int not null default 1 after max_pending")
            .contains("information_schema.columns")
            .contains("max_concurrency_column_count")
            .contains("prepare stmt from @sql")
            .contains("execute stmt");
    }

    @Test
    void shouldValidateSqliteMigrationScript() throws Exception {
        Path scriptPath = resolveScript("migrate-sqlite-instance-concurrency.sh");
        assertThat(Files.isRegularFile(scriptPath)).isTrue();
        assertThat(Files.isExecutable(scriptPath)).isTrue();

        String content = Files.readString(scriptPath, StandardCharsets.UTF_8);
        assertThat(content)
            .startsWith("#!/usr/bin/env bash")
            .contains("set -Eeuo pipefail")
            .contains("sqlite3 -batch -bail")
            .contains("ALTER TABLE hub_instance ADD COLUMN max_concurrency int NOT NULL DEFAULT 1;")
            .contains("PRAGMA table_info(hub_instance)")
            .contains("OPENCLI_HUB_SQLITE_PATH");
    }
}
