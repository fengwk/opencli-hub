package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntime.HubInstanceProcessKind;
import java.lang.ProcessHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;

/**
 * Polls all tracked OS process handles for each Instance and reports the first unexpected exit.
 *
 * <p>The watcher is invoked once per instance and torn down on {@code stop}/{@code delete}.
 * Tests inject a custom consumer instead of spinning real threads.
 *
 * @author fengwk
 */
@Slf4j
public class HubInstanceUnexpectedExitWatcher implements UnexpectedExitListener, DisposableBean {

    private final BiConsumer<String, String> exitConsumer;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> watched = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> generations = new ConcurrentHashMap<>();
    private final AtomicLong generationSequence = new AtomicLong();

    /**
     * Default production wiring: 1-threaded daemon scheduler; the consumer receives
     * {@code (reason, instanceId)}.
     */
    public HubInstanceUnexpectedExitWatcher() {
        this((reason, instanceId) -> log.warn("instance {} exited unexpectedly: {}", instanceId, reason),
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "opencli-hub-exitwatch");
                t.setDaemon(true);
                return t;
            }));
    }

    /** Test wiring: custom consumer + custom scheduler (often a no-op executor). */
    public HubInstanceUnexpectedExitWatcher(BiConsumer<String, String> exitConsumer,
        ScheduledExecutorService scheduler) {
        this.exitConsumer = exitConsumer;
        this.scheduler = scheduler;
    }

    @Override
    public void watch(String instanceId, HubInstanceRuntime runtime) {
        if (runtime.getProcesses().isEmpty()) {
            return;
        }
        long generation = generationSequence.incrementAndGet();
        generations.put(instanceId, generation);
        ScheduledFuture<?> existing = watched.remove(instanceId);
        if (existing != null) {
            existing.cancel(false);
        }
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(() -> {
            try {
                for (Map.Entry<HubInstanceProcessKind, ProcessHandle> entry
                    : runtime.getProcesses().entrySet()) {
                    if (!entry.getValue().isAlive()) {
                        deliver(instanceId, generation, entry.getKey() + " process exited");
                        return;
                    }
                }
            } catch (Throwable ex) {
                log.debug("watcher poll error for instance {}: {}", instanceId, ex.getMessage());
            }
        }, 250L, 250L, TimeUnit.MILLISECONDS);
        watched.put(instanceId, task);
    }

    @Override
    public void unwatch(String instanceId) {
        generations.remove(instanceId);
        ScheduledFuture<?> task = watched.remove(instanceId);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void deliver(String instanceId, long generation, String reason) {
        if (!generations.remove(instanceId, generation)) {
            return;
        }
        ScheduledFuture<?> task = watched.remove(instanceId);
        if (task != null) {
            task.cancel(false);
        }
        try {
            exitConsumer.accept(reason, instanceId);
        } catch (RuntimeException ex) {
            log.warn("unexpected exit consumer failed for instance {}: {}", instanceId, ex.getMessage());
        }
    }

    @Override
    public void destroy() {
        for (ScheduledFuture<?> f : watched.values()) {
            f.cancel(false);
        }
        watched.clear();
        generations.clear();
        scheduler.shutdownNow();
    }

}
