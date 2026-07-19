package fun.fengwk.openclihub.core.execution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.openclihub.core.command.service.HubCommandBlacklistService;
import fun.fengwk.openclihub.core.command.service.HubCommandOutputRuleService;
import fun.fengwk.openclihub.core.command.service.model.HubCommandBlacklist;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.core.command.validator.NormalizedOpenCliArgv;
import fun.fengwk.openclihub.core.command.validator.OpenCliArgvValidator;
import fun.fengwk.openclihub.core.execution.executor.OpenCliExecutionResult;
import fun.fengwk.openclihub.core.execution.executor.OpenCliExecutor;
import fun.fengwk.openclihub.core.execution.repo.HubExecutionRepository;
import fun.fengwk.openclihub.core.execution.runtime.HubDispatchRegistry;
import fun.fengwk.openclihub.core.execution.service.converter.HubExecutionConverter;
import fun.fengwk.openclihub.core.execution.service.model.HubExecution;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.core.resource.model.HubExecutionResourceGroup;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.execution.HubExecutionDTO;
import fun.fengwk.openclihub.share.model.execution.HubExecutionRequestDTO;
import fun.fengwk.openclihub.share.model.execution.HubExecutionStatus;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import fun.fengwk.openclihub.share.model.resource.HubResourceItemDTO;
import fun.fengwk.openclihub.share.util.HubIds;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Synchronous execution orchestration from validated request to persisted terminal result.
 *
 * @author fengwk
 */
@Slf4j
@Service
public class HubExecutionService {

    private final OpenCliArgvValidator argvValidator;
    private final HubCommandBlacklistService blacklistService;
    private final HubCommandOutputRuleService outputRuleService;
    private final HubExecutionRouter router;
    private final HubDispatchRegistry dispatchRegistry;
    private final HubExecutionRepository executionRepository;
    private final HubExecutionArgvBuilder argvBuilder;
    private final HubExecutionResources executionResources;
    private final OpenCliExecutor executor;
    private final HubExecutionConverter converter;
    private final ObjectMapper objectMapper;
    private final OpenCliHubProperties properties;

