package fun.fengwk.openclihub.core.plugin.repo.impl.model;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * Persistence row for a configured OpenCLI plugin source.
 *
 * @author fengwk
 */
@Data
public class HubPluginSourceDO {

    private String id;
    private String name;
    private String source;
    private String desiredPluginsJson;
    private Boolean enabled;
    private Boolean autoUpdate;
    private String lastStatus;
    private String lastError;
    private LocalDateTime lastSyncedAt;
    private String lastResultJson;
    private LocalDateTime createTime;
    private LocalDateTime modifiedTime;
    private Long version;

}
