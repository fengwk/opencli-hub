package fun.fengwk.openclihub.core.execution.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.instance.runtime.HubInstanceDirectoryLayout;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.opencli.daemon.FakeOpenCliDaemonClient;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliDaemonStatus;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliSessionLease;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliSessionLeaseRecoverRequest;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliSessionLeaseRecoveryService;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the real ProcessBuilder executor with deterministic local shell scripts.
 */
class ProcessBuilderOpenCliExecutorTest {

    private static final String EXECUTION_ID = "exec-42";

    @TempDir
    Path tempDir;

    private OpenCliHubProperties properties;
    private HubInstance instance;
    private ObjectMapper objectMapper;
    private FakeOpenCliDaemonClient daemon;
    private OpenCliSessionLeaseRecoveryService recoveryService;
    private RecordingRecoveryService recordingRecovery;

    @BeforeEach
    void setUp() throws Exception {
        properties = new OpenCliHubProperties();
        properties.setDataDir(tempDir.resolve("data").toString());
        properties.getOpencli().setBinary("/bin/sh");
        properties.getOpencli().setWorkdir(tempDir.resolve("workdir").toString());
        properties.getExecution().setProcessStopGraceMillis(50L);
        Files.createDirectories(Path.of(properties.getOpencli().getWorkdir()));
        Files.createDirectories(HubInstanceDirectoryLayout.logsDir(properties.getDataDir(), "9"));

        instance = new HubInstance();
        instance.setId("9");
        instance.setCode("executor-test");
        objectMapper = new ObjectMapper();
        daemon = new FakeOpenCliDaemonClient();
        recoveryService = new OpenCliSessionLeaseRecoveryService(daemon);
        recordingRecovery = new RecordingRecoveryService(daemon);
    }

    @Test
    void shouldUseConfiguredWorkdirAndInstanceEnvironment() throws Exception {
        Path script = script("environment.sh", """
            printf '{"pwd":"%s","id":"%s","code":"%s","owner":"%s","browserTimeout":"%s"}' \
              "$PWD" "$OPENCLI_HUB_INSTANCE_ID" "$OPENCLI_HUB_INSTANCE_CODE" "$OPENCLI_RUN_OWNER" "$OPENCLI_BROWSER_COMMAND_TIMEOUT"
            """);
        ProcessBuilderOpenCliExecutor executor = newExecutor(recoveryService);

        OpenCliExecutionResult result = executor.execute(
            instance, List.of(script.toString()), 2_000L, EXECUTION_ID);

        assertThat(result.getExitCode()).isZero();
        JsonNode json = objectMapper.readTree(result.getStdout());
        assertThat(json.get("pwd").asText()).isEqualTo(Path.of(properties.getOpencli().getWorkdir()).toString());
        assertThat(json.get("id").asText()).isEqualTo("9");
        assertThat(json.get("code").asText()).isEqualTo("executor-test");
        assertThat(json.get("owner").asText()).isEqualTo(OpenCliRunOwner.of("9", EXECUTION_ID));
        assertThat(json.get("owner").asText()).isEqualTo("opencli-hub:9:exec-42");
        // The execution deadline is forwarded as the per-command browser
        // timeout so long uploads do not die on opencli's 60s default; short
        // deadlines are floored at opencli's own 60s minimum.
        assertThat(json.get("browserTimeout").asText()).isEqualTo("60");
    }

