package fun.fengwk.openclihub.core.plugin.service.model;

import fun.fengwk.openclihub.share.model.plugin.HubPluginSourceStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Domain aggregate for one configured OpenCLI plugin source.
 *
 * @author fengwk
 */
@Data
public class HubPluginSource {

    private String id;
    private String name;
    private String source;
    private List<String> desiredPlugins = new ArrayList<>();
    private boolean enabled = true;
    private boolean autoUpdate;
    private HubPluginSourceStatus lastStatus = HubPluginSourceStatus.IDLE;
    private String lastError;
    private LocalDateTime lastSyncedAt;
    private String lastResult;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private long version;

    public void setDesiredPlugins(List<String> desiredPlugins) {
        this.desiredPlugins = desiredPlugins == null ? new ArrayList<>() : new ArrayList<>(desiredPlugins);
    }

}
