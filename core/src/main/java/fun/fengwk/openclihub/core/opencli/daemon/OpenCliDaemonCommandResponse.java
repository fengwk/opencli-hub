package fun.fengwk.openclihub.core.opencli.daemon;

import lombok.Data;

/**
 * Result returned by the daemon {@code POST /command} endpoint.
 *
 * <p>The daemon reports command-level failures in a successful HTTP response, so callers must
 * inspect {@link #getOk()} instead of treating HTTP 2xx as a successful bind.
 *
 * @author fengwk
 */
@Data
public class OpenCliDaemonCommandResponse {

    private String id;
    private Boolean ok;
    private Object data;
    private String errorCode;
    private String error;
    private String errorHint;

}
