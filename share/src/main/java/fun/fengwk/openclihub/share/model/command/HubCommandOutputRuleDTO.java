package fun.fengwk.openclihub.share.model.command;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * Managed output argument rule for one canonical command.
 *
 * @author fengwk
 */
@Data
public class HubCommandOutputRuleDTO {

    private long id;
    private String commandKey;
    private String argumentName;
    private HubCommandOutputTargetType targetType;
    private String fileName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
