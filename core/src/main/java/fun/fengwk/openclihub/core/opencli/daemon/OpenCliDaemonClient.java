package fun.fengwk.openclihub.core.opencli.daemon;

/**
 * Boundary contract between the Hub lifecycle layer and the OpenCLI HTTP daemon.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Always include {@code X-OpenCLI: 1} header (the daemon refuses the {@code /status}
 *       endpoint without it).</li>
 *   <li>Treat connection refused and non-2xx responses as {@link OpenCliDaemonException}.</li>
 * </ul>
 *
 * @author fengwk
 */
public interface OpenCliDaemonClient {

    /**
     * Fetches and parses the current authenticated daemon snapshot.
     *
     * @throws OpenCliDaemonException when the daemon is unreachable or returns an invalid response
     */
    OpenCliDaemonStatus fetchStatus();

    /**
     * Ensures the daemon is ready. Behaviour:
     * <ol>
     *   <li>Fetch authenticated {@code /status}; return when it reports a valid pid.</li>
     *   <li>Otherwise run {@code opencli daemon restart}.</li>
     *   <li>Poll authenticated {@code /status} until a valid pid appears.</li>
     * </ol>
     *
     * @throws OpenCliDaemonException if the daemon does not come up within the bootstrap timeout
     */
    void ensureRunning();

}
