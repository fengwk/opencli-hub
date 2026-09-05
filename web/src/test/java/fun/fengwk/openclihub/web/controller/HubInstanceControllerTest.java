package fun.fengwk.openclihub.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.springboot.starter.web.result.WebExceptionResultHandlerAutoConfiguration;
import fun.fengwk.openclihub.core.instance.runtime.HubInstanceLifecycleService;
import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntimeSnapshot;
import fun.fengwk.openclihub.core.instance.service.HubInstanceService;
import fun.fengwk.openclihub.core.instance.service.converter.HubInstanceConverter;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.instance.HubInstanceCreateDTO;
import fun.fengwk.openclihub.share.model.instance.HubInstanceDTO;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import fun.fengwk.openclihub.share.model.instance.HubInstanceUpdateDTO;
import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Covers all Instance REST routes and convention4j domain status mapping.
 */
@ImportAutoConfiguration(WebExceptionResultHandlerAutoConfiguration.class)
@WebMvcTest(
    controllers = HubInstanceController.class,
    properties = "logging.file.path=${java.io.tmpdir}/opencli-hub-web-test")
class HubInstanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private HubInstanceService instanceService;

    @MockitoBean
    private HubInstanceLifecycleService lifecycleService;

    @MockitoBean
    private HubInstanceConverter converter;

    private HubInstance instance;
    private HubInstanceDTO dto;

    @BeforeEach
    void setUp() {
        instance = new HubInstance();
        instance.setId("11");
        instance.setCode("primary");
        instance.setState(HubInstanceState.RUNNING);
        dto = new HubInstanceDTO();
        dto.setId("11");
        dto.setCode("primary");
        dto.setState(HubInstanceState.RUNNING);
        dto.setProxyMode(HubProxyMode.CUSTOM);
        dto.setProxyServer("http://proxy.example:8080");
        HubInstanceRuntimeSnapshot snapshot = new HubInstanceRuntimeSnapshot(
            true, 99, 5900, 0, 0);
        when(lifecycleService.getSnapshot("11")).thenReturn(snapshot);
        when(converter.toDTO(instance, snapshot)).thenReturn(dto);
    }

    /** List and detail responses must include the merged runtime DTO. */
    @Test
    void shouldListAndGetInstances() throws Exception {
        when(instanceService.list()).thenReturn(List.of(instance));
        when(instanceService.get("11")).thenReturn(instance);

        mockMvc.perform(get("/api/instances"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value("11"));
        mockMvc.perform(get("/api/instances/11"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.code").value("primary"))
            .andExpect(jsonPath("$.data.proxyMode").value("CUSTOM"))
            .andExpect(jsonPath("$.data.proxyServer").value("http://proxy.example:8080"));
    }

    /** Create is synchronous and reports HTTP 201 only after lifecycle success. */
    @Test
    void shouldCreateInstanceSynchronously() throws Exception {
        HubInstanceCreateDTO request = new HubInstanceCreateDTO();
        request.setCode("primary");
        request.setDisplayName("Primary");
        request.setWebsites(List.of("bilibili"));
        request.setMaxPending(5);
        request.setProxyMode(HubProxyMode.CUSTOM);
        request.setProxyServer("http://proxy.example:8080");
        when(lifecycleService.create(any())).thenReturn(instance);

        mockMvc.perform(post("/api/instances")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.id").value("11"));
        verify(lifecycleService).create(argThat(value ->
            value.getProxyMode() == HubProxyMode.CUSTOM
                && "http://proxy.example:8080".equals(value.getProxyServer())));
    }

    /** VNC status must expose availability flags without leaking the loopback VNC port. */
    @Test
    void shouldReportVncStatusForAvailableAndUnavailableRuntime() throws Exception {
        when(instanceService.get("11")).thenReturn(instance);

        mockMvc.perform(get("/api/instances/11/vnc/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.instanceId").value("11"))
            .andExpect(jsonPath("$.data.instanceAvailable").value(true))
            .andExpect(jsonPath("$.data.running").value(true))
            .andExpect(jsonPath("$.data.runtimeAvailable").value(true))
            .andExpect(jsonPath("$.data.vncAvailable").value(true))
            .andExpect(jsonPath("$.data.vncPort").doesNotExist());

        instance.setState(HubInstanceState.STOPPED);
        when(lifecycleService.getSnapshot("11")).thenReturn(HubInstanceRuntimeSnapshot.absent());
        mockMvc.perform(get("/api/instances/11/vnc/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.instanceAvailable").value(true))
            .andExpect(jsonPath("$.data.running").value(false))
            .andExpect(jsonPath("$.data.runtimeAvailable").value(false))
            .andExpect(jsonPath("$.data.vncAvailable").value(false));
    }

    /** A missing persisted Instance remains the stable not-found domain error for VNC status. */
    @Test
    void shouldMapNotFoundDomainErrorForVncStatus() throws Exception {
        doThrow(HubErrorCodes.INSTANCE_NOT_FOUND.asThrowable("missing"))
            .when(instanceService).get("12");

        mockMvc.perform(get("/api/instances/12/vnc/status"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(HubErrorCodes.INSTANCE_NOT_FOUND.getCode()));
    }

    /** PUT and lifecycle actions must return the latest Instance snapshot. */
    @Test
    void shouldUpdateStartStopAndRestartInstance() throws Exception {
        HubInstanceUpdateDTO request = new HubInstanceUpdateDTO();
        request.setCode("primary");
        request.setDisplayName("Primary");
        request.setWebsites(List.of("bilibili"));
        request.setMaxPending(5);
        request.setProxyMode(HubProxyMode.DIRECT);
        when(lifecycleService.update(any(String.class), any())).thenReturn(instance);
        when(lifecycleService.start("11")).thenReturn(instance);
        when(instanceService.get("11")).thenReturn(instance);
        doNothing().when(lifecycleService).stop("11");
        doNothing().when(lifecycleService).restart("11");

        mockMvc.perform(put("/api/instances/11")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("11"));
        mockMvc.perform(post("/api/instances/11/start"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/instances/11/stop"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/instances/11/restart"))
            .andExpect(status().isOk());
        verify(lifecycleService).update("11", request);
        verify(instanceService, never()).update(any(String.class), any());
    }

    /** Delete success remains a Result response so frontend unwrapping stays uniform. */
    @Test
    void shouldDeleteInstance() throws Exception {
        doNothing().when(lifecycleService).delete("11");

        mockMvc.perform(delete("/api/instances/11"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
        verify(lifecycleService).delete("11");
    }

    /** Domain errors must retain their declared HTTP status and stable Hub code. */
    @Test
    void shouldMapBusyDomainError() throws Exception {
        doThrow(HubErrorCodes.INSTANCE_BUSY.asThrowable("busy"))
            .when(lifecycleService).stop("11");

        mockMvc.perform(post("/api/instances/11/stop"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(HubErrorCodes.INSTANCE_BUSY.getCode()));
    }


    /** The bind endpoint has no request body and delegates to the lifecycle service for the legacy ChatGPT route. */
    @Test
    void shouldBindActiveTabThroughLegacyChatgptRoute() throws Exception {
        mockMvc.perform(post("/api/instances/11/chatgpt-agent/bind-active-tab"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
        verify(lifecycleService).bindActiveTab("11", "chatgpt-agent");
    }

    /** The bind endpoint supports arbitrary persistent sites. */
    @Test
    void shouldBindActiveTabThroughArbitrarySiteRoute() throws Exception {
        mockMvc.perform(post("/api/instances/11/custom-site/bind-active-tab"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
        verify(lifecycleService).bindActiveTab("11", "custom-site");
    }

    /** Non-persistent or invalid site produces INSTANCE_ARGUMENT_INVALID error. */
    @Test
    void shouldRejectActiveTabBindWhenSiteIsInvalid() throws Exception {
        doThrow(HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("site is not a persistent website: unknown"))
            .when(lifecycleService).bindActiveTab("11", "unknown");

        mockMvc.perform(post("/api/instances/11/unknown/bind-active-tab"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(HubErrorCodes.INSTANCE_ARGUMENT_INVALID.getCode()));
        verify(lifecycleService).bindActiveTab("11", "unknown");
    }

    /** Site not enabled on the instance produces INSTANCE_WEBSITE_NOT_ENABLED error. */
    @Test
    void shouldRejectActiveTabBindWhenWebsiteNotEnabledOnInstance() throws Exception {
        doThrow(HubErrorCodes.INSTANCE_WEBSITE_NOT_ENABLED.asThrowable("instance does not support site: disabled-site"))
            .when(lifecycleService).bindActiveTab("11", "disabled-site");

        mockMvc.perform(post("/api/instances/11/disabled-site/bind-active-tab"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(HubErrorCodes.INSTANCE_WEBSITE_NOT_ENABLED.getCode()));
        verify(lifecycleService).bindActiveTab("11", "disabled-site");
    }

    /** clear-queue drains pending tasks and reports the cancelled count. */
    @Test
    void shouldClearPendingQueue() throws Exception {
        when(lifecycleService.clearPendingQueue("11")).thenReturn(3);

        mockMvc.perform(post("/api/instances/11/clear-queue"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.instanceId").value("11"))
            .andExpect(jsonPath("$.data.clearedCount").value(3));
        verify(lifecycleService).clearPendingQueue("11");
    }

}