    @Test
    void shouldForwardLongExecutionDeadlineAsBrowserCommandTimeout() throws Exception {
        Path script = script("browser-timeout.sh", """
            printf '%s' "$OPENCLI_BROWSER_COMMAND_TIMEOUT"
            """);
        ProcessBuilderOpenCliExecutor executor = newExecutor(recoveryService);

        OpenCliExecutionResult result = executor.execute(
            instance, List.of(script.toString()), 480_000L, EXECUTION_ID);

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout().trim()).isEqualTo("480");
    }

    /** A normal long JSON result must remain complete beyond the former 65,535-character cap. */
    @Test
    void shouldCaptureLongJsonWithinDefaultLimit() throws Exception {
        Path script = script("long-json-output.sh", """
            printf '{"text":"'
            head -c 100000 /dev/zero | tr '\\0' 'o'
            printf '"}'
            """);
        ProcessBuilderOpenCliExecutor executor = newExecutor(recoveryService);

        OpenCliExecutionResult result = executor.execute(
            instance, List.of(script.toString()), 5_000L, EXECUTION_ID);

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).hasSize(100_011);
        assertThat(result.isStdoutTruncated()).isFalse();
        assertThat(objectMapper.readTree(result.getStdout()).get("text").asText()).hasSize(100_000);
    }

    @Test
    void shouldDrainAndTruncateLargeStdoutAndStderrWithoutDeadlock() throws Exception {
        properties.getExecution().setMaxCaptureChars(1_024);
        Path script = script("large-output.sh", """
            head -c 200000 /dev/zero | tr '\\0' 'o'
            head -c 200000 /dev/zero | tr '\\0' 'e' >&2
            """);
        ProcessBuilderOpenCliExecutor executor = newExecutor(recordingRecovery);

        OpenCliExecutionResult result = executor.execute(
            instance, List.of(script.toString()), 5_000L, EXECUTION_ID);

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).hasSize(1_024);
        assertThat(result.getStderr()).hasSize(1_024);
        assertThat(result.isStdoutTruncated()).isTrue();
        assertThat(result.isStderrTruncated()).isTrue();
        assertThat(recordingRecovery.calls()).isEmpty();
        assertThat(daemon.recoverRequests()).isEmpty();
    }

    @Test
    void shouldKillParentAndDescendantAndRequestRecoveryWhenDeadlineExpires() throws Exception {
        // Recovery is only legal after process-tree cleanup: the double asserts the child is
        // already dead at the moment recoverOwnedActiveLeases is entered.
        Path childPid = tempDir.resolve("child.pid");
        Path script = script("timeout.sh", """
            sleep 30 &
            child=$!
            printf '%%s' "$child" > "%s"
            wait "$child"
            """.formatted(childPid));
        enqueueOwnedActiveLease("run-timeout");
        CleanupOrderRecoveryService orderedRecovery =
            new CleanupOrderRecoveryService(daemon, childPid);
        ProcessBuilderOpenCliExecutor executor = newExecutor(orderedRecovery);

        OpenCliExecutionResult result = executor.execute(
            instance, List.of(script.toString()), 200L, EXECUTION_ID);

        assertThat(result.isTimedOut()).isTrue();
        assertThat(result.getExitCode()).isEqualTo(124);
        assertThat(Files.exists(childPid)).isTrue();
        long child = Long.parseLong(Files.readString(childPid).trim());
        assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        assertThat(orderedRecovery.calls()).hasSize(1);
        assertThat(orderedRecovery.calls().get(0).owner())
            .isEqualTo(OpenCliRunOwner.of("9", EXECUTION_ID));
        assertThat(orderedRecovery.calls().get(0).reason())
            .isEqualTo(OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_TIMEOUT);
        assertThat(orderedRecovery.childWasDeadAtRecoveryCall()).isTrue();
        assertThat(orderedRecovery.observedChildPidAtRecovery()).isEqualTo(child);
        assertThat(daemon.recoverRequests()).hasSize(1);
        OpenCliSessionLeaseRecoverRequest request = daemon.recoverRequests().get(0);
        assertThat(request.getExpectedRunId()).isEqualTo("run-timeout");
        assertThat(request.getMode()).isEqualTo(OpenCliSessionLeaseRecoverRequest.MODE_CANCEL_AND_RESET);
        assertThat(request.getReason())
            .isEqualTo(OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_TIMEOUT);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldCleanupAndRequestRecoveryOnCallerInterruption() throws Exception {
        // Caller interrupt must destroy the spawned shell/child, then request recovery once
        // with hub_execution_interrupted, without leaving process or interrupt pollution.
        Path childPid = tempDir.resolve("interrupt-child.pid");
        Path startedFlag = tempDir.resolve("interrupt-started.flag");
        Path script = script("interrupt.sh", """
            sleep 30 &
            child=$!
            printf '%%s' "$child" > "%s"
            touch "%s"
            wait "$child"
            """.formatted(childPid, startedFlag));
        enqueueOwnedActiveLease("run-interrupt");
        CleanupOrderRecoveryService orderedRecovery =
            new CleanupOrderRecoveryService(daemon, childPid);
        ProcessBuilderOpenCliExecutor executor = newExecutor(orderedRecovery);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptedAfterCatch = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            try {
                executor.execute(instance, List.of(script.toString()), 30_000L, EXECUTION_ID);
            } catch (Throwable ex) {
                failure.set(ex);
                interruptedAfterCatch.set(Thread.currentThread().isInterrupted());
            } finally {
                finished.countDown();
            }
        }, "opencli-interrupt-recovery-test");
        caller.setDaemon(true);

        long child = -1L;
        try {
            caller.start();
            assertThat(awaitFile(startedFlag, 3_000L)).isTrue();
            assertThat(awaitFile(childPid, 3_000L)).isTrue();
            child = Long.parseLong(Files.readString(childPid).trim());
            assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)).isTrue();

            caller.interrupt();
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            caller.join(2_000L);

            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Interrupted while running OpenCLI");
            assertThat(interruptedAfterCatch).isTrue();
            assertThat(orderedRecovery.calls()).hasSize(1);
            assertThat(orderedRecovery.calls().get(0).owner())
                .isEqualTo(OpenCliRunOwner.of("9", EXECUTION_ID));
            assertThat(orderedRecovery.calls().get(0).reason())
                .isEqualTo(OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_INTERRUPTED);
            assertThat(orderedRecovery.childWasDeadAtRecoveryCall()).isTrue();
            assertThat(orderedRecovery.observedChildPidAtRecovery()).isEqualTo(child);
            assertThat(daemon.recoverRequests()).hasSize(1);
            assertThat(daemon.recoverRequests().get(0).getReason())
                .isEqualTo(OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_INTERRUPTED);
            assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        } finally {
            if (caller.isAlive()) {
                caller.interrupt();
                caller.join(2_000L);
            }
            if (child > 0L) {
                ProcessHandle.of(child).ifPresent(handle -> {
                    if (handle.isAlive()) {
                        handle.destroyForcibly();
                    }
                });
            }
            if (Files.exists(childPid)) {
                long leftover = Long.parseLong(Files.readString(childPid).trim());
                ProcessHandle.of(leftover).ifPresent(handle -> {
                    if (handle.isAlive()) {
                        handle.destroyForcibly();
                    }
                });
            }
            executor.destroy();
            // Clear any residual interrupt flag on the JUnit thread.
            Thread.interrupted();
        }
    }

    @Test
    void shouldNotRequestRecoveryOnNormalNonZeroExit() throws Exception {
        Path script = script("fail.sh", "exit 17");
        ProcessBuilderOpenCliExecutor executor = newExecutor(recordingRecovery);

        OpenCliExecutionResult result = executor.execute(
            instance, List.of(script.toString()), 2_000L, EXECUTION_ID);

        assertThat(result.isTimedOut()).isFalse();
        assertThat(result.getExitCode()).isEqualTo(17);
        assertThat(recordingRecovery.calls()).isEmpty();
        assertThat(daemon.recoverRequests()).isEmpty();
    }

    /**
     * Covers the real shell case where the parent exits but a reparented background child
     * keeps both output pipes open, so output drain must share the process deadline.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void shouldTimeOutWhenExitedParentLeavesOutputPipesOpen() throws Exception {
        Path childPid = tempDir.resolve("pipe-child.pid");
        Path script = script("inherited-pipe.sh", """
            printf 'captured-prefix'
            sleep 20 &
            printf '%%s' "$!" > "%s"
            # Let both capture tasks enter their blocking reads before the parent exits.
            sleep 0.05
            exit 0
            """.formatted(childPid));
        // Reparented pipe-holder may outlive the dead parent; this case only asserts recovery
        // is requested after the executor's cleanup path, not descendant reaping of reparented PIDs.
        ProcessBuilderOpenCliExecutor executor = newExecutor(recordingRecovery);

        long startedNanos = System.nanoTime();
        try {
            OpenCliExecutionResult result = executor.execute(
                instance, List.of(script.toString()), 200L, EXECUTION_ID);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

            assertThat(elapsedMillis).isLessThan(1_500L);
            assertThat(result.isTimedOut()).isTrue();
            assertThat(result.getExitCode()).isEqualTo(124);
            assertThat(result.getErrorMessage()).contains("output streams did not reach EOF");
            assertThat(result.getStdout()).startsWith("captured-prefix");
            assertThat(result.isStdoutTruncated()).isTrue();
            assertThat(result.isStderrTruncated()).isTrue();
            assertThat(recordingRecovery.calls()).hasSize(1);
            assertThat(recordingRecovery.calls().get(0).reason())
                .isEqualTo(OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_TIMEOUT);
        } finally {
            executor.destroy();
            if (Files.exists(childPid)) {
                long child = Long.parseLong(Files.readString(childPid).trim());
                ProcessHandle.of(child).ifPresent(handle -> {
                    if (handle.isAlive()) {
                        handle.destroyForcibly();
                    }
                });
            }
        }
    }

    @Test
    void shouldMapProcessStartFailureToDomainError() {
        properties.getOpencli().setBinary(tempDir.resolve("missing-opencli").toString());
        ProcessBuilderOpenCliExecutor executor = newExecutor(recordingRecovery);

        assertThatThrownBy(() -> executor.execute(
            instance, List.of("bilibili", "hot"), 1_000L, EXECUTION_ID))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.OPENCLI_EXECUTION_FAILED.getCode()));
        assertThat(recordingRecovery.calls()).isEmpty();
    }

    private void enqueueOwnedActiveLease(String runId) {
        OpenCliDaemonStatus status = FakeOpenCliDaemonClient.empty();
        status.setPid(1L);
        status.setCapabilities(List.of(OpenCliSessionLeaseRecoveryService.CAPABILITY_SESSION_RECOVER_V1));
        OpenCliSessionLease lease = new OpenCliSessionLease();
        lease.setContextId("ctx");
        lease.setSurface("adapter");
        lease.setSession("site:chatgpt");
        lease.setRunId(runId);
        lease.setOwner(OpenCliRunOwner.of("9", EXECUTION_ID));
        lease.setState(OpenCliSessionLeaseRecoveryService.STATE_ACTIVE);
        status.setSessionLeases(List.of(lease));
        daemon.enqueue(status);
    }

    private ProcessBuilderOpenCliExecutor newExecutor(OpenCliSessionLeaseRecoveryService recovery) {
        return new ProcessBuilderOpenCliExecutor(properties, recovery);
    }

    private Path script(String name, String content) throws Exception {
        Path script = tempDir.resolve(name);
        Files.writeString(script, "#!/bin/sh\nset -eu\n" + content + "\n");
        return script;
    }

    private static boolean awaitFile(Path path, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (Files.exists(path)) {
                return true;
            }
            Thread.sleep(10L);
        }
        return Files.exists(path);
    }

    /**
     * Records recovery invocations while still exercising the real recovery service logic.
     */
    private static final class RecordingRecoveryService extends OpenCliSessionLeaseRecoveryService {

        private final List<Call> calls = new ArrayList<>();

        private RecordingRecoveryService(FakeOpenCliDaemonClient daemon) {
            super(daemon);
        }

        @Override
        public void recoverOwnedActiveLeases(String owner, String reason) {
            calls.add(new Call(owner, reason));
            super.recoverOwnedActiveLeases(owner, reason);
        }

        List<Call> calls() {
            return List.copyOf(calls);
        }

        private record Call(String owner, String reason) {
        }
    }

    /**
     * Recovery double that proves process-tree cleanup already completed: when recovery is
     * entered, the child PID recorded by the shell script must already be non-alive.
     * This is an ordering assertion, not a post-hoc check after execute returns.
     */
    private static final class CleanupOrderRecoveryService extends OpenCliSessionLeaseRecoveryService {

        private final Path childPidFile;
        private final List<Call> calls = new ArrayList<>();
        private final AtomicBoolean childDeadAtRecoveryCall = new AtomicBoolean();
        private final AtomicLong observedChildPid = new AtomicLong(-1L);

        private CleanupOrderRecoveryService(FakeOpenCliDaemonClient daemon, Path childPidFile) {
            super(daemon);
            this.childPidFile = childPidFile;
        }

        @Override
        public void recoverOwnedActiveLeases(String owner, String reason) {
            // Fail the test immediately if recovery runs before cleanup finished.
            assertThat(Files.exists(childPidFile))
                .as("child pid file must exist before recovery (script started)")
                .isTrue();
            long child;
            try {
                child = Long.parseLong(Files.readString(childPidFile).trim());
            } catch (Exception ex) {
                throw new AssertionError("failed to read child pid before recovery", ex);
            }
            observedChildPid.set(child);
            boolean alive = ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false);
            assertThat(alive)
                .as("child pid %s must already be dead when recovery is invoked", child)
                .isFalse();
            childDeadAtRecoveryCall.set(true);
            calls.add(new Call(owner, reason));
            super.recoverOwnedActiveLeases(owner, reason);
        }

        List<Call> calls() {
            return List.copyOf(calls);
        }

        boolean childWasDeadAtRecoveryCall() {
            return childDeadAtRecoveryCall.get();
        }

        long observedChildPidAtRecovery() {
            return observedChildPid.get();
        }

        private record Call(String owner, String reason) {
        }
    }

}
