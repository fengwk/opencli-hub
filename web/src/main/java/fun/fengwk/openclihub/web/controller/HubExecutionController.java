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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;

/**
 * OpenCLI execution and execution history API.
 *
 * <p>{@code POST /api/opencli/execute} uses servlet async ({@link DeferredResult}) so that
 * client disconnect / async timeout can flip a liveness flag. The dispatcher polls that
 * flag while the task is queued and cancels it before opencli starts when the client is gone.
 *
 * @author fengwk
 */
@Slf4j
@RestController
public class HubExecutionController {

    private static final String LIVENESS_INTERCEPTOR = "hubExecuteClientLiveness";

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
        @Valid @RequestBody HubExecutionRequestDTO request,
        HttpServletRequest servletRequest) {
        long timeoutMs = properties.getExecution().getMaxTimeoutMillis() + 60_000L;
        DeferredResult<Result<HubExecutionDTO>> deferred = new DeferredResult<>(timeoutMs);
        // finished=true means this request already produced a terminal DeferredResult value.
        // onCompletion also runs after success; only treat incomplete completion as client-gone.
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean clientOpen = new AtomicBoolean(true);

        Runnable markClientGone = () -> {
            if (clientOpen.compareAndSet(true, false)) {
                log.info("Execute client liveness lost (timeout/error/abort); pending work will be cancelled");
            }
        };

        deferred.onTimeout(markClientGone);
        deferred.onError(ex -> markClientGone.run());
        deferred.onCompletion(() -> {
            if (!finished.get()) {
                markClientGone.run();
            }
        });

        // Interceptor sees container timeout/error even when DeferredResult callbacks are late.
        WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(servletRequest);
        asyncManager.registerDeferredResultInterceptor(LIVENESS_INTERCEPTOR,
            new DeferredResultProcessingInterceptor() {
                @Override
                public <T> boolean handleTimeout(NativeWebRequest request, DeferredResult<T> deferredResult) {
                    markClientGone.run();
                    return true;
                }

                @Override
                public <T> boolean handleError(NativeWebRequest request, DeferredResult<T> deferredResult,
                                               Throwable t) {
                    markClientGone.run();
                    return true;
                }

                @Override
                public <T> void afterCompletion(NativeWebRequest request, DeferredResult<T> deferredResult) {
                    if (!finished.get()) {
                        markClientGone.run();
                    }
                }
            });

        try {
            hubExecuteTaskExecutor.execute(() -> {
                try {
                    HubExecutionDTO dto = executionService.execute(request, clientOpen::get);
                    finish(deferred, finished, Results.ok(dto));
                } catch (ThrowableConventionErrorCode ex) {
                    // Domain errors as Result keep HTTP status/code stable under DeferredResult.
                    finish(deferred, finished, Results.error(ex));
                } catch (Throwable ex) {
                    finished.set(true);
                    if (!deferred.isSetOrExpired()) {
                        deferred.setErrorResult(ex);
                    }
                }
            });
        } catch (RejectedExecutionException ex) {
            finish(deferred, finished, Results.error(HubErrorCodes.OPENCLI_EXECUTION_FAILED.asThrowable(
                "Execute worker pool is saturated")));
        }
        return deferred;
    }

    private static <T> void finish(DeferredResult<Result<T>> deferred,
                                   AtomicBoolean finished,
                                   Result<T> result) {
        finished.set(true);
        if (!deferred.isSetOrExpired()) {
            deferred.setResult(result);
        }
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
