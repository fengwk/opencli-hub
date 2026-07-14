package fun.fengwk.openclihub.core.command.service.model;

import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Aggregated output rule domain object held by the command policy service.
 *
 * <p>One rule per canonical command key (uniqueness is enforced by the schema). The
 * associated argument name and target type describe how Hub should materialize the
 * managed output when the command is executed.
 *
 * @author fengwk
 */
@Data
public class HubCommandOutputRule {

    private String id;
    private String commandKey;
    private String argumentName;
    private HubCommandOutputTargetType targetType;
    private String fileName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
