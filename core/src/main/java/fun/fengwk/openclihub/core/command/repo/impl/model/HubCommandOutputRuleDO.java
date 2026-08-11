package fun.fengwk.openclihub.core.command.repo.impl.model;

import fun.fengwk.automapper.annotation.FieldName;
import fun.fengwk.automapper.annotation.Id;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Persistence model for {@code hub_command_output_rule}.
 *
 * @author fengwk
 */
@Data
public class HubCommandOutputRuleDO {

    @Id
    private String id;
    private String commandKey;
    private String argumentName;
    private String targetType;
    private String fileName;
    @FieldName("gmt_create")
    private LocalDateTime createTime;
    @FieldName("gmt_modified")
    private LocalDateTime updateTime;
    private Long version;

}
