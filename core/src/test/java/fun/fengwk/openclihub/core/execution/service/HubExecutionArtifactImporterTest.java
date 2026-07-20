package fun.fengwk.openclihub.core.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.resource.model.HubExecutionResourceGroup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HubExecutionArtifactImporterTest {

    @TempDir
    Path tempDir;

    /** Allowlisted external files discovered in stdout downloads are copied into the execution group. */
    @Test
    void shouldImportAllowlistedDownloadPathIntoExecutionGroup() throws Exception {
        Path pictures = tempDir.resolve("Pictures/chatgpt-agent");
        Files.createDirectories(pictures);
        Path source = pictures.resolve("cat.png");
        Files.writeString(source, "png-bytes");

        Path groupDir = tempDir.resolve("resources/2026-07-20/execution-abc");
        Files.createDirectories(groupDir);
        // Pretend HOME is tempDir so allowlist includes Pictures under home.
        String previousHome = System.getenv("HOME");
        // Cannot mutate env easily; place file under java.io.tmpdir which is allowlisted.
        Path tmpSource = Files.createTempFile(tempDir, "artifact-", ".png");
        Files.writeString(tmpSource, "png-bytes");

        HubExecutionResourceGroup group = HubExecutionResourceGroup.builder()
            .executionId("abc")
            .date(LocalDate.of(2026, 7, 20))
            .group("execution-abc")
            .realPath(groupDir)
            .build();

        String stdout = """
            [{
              "downloads": "[{\\"path\\":\\"%s\\",\\"downloaded\\":true}]"
            }]
            """.formatted(tmpSource.toString().replace("\\", "\\\\"));

        HubExecutionArtifactImporter importer = new HubExecutionArtifactImporter();
        // Force allowlist via temp dir by putting file under java.io.tmpdir
        Path realTmp = Path.of(System.getProperty("java.io.tmpdir"));
        Path underTmp = Files.createTempFile(realTmp, "hub-import-", ".png");
        Files.writeString(underTmp, "png-bytes");
        underTmp.toFile().deleteOnExit();
        stdout = """
            [{
              "downloads": "[{\\"path\\":\\"%s\\",\\"downloaded\\":true}]"
            }]
            """.formatted(underTmp.toString().replace("\\", "\\\\"));

        importer.importFromStdout(group, stdout);

        try (var stream = Files.list(groupDir)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            assertThat(files).isNotEmpty();
            assertThat(Files.readString(files.get(0))).isEqualTo("png-bytes");
        }
    }

    @Test
    void shouldExtractLocalPathsFromNestedDownloadsJson() {
        var paths = HubExecutionArtifactImporter.extractLocalPaths("""
            [{
              "downloads": "[{\\"path\\":\\"/var/lib/opencli/Pictures/a.png\\",\\"filename\\":\\"/tmp/b.png\\"}]"
            }]
            """);
        assertThat(paths).extracting(Path::toString)
            .contains("/var/lib/opencli/Pictures/a.png", "/tmp/b.png");
    }
}
