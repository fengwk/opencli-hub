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
 *   <li>{@link HubInstanceLifecycleService#recoverAll(List)} — start each instance in id order
 *       on a single-thread executor; failures are isolated and do not block Spring ready.</li>
 * </ol>
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
    private final ExecutorService recoveryExecutor;

    public HubInstanceRuntimeApplicationRunner(
        HubInstanceLifecycleService lifecycleService,
        OrphanInstanceScanner orphanScanner,
        OpenCliHubProperties properties) {
        this.lifecycleService = lifecycleService;
        this.orphanScanner = orphanScanner;
        this.properties = properties;
        this.recoveryExecutor = Executors.newSingleThreadExecutor(recoveryThreadFactory());
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getRuntime().isStartupRecoveryEnabled()) {
            log.info("Hub instance startup recovery is disabled");
            return;
        }
        recoveryExecutor.submit(this::runRecovery);
    }

    private void runRecovery() {
        try {
            OrphanInstanceScanner.Result result = orphanScanner.scan();
            log.info("Hub startup: orphan scan complete, normalising instance states");
            lifecycleService.normalizeAllStatesToStarting();
            List<HubInstance> instances = lifecycleService.listInstancesOrderedById();
            log.info("Hub startup: starting recovery for {} instances", instances.size());
            lifecycleService.recoverAll(instances);
            log.info("Hub recovery complete: creatingOrphanDeleted={} creatingMarkerRemoved={} "
                + "numericOrphanDeleted={} nonNumericProtected={}",
                result.creatingOrphanDeleted, result.creatingMarkerRemoved,
                result.numericOrphanDeleted, result.nonNumericProtected);
        } catch (RuntimeException ex) {
            log.error("Hub recovery sweep failed: {}", ex.getMessage(), ex);
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
    }

    private static ThreadFactory recoveryThreadFactory() {
        return r -> {
            Thread t = new Thread(r, "opencli-hub-recovery");
            t.setDaemon(true);
            return t;
        };
    }

}
