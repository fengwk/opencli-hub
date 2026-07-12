package fun.fengwk.openclihub.share.model.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for creating or replacing a managed command output rule.
 *
 * @author fengwk
 */
@Data
public class HubCommandOutputRuleUpdateDTO {

    @NotBlank
    @Size(max = 64)
    private String argumentName;

    @NotNull
    private HubCommandOutputTargetType targetType;

    @Size(max = 255)
    private String fileName;

}
