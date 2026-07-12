package fun.fengwk.openclihub.core.execution.executor;

import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
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
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Local process implementation. The caller must pass catalog-validated normalized arguments.
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
    public OpenCliExecutionResult execute(HubInstance instance, List<String> normalizedArgv, long timeoutMillis) {
        List<String> command = buildCommand(instance, normalizedArgv);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(Path.of(properties.getOpencli().getWorkdir()).toFile());
        processBuilder.redirectErrorStream(false);
        try {
            Process process = processBuilder.start();
            CompletableFuture<CapturedText> stdoutFuture = capture(process.getInputStream());
            CompletableFuture<CapturedText> stderrFuture = capture(process.getErrorStream());
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                terminate(process);
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
        } catch (IOException ex) {
            log.error("Failed to start OpenCLI for instance {}", instance.getId(), ex);
            throw new IllegalStateException("Failed to start OpenCLI process", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for OpenCLI process", ex);
        }
    }

    @PreDestroy
    public void destroy() {
        ioExecutor.shutdownNow();
    }

    private List<String> buildCommand(HubInstance instance, List<String> normalizedArgv) {
        if (instance == null || instance.getContextId() == null || instance.getContextId().isBlank()) {
            throw new IllegalArgumentException("Instance contextId is required");
        }
        if (normalizedArgv == null || normalizedArgv.isEmpty()) {
            throw new IllegalArgumentException("Normalized argv is required");
        }
        List<String> command = new ArrayList<>();
        command.add(properties.getOpencli().getBinary());
        command.add("--profile");
        command.add(instance.getContextId());
        command.addAll(normalizedArgv);
        command.add("--format");
        command.add("json");
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

    private void terminate(Process process) throws InterruptedException {
        process.destroy();
        long graceMillis = properties.getExecution().getProcessStopGraceMillis();
        if (!process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor();
        }
    }

    private CapturedText join(CompletableFuture<CapturedText> future) {
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
