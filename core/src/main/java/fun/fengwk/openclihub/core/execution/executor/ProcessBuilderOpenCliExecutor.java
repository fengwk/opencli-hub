package fun.fengwk.openclihub.core.execution.executor;

import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliSessionLeaseRecoveryService;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Local process implementation. The caller must pass catalog-validated normalized
 * arguments plus any Hub-owned segments ({@code --profile}, managed output argument and
 * {@code --format json}). This class only prepends the configured binary, sets the working
 * directory, runs {@code ProcessBuilder}, reads stdout/stderr concurrently, and enforces
 * one deadline across process exit and output drain via a
 * destroy-then-grace-then-descendant-kill chain.
 *
 * <p>On process timeout or caller interruption, after local process-tree cleanup, Hub may
 * request capability-gated daemon session lease recovery for the exact
 * {@code OPENCLI_RUN_OWNER}. Normal nonzero command exits never trigger recovery and never
 * replay the original command.
 *
 * @author fengwk
 */
@Component
@Slf4j
public class ProcessBuilderOpenCliExecutor implements OpenCliExecutor {

    private final OpenCliHubProperties properties;
    private final OpenCliSessionLeaseRecoveryService sessionLeaseRecoveryService;
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "opencli-hub-process-io");
        thread.setDaemon(true);
        return thread;
    });

    public ProcessBuilderOpenCliExecutor(
        OpenCliHubProperties properties,
        OpenCliSessionLeaseRecoveryService sessionLeaseRecoveryService) {
        this.properties = properties;
        this.sessionLeaseRecoveryService = sessionLeaseRecoveryService;
    }

    @Override
    public OpenCliExecutionResult execute(
        HubInstance instance, List<String> hubManagedArgv, long timeoutMillis, String executionId) {
        long deadlineNanos = deadlineAfterMillis(timeoutMillis);
        List<String> command = buildCommand(hubManagedArgv);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(Path.of(properties.getOpencli().getWorkdir()).toFile());
        processBuilder.redirectErrorStream(false);
        String instanceId = instance == null ? null : instance.getId();
        String runOwner = injectEnvironment(processBuilder, instance, executionId);
        log.info(
            "Starting OpenCLI process instanceId={} executionId={} runOwner={} timeoutMillis={} argvSize={} binary={}",
            instanceId,
            executionId,
            runOwner,
            timeoutMillis,
            hubManagedArgv == null ? 0 : hubManagedArgv.size(),
            properties.getOpencli().getBinary());
        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException ex) {
            log.error("Failed to start OpenCLI for instance {}", instanceId, ex);
            throw HubErrorCodes.OPENCLI_EXECUTION_FAILED.asThrowable(
                ex, "Failed to start OpenCLI process");
        }
        log.info("OpenCLI process started instanceId={} pid={}", instanceId, process.pid());
        CaptureGroup captures = capture(process);
        try {
            boolean finished = waitFor(process, deadlineNanos);
            if (!finished) {
                cleanup(process, captures);
                requestRecovery(runOwner, OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_TIMEOUT);
                log.warn(
                    "OpenCLI process deadline exceeded instanceId={} pid={} timeoutMillis={}",
                    instanceId,
                    process.pid(),
                    timeoutMillis);
                return result(
                    captures,
                    true,
                    124,
                    "OpenCLI process exceeded deadline of " + timeoutMillis + " ms");
            }

            if (!captures.awaitUntil(deadlineNanos)) {
                cleanup(process, captures);
                requestRecovery(runOwner, OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_TIMEOUT);
                log.warn(
                    "OpenCLI process exited but streams incomplete instanceId={} pid={} timeoutMillis={}",
                    instanceId,
                    process.pid(),
                    timeoutMillis);
                return result(
                    captures,
                    true,
                    124,
                    "OpenCLI process exited but output streams did not reach EOF before deadline of "
                        + timeoutMillis + " ms");
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("OpenCLI process completed instanceId={} pid={} exitCode=0", instanceId, process.pid());
            } else {
                log.warn(
                    "OpenCLI process completed with failure instanceId={} pid={} exitCode={}",
                    instanceId,
                    process.pid(),
                    exitCode);
            }
            return result(
                captures,
                false,
                exitCode,
                exitCode == 0 ? null : "OpenCLI exited with code " + exitCode);
        } catch (InterruptedException ex) {
            // Caller-side interrupt. Kill the process we just spawned before propagating
            // the interrupt status so we never leak an orphan OpenCLI/Chrome.
            cleanup(process, captures);
            requestRecovery(runOwner, OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_INTERRUPTED);
            Thread.currentThread().interrupt();
            log.warn("OpenCLI process interrupted instanceId={} pid={}", instanceId, process.pid());
            throw new IllegalStateException("Interrupted while running OpenCLI", ex);
        }
    }

    private String injectEnvironment(
        ProcessBuilder processBuilder, HubInstance instance, String executionId) {
        if (instance != null) {
            processBuilder.environment().put("OPENCLI_HUB_INSTANCE_ID", instance.getId());
            processBuilder.environment().put("OPENCLI_HUB_INSTANCE_CODE",
                instance.getCode() == null ? "" : instance.getCode());
        }
        if (instance == null || instance.getId() == null || instance.getId().isBlank()
            || executionId == null || executionId.isBlank()) {
            return null;
        }
        String runOwner = OpenCliRunOwner.of(instance.getId(), executionId);
        processBuilder.environment().put(OpenCliRunOwner.ENV_NAME, runOwner);
        return runOwner;
    }

    private void requestRecovery(String runOwner, String reason) {
        if (runOwner == null || runOwner.isBlank() || sessionLeaseRecoveryService == null) {
            return;
        }
        try {
            sessionLeaseRecoveryService.recoverOwnedActiveLeases(runOwner, reason);
        } catch (RuntimeException ex) {
            // Recovery must never change the execution terminal outcome.
            log.warn(
                "Session lease recovery threw for owner={} reason={}: {}",
                runOwner,
                reason,
                ex.getMessage());
        }
    }

    private OpenCliExecutionResult result(
        CaptureGroup captures,
        boolean timedOut,
        int exitCode,
        String errorMessage) {
        CapturedText stdout = captures.stdout().snapshot();
        CapturedText stderr = captures.stderr().snapshot();
        OpenCliExecutionResult result = new OpenCliExecutionResult();
        result.setExitCode(exitCode);
        result.setStdout(stdout.content());
        result.setStdoutTruncated(stdout.truncated());
        result.setStderr(stderr.content());
        result.setStderrTruncated(stderr.truncated());
        result.setTimedOut(timedOut);
        result.setErrorMessage(errorMessage);
        return result;
    }

    @PreDestroy
    public void destroy() {
        ioExecutor.shutdownNow();
    }

    private List<String> buildCommand(List<String> hubManagedArgv) {
        if (hubManagedArgv == null || hubManagedArgv.isEmpty()) {
            throw new IllegalArgumentException("hubManagedArgv is required");
        }
        List<String> command = new ArrayList<>(hubManagedArgv.size() + 1);
        command.add(properties.getOpencli().getBinary());
        command.addAll(hubManagedArgv);
        return command;
    }

    private CaptureGroup capture(Process process) {
        StreamCapture stdout = new StreamCapture(
            process.getInputStream(), properties.getExecution().getMaxCaptureChars());
        StreamCapture stderr = new StreamCapture(
            process.getErrorStream(), properties.getExecution().getMaxCaptureChars());
        stdout.start(ioExecutor);
        stderr.start(ioExecutor);
        return new CaptureGroup(stdout, stderr);
    }

    private boolean waitFor(Process process, long deadlineNanos) throws InterruptedException {
        long remainingNanos = remainingNanos(deadlineNanos);
        if (remainingNanos == 0L) {
            return !process.isAlive();
        }
        return process.waitFor(remainingNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Destroys the OpenCLI process tree. Descendants are snapshotted immediately before
     * terminating the parent because a short-lived shell can disappear first and make
     * {@link ProcessHandle#descendants()} empty while its children still hold the pipes.
     */
    private boolean terminateProcess(
        Process process,
        List<ProcessHandle> descendants,
        long cleanupDeadlineNanos) {
        boolean interrupted = false;
        if (process.isAlive()) {
            process.destroy();
            try {
                long remainingNanos = remainingNanos(cleanupDeadlineNanos);
                if (remainingNanos > 0L) {
                    process.waitFor(remainingNanos, TimeUnit.NANOSECONDS);
                }
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }

        for (ProcessHandle handle : descendants) {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        }
        process.descendants().forEach(handle -> {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        });
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        if (!interrupted && process.isAlive()) {
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

    private void cleanup(Process process, CaptureGroup captures) {
        long cleanupDeadlineNanos = deadlineAfterMillis(
            Math.max(0L, properties.getExecution().getProcessStopGraceMillis()));
        List<ProcessHandle> descendants = process.descendants().toList();
        captures.closeAndCancel();
        boolean interrupted = terminateProcess(process, descendants, cleanupDeadlineNanos);
        try {
            captures.awaitUntil(cleanupDeadlineNanos);
        } catch (InterruptedException ex) {
            interrupted = true;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record CapturedText(String content, boolean truncated) {
    }

    private static long deadlineAfterMillis(long timeoutMillis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
    }

    private static long remainingNanos(long deadlineNanos) {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    private static final class CaptureGroup {

        private final StreamCapture stdout;
        private final StreamCapture stderr;
        private final CompletableFuture<Void> completion;

        private CaptureGroup(StreamCapture stdout, StreamCapture stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
            completion = CompletableFuture.allOf(stdout.completion(), stderr.completion());
        }

        private StreamCapture stdout() {
            return stdout;
        }

        private StreamCapture stderr() {
            return stderr;
        }

        private boolean awaitUntil(long deadlineNanos) throws InterruptedException {
            if (completion.isDone()) {
                return true;
            }
            long remainingNanos = remainingNanos(deadlineNanos);
            if (remainingNanos == 0L) {
                return false;
            }
            try {
                completion.get(remainingNanos, TimeUnit.NANOSECONDS);
                return true;
            } catch (TimeoutException ex) {
                return false;
            } catch (ExecutionException ex) {
                throw new IllegalStateException("Failed to capture OpenCLI output", ex.getCause());
            }
        }

        private void closeAndCancel() {
            stdout.requestClose();
            stderr.requestClose();
            stdout.closeInput();
            stderr.closeInput();
            stdout.cancel();
            stderr.cancel();
        }

    }

    private static final class StreamCapture implements Runnable {

        private final InputStream inputStream;
        private final int maxChars;
        private final StringBuilder content;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private Future<?> future;
        private boolean closeRequested;
        private boolean eof;
        private boolean truncated;
        private IOException failure;

        private StreamCapture(InputStream inputStream, int maxChars) {
            this.inputStream = inputStream;
            this.maxChars = maxChars;
            content = new StringBuilder(Math.min(maxChars, 8192));
        }

        private void start(ExecutorService executor) {
            future = executor.submit(this);
        }

        @Override
        public void run() {
            try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    append(buffer, read);
                }
                synchronized (this) {
                    eof = true;
                }
            } catch (IOException ex) {
                synchronized (this) {
                    if (closeRequested) {
                        truncated = true;
                    } else {
                        failure = ex;
                    }
                }
            } finally {
                completion.complete(null);
            }
        }

        private synchronized void append(char[] buffer, int read) {
            int remaining = maxChars - content.length();
            if (remaining > 0) {
                content.append(buffer, 0, Math.min(read, remaining));
            }
            truncated |= read > remaining;
        }

        private CompletableFuture<Void> completion() {
            return completion;
        }

        private synchronized void requestClose() {
            closeRequested = true;
            if (!eof) {
                truncated = true;
            }
        }

        private void closeInput() {
            try {
                inputStream.close();
            } catch (IOException ignored) {
                // Best effort: cancellation and the process-tree cleanup remain bounded.
            }
        }

        private void cancel() {
            Future<?> current = future;
            if (current != null) {
                current.cancel(true);
            }
        }

        private synchronized CapturedText snapshot() {
            if (failure != null) {
                throw new IllegalStateException("Failed to capture OpenCLI output", failure);
            }
            return new CapturedText(content.toString(), truncated);
        }

    }

}
