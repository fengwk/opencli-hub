package fun.fengwk.openclihub.core.opencli.daemon;

import lombok.Data;

/**
 * CAS recovery request body for {@code POST /session-leases/recover}.
 *
 * @author fengwk
 */
@Data
public class OpenCliSessionLeaseRecoverRequest {

    public static final String MODE_CANCEL_AND_RESET = "CANCEL_AND_RESET";

    private String contextId;
    private String surface;
    private String session;
    private String expectedRunId;
    private String mode;
    private String reason;

}
