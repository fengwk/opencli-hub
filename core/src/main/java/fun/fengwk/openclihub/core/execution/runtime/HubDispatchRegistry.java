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
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/**
 * Dispatcher registry shared by routing and lifecycle management.
 *
 * <p>The registry cooperates with the execution service and lifecycle layer:
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
        return dispatch(instance, task, deadlineNanos, null);
    }

    public <T> T dispatch(HubInstance instance,
                          Callable<T> task,
                          long deadlineNanos,
                          BooleanSupplier stillWanted) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instance.getId());
        if (dispatcher == null) {
            throw HubErrorCodes.INSTANCE_RUNTIME_NOT_FOUND
                .asThrowable("Instance dispatcher is not registered: " + instance.getId());
        }
        return dispatcher.dispatch(task, deadlineNanos, stillWanted);
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

    /**
     * Executes a lifecycle-side operation only when the instance dispatcher is idle. The
     * dispatcher holds its submit lock through the callback, making the guard atomic with submit.
     */
    public <T> T executeWhenIdle(HubInstance instance, Callable<T> task) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instance.getId());
        if (dispatcher == null) {
            throw HubErrorCodes.INSTANCE_RUNTIME_NOT_FOUND
                .asThrowable("Instance dispatcher is not registered: " + instance.getId());
        }
        return dispatcher.executeWhenIdle(task);
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

    /** Applies an editable maxPending change to an already-running instance, if present. */

    /**
     * Cancel all pending (not running) tasks for an instance. Returns 0 when no
     * dispatcher is registered.
     */
    public int clearPending(String instanceId) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instanceId);
        if (dispatcher == null) {
            return 0;
        }
        return dispatcher.clearPending();
    }

    public void updateMaxPending(String instanceId, int maxPending) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instanceId);
        if (dispatcher != null) {
            dispatcher.updateMaxPending(maxPending);
        }
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
