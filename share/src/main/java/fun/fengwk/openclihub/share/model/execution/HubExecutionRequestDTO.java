package fun.fengwk.openclihub.share.model.execution;

import java.util.List;
import lombok.Data;

/**
 * Controlled OpenCLI execution request.
 *
 * @author fengwk
 */
@Data
public class HubExecutionRequestDTO {

    private Long instanceId;
    private List<String> argv;
    private Long timeoutMillis;

}
