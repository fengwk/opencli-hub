package fun.fengwk.openclihub.core.instance.runtime;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Immutable-at-publication view of volatile instance runtime state.
 *
 * @author fengwk
 */
@AllArgsConstructor
@Data
public class HubInstanceRuntimeSnapshot {

    private boolean registered;
    private Integer displayNumber;
    private Integer vncPort;
    private int activeCount;
    private int pendingCount;

    public static HubInstanceRuntimeSnapshot absent() {
        return new HubInstanceRuntimeSnapshot(false, null, null, 0, 0);
    }

    public int getLoad() {
        return activeCount + pendingCount;
    }

    public boolean isIdle() {
        return getLoad() == 0;
    }

}
