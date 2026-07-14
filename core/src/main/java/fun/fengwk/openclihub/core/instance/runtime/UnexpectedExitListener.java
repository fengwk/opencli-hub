package fun.fengwk.openclihub.core.instance.runtime;

/**
 * Receives "the runtime of instance X just exited unexpectedly" callbacks.
 *
 * <p>Implemented by {@link HubInstanceUnexpectedExitWatcher}, which polls every tracked child
 * process. The lifecycle layer is registered as the consumer through constructor wiring,
 * avoiding a setter-based circular dependency.
 *
 * @author fengwk
 */
public interface UnexpectedExitListener {

    /** Begin watching all tracked processes of an instance. Idempotent. */
    void watch(String instanceId, HubInstanceRuntime runtime);

    /** Cancel any in-flight watcher for the instance. Idempotent. */
    void unwatch(String instanceId);

}
