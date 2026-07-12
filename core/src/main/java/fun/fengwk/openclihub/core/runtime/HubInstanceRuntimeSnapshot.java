package fun.fengwk.openclihub.core.runtime;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Data
public class HubInstanceRuntimeSnapshot {

    private int activeCount;
    private int pendingCount;

    public static HubInstanceRuntimeSnapshot idle() {
        return new HubInstanceRuntimeSnapshot(0, 0);
    }

    public boolean isIdle() {
        return activeCount == 0 && pendingCount == 0;
    }

    public int getLoad() {
        return activeCount + pendingCount;
    }

}
