package fun.fengwk.openclihub.core.execution;

import fun.fengwk.openclihub.core.execution.executor.OpenCliExecutionResult;
import fun.fengwk.openclihub.core.execution.executor.OpenCliExecutor;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Configurable in-process replacement for {@link OpenCliExecutor} used by the service
 * integration tests. The fake records every invocation (so tests can assert on the argv
 * that flowed through) and applies a per-test behaviour chosen via
 * {@link #setBehavior(Supplier)}.
 *
 * <p>A {@link #spawnProcessFactory} alternative is also available for tests that need to
 * exercise the real ProcessBuilder path against a small shell script — see
 * {@link #spawnProcessFactory} for usage. Both modes are useful so execution tests can pin
 * behaviour without depending on a real OpenCLI binary.
 *
 * @author fengwk
 */
public class FakeOpenCliExecutor implements OpenCliExecutor {

    private final AtomicLong invocationCount = new AtomicLong();
    private Supplier<Behaviour> behaviour = () -> Behaviour.successJson("{}");
    /** Optional factory returning a Process so selected tests can drive the real path. */
    private java.util.function.Function<List<String>, Process> spawnProcessFactory;
    private final java.util.List<Invocation> invocations =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public void setBehavior(Supplier<Behaviour> behaviour) {
        this.behaviour = behaviour;
    }

    public void spawnProcessFactory(java.util.function.Function<List<String>, Process> factory) {
        this.spawnProcessFactory = factory;
    }

    public long invocationCount() {
        return invocationCount.get();
    }

    public List<Invocation> invocations() {
        return java.util.List.copyOf(invocations);
    }

    @Override
    public OpenCliExecutionResult execute(
        HubInstance instance, List<String> hubManagedArgv, long timeoutMillis, String executionId) {
        invocationCount.incrementAndGet();
        Invocation inv = new Invocation(
            instance, java.util.List.copyOf(hubManagedArgv), timeoutMillis, executionId);
        invocations.add(inv);
        if (spawnProcessFactory != null) {
            return executeWithProcessFactory(hubManagedArgv, timeoutMillis, inv);
        }
        Behaviour b = behaviour.get();
        return b == null ? Behaviour.successJson("{}").run(timeoutMillis) : b.run(timeoutMillis);
    }

    private OpenCliExecutionResult executeWithProcessFactory(List<String> hubManagedArgv, long timeoutMillis, Invocation inv) {
        Process process = null;
        try {
            process = spawnProcessFactory.apply(hubManagedArgv);
            boolean finished = process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor();
            }
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());
            OpenCliExecutionResult result = new OpenCliExecutionResult();
            result.setExitCode(finished ? process.exitValue() : 124);
            result.setStdout(stdout);
            result.setStderr(stderr);
            result.setTimedOut(!finished);
            if (!finished) {
                result.setErrorMessage("OpenCLI process exceeded deadline of " + timeoutMillis + " ms");
            }
            return result;
        } catch (InterruptedException ex) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running fake OpenCLI", ex);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String readStream(InputStream stream) {
        try {
            byte[] buf = stream.readAllBytes();
            return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        } catch (java.io.UncheckedIOException ex) {
            throw new IllegalStateException(ex);
        } catch (Exception ex) {
            // AsyncCloseException can leak when the process exits; consume so the test
            // does not see spurious noise.
            return "";
        }
    }

    public static final class Invocation {
        public final HubInstance instance;
        public final List<String> argv;
        public final long timeoutMillis;
        public final String executionId;

        Invocation(HubInstance instance, List<String> argv, long timeoutMillis, String executionId) {
            this.instance = instance;
            this.argv = argv;
            this.timeoutMillis = timeoutMillis;
            this.executionId = executionId;
        }
    }

    /**
     * Configurable behaviour applied per invocation. Built-ins below cover the
     * success/non-zero/invalid-JSON/long-running/throw patterns the execution acceptance
     * criteria require.
     */
    public static final class Behaviour {

        private final int exitCode;
        private final String stdout;
        private final String stderr;
        private final boolean invalidJson;
        private final long delayMillis;
        private final RuntimeException toThrow;

        private Behaviour(int exitCode, String stdout, String stderr, boolean invalidJson,
                          long delayMillis, RuntimeException toThrow) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.invalidJson = invalidJson;
            this.delayMillis = delayMillis;
            this.toThrow = toThrow;
        }

        public static Behaviour successJson(String stdout) {
            return new Behaviour(0, stdout, "", false, 0L, null);
        }

        public static Behaviour failure(int exitCode, String stderr) {
            return new Behaviour(exitCode, "", stderr, false, 0L, null);
        }

        public static Behaviour invalidJson(String stdout) {
            return new Behaviour(0, stdout, "", true, 0L, null);
        }

        public static Behaviour slow(long delayMillis, String stdout) {
            return new Behaviour(0, stdout, "", false, delayMillis, null);
        }

        public static Behaviour throwsOnStart(RuntimeException toThrow) {
            return new Behaviour(0, "", "", false, 0L, toThrow);
        }

        OpenCliExecutionResult run(long timeoutMillis) {
            if (toThrow != null) {
                throw toThrow;
            }
            if (delayMillis > 0L) {
                long effective = timeoutMillis > 0 ? Math.min(delayMillis, timeoutMillis) : delayMillis;
                try {
                    Thread.sleep(effective);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while sleeping", ex);
                }
                // If the requested wait was less than the fake's intended delay, treat as
                // a timeout so the service sees OPENCLI_EXECUTION_TIMEOUT rather than
                // running late.
                if (timeoutMillis > 0 && delayMillis > timeoutMillis) {
                    OpenCliExecutionResult timedOut = new OpenCliExecutionResult();
                    timedOut.setExitCode(124);
                    timedOut.setTimedOut(true);
                    timedOut.setErrorMessage(
                        "OpenCLI process exceeded deadline of " + timeoutMillis + " ms");
                    return timedOut;
                }
            }
            OpenCliExecutionResult result = new OpenCliExecutionResult();
            result.setExitCode(exitCode);
            result.setStdout(stdout);
            result.setStderr(stderr);
            if (exitCode != 0) {
                result.setErrorMessage("OpenCLI exited with code " + exitCode);
            }
            return result;
        }

    }

}
