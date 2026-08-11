package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * ApplicationRunner that submits single-threaded instance recovery without blocking readiness.
 *
 * <p>Ordering (must match design §17):
 * <ol>
 *   <li>{@link OrphanInstanceScanner#scan()} — reconcile on-disk orphan directories.</li>
 *   <li>{@link HubInstanceLifecycleService#normalizeAllStatesToStarting()} — drop any stale
 *       RUNNING so the UI doesn't see a fake running Instance.</li>
 *   <li>{@link HubInstanceLifecycleService#recoverAll(List)} — start each instance in creation order
 *       on a single-thread executor; failures are isolated and do not block Spring ready.</li>
 * </ol>
 *
 * <p>The whole sweep runs inside the coordinator's recovery barrier: the barrier is declared
 * synchronously before {@code run(ApplicationArguments)} returns (so API start/create/restart
 * are held back even while the recovery task is still queued) and is released by the sweep's
 * {@code finally}, by submit-failure handling and by {@link #destroy()} — it can never stay
 * active forever.
 *
 * <p>The executor is owned by this bean so {@code DisposableBean#destroy} can shut it down
 * during Hub shutdown; running threads are daemon threads, so a stuck lifecycle flow will not
 * prevent JVM exit.
 *
 * @author fengwk
 */
@Slf4j
@Component
public class HubInstanceRuntimeApplicationRunner implements ApplicationRunner, DisposableBean {

    private final HubInstanceLifecycleService lifecycleService;
    private final OrphanInstanceScanner orphanScanner;
    private final OpenCliHubProperties properties;
    private final HubInstanceStartCoordinator startCoordinator;
    private final ExecutorService recoveryExecutor;

    public HubInstanceRuntimeApplicationRunner(
        HubInstanceLifecycleService lifecycleService,
        OrphanInstanceScanner orphanScanner,
        OpenCliHubProperties properties,
        HubInstanceStartCoordinator startCoordinator) {
        this.lifecycleService = lifecycleService;
        this.orphanScanner = orphanScanner;
        this.properties = properties;
        this.startCoordinator = startCoordinator;
        this.recoveryExecutor = Executors.newSingleThreadExecutor(recoveryThreadFactory());
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getRuntime().isStartupRecoveryEnabled()) {
            log.info("Hub instance startup recovery is disabled");
            return;
        }
        // Announce the barrier synchronously BEFORE returning: from this moment on API
        // start/create/restart wait (bounded) instead of racing the not-yet-started sweep.
        startCoordinator.beginRecovery();
        try {
            recoveryExecutor.submit(this::runRecovery);
        } catch (RuntimeException ex) {
            startCoordinator.completeRecovery();
            log.error("failed to submit startup recovery; recovery barrier released", ex);
            throw ex;
        }
    }

    private void runRecovery() {
        try {
            OrphanInstanceScanner.Result result = orphanScanner.scan();
            log.info("Hub startup: orphan scan complete, normalising instance states");
            lifecycleService.normalizeAllStatesToStarting();
            List<HubInstance> instances = lifecycleService.listInstancesOrderedByCreationTime();
            log.info("Hub startup: starting recovery for {} instances", instances.size());
            lifecycleService.recoverAll(instances);
            log.info("Hub recovery complete: creatingOrphanDeleted={} creatingMarkerRemoved={} "
                + "managedOrphanDeleted={} unsafeNameProtected={}",
                result.creatingOrphanDeleted, result.creatingMarkerRemoved,
                result.managedOrphanDeleted, result.unsafeNameProtected);
        } catch (RuntimeException ex) {
            log.error("Hub recovery sweep failed: {}", ex.getMessage(), ex);
        } finally {
            // Success and failure both release the barrier; idempotent.
            startCoordinator.completeRecovery();
        }
    }

    @Override
    public void destroy() {
        recoveryExecutor.shutdown();
        try {
            // Allow in-flight start() to finish or interrupt.
            if (!recoveryExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                recoveryExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            recoveryExecutor.shutdownNow();
        }
        // Covers destroy-before-start (the queued task was cancelled and never ran its
        // finally) and interrupted termination: the barrier must not stay active forever.
        startCoordinator.completeRecovery();
    }

    private static ThreadFactory recoveryThreadFactory() {
        return r -> {
            Thread t = new Thread(r, "opencli-hub-recovery");
            t.setDaemon(true);
            return t;
        };
    }

}
