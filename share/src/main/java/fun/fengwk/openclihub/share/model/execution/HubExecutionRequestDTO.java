package fun.fengwk.openclihub.share.model.execution;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.Data;

/**
 * Controlled OpenCLI execution request.
 *
 * @author fengwk
 */
@Data
public class HubExecutionRequestDTO {

    @Positive
    private Long instanceId;

    @NotEmpty
    private List<String> argv;

    @Positive
    private Long timeoutMillis;

}
