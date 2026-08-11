package fun.fengwk.openclihub.core.plugin.repo.impl.model;

import fun.fengwk.automapper.annotation.FieldName;
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
    private String lastStatus;
    private String lastError;
    private LocalDateTime lastSyncedAt;
    private String lastResultJson;
    @FieldName("gmt_create")
    private LocalDateTime createTime;
    @FieldName("gmt_modified")
    private LocalDateTime updateTime;
    private Long version;

}
