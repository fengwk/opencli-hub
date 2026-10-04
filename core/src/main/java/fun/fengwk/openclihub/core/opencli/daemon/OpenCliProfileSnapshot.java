package fun.fengwk.openclihub.core.opencli.daemon;

import java.util.List;
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
    /**
     * Capability tokens advertised by the extension of this profile (for example
     * {@code adapter-tab-reclaim-v1}). Capability support must never be inferred from the
     * extension version.
     */
    private List<String> capabilities = List.of();
    private Integer pending;
    private Long lastSeenAt;

}
