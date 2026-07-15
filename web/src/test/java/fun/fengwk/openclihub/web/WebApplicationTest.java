package fun.fengwk.openclihub.web;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;
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
    "OPENCLI_HUB_RESOURCE_MAX_REQUEST_SIZE=67890"
})
class WebApplicationTest {

    @Autowired
    private OpenCliHubProperties hubProperties;

    @Autowired
    private MultipartProperties multipartProperties;

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

}
