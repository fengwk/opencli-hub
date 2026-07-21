package fun.fengwk.openclihub.share.model.execution;

/**
 * Execution lifecycle state.
 *
 * @author fengwk
 */
public enum HubExecutionStatus {

    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    /** Cancelled while still queued (before opencli started). */
    CANCELLED

}
