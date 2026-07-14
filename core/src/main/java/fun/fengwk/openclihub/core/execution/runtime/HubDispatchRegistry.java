package fun.fengwk.openclihub.core.execution.runtime;

import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntimeSnapshot;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import org.springframework.stereotype.Component;

/**
 * Dispatcher registry shared by routing and lifecycle management.
 *
 * <p>The registry cooperates with the M5 execution service and the M4 lifecycle layer:
 * <ul>
 *   <li>{@link #register(HubInstance)} is called by the lifecycle {@code start/create}
 *       path once the runtime has been registered,</li>
 *   <li>{@link #dispatch(HubInstance, Callable, long)} forwards a task with a deadline
 *       budget to the matching per-instance dispatcher,</li>
 *   <li>{@link #unregisterWhenIdle(String)} closes an idle dispatcher atomically against
 *       concurrent submissions.</li>
 * </ul>
 *
 * @author fengwk
 */
@Component
public class HubDispatchRegistry {

    private final Map<String, HubInstanceDispatcher> dispatchers = new ConcurrentHashMap<>();

    public void register(HubInstance instance) {
        if (instance == null) {
            throw new IllegalArgumentException("instance must not be null");
        }
        dispatchers.computeIfAbsent(instance.getId(),
            ignored -> new HubInstanceDispatcher(instance.getCode(), instance.getMaxPending()));
    }

    public <T> T dispatch(HubInstance instance, Callable<T> task, long deadlineNanos) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instance.getId());
        if (dispatcher == null) {
            throw HubErrorCodes.INSTANCE_RUNTIME_NOT_FOUND
                .asThrowable("Instance dispatcher is not registered: " + instance.getId());
        }
        return dispatcher.dispatch(task, deadlineNanos);
    }

    public <T> Future<T> submit(HubInstance instance,
                                Callable<T> task,
                                long deadlineNanos) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instance.getId());
        if (dispatcher == null) {
            throw HubErrorCodes.INSTANCE_RUNTIME_NOT_FOUND
                .asThrowable("Instance dispatcher is not registered: " + instance.getId());
        }
        return dispatcher.submit(task, deadlineNanos);
    }

    public HubInstanceRuntimeSnapshot getSnapshot(String instanceId) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instanceId);
        if (dispatcher == null) {
            return HubInstanceRuntimeSnapshot.absent();
        }
        return new HubInstanceRuntimeSnapshot(
            true,
            null,
            null,
            dispatcher.activeCount(),
            dispatcher.pendingCount());
    }

    public int getMaxPending(String instanceId) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instanceId);
        return dispatcher == null ? 0 : dispatcher.getMaxPending();
    }

    /**
     * Remove the per-instance dispatcher when it has no live or pending work; refuses
     * the removal otherwise (caller's lifecycle lock already verified busy=false, this is
     * the belt-and-braces race guard for sub-millisecond scheduling).
     */
    public boolean unregisterWhenIdle(String instanceId) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instanceId);
        if (dispatcher == null) {
            return true;
        }
        boolean shut = dispatcher.shutdownIfIdle();
        if (shut) {
            dispatchers.remove(instanceId, dispatcher);
        }
        return shut;
    }

    /** Force-remove the dispatcher for unexpected exit, startup rollback or teardown. */
    public void unregister(String instanceId) {
        HubInstanceDispatcher dispatcher = dispatchers.remove(instanceId);
        if (dispatcher != null) {
            dispatcher.shutdownNow();
        }
    }

    @PreDestroy
    void shutdown() {
        for (String instanceId : List.copyOf(dispatchers.keySet())) {
            unregister(instanceId);
        }
    }

}
