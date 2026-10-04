package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.openclihub.core.instance.service.HubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Periodic sweep that reclaims idle adapter tabs for every RUNNING instance whose continuous
 * idle time reached its {@code warmTabTtlSeconds}.
 *
 * <p>The sweep itself is stateless: per-instance eligibility, idempotency for the current
 * idle period, and the busy guard all live in {@link HubInstanceLifecycleService#reclaimIdleTabs}
 * and the per-instance dispatcher, so this component only sequences the passes. A failure for
 * one instance is logged and never stops the others. The fixed short interval is intentionally
 * not configurable: TTL {@code 0} is defined to take effect on the next sweep.
 *
 * <p>The scheduler is owned by this bean; {@link #destroy()} stops it on Hub shutdown.
 *
 * @author fengwk
 */
@Slf4j
@Component
public class HubInstanceIdleTabReclaimer implements InitializingBean, DisposableBean {

    /** Fixed sweep period; TTL 0 becomes effective on the next sweep. */
    static final long SWEEP_INTERVAL_MILLIS = 1000L;

    private final HubInstanceService instanceService;
    private final HubInstanceLifecycleService lifecycleService;
    private final ScheduledExecutorService scheduler;

    /**
     * Production constructor. {@code @Autowired} is required because the package-private
     * test constructor below gives Spring two candidates to choose from.
     */
    @Autowired
    public HubInstanceIdleTabReclaimer(
        HubInstanceService instanceService,
        HubInstanceLifecycleService lifecycleService) {
        this(instanceService, lifecycleService, newScheduler());
    }

    /** Package-private test wiring with a caller-owned scheduler. */
    HubInstanceIdleTabReclaimer(
        HubInstanceService instanceService,
        HubInstanceLifecycleService lifecycleService,
        ScheduledExecutorService scheduler) {
        this.instanceService = instanceService;
        this.lifecycleService = lifecycleService;
        this.scheduler = scheduler;
    }

    @Override
    public void afterPropertiesSet() {
        scheduler.scheduleWithFixedDelay(
            this::sweepQuietly, SWEEP_INTERVAL_MILLIS, SWEEP_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException ex) {
            log.warn("idle adapter tab reclaim sweep failed: {}", ex.getMessage());
        }
    }

    /**
     * Runs a single reclaim pass over the persisted instances. Exposed for deterministic tests;
     * each instance is isolated so one failure never affects the others.
     */
    public void sweep() {
        List<HubInstance> instances;
        try {
            instances = instanceService.list();
        } catch (RuntimeException ex) {
            log.warn("idle adapter tab reclaim could not list instances: {}", ex.getMessage());
            return;
        }
        for (HubInstance instance : instances) {
            try {
                lifecycleService.reclaimIdleTabs(instance.getId());
            } catch (RuntimeException ex) {
                log.warn("idle adapter tab reclaim failed for instance {}: {}",
                    instance.getId(), ex.getMessage());
            }
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "opencli-hub-idle-tab-reclaimer");
            thread.setDaemon(true);
            return thread;
        });
    }

}
