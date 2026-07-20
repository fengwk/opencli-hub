package fun.fengwk.openclihub.web.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Bounded pool for {@code POST /api/opencli/execute} async workers.
 *
 * <p>Each accepted execute occupies one pool thread for the whole queue-wait + opencli
 * lifetime. The queue absorbs bursts; overflow fails fast instead of spawning unbounded
 * threads.
 *
 * @author fengwk
 */
@Configuration
public class HubExecutionAsyncConfiguration {

    public static final String EXECUTOR_BEAN_NAME = "hubExecuteTaskExecutor";

    @Bean(name = EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor hubExecuteTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("hub-execute-");
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(64);
        executor.setQueueCapacity(256);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);
        // Fail fast when saturated; controller maps this to a domain error Result.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
