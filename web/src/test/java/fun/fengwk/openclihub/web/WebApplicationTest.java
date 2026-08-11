package fun.fengwk.openclihub.web;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies that the deployable web application can boot with the local H2 profile.
 * This catches module wiring, auto-configuration and SQL resource packaging regressions.
 *
 * @author fengwk
 */
@ActiveProfiles("local-h2")
@SpringBootTest(properties = {
    "OPENCLI_HUB_RESOURCE_MAX_FILE_SIZE=12345",
    "OPENCLI_HUB_RESOURCE_MAX_REQUEST_SIZE=67890",
    "OPENCLI_HUB_VNC_ALLOWED_ORIGINS=https://opencli.example,https://admin.example"
})
class WebApplicationTest {

    @Autowired
    private OpenCliHubProperties hubProperties;

    @Autowired
    private MultipartProperties multipartProperties;

    @Autowired
    @Qualifier("applicationTaskExecutor")
    private ThreadPoolTaskExecutor applicationTaskExecutor;

    @Test
    void contextLoads() {
    }

    /** One byte-valued environment setting must bind identically to core and multipart limits. */
    @Test
    void shouldBindResourceAndMultipartLimitsFromSameValues() {
        assertThat(hubProperties.getResource().getMaxFileSize()).isEqualTo(12345L);
        assertThat(hubProperties.getResource().getMaxRequestSize()).isEqualTo(67890L);
        assertThat(multipartProperties.getMaxFileSize().toBytes()).isEqualTo(12345L);
        assertThat(multipartProperties.getMaxRequestSize().toBytes()).isEqualTo(67890L);
    }

    /** Comma-separated deployment origins must bind as exact list entries for the VNC endpoint. */
    @Test
    void shouldBindMultipleVncAllowedOrigins() {
        assertThat(hubProperties.getVnc().getAllowedOrigins())
            .containsExactly("https://opencli.example", "https://admin.example");
    }

    /**
     * The async execute pool must come from Spring Boot's spring.task.execution
     * configuration (applicationTaskExecutor), replacing the removed custom
     * HubExecutionAsyncConfiguration bean with identical sizing.
     */
    @Test
    void shouldBindApplicationTaskExecutorFromSpringTaskExecutionProperties() {
        assertThat(applicationTaskExecutor.getCorePoolSize()).isEqualTo(8);
        assertThat(applicationTaskExecutor.getMaxPoolSize()).isEqualTo(64);
        assertThat(applicationTaskExecutor.getThreadNamePrefix()).isEqualTo("hub-execute-");
        assertThat(applicationTaskExecutor.getThreadPoolExecutor().getQueue().remainingCapacity())
            .isEqualTo(256);
        assertThat(applicationTaskExecutor.getThreadPoolExecutor().allowsCoreThreadTimeOut())
            .isTrue();
        assertThat(applicationTaskExecutor.getKeepAliveSeconds()).isEqualTo(60L);
    }

}
