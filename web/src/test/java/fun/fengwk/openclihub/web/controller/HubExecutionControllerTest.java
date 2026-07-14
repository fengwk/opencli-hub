package fun.fengwk.openclihub.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
 * Verifies synchronous execute semantics, history pagination and execution errors.
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

    /** Terminal failure remains HTTP 200 while UUID Instance and Execution IDs stay lossless strings. */
    @Test
    void shouldReturnFailedTerminalExecutionWithHttp200() throws Exception {
        String instanceId = "c39c0ecf-b905-4526-b9b7-0825a16acb14";
        String executionId = "4996df0f-f999-4b54-99a0-5468ba70f12d";
        HubExecutionRequestDTO request = request();
        request.setInstanceId(instanceId);
        HubExecutionDTO execution = execution(executionId, HubExecutionStatus.FAILED);
        when(executionService.execute(any())).thenReturn(execution);

        mockMvc.perform(post("/api/opencli/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(executionId))
            .andExpect(jsonPath("$.data.status").value("FAILED"));
        verify(executionService).execute(argThat(
            submitted -> instanceId.equals(submitted.getInstanceId())));
    }

    /** Bean validation rejects an empty argv before the execution service is called. */
    @Test
    void shouldRejectEmptyArgv() throws Exception {
        HubExecutionRequestDTO request = new HubExecutionRequestDTO();
        request.setArgv(List.of());

        mockMvc.perform(post("/api/opencli/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
        verify(executionService, never()).execute(any());
    }

    /** Core argv validation exceptions retain their specific Hub error code. */
    @Test
    void shouldMapArgvValidationError() throws Exception {
        when(executionService.execute(any())).thenThrow(new OpenCliArgvValidationException(
            HubErrorCodes.OPENCLI_COMMAND_NOT_FOUND, "unknown"));

        mockMvc.perform(post("/api/opencli/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(
                HubErrorCodes.OPENCLI_COMMAND_NOT_FOUND.getCode()));
    }

    /** Queue capacity errors retain HTTP 429 instead of becoming terminal DTOs. */
    @Test
    void shouldMapQueueFullError() throws Exception {
        when(executionService.execute(any()))
            .thenThrow(HubErrorCodes.INSTANCE_QUEUE_FULL.asThrowable("full"));

        mockMvc.perform(post("/api/opencli/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request())))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value(HubErrorCodes.INSTANCE_QUEUE_FULL.getCode()));
    }

    /** Persistence failures remain explicit HTTP 500 domain errors. */
    @Test
    void shouldMapExecutionPersistenceFailure() throws Exception {
        when(executionService.execute(any()))
            .thenThrow(HubErrorCodes.EXECUTION_PERSIST_FAILED.asThrowable("database"));

        mockMvc.perform(post("/api/opencli/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request())))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(
                HubErrorCodes.EXECUTION_PERSIST_FAILED.getCode()));
    }

    /** Page query parameters are translated into convention4j PageQuery exactly once. */
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
            .andExpect(jsonPath("$.data.pageNumber").value(2))
            .andExpect(jsonPath("$.data.results[0].id").value("102"))
            .andExpect(jsonPath("$.data.totalCount").value(6));
    }

    /** Execution detail returns the persisted terminal DTO. */
    @Test
    void shouldReturnExecutionDetail() throws Exception {
        when(executionService.getById("103"))
            .thenReturn(execution("103", HubExecutionStatus.SUCCEEDED));

        mockMvc.perform(get("/api/executions/103"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("103"))
            .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    }

    /** Missing execution details use a dedicated stable 404 code. */
    @Test
    void shouldReturnExecutionNotFound() throws Exception {
        when(executionService.getById("404")).thenReturn(null);

        mockMvc.perform(get("/api/executions/404"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(HubErrorCodes.EXECUTION_NOT_FOUND.getCode()));
    }

    /** Invalid pagination is converted to a uniform 400 Result. */
    @Test
    void shouldRejectInvalidPageNumber() throws Exception {
        mockMvc.perform(get("/api/executions").param("pageNumber", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
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
        return execution;
    }

}
