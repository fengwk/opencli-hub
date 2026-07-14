package fun.fengwk.openclihub.core.instance.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * In-memory runtime for a single browser instance. Mirrors the design contract in
 * {@code docs/technical-design.md §19.1}; volatility is intentional — every field is
 * reconstructed on Hub start.
 *
 * <p>Lifecycle: created during Instance process startup, registered only after Chrome and the
 * Browser Bridge context are ready, and destroyed on {@code stop}, {@code restart},
 * {@code delete}, or failure.
 *
 * @author fengwk
 */
@Data
public class HubInstanceRuntime {

    private String instanceId;
    private String instanceCode;

    /** Allocated X11 display number (>= the configured base). */
    private int displayNumber;

    /** Allocated loopback VNC TCP port (only meaningful between Xvfb alive and stop). */
    private int vncPort;

    /** Absolute path to the on-disk instance directory (chrome / logs / runtime). */
    private String instanceDir;

    /** Lifecycle handles. Each entry maps to a logical child process. */
    private final Map<HubInstanceProcessKind, ProcessHandle> processes = new LinkedHashMap<>();

    /**
     * Records the live {@code contextId} once the extension profile has connected; nullable
     * during the STARTING grace window.
     */
    private volatile String contextId;

    /**
     * Wall-clock time (epoch millis) at which the runtime became RUNNING. May be {@code 0}
     * when the runtime was created but never reached RUNNING.
     */
    private volatile long startedAtMillis;

    /**
     * Ordered shutdown sequence (reverse of start).
     */
    public List<HubInstanceProcessKind> shutdownOrder() {
        return List.of(
            HubInstanceProcessKind.CHROME,
            HubInstanceProcessKind.X11VNC,
            HubInstanceProcessKind.OPENBOX,
            HubInstanceProcessKind.XVFB);
    }

    /**
     * Kinds of processes whose handles the runtime tracks. Order is significant because the
     * reverse list is the teardown order.
     */
    public enum HubInstanceProcessKind {
        XVFB,
        OPENBOX,
        X11VNC,
        CHROME
    }

}
