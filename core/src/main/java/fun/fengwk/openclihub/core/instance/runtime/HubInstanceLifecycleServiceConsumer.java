package fun.fengwk.openclihub.core.instance.runtime;

/**
 * Narrow lifecycle callback consumed by unexpected-exit watcher configuration to avoid a
 * direct setter-based cycle with the lifecycle service.
 *
 * <p>Implemented by {@link HubInstanceLifecycleService}.
 *
 * @author fengwk
 */
public interface HubInstanceLifecycleServiceConsumer {

    /**
     * Marks the instance as ERROR and removes its runtime + dispatcher bindings. Called by
     * the {@link UnexpectedExitListener} chain.
     */
    void markUnexpectedExit(long instanceId, String reason);

}
