package fun.fengwk.openclihub.share.model;

import java.util.List;
import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class HubExecutionRequestDTO {

    private Long instanceId;
    private String commandKey;
    private List<String> argv;
    private Long timeoutMillis;

}
