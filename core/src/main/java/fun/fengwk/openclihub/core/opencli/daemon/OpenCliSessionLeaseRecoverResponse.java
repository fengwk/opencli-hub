package fun.fengwk.openclihub.core.opencli.daemon;

import java.util.Set;
import lombok.Data;

/**
 * Response envelope for {@code POST /session-leases/recover}.
 *
 * @author fengwk
 */
@Data
public class OpenCliSessionLeaseRecoverResponse {

    public static final String RESULT_RECOVERED = "RECOVERED";
    public static final String RESULT_ALREADY_FREE = "ALREADY_FREE";
    public static final String RESULT_STILL_ACTIVE = "STILL_ACTIVE";
    public static final String RESULT_OWNER_CHANGED = "OWNER_CHANGED";
    public static final String RESULT_RESET_FAILED = "RESET_FAILED";

    /**
     * Protocol result tokens accepted from a 2xx recovery response. Unknown values are invalid.
     */
    public static final Set<String> VALID_RESULTS = Set.of(
        RESULT_RECOVERED,
        RESULT_ALREADY_FREE,
        RESULT_STILL_ACTIVE,
        RESULT_OWNER_CHANGED,
        RESULT_RESET_FAILED);

    private Boolean ok;
    private String result;
    private String runId;
    private Boolean tabReset;
    private Integer cancelledPending;
    private String errorCode;
    private String error;
    private String errorHint;

    public static boolean isKnownResult(String result) {
        return result != null && VALID_RESULTS.contains(result);
    }

}
