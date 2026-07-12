package fun.fengwk.openclihub.infra.executor;

import fun.fengwk.openclihub.core.executor.OpenCliExecutor;
import fun.fengwk.openclihub.core.model.HubInstance;
import fun.fengwk.openclihub.core.model.OpenCliExecutionResult;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.io.IOException;
import java.io.InputStream;
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
 * @author fengwk
 */
@Component
@Slf4j
public class ProcessBuilderOpenCliExecutor implements OpenCliExecutor {

    private final OpenCliHubProperties properties;
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("opencli-hub-process-io");
        thread.setDaemon(true);
        return thread;
    });

    public ProcessBuilderOpenCliExecutor(OpenCliHubProperties properties) {
        this.properties = properties;
    }

    @Override
    public OpenCliExecutionResult execute(HubInstance instance, List<String> argv, long timeoutMillis) {
        List<String> command = buildCommand(instance, argv);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(Path.of(properties.getExecution().getWorkdir()).toFile());
        processBuilder.redirectErrorStream(false);
        try {
            Process process = processBuilder.start();
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                () -> readStream(process.getInputStream()),
                ioExecutor);
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                () -> readStream(process.getErrorStream()),
                ioExecutor);
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                String stdout = safeJoin(stdoutFuture);
                String stderr = safeJoin(stderrFuture);
                return OpenCliExecutionResult.failed(
                    124,
                    stdout,
                    stderr,
                    "opencli process timed out after " + timeoutMillis + " ms");
            }
            int exitCode = process.exitValue();
            String stdout = safeJoin(stdoutFuture);
            String stderr = safeJoin(stderrFuture);
            if (exitCode == 0) {
                return OpenCliExecutionResult.success(exitCode, stdout, stderr);
            }
            return OpenCliExecutionResult.failed(exitCode, stdout, stderr, "opencli exited with code " + exitCode);
        } catch (IOException ex) {
            log.error("Start opencli process failed, instanceCode: {}", instance.getCode(), ex);
            throw new IllegalStateException("Start opencli process failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Wait opencli process interrupted", ex);
        }
    }

    private List<String> buildCommand(HubInstance instance, List<String> argv) {
        List<String> command = new ArrayList<>();
        command.add(properties.getExecution().getBinary());
        command.add("--profile");
        command.add(instance.getOpencliProfile());
        command.addAll(argv);
        command.add("--format");
        command.add("json");
        return command;
    }

    private String readStream(InputStream inputStream) {
        try (InputStream ignored = inputStream) {
            String text = new String(ignored.readAllBytes(), StandardCharsets.UTF_8);
            int maxCaptureChars = properties.getExecution().getMaxCaptureChars();
            if (text.length() <= maxCaptureChars) {
                return text;
            }
            return text.substring(0, maxCaptureChars);
        } catch (IOException ex) {
            throw new IllegalStateException("Read process stream failed", ex);
        }
    }

    private String safeJoin(CompletableFuture<String> future) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Read process stream interrupted", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Read process stream failed", ex.getCause());
        }
    }

}
