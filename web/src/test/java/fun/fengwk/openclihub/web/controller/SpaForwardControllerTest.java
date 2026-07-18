package fun.fengwk.openclihub.web.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that direct SPA navigation reaches the frontend without swallowing backend paths.
 *
 * @author fengwk
 */
class SpaForwardControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new SpaForwardController())
        .build();

    /** Current BrowserRouter routes must forward to the packaged SPA entry point. */
    @ParameterizedTest
    @ValueSource(strings = {
        "/instances",
        "/instances/1001",
        "/executions",
        "/executions/2001",
        "/commands",
        "/resources",
        "/settings",
        "/plugins",
        "/logs"
    })
    void shouldForwardKnownSpaRoutes(String path) throws Exception {
        mockMvc.perform(get(path))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/index.html"));
    }

    /** API, actuator and asset paths must remain available to their own handlers. */
    @ParameterizedTest
    @ValueSource(strings = {
        "/api/instances",
        "/actuator/health",
        "/assets/index.js"
    })
    void shouldNotCaptureBackendOrAssetPaths(String path) throws Exception {
        mockMvc.perform(get(path))
            .andExpect(status().isNotFound());
    }

}
