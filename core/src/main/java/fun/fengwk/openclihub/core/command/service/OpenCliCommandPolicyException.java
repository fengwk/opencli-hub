package fun.fengwk.openclihub.core.command.service;

import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import lombok.Getter;

/**
 * Thrown by the command policy services (blacklist / output rule) when a caller-supplied
 * input is structurally invalid. Mapped to a Hub error code so the web layer can convert
 * the failure into a proper HTTP status.
 *
 * @author fengwk
 */
@Getter
public class OpenCliCommandPolicyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HubErrorCodes errorCode;

    public OpenCliCommandPolicyException(HubErrorCodes errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

}