    public HubExecutionService(
        OpenCliArgvValidator argvValidator,
        HubCommandBlacklistService blacklistService,
        HubCommandOutputRuleService outputRuleService,
        HubExecutionRouter router,
        HubDispatchRegistry dispatchRegistry,
        HubExecutionRepository executionRepository,
        HubExecutionArgvBuilder argvBuilder,
        HubExecutionResources executionResources,
        OpenCliExecutor executor,
        HubExecutionConverter converter,
        ObjectMapper objectMapper,
        OpenCliHubProperties properties) {
        this.argvValidator = argvValidator;
        this.blacklistService = blacklistService;
        this.outputRuleService = outputRuleService;
        this.router = router;
        this.dispatchRegistry = dispatchRegistry;
        this.executionRepository = executionRepository;
        this.argvBuilder = argvBuilder;
        this.executionResources = executionResources;
        this.executor = executor;
        this.converter = converter;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Executes a controlled OpenCLI command. Validation and route/queue prechecks happen
     * before the PENDING row is inserted. After insertion, command failures and deadlines
     * are represented by a terminal DTO so callers retain the execution id and diagnostics.
     */
    public HubExecutionDTO execute(HubExecutionRequestDTO request) {
        validateRequest(request);
        NormalizedOpenCliArgv normalized = argvValidator.validate(request.getArgv());
        rejectBlacklisted(normalized.getCanonicalKey());
        HubCommandOutputRule outputRule = outputRuleService
            .findByCommandKey(normalized.getCanonicalKey()).orElse(null);
        argvBuilder.assertNoCallerOutputArgument(normalized, outputRule);

        long timeoutMillis = resolveTimeout(request.getTimeoutMillis());
        HubInstance instance = router.chooseInstance(
            normalized.getCommand().getSite(), request.getInstanceId());
        HubExecutionDeadline deadline = HubExecutionDeadline.fromNow(timeoutMillis);

        HubExecution execution = newExecution(request, normalized, instance, timeoutMillis);
        persistAdd(execution);
        log.info(
            "Execution accepted id={} command={} site={} instanceId={} instanceCode={} timeoutMillis={} reuseInstance={}",
            execution.getId(),
            execution.getCommandKey(),
            execution.getSite(),
            instance.getId(),
            instance.getCode(),
            timeoutMillis,
            execution.isReuseInstance());

        ExecutionOutcome outcome;
        try {
            outcome = dispatchRegistry.dispatch(
                instance,
                () -> runOnInstance(execution, instance, normalized, outputRule, deadline),
                deadline.deadlineNanos());
        } catch (RuntimeException ex) {
            if (isError(ex, HubErrorCodes.EXECUTION_PERSIST_FAILED)) {
                throw ex;
            }
            log.warn(
                "Execution dispatch failed id={} command={} instanceId={} error={}",
                execution.getId(),
                execution.getCommandKey(),
                instance.getId(),
                ex.getMessage());
            return terminalAfterDispatchFailure(execution, ex);
        }
        log.info(
            "Execution finished id={} command={} instanceId={} status={} exitCode={} durationMillis={}",
            execution.getId(),
            execution.getCommandKey(),
            instance.getId(),
            execution.getStatus(),
            execution.getExitCode(),
            execution.getDurationMillis());
        return converter.toDTO(execution, outcome.resources());
    }

    public HubExecutionDTO getById(String id) {
        if (!HubIds.isSupported(id)) {
            return null;
        }
        HubExecution execution = executionRepository.findById(id);
        if (execution == null) {
            return null;
        }
        return converter.toDTO(execution, executionResources.scanExisting(id));
    }

    public Page<HubExecutionDTO> page(PageQuery pageQuery, String instanceId) {
        return executionRepository.page(pageQuery, instanceId)
            .map(execution -> converter.toDTO(execution, List.of()));
    }

    private ExecutionOutcome runOnInstance(
        HubExecution execution,
        HubInstance instance,
        NormalizedOpenCliArgv normalized,
        HubCommandOutputRule outputRule,
        HubExecutionDeadline deadline) {
        execution.markRunning(LocalDateTime.now());
        persistUpdate(execution);

        HubExecutionResources.ResourceContext resourceContext = null;
        List<HubResourceItemDTO> resources = List.of();
        try {
            long remainingMillis = deadline.remainingMillis();
            if (remainingMillis <= 0) {
                OpenCliExecutionResult timeout = timedOutResult("Execution deadline elapsed before OpenCLI start");
                execution.markFinished(timeout, LocalDateTime.now());
                persistUpdate(execution);
                return new ExecutionOutcome(resources);
            }

            resourceContext = executionResources.prepare(execution.getId(), normalized, outputRule);
            NormalizedOpenCliArgv substituted = new NormalizedOpenCliArgv(
                normalized.getCommand(),
                normalized.getCanonicalKey(),
                normalized.getPositionalValues(),
                normalized.getNamedValues(),
                resourceContext.getSubstitutedArgv());
            List<String> managedArgv = argvBuilder.build(
                instance,
                substituted,
                outputRule,
                argvBuilder.resolveManagedOutputPath(
                    outputRule,
                    resourceContext.getGroup() == null
                        ? null : resourceContext.getGroup().getRealPath()));

            remainingMillis = deadline.remainingMillis();
            OpenCliExecutionResult result = remainingMillis <= 0
                ? timedOutResult("Execution deadline elapsed before OpenCLI start")
                : executor.execute(instance, managedArgv, remainingMillis, execution.getId());
            validateJsonOutput(result);
            execution.markFinished(result, LocalDateTime.now());
        } catch (RuntimeException ex) {
            execution.markFinished(failedResult(ex), LocalDateTime.now());
        } finally {
            HubExecutionResourceGroup group = resourceContext == null ? null : resourceContext.getGroup();
            if (resourceContext != null) {
                try {
                    resources = executionResources.scan(group);
                } catch (RuntimeException ex) {
                    recordResourceScanFailure(execution, ex);
                    log.warn("Failed to scan resources for execution {}: {}",
                        execution.getId(), message(ex));
                }
                try {
                    resourceContext.close();
                } catch (RuntimeException ex) {
                    log.warn("Failed to release resources for execution {}: {}",
                        execution.getId(), message(ex));
                }
                executionResources.removeGroupIfEmpty(group);
            }
        }
        persistUpdate(execution);
        return new ExecutionOutcome(resources);
    }

    private static void recordResourceScanFailure(
        HubExecution execution, RuntimeException failure) {
        String scanError = "Failed to scan execution resources: " + message(failure);
        if (execution.getStatus() == HubExecutionStatus.SUCCEEDED) {
            execution.setStatus(HubExecutionStatus.FAILED);
            execution.setErrorMessage(scanError);
            execution.setFinishedAt(LocalDateTime.now());
        } else if (execution.getErrorMessage() == null || execution.getErrorMessage().isBlank()) {
            execution.setErrorMessage(scanError);
        } else {
            execution.setErrorMessage(execution.getErrorMessage() + "; " + scanError);
        }
    }

    private HubExecutionDTO terminalAfterDispatchFailure(HubExecution execution, RuntimeException failure) {
        if (isError(failure, HubErrorCodes.QUEUE_WAIT_TIMEOUT)) {
            execution.markFinished(timedOutResult("Execution deadline elapsed while queued"),
                LocalDateTime.now());
        } else {
            execution.markFinished(failedResult(failure), LocalDateTime.now());
        }
        persistUpdate(execution);
        return converter.toDTO(execution, List.of());
    }

    private HubExecution newExecution(
        HubExecutionRequestDTO request,
        NormalizedOpenCliArgv normalized,
        HubInstance instance,
        long timeoutMillis) {
        HubExecution execution = new HubExecution();
        execution.setId(generateExecutionId());
        execution.setInstanceId(instance.getId());
        execution.setInstanceCode(instance.getCode());
        execution.setCommandKey(normalized.getCanonicalKey());
        execution.setSite(normalized.getCommand().getSite());
        SiteSessionMode sessionMode = Optional.ofNullable(normalized.getCommand().getSiteSession())
            .orElse(SiteSessionMode.EPHEMERAL);
        execution.setSiteSession(sessionMode);
        execution.setArgv(normalized.getNormalizedArgv());
        execution.setReuseInstance(
            sessionMode == SiteSessionMode.PERSISTENT && request.getInstanceId() == null);
        execution.setStatus(HubExecutionStatus.PENDING);
        execution.setTimeoutMillis(timeoutMillis);
        execution.setQueuedAt(LocalDateTime.now());
        return execution;
    }

    private void validateJsonOutput(OpenCliExecutionResult result) {
        if (result == null) {
            throw HubErrorCodes.OPENCLI_EXECUTION_FAILED.asThrowable(
                "OpenCLI executor returned no result");
        }
        if (result.isTimedOut()) {
            if (result.getErrorMessage() == null || result.getErrorMessage().isBlank()) {
                result.setErrorMessage("OpenCLI execution exceeded its deadline");
            }
            return;
        }
        if (result.getExitCode() != 0) {
            if (result.getErrorMessage() == null || result.getErrorMessage().isBlank()) {
                result.setErrorMessage("OpenCLI exited with code " + result.getExitCode());
            }
            return;
        }
        try {
            var json = objectMapper.readTree(result.getStdout());
            if (json == null || json.isMissingNode()) {
                throw new IllegalArgumentException("empty JSON output");
            }
        } catch (Exception ex) {
            result.setExitCode(1);
            result.setErrorMessage(HubErrorCodes.OPENCLI_INVALID_JSON_OUTPUT.getCode()
                + ": stdout is not valid JSON");
        }
    }

    private void rejectBlacklisted(String commandKey) {
        Optional<HubCommandBlacklist> blacklisted = blacklistService.findByCommandKey(commandKey);
        if (blacklisted.isPresent()) {
            String reason = blacklisted.get().getReason();
            throw HubErrorCodes.OPENCLI_COMMAND_BLACKLISTED.asThrowable(
                reason == null || reason.isBlank()
                    ? "Command is blacklisted: " + commandKey
                    : reason);
        }
    }

    private long resolveTimeout(Long requested) {
        long timeout = requested == null
            ? properties.getExecution().getDefaultTimeoutMillis() : requested;
        if (timeout <= 0 || timeout > properties.getExecution().getMaxTimeoutMillis()) {
            throw HubErrorCodes.EXECUTION_TIMEOUT_OUT_OF_RANGE.asThrowable(
                "timeoutMillis must be in (0, "
                    + properties.getExecution().getMaxTimeoutMillis() + "]");
        }
        return timeout;
    }

    private static void validateRequest(HubExecutionRequestDTO request) {
        if (request == null || request.getArgv() == null || request.getArgv().isEmpty()) {
            throw HubErrorCodes.INVALID_EXECUTION_REQUEST.asThrowable(
                "execution request and argv are required");
        }
        if (request.getInstanceId() != null && !HubIds.isSupported(request.getInstanceId())) {
            throw HubErrorCodes.INVALID_EXECUTION_REQUEST.asThrowable(
                "instanceId must be a UUID or a migrated positive-long id");
        }
    }

    private String generateExecutionId() {
        try {
            return executionRepository.generateId();
        } catch (RuntimeException ex) {
            throw HubErrorCodes.EXECUTION_PERSIST_FAILED.asThrowable(
                ex, "Failed to generate execution id");
        }
    }

    private void persistAdd(HubExecution execution) {
        try {
            if (!executionRepository.add(execution)) {
                throw HubErrorCodes.EXECUTION_PERSIST_FAILED.asThrowable(
                    "Failed to insert execution " + execution.getId());
            }
        } catch (RuntimeException ex) {
            if (isError(ex, HubErrorCodes.EXECUTION_PERSIST_FAILED)) {
                throw ex;
            }
            throw HubErrorCodes.EXECUTION_PERSIST_FAILED.asThrowable(
                ex, "Failed to insert execution " + execution.getId());
        }
    }

    private void persistUpdate(HubExecution execution) {
        try {
            if (!executionRepository.update(execution)) {
                throw HubErrorCodes.EXECUTION_PERSIST_FAILED.asThrowable(
                    "Failed to update execution " + execution.getId()
                        + " to " + execution.getStatus());
            }
        } catch (RuntimeException ex) {
            if (isError(ex, HubErrorCodes.EXECUTION_PERSIST_FAILED)) {
                throw ex;
            }
            throw HubErrorCodes.EXECUTION_PERSIST_FAILED.asThrowable(
                ex, "Failed to update execution " + execution.getId()
                    + " to " + execution.getStatus());
        }
    }

    private static OpenCliExecutionResult timedOutResult(String errorMessage) {
        OpenCliExecutionResult result = new OpenCliExecutionResult();
        result.setExitCode(124);
        result.setTimedOut(true);
        result.setErrorMessage(errorMessage);
        return result;
    }

    private static OpenCliExecutionResult failedResult(RuntimeException failure) {
        OpenCliExecutionResult result = new OpenCliExecutionResult();
        result.setExitCode(1);
        result.setErrorMessage(message(failure));
        return result;
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName() : message;
    }

    private static boolean isError(Throwable failure, HubErrorCodes expected) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ThrowableConventionErrorCode domain
                && expected.getCode().equals(domain.getCode())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record ExecutionOutcome(List<HubResourceItemDTO> resources) {
    }

}
