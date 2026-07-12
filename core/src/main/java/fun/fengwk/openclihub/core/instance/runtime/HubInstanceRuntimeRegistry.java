package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntime.HubInstanceProcessKind;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Thread-safe in-memory registry of all live {@link HubInstanceRuntime}s.
 *
 * <p>Provides two concurrency primitives:
 * <ul>
 *   <li>Per-instance lifecycle lock — serialises {@code start}, {@code stop}, {@code restart}
 *       against each other so a single Hub instance never runs two simultaneous flows.</li>
 *   <li>Registry map — keyed by {@code instanceId}, holding the runtime.</li>
 * </ul>
 *
 * <p>The dispatch registry (queueing) lives next door and is owned by the lifecycle service —
 * this class only tracks volatile processes and allocates resources.
 *
 * @author fengwk
 */
@Slf4j
@Component
public class HubInstanceRuntimeRegistry {

    private final Map<Long, HubInstanceRuntime> runtimes = new ConcurrentHashMap<>();
    private final Map<Long, ReentrantLock> lifecycleLocks = new ConcurrentHashMap<>();
    private final InstanceProcessLauncher launcher;
    private final HubInstanceAllocationService allocationService;
    private final UnexpectedExitListener unexpectedExitListener;

    public HubInstanceRuntimeRegistry(
        InstanceProcessLauncher launcher,
        HubInstanceAllocationService allocationService,
        UnexpectedExitListener unexpectedExitListener) {
        this.launcher = launcher;
        this.allocationService = allocationService;
        this.unexpectedExitListener = unexpectedExitListener;
    }

    public ReentrantLock lifecycleLock(long instanceId) {
        return lifecycleLocks.computeIfAbsent(instanceId, ignored -> new ReentrantLock());
    }

    /** Records a runtime under the registry and rejects duplicate live registrations. */
    public void register(HubInstanceRuntime runtime) {
        HubInstanceRuntime existing = runtimes.putIfAbsent(runtime.getInstanceId(), runtime);
        if (existing != null) {
            throw new IllegalStateException(
                "runtime already registered for instance " + runtime.getInstanceId());
        }
    }

    /** Drops a runtime from the registry. Idempotent. */
    public void unregister(long instanceId) {
        HubInstanceRuntime runtime = runtimes.remove(instanceId);
        if (runtime != null) {
            allocationService.release(new HubInstanceAllocationService.Allocation(
                runtime.getDisplayNumber(), runtime.getVncPort()));
        }
    }

    public HubInstanceRuntime get(long instanceId) {
        return runtimes.get(instanceId);
    }

    public List<HubInstanceRuntime> list() {
        return List.copyOf(runtimes.values());
    }

    public boolean contains(long instanceId) {
        return runtimes.containsKey(instanceId);
    }

    /**
     * Stops the runtime's processes in reverse of {@link HubInstanceRuntime#shutdownOrder()}
     * and disengages the unexpected exit watcher. Designed to be called from both the
     * lifecycle service (planned) and the unexpected exit path (unplanned).
     */
    public void stopProcesses(HubInstanceRuntime runtime) {
        long instanceId = runtime.getInstanceId();
        unexpectedExitListener.unwatch(instanceId);
        for (HubInstanceProcessKind kind : runtime.shutdownOrder()) {
            ProcessHandle handle = runtime.getProcesses().get(kind);
            if (handle == null) {
                continue;
            }
            try {
                launcher.stop(handle);
            } catch (RuntimeException ex) {
                log.warn("Failed to stop {} for instance {}: {}", kind, instanceId, ex.getMessage());
            }
        }
    }

    /**
     * Stops every registered runtime's processes in reverse order. Used by Hub shutdown.
     */
    public void stopAll() {
        for (HubInstanceRuntime runtime : List.copyOf(runtimes.values())) {
            stopProcesses(runtime);
            unregister(runtime.getInstanceId());
        }
    }

    @PreDestroy
    void shutdown() {
        stopAll();
        lifecycleLocks.clear();
    }

    public HubInstanceAllocationService allocationService() {
        return allocationService;
    }

    public UnexpectedExitListener unexpectedExitListener() {
        return unexpectedExitListener;
    }

}
