package fun.fengwk.openclihub.core.execution.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.instance.runtime.HubInstanceDirectoryLayout;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the real ProcessBuilder executor with deterministic local shell scripts.
 */
class ProcessBuilderOpenCliExecutorTest {

    @TempDir
    Path tempDir;

    private OpenCliHubProperties properties;
    private HubInstance instance;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        properties = new OpenCliHubProperties();
        properties.setDataDir(tempDir.resolve("data").toString());
        properties.getOpencli().setBinary("/bin/sh");
        properties.getOpencli().setWorkdir(tempDir.resolve("workdir").toString());
        properties.getExecution().setProcessStopGraceMillis(50L);
        Files.createDirectories(Path.of(properties.getOpencli().getWorkdir()));
        Files.createDirectories(HubInstanceDirectoryLayout.logsDir(properties.getDataDir(), 9L));

        instance = new HubInstance();
        instance.setId(9L);
        instance.setCode("executor-test");
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldUseConfiguredWorkdirAndInstanceEnvironment() throws Exception {
        Path script = script("environment.sh", """
            printf '{\"pwd\":\"%s\",\"id\":\"%s\",\"code\":\"%s\"}' \
              "$PWD" "$OPENCLI_HUB_INSTANCE_ID" "$OPENCLI_HUB_INSTANCE_CODE"
            """);
        ProcessBuilderOpenCliExecutor executor = new ProcessBuilderOpenCliExecutor(properties);

        OpenCliExecutionResult result = executor.execute(instance, List.of(script.toString()), 2_000L);

        assertThat(result.getExitCode()).isZero();
        JsonNode json = objectMapper.readTree(result.getStdout());
        assertThat(json.get("pwd").asText()).isEqualTo(Path.of(properties.getOpencli().getWorkdir()).toString());
        assertThat(json.get("id").asText()).isEqualTo("9");
        assertThat(json.get("code").asText()).isEqualTo("executor-test");
    }

    @Test
    void shouldDrainAndTruncateLargeStdoutAndStderrWithoutDeadlock() throws Exception {
        properties.getExecution().setMaxCaptureChars(1_024);
        Path script = script("large-output.sh", """
            head -c 200000 /dev/zero | tr '\\0' 'o'
            head -c 200000 /dev/zero | tr '\\0' 'e' >&2
            """);
        ProcessBuilderOpenCliExecutor executor = new ProcessBuilderOpenCliExecutor(properties);

        OpenCliExecutionResult result = executor.execute(instance, List.of(script.toString()), 5_000L);

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).hasSize(1_024);
        assertThat(result.getStderr()).hasSize(1_024);
        assertThat(result.isStdoutTruncated()).isTrue();
        assertThat(result.isStderrTruncated()).isTrue();
    }

    @Test
    void shouldKillParentAndDescendantWhenDeadlineExpires() throws Exception {
        Path childPid = tempDir.resolve("child.pid");
        Path script = script("timeout.sh", """
            sleep 30 &
            child=$!
            printf '%%s' "$child" > "%s"
            wait "$child"
            """.formatted(childPid));
        ProcessBuilderOpenCliExecutor executor = new ProcessBuilderOpenCliExecutor(properties);

        OpenCliExecutionResult result = executor.execute(instance, List.of(script.toString()), 200L);

        assertThat(result.isTimedOut()).isTrue();
        assertThat(result.getExitCode()).isEqualTo(124);
        assertThat(Files.exists(childPid)).isTrue();
        long child = Long.parseLong(Files.readString(childPid));
        for (int i = 0; i < 100 && ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false); i++) {
            Thread.sleep(10L);
        }
        assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void shouldMapProcessStartFailureToDomainError() {
        properties.getOpencli().setBinary(tempDir.resolve("missing-opencli").toString());
        ProcessBuilderOpenCliExecutor executor = new ProcessBuilderOpenCliExecutor(properties);

        assertThatThrownBy(() -> executor.execute(instance, List.of("bilibili", "hot"), 1_000L))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_EXECUTION_FAILED.getCode()));
    }

    private Path script(String name, String content) throws Exception {
        Path script = tempDir.resolve(name);
        Files.writeString(script, "#!/bin/sh\nset -eu\n" + content + "\n");
        return script;
    }

}
