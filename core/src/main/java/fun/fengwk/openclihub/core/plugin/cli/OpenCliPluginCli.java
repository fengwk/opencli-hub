package fun.fengwk.openclihub.core.plugin.cli;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around official {@code opencli plugin *} commands.
 *
 * @author fengwk
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenCliPluginCli {

    private static final long DEFAULT_TIMEOUT_SECONDS = 300L;

    private final OpenCliHubProperties properties;

    public CliResult run(List<String> pluginArgs) {
        if (pluginArgs == null || pluginArgs.isEmpty()) {
            throw HubErrorCodes.PLUGIN_SOURCE_ARGUMENT_INVALID.asThrowable("plugin argv is required");
        }
        List<String> command = new ArrayList<>();
        command.add(properties.getOpencli().getBinary());
        command.add("plugin");
        command.addAll(pluginArgs);

        ProcessBuilder builder = new ProcessBuilder(command);
        String workdir = properties.getOpencli().getWorkdir();
        if (workdir != null && !workdir.isBlank()) {
            builder.directory(Path.of(workdir).toFile());
        }
        // OpenCLI discovers plugins under $HOME/.opencli; Hub container HOME is /var/lib/opencli.
        builder.environment().putIfAbsent("HOME", System.getenv().getOrDefault("HOME", "/var/lib/opencli"));
        builder.redirectErrorStream(false);

        log.info("Running OpenCLI plugin CLI argv={}", command);
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            log.error("Failed to start OpenCLI plugin CLI: {}", ex.getMessage(), ex);
            throw HubErrorCodes.PLUGIN_CLI_FAILED.asThrowable(ex, "Failed to start opencli plugin CLI");
        }

        try {
            String stdout = readFully(process.getInputStream());
            String stderr = readFully(process.getErrorStream());
            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("OpenCLI plugin CLI timed out argv={}", command);
                throw HubErrorCodes.PLUGIN_CLI_FAILED.asThrowable(
                    "opencli plugin CLI timed out after " + DEFAULT_TIMEOUT_SECONDS + "s");
            }
            int exitCode = process.exitValue();
            log.info(
                "OpenCLI plugin CLI finished exitCode={} stdoutChars={} stderrChars={}",
                exitCode,
                stdout.length(),
                stderr.length());
            if (exitCode != 0) {
                log.warn("OpenCLI plugin CLI failed exitCode={} stderr={}", exitCode, abbreviate(stderr));
            }
            return new CliResult(exitCode, stdout, stderr);
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw HubErrorCodes.PLUGIN_CLI_FAILED.asThrowable(ex, "Interrupted while running opencli plugin CLI");
        } catch (IOException ex) {
            process.destroyForcibly();
            throw HubErrorCodes.PLUGIN_CLI_FAILED.asThrowable(ex, "Failed to read opencli plugin CLI output");
        }
    }

    private static String readFully(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        input.transferTo(buffer);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "...";
    }

    public record CliResult(int exitCode, String stdout, String stderr) {
    }

}
