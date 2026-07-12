package fun.fengwk.openclihub.core.opencli.daemon;

/**
 * Exception thrown by {@link OpenCliDaemonClient} when the daemon is unreachable or returns
 * an unexpected response. Lifecycle code maps it to {@code INSTANCE_START_FAILED}.
 *
 * @author fengwk
 */
public class OpenCliDaemonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OpenCliDaemonException(String message) {
        super(message);
    }

    public OpenCliDaemonException(String message, Throwable cause) {
        super(message, cause);
    }

}
