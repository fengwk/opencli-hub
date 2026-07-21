package fun.fengwk.openclihub.web.controller;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.openclihub.core.execution.service.HubExecutionService;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.execution.HubExecutionDTO;
import fun.fengwk.openclihub.share.model.execution.HubExecutionRequestDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenCLI execution API (scheme B):
 * <ul>
 *   <li>{@code POST /api/opencli/execute} — accept work, return PENDING id immediately,</li>
 *   <li>{@code GET /api/executions/{id}?waitSeconds=N} — optional long-poll,</li>
 *   <li>{@code POST /api/executions/{id}/cancel} — cancel while still PENDING.</li>
 * </ul>
 *
 * @author fengwk
 */
@RequiredArgsConstructor
@RestController
public class HubExecutionController {

    private final HubExecutionService executionService;

    /**
     * Submit an execution. Returns as soon as the row is PENDING and work is enqueued.
     * Poll {@code GET /api/executions/{id}} (optionally with {@code waitSeconds}) for the terminal result.
     */
    @PostMapping("/api/opencli/execute")
    public Result<HubExecutionDTO> execute(@Valid @RequestBody HubExecutionRequestDTO request) {
        return Results.ok(executionService.submit(request));
    }

    @GetMapping("/api/executions")
    public Result<Page<HubExecutionDTO>> page(
        @RequestParam(defaultValue = "1") int pageNumber,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String instanceId) {
        return Results.ok(executionService.page(new PageQuery(pageNumber, pageSize), instanceId));
    }

    /**
     * @param waitSeconds 0 = immediate; up to 120s long-poll until terminal or timeout
     */
    @GetMapping("/api/executions/{id}")
    public Result<HubExecutionDTO> get(
        @PathVariable String id,
        @RequestParam(defaultValue = "0") int waitSeconds) {
        HubExecutionDTO execution = executionService.getById(id, waitSeconds);
        if (execution == null) {
            throw HubErrorCodes.EXECUTION_NOT_FOUND.asThrowable("execution not found: " + id);
        }
        return Results.ok(execution);
    }

    /** Cancel a still-queued execution (PENDING only). */
    @PostMapping("/api/executions/{id}/cancel")
    public Result<HubExecutionDTO> cancel(@PathVariable String id) {
        return Results.ok(executionService.cancel(id));
    }

}
