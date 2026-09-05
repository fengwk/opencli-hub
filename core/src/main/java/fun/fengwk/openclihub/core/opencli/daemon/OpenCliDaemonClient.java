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
     * Issues a compare-and-set session lease recovery request against the daemon.
     *
     * <p>Implementations must include {@code X-OpenCLI: 1}, send JSON, enforce a bounded
     * request timeout, and throw {@link OpenCliDaemonException} on non-2xx or invalid
     * responses. This method must never trigger daemon restart.
     *
     * @throws OpenCliDaemonException when the daemon is unreachable or returns an invalid response
     */
    OpenCliSessionLeaseRecoverResponse recoverSessionLease(OpenCliSessionLeaseRecoverRequest request);

    /**
     * Binds the named daemon session to the active tab in the selected browser profile.
     *
     * <p>The session is the caller's domain contract (the Hub endpoint that owns the binding
     * supplies its fixed session name); the transport never hardcodes a site-specific session.
     *
     * @param contextId the live daemon profile context to route to
     * @param session the adapter session to bind, e.g. {@code site:example}
     * @return the daemon command result; command-level failures are represented by {@code ok=false}
     * @throws OpenCliDaemonException when the daemon is unreachable or returns an invalid response
     */
    OpenCliDaemonCommandResponse bindActiveTab(String contextId, String session);

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
