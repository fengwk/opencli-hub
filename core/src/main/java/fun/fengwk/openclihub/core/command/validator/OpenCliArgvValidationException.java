package fun.fengwk.openclihub.core.command.validator;

import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import lombok.Getter;

/**
 * Thrown when the supplied argv cannot be safely forwarded to OpenCLI. The error code
 * is mapped to a Hub {@link HubErrorCodes} constant so the web layer can convert the
 * failure into an HTTP status without leaking parser internals.
 *
 * @author fengwk
 */
@Getter
public class OpenCliArgvValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HubErrorCodes errorCode;
    private final transient Object problem;

    public OpenCliArgvValidationException(HubErrorCodes errorCode, String message) {
        this(errorCode, message, null);
    }

    public OpenCliArgvValidationException(HubErrorCodes errorCode, String message, Object problem) {
        super(message);
        this.errorCode = errorCode;
        this.problem = problem;
    }

}
