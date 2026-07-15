package fun.fengwk.openclihub.core.opencli.catalog;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    private static final long PROCESS_TERMINATION_TIMEOUT_MILLIS = 5000L;
    private static final long OUTPUT_READER_CLEANUP_TIMEOUT_MILLIS = 100L;

    private final OpenCliHubProperties properties;
    private final long timeoutMillis;

    public ProcessBuilderOpenCliCatalogSource(OpenCliHubProperties properties) {
        this(properties, 30000L);
    }

    public ProcessBuilderOpenCliCatalogSource(OpenCliHubProperties properties, long timeoutMillis) {
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        this.properties = properties;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public InputStream open() throws IOException {
        long deadlineNanos = deadlineAfterMillis(timeoutMillis);
        Process process = null;
        OutputCapture outputCapture = null;
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
            outputCapture = new OutputCapture(process);
            outputCapture.start();

            if (!waitFor(process, deadlineNanos)) {
                throw processTimeoutException();
            }
            byte[] output = awaitOutput(outputCapture, deadlineNanos);
            int exit = process.exitValue();
            if (exit != 0) {
                throw new IOException("`opencli list -f json` exited with code " + exit);
            }
            return new ByteArrayInputStream(output);
        } catch (InterruptedException ex) {
            cleanup(process, outputCapture);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while loading OpenCLI catalog", ex);
        } catch (IOException ex) {
            cleanup(process, outputCapture);
            throw ex;
        }
    }

    private IOException processTimeoutException() {
        return new IOException("Timed out waiting for `opencli list -f json` process to exit after "
            + timeoutMillis + " ms");
    }

    private IOException outputTimeoutException() {
        return new IOException("Timed out: `opencli list -f json` process exited but output did not reach EOF within "
            + timeoutMillis + " ms");
    }

    private static boolean waitFor(Process process, long deadlineNanos) throws InterruptedException {
        long remainingNanos = remainingNanos(deadlineNanos);
        if (remainingNanos == 0L) {
            return !process.isAlive();
        }
        return process.waitFor(remainingNanos, TimeUnit.NANOSECONDS);
    }

    private static boolean terminateProcess(Process process, long cleanupDeadlineNanos) {
        boolean interrupted = false;
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            try {
                long remainingNanos = remainingNanos(cleanupDeadlineNanos);
                if (remainingNanos > 0L) {
                    process.waitFor(remainingNanos, TimeUnit.NANOSECONDS);
                }
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private byte[] awaitOutput(OutputCapture outputCapture, long deadlineNanos)
        throws IOException, InterruptedException {
        try {
            return outputCapture.get(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            throw outputTimeoutException();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to capture `opencli list -f json` output", cause);
        } catch (CancellationException ex) {
            throw new IOException("Failed to capture `opencli list -f json` output", ex);
        }
    }

    private static void cleanup(Process process, OutputCapture outputCapture) {
        if (process == null) {
            return;
        }
        long cleanupDeadlineNanos = deadlineAfterMillis(PROCESS_TERMINATION_TIMEOUT_MILLIS);
        Thread inputCloser = null;
        if (outputCapture != null) {
            outputCapture.cancel();
            inputCloser = outputCapture.closeInputAsync();
        }
        boolean interrupted = terminateProcess(process, cleanupDeadlineNanos);
        if (outputCapture != null) {
            long readerCleanupDeadlineNanos = deadlineAfterMillis(OUTPUT_READER_CLEANUP_TIMEOUT_MILLIS);
            try {
                outputCapture.awaitReaderUntil(readerCleanupDeadlineNanos);
                awaitThreadUntil(inputCloser, readerCleanupDeadlineNanos);
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static long deadlineAfterMillis(long timeoutMillis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    private static long remainingNanos(long deadlineNanos) {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    private static boolean awaitThreadUntil(Thread thread, long deadlineNanos) throws InterruptedException {
        if (thread == null) {
            return true;
        }
        while (thread.isAlive()) {
            long remainingNanos = remainingNanos(deadlineNanos);
            if (remainingNanos == 0L) {
                return false;
            }
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            int remainingNanoPart = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(remainingMillis));
            thread.join(remainingMillis, remainingNanoPart);
        }
        return true;
    }

    private static final class OutputCapture {

        private final InputStream inputStream;
        private final FutureTask<byte[]> outputTask;
        private final Thread outputReader;
        private final long processId;

        private OutputCapture(Process process) {
            inputStream = process.getInputStream();
            outputTask = new FutureTask<>(() -> {
                try (InputStream stream = inputStream) {
                    return stream.readAllBytes();
                }
            });
            processId = process.pid();
            outputReader = new Thread(outputTask, "opencli-catalog-output-" + processId);
            outputReader.setDaemon(true);
        }

        private void start() {
            outputReader.start();
        }

        private byte[] get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
            return outputTask.get(timeout, unit);
        }

        private void cancel() {
            outputTask.cancel(true);
        }

        private Thread closeInputAsync() {
            Thread inputCloser = new Thread(() -> {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                    // Best effort: process-tree cleanup and both waits remain bounded.
                }
            }, "opencli-catalog-output-close-" + processId);
            inputCloser.setDaemon(true);
            inputCloser.start();
            return inputCloser;
        }

        private boolean awaitReaderUntil(long deadlineNanos) throws InterruptedException {
            return awaitThreadUntil(outputReader, deadlineNanos);
        }

    }

}
