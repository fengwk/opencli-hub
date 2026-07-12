package fun.fengwk.openclihub.share.model;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class HubExecutionDTO {

    private long id;
    private long instanceId;
    private String instanceCode;
    private String commandKey;
    private List<String> argv;
    private HubExecutionStatus status;
    private Integer exitCode;
    private String stdout;
    private String stderr;
    private String errorMessage;
    private long timeoutMillis;
    private long queuedMillis;
    private long durationMillis;
    private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

}
