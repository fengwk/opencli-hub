package fun.fengwk.openclihub.core.opencli.daemon;

import java.util.List;
import lombok.Data;

/**
 * Snapshot of the OpenCLI daemon published at {@code GET /status}.
 *
 * <p>Field shape mirrors the daemon JSON contract observed in
 * {@code opencli/src/daemon.ts}; unknown fields are ignored so the pinned version may grow.
 *
 * @author fengwk
 */
@Data
public class OpenCliDaemonStatus {

    /**
     * Daemon process id. {@code null} when the daemon crashed or has not fully reported back.
     */
    private Long pid;

    /**
     * Seconds since the daemon process started.
     */
    private Double uptime;

    private String daemonVersion;
    private Boolean extensionConnected;
    private String extensionVersion;
    private String extensionCompatRange;
    private String contextId;
    private Boolean profileRequired;
    private Boolean profileDisconnected;
    private List<OpenCliProfileSnapshot> profiles = List.of();
    private Integer pending;
    private Integer commandResultUnknown;
    private Double memoryMB;
    private Integer port;

    public List<String> connectedContextIds() {
        if (profiles == null) {
            return List.of();
        }
        return profiles.stream()
            .map(OpenCliProfileSnapshot::getContextId)
            .filter(id -> id != null && !id.isBlank())
            .toList();
    }

}
