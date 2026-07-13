package fun.fengwk.openclihub.core.opencli.catalog;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Production catalog source: shells out to the pinned OpenCLI {@code list -f json} binary
 * and exposes its stdout as the manifest stream.
 *
 * <p>The implementation uses {@link ProcessBuilder} directly, never {@code bash -c},
 * to keep argv injection safe.
 *
 * @author fengwk
 */
public class ProcessBuilderOpenCliCatalogSource implements OpenCliCatalogSource {

    private final OpenCliHubProperties properties;
    private final long timeoutMillis;

    public ProcessBuilderOpenCliCatalogSource(OpenCliHubProperties properties) {
        this(properties, 30000L);
    }

    public ProcessBuilderOpenCliCatalogSource(OpenCliHubProperties properties, long timeoutMillis) {
        this.properties = properties;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public InputStream open() throws IOException {
        Process process = null;
        try {
            List<String> command = List.of(
                properties.getOpencli().getBinary(),
                "list",
                "-f",
                "json");
            ProcessBuilder builder = new ProcessBuilder(command);
            String workdir = properties.getOpencli().getWorkdir();
            if (workdir != null && !workdir.isBlank()) {
                builder.directory(Path.of(workdir).toFile());
            }
            builder.redirectErrorStream(true);
            process = builder.start();
            Process runningProcess = process;
            FutureTask<byte[]> outputTask = new FutureTask<>(() -> {
                try (InputStream inputStream = runningProcess.getInputStream()) {
                    return inputStream.readAllBytes();
                }
            });
            Thread outputReader = new Thread(outputTask, "opencli-catalog-output");
            outputReader.setDaemon(true);
            outputReader.start();

            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                terminateProcess(process);
                throw timeoutException();
            }
            byte[] output = awaitOutput(outputTask);
            int exit = process.exitValue();
            if (exit != 0) {
                throw new IOException("`opencli list -f json` exited with code " + exit);
            }
            return new ByteArrayInputStream(output);
        } catch (InterruptedException ex) {
            if (process != null) {
                terminateProcess(process);
            }
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while loading OpenCLI catalog", ex);
        }
    }

    private IOException timeoutException() {
        return new IOException("Timed out waiting for `opencli list -f json` after "
            + timeoutMillis + " ms");
    }

    private static void terminateProcess(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // Closing the process stream is best-effort cleanup for a blocked output reader.
        }
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] awaitOutput(FutureTask<byte[]> outputTask) throws IOException, InterruptedException {
        try {
            return outputTask.get();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to capture `opencli list -f json` output", cause);
        }
    }

}
