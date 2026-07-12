package fun.fengwk.openclihub.core.opencli.daemon;

import lombok.Data;

/**
 * Single connected Browser Bridge extension profile as reported by the daemon.
 *
 * @author fengwk
 */
@Data
public class OpenCliProfileSnapshot {

    private String contextId;
    private Boolean extensionConnected;
    private String extensionVersion;
    private String extensionCompatRange;
    private Integer pending;
    private Long lastSeenAt;

}
