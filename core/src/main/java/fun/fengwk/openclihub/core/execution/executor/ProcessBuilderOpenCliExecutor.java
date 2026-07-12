package fun.fengwk.openclihub.core.execution.executor;

import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Local process implementation. The caller must pass catalog-validated normalized
 * arguments plus any Hub-owned segments ({@code --profile}, managed output argument and
 * {@code --format json}). This class only prepends the configured binary, sets the working
 * directory, runs {@code ProcessBuilder}, reads stdout/stderr concurrently, and enforces
 * the timeout via a destroy-then-grace-then-descendant-kill chain.
 *
 * @author fengwk
 */
@Component
@Slf4j
public class ProcessBuilderOpenCliExecutor implements OpenCliExecutor {

    private final OpenCliHubProperties properties;
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "opencli-hub-process-io");
        thread.setDaemon(true);
        return thread;
    });

    public ProcessBuilderOpenCliExecutor(OpenCliHubProperties properties) {
        this.properties = properties;
    }

    @Override
    public OpenCliExecutionResult execute(HubInstance instance, List<String> hubManagedArgv, long timeoutMillis) {
        List<String> command = buildCommand(hubManagedArgv);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(Path.of(properties.getOpencli().getWorkdir()).toFile());
        processBuilder.redirectErrorStream(false);
        if (instance != null) {
            processBuilder.environment().put("OPENCLI_HUB_INSTANCE_ID",
                Long.toString(instance.getId()));
            processBuilder.environment().put("OPENCLI_HUB_INSTANCE_CODE",
                instance.getCode() == null ? "" : instance.getCode());
        }
        Process process = null;
        Future<CapturedText> stdoutFuture = null;
        Future<CapturedText> stderrFuture = null;
        try {
            process = processBuilder.start();
        } catch (IOException ex) {
            log.error("Failed to start OpenCLI for instance {}", instance == null ? null : instance.getId(), ex);
            throw HubErrorCodes.OPENCLI_EXECUTION_FAILED.asThrowable(
                ex, "Failed to start OpenCLI process");
        }
        stdoutFuture = capture(process.getInputStream());
        stderrFuture = capture(process.getErrorStream());
        boolean finished;
        try {
            finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            // Caller-side interrupt. Kill the process we just spawned before propagating
            // the interrupt status so we never leak an orphan OpenCLI/Chrome.
            terminateProcess(process);
            try {
                stdoutFuture.get();
                stderrFuture.get();
            } catch (Exception ignored) {
                // best-effort drain; the result is undefined when the caller is gone
            }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running OpenCLI", ex);
        }
        if (!finished) {
            terminateProcess(process);
        }
        CapturedText stdout = join(stdoutFuture);
        CapturedText stderr = join(stderrFuture);
        OpenCliExecutionResult result = new OpenCliExecutionResult();
        result.setExitCode(finished ? process.exitValue() : 124);
        result.setStdout(stdout.content());
        result.setStdoutTruncated(stdout.truncated());
        result.setStderr(stderr.content());
        result.setStderrTruncated(stderr.truncated());
        result.setTimedOut(!finished);
        if (!finished) {
            result.setErrorMessage("OpenCLI process exceeded deadline of " + timeoutMillis + " ms");
        } else if (result.getExitCode() != 0) {
            result.setErrorMessage("OpenCLI exited with code " + result.getExitCode());
        }
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

    private CompletableFuture<CapturedText> capture(InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> readStream(inputStream), ioExecutor);
    }

    private CapturedText readStream(InputStream inputStream) {
        int maxChars = properties.getExecution().getMaxCaptureChars();
        StringBuilder content = new StringBuilder(Math.min(maxChars, 8192));
        boolean truncated = false;
        try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                int remaining = maxChars - content.length();
                if (remaining > 0) {
                    content.append(buffer, 0, Math.min(read, remaining));
                }
                truncated |= read > remaining;
            }
            return new CapturedText(content.toString(), truncated);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to capture OpenCLI process output", ex);
        }
    }

    /**
     * Destroys the OpenCLI process tree. Descendants are snapshotted immediately before
     * terminating the parent because a short-lived shell can disappear first and make
     * {@link ProcessHandle#descendants()} empty while its children still hold the pipes.
     */
    private void terminateProcess(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        boolean interrupted = false;
        process.destroy();
        try {
            process.waitFor(properties.getExecution().getProcessStopGraceMillis(),
                TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            interrupted = true;
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
        try {
            process.waitFor();
        } catch (InterruptedException ex) {
            interrupted = true;
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private CapturedText join(Future<CapturedText> future) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while capturing OpenCLI output", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Failed to capture OpenCLI output", ex.getCause());
        }
    }

    private record CapturedText(String content, boolean truncated) {
    }

}
