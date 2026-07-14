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
 * Synchronous OpenCLI execution and execution history API.
 *
 * @author fengwk
 */
@RequiredArgsConstructor
@RestController
public class HubExecutionController {

    private final HubExecutionService executionService;

    @PostMapping("/api/opencli/execute")
    public Result<HubExecutionDTO> execute(
        @Valid @RequestBody HubExecutionRequestDTO request) {
        return Results.ok(executionService.execute(request));
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
