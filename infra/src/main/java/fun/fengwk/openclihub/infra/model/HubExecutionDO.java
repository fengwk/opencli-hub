package fun.fengwk.openclihub.infra.model;

import fun.fengwk.convention4j.springboot.starter.persistence.ConventionDO;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class HubExecutionDO extends ConventionDO<Long> {

    private Long instanceId;
    private String instanceCode;
    private String commandKey;
    private String argvJson;
    private String status;
    private Integer exitCode;
    private String stdoutContent;
    private String stderrContent;
    private String errorMessage;
    private Long timeoutMillis;
    private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

}
