package fun.fengwk.openclihub.core.command.service.model;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * Aggregated blacklist domain object held by the command policy service.
 *
 * <p>This is the persisted domain shape exposed to the executor and to the web layer.
 * It mirrors the {@code hub_command_blacklist} row one-for-one.
 *
 * @author fengwk
 */
@Data
public class HubCommandBlacklist {

    private long id;
    private String commandKey;
    private String reason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}