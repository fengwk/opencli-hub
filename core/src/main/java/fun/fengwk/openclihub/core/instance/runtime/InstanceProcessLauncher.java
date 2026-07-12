package fun.fengwk.openclihub.core.instance.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Boundary between the Hub lifecycle layer and the underlying process creation mechanism.
 *
 * <p>The launcher is the only component that knows how to spawn {@code Xvfb}, {@code openbox},
 * {@code x11vnc} and {@code google-chrome-stable}. Keeping it isolated means tests drive the
 * lifecycle paths with a deterministic fake.
 *
 * @author fengwk
 */
public interface InstanceProcessLauncher {

    /**
     * Result of spawning a single child process: the live {@link ProcessHandle} plus the
     * absolute log path the launcher is streaming stdout/stderr into. Lifecycle code uses
     * the log path for {@code /api/instances/{id}/logs}.
     */
    class LaunchedProcess {

        public final ProcessHandle process;
        public final Path logPath;

        public LaunchedProcess(ProcessHandle process, Path logPath) {
            this.process = process;
            this.logPath = logPath;
        }

    }

    /**
     * Launches an Xvfb instance for the given display.
     */
    LaunchedProcess launchXvfb(int displayNumber, Path logPath);

    /**
     * Launches an openbox window manager on the given display. This is fire-and-forget — there
     * is no readiness signal in upstream openbox.
     */
    LaunchedProcess launchOpenbox(int displayNumber, Path logPath);

    /**
     * Launches an x11vnc bound to {@code 127.0.0.1:port} on the given display.
     */
    LaunchedProcess launchX11vnc(int displayNumber, int port, Path logPath);

    /**
     * Launches Chrome with the given argv and environment. Caller is expected to have
     * already cleared the profile singleton locks.
     */
    LaunchedProcess launchChrome(List<String> extraArgs, Map<String, String> env, Path logPath);

    /**
     * Stops a previously-started child process using the configured grace period followed
     * by descendant tree destruction. Idempotent — safe to call on already-exited processes.
     */
    void stop(ProcessHandle handle);

}
