package fun.fengwk.openclihub.share.model.instance;

import lombok.Data;

/**
 * Result of draining pending (not yet running) executions from an instance queue.
 *
 * @author fengwk
 */
@Data
public class HubInstanceQueueClearResultDTO {

    private String instanceId;
    /** Number of pending tasks cancelled and rejected. Active running task is not included. */
    private int clearedCount;
}
