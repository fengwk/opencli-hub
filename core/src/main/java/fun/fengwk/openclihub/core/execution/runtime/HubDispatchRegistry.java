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
            ignored -> new HubInstanceDispatcher(
                instance.getCode(),
                instance.getMaxConcurrency(),
                instance.getMaxPending()));
    }

    public <T> T executeGuarded(
        String instanceId,
        HubExecutionConcurrencyMode mode,
        long deadlineNanos,
        Callable<T> task) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instanceId);
        if (dispatcher == null) {
            throw HubErrorCodes.INSTANCE_RUNTIME_NOT_FOUND
                .asThrowable("Instance dispatcher is not registered: " + instanceId);
        }
        return dispatcher.executeGuarded(mode, deadlineNanos, task);
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
        return submit(instance, null, task, deadlineNanos, null);
    }

    /**
     * Submit with an owning {@code executionId} and a one-shot {@code onQueuedDiscard}
     * callback fired when the queued task is discarded before running (queue clear,
     * force shutdown, cancel). The execution service uses the callback to persist the
     * execution CANCELLED; the registry itself never touches execution persistence.
     */
    public <T> Future<T> submit(HubInstance instance,
                                String executionId,
                                Callable<T> task,
                                long deadlineNanos,
                                Runnable onQueuedDiscard) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instance.getId());
        if (dispatcher == null) {
            throw HubErrorCodes.INSTANCE_RUNTIME_NOT_FOUND
                .asThrowable("Instance dispatcher is not registered: " + instance.getId());
        }
        return dispatcher.submit(executionId, task, deadlineNanos, onQueuedDiscard);
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

    /**
     * Returns the admission load used by automatic execution routing. Runtime snapshots keep
     * exposing active and pending executor metrics, while routing must count every accepted
     * task until its future reaches {@code done()}.
     */
    public int getRoutingLoad(String instanceId) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instanceId);
        return dispatcher == null ? 0 : dispatcher.acceptedNotTerminalCount();
    }

    public int getMaxConcurrency(String instanceId) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instanceId);
        return dispatcher == null ? 1 : dispatcher.getMaxConcurrency();
    }

    public int getMaxPending(String instanceId) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instanceId);
        return dispatcher == null ? 0 : dispatcher.getMaxPending();
    }

    public int getTotalCapacity(String instanceId) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instanceId);
        return dispatcher == null ? 0 : dispatcher.totalCapacity();
    }

    public void updateLimits(String instanceId, int maxConcurrency, int maxPending) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instanceId);
        if (dispatcher != null) {
            dispatcher.updateLimits(maxConcurrency, maxPending);
        }
    }

    /**
     * Cancel all pending (not running) tasks for an instance. Returns 0 when no
     * dispatcher is registered. Each discarded task notifies its execution owner so
     * the DB rows are persisted CANCELLED.
     */
    public int clearPending(String instanceId) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instanceId);
        if (dispatcher == null) {
            return 0;
        }
        return dispatcher.clearPending();
    }

    /**
     * Cancel a single still-queued execution handle. Returns {@code false} when the
     * dispatcher is not registered or the execution is not queued (running/completed);
     * the DB row must already be terminal in those cases.
     */
    public boolean cancelPending(String instanceId, String executionId) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instanceId);
        if (dispatcher == null) {
            return false;
        }
        return dispatcher.cancelPending(executionId);
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
