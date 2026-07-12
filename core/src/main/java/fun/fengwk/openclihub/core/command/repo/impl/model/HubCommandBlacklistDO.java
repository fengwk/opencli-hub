package fun.fengwk.openclihub.core.command.repo.impl.model;

import fun.fengwk.automapper.annotation.FieldName;
import fun.fengwk.automapper.annotation.Id;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Persistence model for {@code hub_command_blacklist}.
 *
 * @author fengwk
 */
@Data
public class HubCommandBlacklistDO {

    @Id
    private Long id;
    private String commandKey;
    private String reason;
    @FieldName("gmt_create")
    private LocalDateTime createTime;
    @FieldName("gmt_modified")
    private LocalDateTime modifiedTime;
    private Long version;

}