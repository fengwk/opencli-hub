package fun.fengwk.openclihub.core.execution.repo.impl.model;

import fun.fengwk.automapper.annotation.FieldName;
import fun.fengwk.automapper.annotation.Id;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Persistence model for hub_execution.
 *
 * @author fengwk
 */
@Data
public class HubExecutionDO {

    @Id
    private String id;
    private String instanceId;
    private String instanceCode;
    private String commandKey;
    private String site;
    private String siteSession;
    private String argvJson;
    private boolean reuseInstance;
    private String status;
    private Integer exitCode;
    private String stdoutContent;
    private Boolean stdoutTruncated;
    private String stderrContent;
    private Boolean stderrTruncated;
    private String errorMessage;
    private Long timeoutMillis;
    private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @FieldName("gmt_create")
    private LocalDateTime createTime;
    @FieldName("gmt_modified")
    private LocalDateTime modifiedTime;
    private Long version;

}
