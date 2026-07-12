package fun.fengwk.openclihub.share.model.execution;

import fun.fengwk.openclihub.share.model.resource.HubResourceItemDTO;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * Persisted execution details and terminal output.
 *
 * @author fengwk
 */
@Data
public class HubExecutionDTO {

    private long id;
    private Long instanceId;
    private String instanceCode;
    private String commandKey;
    private String site;
    private SiteSessionMode siteSession;
    private boolean reuseInstance;
    private List<String> argv;
    private HubExecutionStatus status;
    private Integer exitCode;
    private String stdout;
    private boolean stdoutTruncated;
    private String stderr;
    private boolean stderrTruncated;
    private String errorMessage;
    private long timeoutMillis;
    private long queuedMillis;
    private long durationMillis;
    private List<HubResourceItemDTO> resources;
    private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

}
