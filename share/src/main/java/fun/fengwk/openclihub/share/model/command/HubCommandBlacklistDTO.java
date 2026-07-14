package fun.fengwk.openclihub.share.model.command;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * Global command blacklist entry.
 *
 * @author fengwk
 */
@Data
public class HubCommandBlacklistDTO {

    private String id;
    private String commandKey;
    private String reason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
