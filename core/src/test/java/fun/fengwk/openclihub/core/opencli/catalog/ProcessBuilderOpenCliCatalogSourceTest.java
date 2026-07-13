package fun.fengwk.openclihub.core.opencli.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
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

    @Test
    void shouldTerminateCatalogProcessWhenCallerIsInterrupted() throws Exception {
        // Interrupting startup must not leave a long-running OpenCLI catalog process behind.
        Path pidFile = tempDir.resolve("catalog.pid");
        Path binary = executable("interruptible.sh", """
            #!/bin/sh
            echo $$ > "%s"
            exec sleep 30
            """.formatted(pidFile));
        ProcessBuilderOpenCliCatalogSource source = new ProcessBuilderOpenCliCatalogSource(
            properties(binary), 30000L);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                source.open();
            } catch (Throwable ex) {
                failure.set(ex);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "catalog-interrupt-test");
        caller.start();

        long pid = -1L;
        try {
            assertTrue(await(() -> Files.isRegularFile(pidFile), Duration.ofSeconds(2)),
                "Catalog process did not publish its pid");
            pid = Long.parseLong(Files.readString(pidFile).trim());
            caller.interrupt();
            caller.join(2000L);

            assertFalse(caller.isAlive(), "Catalog caller did not stop after interruption");
            assertInstanceOf(IOException.class, failure.get());
            assertTrue(interrupted.get(), "Catalog caller interrupt flag was not restored");
            long processId = pid;
            assertTrue(await(() -> !ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false),
                Duration.ofSeconds(2)), "Catalog process survived caller interruption");
        } finally {
            caller.interrupt();
            caller.join(2000L);
            if (pid > 0L) {
                destroyProcessTree(pid);
            }
        }
    }

    private void destroyProcessTree(long pid) {
        ProcessHandle.of(pid).ifPresent(process -> {
            List<ProcessHandle> descendants = process.descendants().toList();
            process.destroyForcibly();
            descendants.forEach(ProcessHandle::destroyForcibly);
        });
    }

    private boolean await(BooleanSupplier check, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return check.getAsBoolean();
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
