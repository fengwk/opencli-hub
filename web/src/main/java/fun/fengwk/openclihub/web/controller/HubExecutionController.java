package fun.fengwk.openclihub.web.controller;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.openclihub.core.execution.service.HubExecutionService;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.execution.HubExecutionDTO;
import fun.fengwk.openclihub.share.model.execution.HubExecutionRequestDTO;
import fun.fengwk.openclihub.web.config.HubExecutionAsyncConfiguration;
import jakarta.validation.Valid;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * OpenCLI execution and execution history API.
 *
 * <p>{@code POST /api/opencli/execute} uses servlet async ({@link DeferredResult}) so that
 * client disconnect / async timeout can flip a liveness flag. The dispatcher polls that
 * flag while the task is queued and cancels it before opencli starts when the client is gone.
 *
 * @author fengwk
 */
@RestController
public class HubExecutionController {

    private final HubExecutionService executionService;
    private final OpenCliHubProperties properties;
    private final ThreadPoolTaskExecutor hubExecuteTaskExecutor;

    public HubExecutionController(
        HubExecutionService executionService,
        OpenCliHubProperties properties,
        @Qualifier(HubExecutionAsyncConfiguration.EXECUTOR_BEAN_NAME)
        ThreadPoolTaskExecutor hubExecuteTaskExecutor) {
        this.executionService = executionService;
        this.properties = properties;
        this.hubExecuteTaskExecutor = hubExecuteTaskExecutor;
    }

    @PostMapping("/api/opencli/execute")
    public DeferredResult<Result<HubExecutionDTO>> execute(
        @Valid @RequestBody HubExecutionRequestDTO request) {
        long timeoutMs = properties.getExecution().getMaxTimeoutMillis() + 60_000L;
        DeferredResult<Result<HubExecutionDTO>> deferred = new DeferredResult<>(timeoutMs);
        AtomicBoolean clientOpen = new AtomicBoolean(true);

        deferred.onTimeout(() -> clientOpen.set(false));
        deferred.onError(ex -> clientOpen.set(false));

        try {
            hubExecuteTaskExecutor.execute(() -> {
                try {
                    HubExecutionDTO dto = executionService.execute(request, clientOpen::get);
                    if (!deferred.isSetOrExpired()) {
                        deferred.setResult(Results.ok(dto));
                    }
                } catch (ThrowableConventionErrorCode ex) {
                    // Domain errors as Result keep HTTP status/code stable under DeferredResult.
                    if (!deferred.isSetOrExpired()) {
                        deferred.setResult(Results.error(ex));
                    }
                } catch (Throwable ex) {
                    if (!deferred.isSetOrExpired()) {
                        deferred.setErrorResult(ex);
                    }
                }
            });
        } catch (RejectedExecutionException ex) {
            if (!deferred.isSetOrExpired()) {
                deferred.setResult(Results.error(HubErrorCodes.OPENCLI_EXECUTION_FAILED.asThrowable(
                    "Execute worker pool is saturated")));
            }
        }
        return deferred;
    }

    @GetMapping("/api/executions")
    public Result<Page<HubExecutionDTO>> page(
        @RequestParam(defaultValue = "1") int pageNumber,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String instanceId) {
        return Results.ok(executionService.page(new PageQuery(pageNumber, pageSize), instanceId));
    }

    @GetMapping("/api/executions/{id}")
    public Result<HubExecutionDTO> get(@PathVariable String id) {
        HubExecutionDTO execution = executionService.getById(id);
        if (execution == null) {
            throw HubErrorCodes.EXECUTION_NOT_FOUND.asThrowable("execution not found: " + id);
        }
        return Results.ok(execution);
    }

}
