package fun.fengwk.openclihub.core.instance.repo.impl.model;

import fun.fengwk.automapper.annotation.FieldName;
import fun.fengwk.automapper.annotation.Id;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Persistence model for hub_instance.
 *
 * @author fengwk
 */
@Data
public class HubInstanceDO {

    @Id
    private String id;
    private String code;
    private String displayName;
    private String contextId;
    private String state;
    private String websitesJson;
    private Integer maxPending;
    private String proxyMode;
    private String proxyServer;
    private String lastErrorMessage;
    private LocalDateTime stateChangedAt;
    @FieldName("gmt_create")
    private LocalDateTime createTime;
    @FieldName("gmt_modified")
    private LocalDateTime modifiedTime;
    private Long version;

}
