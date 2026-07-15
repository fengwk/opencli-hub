package fun.fengwk.openclihub.web.controller;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.WebExceptionResultHandlerAutoConfiguration;
import fun.fengwk.openclihub.core.settings.service.HubSystemSettingsService;
import fun.fengwk.openclihub.core.settings.service.converter.HubSystemSettingsConverter;
import fun.fengwk.openclihub.core.settings.service.model.HubSystemSettings;
import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Pins the management API for global browser proxy settings. */
@Import(HubSystemSettingsConverter.class)
@ImportAutoConfiguration(WebExceptionResultHandlerAutoConfiguration.class)
@WebMvcTest(
    controllers = HubSystemSettingsController.class,
    properties = "logging.file.path=${java.io.tmpdir}/opencli-hub-web-test")
class HubSystemSettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HubSystemSettingsService settingsService;

    @Test
    void shouldGetGlobalSettings() throws Exception {
        when(settingsService.get()).thenReturn(settings(
            HubProxyMode.CUSTOM, "http://proxy.example:8080"));

        mockMvc.perform(get("/api/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.proxyMode").value("CUSTOM"))
            .andExpect(jsonPath("$.data.proxyServer").value("http://proxy.example:8080"));
    }

    @Test
    void shouldUpdateGlobalSettings() throws Exception {
        when(settingsService.update(org.mockito.ArgumentMatchers.any())).thenReturn(
            settings(HubProxyMode.CUSTOM, "socks5://proxy.example:1080"));

        mockMvc.perform(put("/api/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"proxyMode":"CUSTOM","proxyServer":"socks5://proxy.example:1080"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.proxyMode").value("CUSTOM"))
            .andExpect(jsonPath("$.data.proxyServer").value("socks5://proxy.example:1080"));

        verify(settingsService).update(argThat(request ->
            request.getProxyMode() == HubProxyMode.CUSTOM
                && "socks5://proxy.example:1080".equals(request.getProxyServer())));
    }

    private static HubSystemSettings settings(HubProxyMode mode, String server) {
        HubSystemSettings settings = new HubSystemSettings();
        settings.setProxyMode(mode);
        settings.setProxyServer(server);
        return settings;
    }

}
