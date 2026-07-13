package fun.fengwk.openclihub.core.opencli.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the production catalog process boundary.
 *
 * @author fengwk
 */
class ProcessBuilderOpenCliCatalogSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDrainLargeOutputWhileProcessIsRunning() throws Exception {
        // Output larger than a normal OS pipe proves the source cannot wait before draining stdout.
        Path binary = executable("large-output.sh", """
            #!/bin/sh
            head -c 262144 /dev/zero | tr '\\000' x
            """);
        ProcessBuilderOpenCliCatalogSource source = new ProcessBuilderOpenCliCatalogSource(
            properties(binary), 5000L);

        try (var inputStream = source.open()) {
            assertEquals(262144, inputStream.readAllBytes().length);
        }
    }

    @Test
    void shouldKeepCatalogProcessTimeoutBounded() throws Exception {
        // A stuck CLI must still fail within the configured catalog timeout.
        Path binary = executable("slow.sh", """
            #!/bin/sh
            sleep 2
            printf '[]'
            """);
        ProcessBuilderOpenCliCatalogSource source = new ProcessBuilderOpenCliCatalogSource(
            properties(binary), 100L);

        IOException exception = assertThrows(IOException.class, source::open);
        assertTrue(exception.getMessage().contains("Timed out"));
    }

    private OpenCliHubProperties properties(Path binary) {
        OpenCliHubProperties properties = new OpenCliHubProperties();
        properties.getOpencli().setBinary(binary.toString());
        properties.getOpencli().setWorkdir(tempDir.toString());
        return properties;
    }

    private Path executable(String fileName, String content) throws IOException {
        Path script = tempDir.resolve(fileName);
        Files.writeString(script, content.stripLeading(), StandardCharsets.UTF_8);
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

}
