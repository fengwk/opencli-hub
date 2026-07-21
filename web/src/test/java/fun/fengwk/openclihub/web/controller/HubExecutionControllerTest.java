package fun.fengwk.openclihub.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.DefaultPage;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.springboot.starter.web.result.WebExceptionResultHandlerAutoConfiguration;
import fun.fengwk.openclihub.core.command.validator.OpenCliArgvValidationException;
import fun.fengwk.openclihub.core.execution.service.HubExecutionService;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.execution.HubExecutionDTO;
import fun.fengwk.openclihub.share.model.execution.HubExecutionRequestDTO;
import fun.fengwk.openclihub.share.model.execution.HubExecutionStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies async submit / long-poll get / cancel API surface.
 */
@ImportAutoConfiguration(WebExceptionResultHandlerAutoConfiguration.class)
@WebMvcTest(
    controllers = HubExecutionController.class,
    properties = "logging.file.path=${java.io.tmpdir}/opencli-hub-web-test")
class HubExecutionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private HubExecutionService executionService;

    @Test
    void shouldSubmitAndReturnPending() throws Exception {
        HubExecutionDTO pending = execution("e1", HubExecutionStatus.PENDING);
        when(executionService.submit(any())).thenReturn(pending);

        mockMvc.perform(post("/api/opencli/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("e1"))
            .andExpect(jsonPath("$.data.status").value("PENDING"));
        verify(executionService).submit(any());
    }

    @Test
    void shouldRejectEmptyArgv() throws Exception {
        HubExecutionRequestDTO request = new HubExecutionRequestDTO();
        request.setArgv(List.of());

        mockMvc.perform(post("/api/opencli/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
        verify(executionService, never()).submit(any());
    }

    @Test
    void shouldMapArgvValidationError() throws Exception {
        when(executionService.submit(any())).thenThrow(new OpenCliArgvValidationException(
            HubErrorCodes.OPENCLI_COMMAND_NOT_FOUND, "unknown"));

        mockMvc.perform(post("/api/opencli/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(HubErrorCodes.OPENCLI_COMMAND_NOT_FOUND.getCode()));
    }

    @Test
    void shouldLongPollGet() throws Exception {
        when(executionService.getById("e1", 30))
            .thenReturn(execution("e1", HubExecutionStatus.SUCCEEDED));

        mockMvc.perform(get("/api/executions/e1").param("waitSeconds", "30"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    }

    @Test
    void shouldCancelPending() throws Exception {
        when(executionService.cancel("e1"))
            .thenReturn(execution("e1", HubExecutionStatus.CANCELLED));

        mockMvc.perform(post("/api/executions/e1/cancel"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void shouldPageExecutions() throws Exception {
        HubExecutionDTO execution = execution("102", HubExecutionStatus.SUCCEEDED);
        when(executionService.page(eq(new PageQuery(2, 5)), eq("11")))
            .thenReturn(new DefaultPage<>(2, 5, List.of(execution), 6));

        mockMvc.perform(get("/api/executions")
                .param("pageNumber", "2")
                .param("pageSize", "5")
                .param("instanceId", "11"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.results[0].id").value("102"));
    }

    @Test
    void shouldReturnExecutionNotFound() throws Exception {
        when(executionService.getById("404", 0)).thenReturn(null);

        mockMvc.perform(get("/api/executions/404"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(HubErrorCodes.EXECUTION_NOT_FOUND.getCode()));
    }

    private static HubExecutionRequestDTO request() {
        HubExecutionRequestDTO request = new HubExecutionRequestDTO();
        request.setArgv(List.of("bilibili", "hot"));
        request.setTimeoutMillis(1_000L);
        return request;
    }

    private static HubExecutionDTO execution(String id, HubExecutionStatus status) {
        HubExecutionDTO execution = new HubExecutionDTO();
        execution.setId(id);
        execution.setStatus(status);
        execution.setInstanceId("c39c0ecf-b905-4526-b9b7-0825a16acb14");
        execution.setInstanceCode("primary");
        execution.setCommandKey("bilibili/hot");
        execution.setSite("bilibili");
        execution.setArgv(List.of("bilibili", "hot"));
        execution.setTimeoutMillis(1_000L);
        return execution;
    }
}
