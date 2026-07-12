package fun.fengwk.openclihub.share.model.command;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for enabling a command blacklist entry.
 *
 * @author fengwk
 */
@Data
public class HubCommandBlacklistUpdateDTO {

    @Size(max = 512)
    private String reason;

}
