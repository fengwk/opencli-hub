package fun.fengwk.openclihub.core.opencli.daemon;

import lombok.Data;

/**
 * One daemon-owned session lease as published in {@code GET /status.sessionLeases}.
 *
 * <p>The daemon is the sole truth source for lease ownership and state. Hub may only request
 * recovery against an entry whose identity fields and {@code runId} still match.
 *
 * @author fengwk
 */
@Data
public class OpenCliSessionLease {

    private String contextId;
    private String surface;
    private String session;
    private String runId;
    private String command;
    private Long pid;
    private String owner;
    private Long startedAt;
    private Long lastSeenAt;
    private Integer pendingCount;
    /**
     * Lease fence state. Known values are {@code ACTIVE} and {@code RECOVERING}.
     */
    private String state;

}
