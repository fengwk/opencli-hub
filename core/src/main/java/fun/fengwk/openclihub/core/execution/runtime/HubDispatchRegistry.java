package fun.fengwk.openclihub.core.execution.runtime;

import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntimeSnapshot;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Dispatcher registry shared by routing and lifecycle management.
 *
 * @author fengwk
 */
@Component
public class HubDispatchRegistry {

    private final Map<Long, HubInstanceDispatcher> dispatchers = new ConcurrentHashMap<>();

    public void register(HubInstance instance) {
        dispatchers.computeIfAbsent(
            instance.getId(),
            ignored -> new HubInstanceDispatcher(instance.getCode(), instance.getMaxPending()));
    }

    public <T> T dispatch(HubInstance instance, Callable<T> task) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instance.getId());
        if (dispatcher == null) {
            throw new IllegalStateException("Instance dispatcher is not registered: " + instance.getId());
        }
        return dispatcher.dispatch(task);
    }

    public HubInstanceRuntimeSnapshot getSnapshot(long instanceId) {
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

    public void remove(long instanceId) {
        HubInstanceDispatcher dispatcher = dispatchers.remove(instanceId);
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }

}
