package fun.fengwk.openclihub.core.runtime;

import fun.fengwk.openclihub.core.model.HubInstance;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * @author fengwk
 */
@Component
public class HubDispatchRegistry {

    private final Map<Long, HubInstanceDispatcher> dispatchers = new ConcurrentHashMap<>();

    public <T> T dispatch(HubInstance instance, Callable<T> callable) {
        return getOrCreateDispatcher(instance).dispatch(callable);
    }

    public HubInstanceRuntimeSnapshot getSnapshot(long instanceId) {
        HubInstanceDispatcher dispatcher = dispatchers.get(instanceId);
        if (dispatcher == null) {
            return HubInstanceRuntimeSnapshot.idle();
        }
        return dispatcher.snapshot();
    }

    public void refresh(HubInstance instance) {
        if (instance == null) {
            return;
        }
        dispatchers.compute(instance.getId(), (instanceId, current) -> {
            if (current == null) {
                return null;
            }
            if (current.getMaxPending() == instance.getMaxPending()) {
                return current;
            }
            if (current.snapshot().getLoad() > 0) {
                return current;
            }
            current.shutdown();
            return new HubInstanceDispatcher(instance.getCode(), instance.getMaxPending());
        });
    }

    public void remove(long instanceId) {
        HubInstanceDispatcher dispatcher = dispatchers.remove(instanceId);
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }

    private HubInstanceDispatcher getOrCreateDispatcher(HubInstance instance) {
        return dispatchers.compute(
            instance.getId(),
            (instanceId, current) -> current == null
                ? new HubInstanceDispatcher(instance.getCode(), instance.getMaxPending())
                : current);
    }

}
