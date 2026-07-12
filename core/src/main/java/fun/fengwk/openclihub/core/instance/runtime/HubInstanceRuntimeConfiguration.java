package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.openclihub.core.opencli.daemon.HttpOpenCliDaemonClient;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliDaemonClient;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.ObjLongConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Wires runtime-layer components that need composition:
 * <ul>
 *   <li>{@link HubInstanceUnexpectedExitWatcher} — receives a lifecycle consumer to break the
 *       circular dependency between the watcher and {@link HubInstanceLifecycleService}.</li>
 *   <li>{@link ProfileSingletonCleaner} — pure helper.</li>
 *   <li>{@link HubInstanceAllocationService} — depends on properties only.</li>
 * </ul>
 *
 * @author fengwk
 */
@Slf4j
@Configuration
public class HubInstanceRuntimeConfiguration {

    @Bean
    public ProfileSingletonCleaner profileSingletonCleaner() {
        return new ProfileSingletonCleaner();
    }

    @Bean
    public HubInstanceAllocationService hubInstanceAllocationService(OpenCliHubProperties props) {
        return new HubInstanceAllocationService(props);
    }

    /**
     * Production launcher: real {@link ProcessBuilder} spawning Xvfb / openbox / x11vnc /
     * google-chrome-stable. Tests provide their own {@link InstanceProcessLauncher} bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public InstanceProcessLauncher instanceProcessLauncher(OpenCliHubProperties props) {
        return new ProcessBuilderInstanceProcessLauncher(props);
    }

    /**
     * Production OpenCLI daemon client: JDK {@code HttpClient} + Jackson. Tests provide
     * their own implementation.
     */
    @Bean
    @ConditionalOnMissingBean
    public OpenCliDaemonClient openCliDaemonClient(OpenCliHubProperties props) {
        return new HttpOpenCliDaemonClient(props);
    }

    /**
     * Production wiring: real scheduled executor; the consumer is wired lazily to the
     * lifecycle service so the bean graph can complete construction without a cycle.
     * Tests inject a deterministic listener directly.
     */
    @Bean
    @ConditionalOnMissingBean(UnexpectedExitListener.class)
    public HubInstanceUnexpectedExitWatcher hubInstanceUnexpectedExitWatcher(
        @Lazy HubInstanceLifecycleServiceConsumer lifecycleService) {
        ObjLongConsumer<String> consumer = (reason, instanceId) -> {
            log.warn("instance {} exited unexpectedly: {}", instanceId, reason);
            lifecycleService.markUnexpectedExit(instanceId, reason);
        };
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
            r -> {
                Thread t = new Thread(r, "opencli-hub-exitwatch");
                t.setDaemon(true);
                return t;
            });
        return new HubInstanceUnexpectedExitWatcher(consumer, scheduler);
    }

}
