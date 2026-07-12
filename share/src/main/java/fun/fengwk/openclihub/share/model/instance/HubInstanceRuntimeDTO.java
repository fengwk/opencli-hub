package fun.fengwk.openclihub.share.model.instance;

import lombok.Data;

/**
 * Volatile runtime information which is never persisted.
 *
 * @author fengwk
 */
@Data
public class HubInstanceRuntimeDTO {

    private boolean registered;
    private Integer displayNumber;
    private Integer vncPort;
    private int activeCount;
    private int pendingCount;

    public int getLoad() {
        return activeCount + pendingCount;
    }

}
