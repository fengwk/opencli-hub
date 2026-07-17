package fun.fengwk.openclihub.share.model.plugin;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * Configured OpenCLI plugin source exposed to the management UI.
 *
 * @author fengwk
 */
@Data
public class HubPluginSourceDTO {

    private String id;
    private String name;
    private String source;
    private List<String> desiredPlugins;
    private boolean enabled;
    private boolean autoUpdate;
    private HubPluginSourceStatus lastStatus;
    private String lastError;
    private LocalDateTime lastSyncedAt;
    private String lastResult;
    private long version;

}
