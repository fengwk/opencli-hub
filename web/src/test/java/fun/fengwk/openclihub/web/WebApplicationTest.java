package fun.fengwk.openclihub.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies that the deployable web application can boot with the local H2 profile.
 * This catches module wiring, auto-configuration and SQL resource packaging regressions.
 *
 * @author fengwk
 */
@ActiveProfiles("local-h2")
@SpringBootTest
class WebApplicationTest {

    @Test
    void contextLoads() {
    }

}
