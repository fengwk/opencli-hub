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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
    void shouldRejectNonPositiveTimeout() {
        // A non-positive timeout cannot establish a usable shared execution deadline.
        assertThrows(IllegalArgumentException.class,
            () -> new ProcessBuilderOpenCliCatalogSource(new OpenCliHubProperties(), 0L));
    }

    @Test
    void shouldReturnSmallJsonOutput() throws Exception {
        // The successful path must still expose the complete catalog as an in-memory stream.
        Path binary = executable("small-output.sh", """
            #!/bin/sh
            printf '[{"name":"demo"}]'
            """);
        ProcessBuilderOpenCliCatalogSource source = new ProcessBuilderOpenCliCatalogSource(
            properties(binary), 2000L);

        try (var inputStream = source.open()) {
            assertEquals("[{\"name\":\"demo\"}]",
                new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void shouldKeepStderrOutOfSuccessfulJsonOutput() throws Exception {
        // Plugin discovery may emit diagnostics (including non-UTF-8 bytes) on stderr;
        // catalog JSON must remain a stdout-only protocol.
        Path binary = executable("stderr-diagnostic.sh", """
            #!/bin/sh
            printf '\\232plugin diagnostic\\n' >&2
            printf '[{"name":"demo"}]'
            """);
        ProcessBuilderOpenCliCatalogSource source = new ProcessBuilderOpenCliCatalogSource(
            properties(binary), 2000L);

        try (var inputStream = source.open()) {
            assertEquals("[{\"name\":\"demo\"}]",
                new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

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
    void shouldDrainLargeStderrWhileProcessIsRunning() throws Exception {
        // Stderr must be drained independently or plugin diagnostics can fill the pipe and block JSON output.
        Path binary = executable("large-stderr.sh", """
            #!/bin/sh
            head -c 262144 /dev/zero | tr '\\000' e >&2
            printf '[]'
            """);
        ProcessBuilderOpenCliCatalogSource source = new ProcessBuilderOpenCliCatalogSource(
            properties(binary), 5000L);

        try (var inputStream = source.open()) {
            assertEquals("[]", new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void shouldRejectNonZeroExitCode() throws Exception {
        // Non-zero failures should preserve bounded stderr detail without mixing it into JSON.
        Path binary = executable("non-zero.sh", """
            #!/bin/sh
            printf 'failure detail' >&2
            exit 7
            """);
        ProcessBuilderOpenCliCatalogSource source = new ProcessBuilderOpenCliCatalogSource(
            properties(binary), 2000L);

        IOException exception = assertThrows(IOException.class, source::open);
        assertTrue(exception.getMessage().contains("exited with code 7"));
        assertTrue(exception.getMessage().contains("failure detail"));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void shouldKeepCatalogProcessTimeoutBounded() throws Exception {
        // A stuck CLI must still fail within the configured catalog timeout.
        Path binary = executable("slow.sh", """
            #!/bin/sh
            sleep 2
            printf '[]'
            """);
        ProcessBuilderOpenCliCatalogSource source = new ProcessBuilderOpenCliCatalogSource(
            properties(binary), 100L);

        long startedNanos = System.nanoTime();
        IOException exception = assertThrows(IOException.class, source::open);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        assertTrue(exception.getMessage().contains("Timed out"));
        assertTrue(elapsedMillis < 1500L, "Direct process timeout took " + elapsedMillis + " ms");
    }

    /**
     * Reproduces a real {@code /bin/sh list -f json} parent that exits successfully while
     * its reparented background child still owns the merged stdout pipe.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void shouldTimeOutWhenExitedParentLeavesOutputPipeOpen() throws Exception {
        Path pidFile = tempDir.resolve("inherited-pipe.pid");
        Files.writeString(tempDir.resolve("list"), """
            sleep 20 &
            child=$!
            printf '%%s %%s\n' "$$" "$child" > "%s"
            printf '[]'
            # Keep the parent alive briefly so the reader deterministically blocks on the inherited pipe.
            sleep 0.05
            exit 0
            """.formatted(pidFile), StandardCharsets.UTF_8);
        ProcessBuilderOpenCliCatalogSource source = new ProcessBuilderOpenCliCatalogSource(
            properties(Path.of("/bin/sh")), 250L);

        long childPid = -1L;
        String readerName = null;
        try {
            long startedNanos = System.nanoTime();
            IOException exception = assertThrows(IOException.class, source::open);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

            assertTrue(elapsedMillis >= 150L, "Output drain timed out too early after " + elapsedMillis + " ms");
            assertTrue(elapsedMillis < 1500L, "Output drain timeout took " + elapsedMillis + " ms");
            assertTrue(exception.getMessage().contains("process exited but output did not reach EOF"));
            assertTrue(Files.isRegularFile(pidFile), "Catalog script did not publish its pids");
            String[] pids = Files.readString(pidFile).trim().split("\\s+");
            long parentPid = Long.parseLong(pids[0]);
            childPid = Long.parseLong(pids[1]);
            readerName = "opencli-catalog-output-" + parentPid;
            assertTrue(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                "Reparented pipe-holding child should remain as the documented cleanup limitation");
        } finally {
            if (childPid < 0L && Files.isRegularFile(pidFile)) {
                String[] pids = Files.readString(pidFile).trim().split("\\s+");
                childPid = Long.parseLong(pids[1]);
            }
            if (childPid > 0L) {
                destroyProcessTree(childPid);
            }
            if (readerName != null) {
                String expectedReaderName = readerName;
                assertTrue(await(() -> Thread.getAllStackTraces().keySet().stream()
                        .noneMatch(thread -> thread.isAlive() && thread.getName().equals(expectedReaderName)),
                    Duration.ofMillis(500)), "Catalog output reader survived child cleanup");
            }
        }
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
