package fun.fengwk.openclihub.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that the SQLite migration script ({@code scripts/migrate-sqlite-instance-concurrency.sh})
 * successfully upgrades a legacy SQLite database by adding the {@code max_concurrency} column
 * with {@code NOT NULL DEFAULT 1}, preserves existing rows, works with paths containing spaces,
 * and is safe to execute repeatedly (idempotent).
 */
class SqliteInstanceConcurrencyMigrationTest {

    @TempDir
    Path tempDir;

    private static Path findScriptPath() {
        Path script = Path.of("scripts/migrate-sqlite-instance-concurrency.sh");
        if (Files.exists(script)) {
            return script.toAbsolutePath();
        }
        Path parentScript = Path.of("../scripts/migrate-sqlite-instance-concurrency.sh");
        if (Files.exists(parentScript)) {
            return parentScript.toAbsolutePath();
        }
        throw new IllegalStateException("Cannot find scripts/migrate-sqlite-instance-concurrency.sh");
    }

    private static boolean isSqliteCliAvailable() {
        try {
            Process process = new ProcessBuilder("sqlite3", "--version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isSqliteDriverPresent() {
        try {
            Class.forName("org.sqlite.JDBC");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Test
    void shouldMigrateLegacySqliteDatabaseViaShellScript() throws Exception {
        Assumptions.assumeTrue(isSqliteCliAvailable(), "sqlite3 CLI must be available to run shell migration");

        Path scriptPath = findScriptPath();
        assertThat(Files.isExecutable(scriptPath)).as("Script must be executable").isTrue();

        // Create a database directory path containing spaces
        Path dirWithSpaces = tempDir.resolve("opencli hub space dir");
        Files.createDirectories(dirWithSpaces);
        Path dbPath = dirWithSpaces.resolve("test-legacy.db");

        // Initialize legacy SQLite schema (without max_concurrency) and insert legacy rows
        initializeLegacySqliteDatabase(dbPath);

        // Run the migration script
        ProcessResult result1 = runScript(scriptPath, dbPath.toString());
        assertThat(result1.exitCode).as("First migration execution must exit with 0").isEqualTo(0);
        assertThat(result1.stdout).contains("Successfully added max_concurrency column");

        // Verify column and values via sqlite3 CLI
        String tableInfo = executeSqliteQuery(dbPath, "PRAGMA table_info(hub_instance);");
        assertThat(tableInfo).contains("max_concurrency");

        String legacyConcurrency = executeSqliteQuery(dbPath,
            "SELECT max_concurrency FROM hub_instance WHERE id = 'legacy-inst-1';");
        assertThat(legacyConcurrency.trim()).isEqualTo("1");

        String legacyMaxPending = executeSqliteQuery(dbPath,
            "SELECT max_pending FROM hub_instance WHERE id = 'legacy-inst-1';");
        assertThat(legacyMaxPending.trim()).isEqualTo("5");

        // Re-run migration to verify idempotency
        ProcessResult result2 = runScript(scriptPath, dbPath.toString());
        assertThat(result2.exitCode).as("Repeated migration execution must exit with 0").isEqualTo(0);
        assertThat(result2.stdout).contains("already present");

        // Verify JDBC operations if driver is on classpath
        if (isSqliteDriverPresent()) {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT max_concurrency, max_pending, priority FROM hub_instance WHERE id = 'legacy-inst-1'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                    assertThat(rs.getInt(2)).isEqualTo(5);
                    assertThat(rs.getInt(3)).isEqualTo(0);
                }

                // Insert modern row with max_concurrency = 3
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("""
                        INSERT INTO hub_instance (
                            id, code, display_name, context_id, state, websites_json, max_pending,
                            max_concurrency, priority, proxy_mode, proxy_server,
                            last_error_message, state_changed_at, gmt_create, gmt_modified, version
                        ) VALUES (
                            'modern-inst-1', 'bilibili-modern', 'Modern', 'ctx-m', 'STOPPED',
                            '["bilibili"]', 10, 3, 2, 'INHERIT', null,
                            null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
                        )
                        """);
                }

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT max_concurrency FROM hub_instance WHERE id = 'modern-inst-1'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(3);
                }
            }
        }
    }

    @Test
    void shouldFailWhenDatabaseFileDoesNotExist() throws Exception {
        Path scriptPath = findScriptPath();
        Path nonExistent = tempDir.resolve("missing.db");

        ProcessResult result = runScript(scriptPath, nonExistent.toString());
        assertThat(result.exitCode).isNotEqualTo(0);
        assertThat(result.stderr).contains("Database file does not exist");
    }

    @Test
    void shouldFailWhenDatabasePathIsOmitted() throws Exception {
        Path scriptPath = findScriptPath();

        ProcessResult result = runScript(scriptPath);
        assertThat(result.exitCode).isNotEqualTo(0);
        assertThat(result.stderr).contains("SQLite database path not provided");
    }

    private static void initializeLegacySqliteDatabase(Path dbPath) throws Exception {
        String legacyDdl = """
            CREATE TABLE hub_instance (
                id varchar(36) not null primary key,
                code varchar(64) not null,
                display_name varchar(128) not null,
                context_id varchar(128) null,
                state varchar(32) not null,
                websites_json text not null,
                max_pending int not null,
                priority int not null default 0,
                proxy_mode varchar(16) not null default 'INHERIT',
                proxy_server varchar(512) null,
                last_error_message text null,
                state_changed_at timestamp(3) not null default current_timestamp,
                gmt_create timestamp(3) not null default current_timestamp,
                gmt_modified timestamp(3) not null default current_timestamp,
                version bigint not null default 0
            );
            INSERT INTO hub_instance (
                id, code, display_name, context_id, state, websites_json, max_pending,
                priority, proxy_mode, proxy_server, last_error_message,
                state_changed_at, gmt_create, gmt_modified, version
            ) VALUES (
                'legacy-inst-1', 'bilibili-legacy', 'Legacy Instance', 'ctx-1', 'STOPPED',
                '["bilibili"]', 5, 0, 'INHERIT', null, null,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
            );
            """;
        Process process = new ProcessBuilder("sqlite3", dbPath.toString()).start();
        process.getOutputStream().write(legacyDdl.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        int code = process.waitFor();
        if (code != 0) {
            throw new RuntimeException("Failed to init legacy sqlite db: " + new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String executeSqliteQuery(Path dbPath, String sql) throws Exception {
        Process process = new ProcessBuilder("sqlite3", dbPath.toString(), sql).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0) {
            throw new RuntimeException("Query failed: " + new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        }
        return stdout;
    }

    private static ProcessResult runScript(Path scriptPath, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(scriptPath.toString());
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, stdout, stderr);
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
