package fun.fengwk.openclihub.core.execution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.openclihub.core.command.service.HubCommandBlacklistService;
import fun.fengwk.openclihub.core.command.service.HubManagedOutputArguments;
import fun.fengwk.openclihub.core.command.service.HubCommandOutputRuleService;
import fun.fengwk.openclihub.core.command.service.model.HubCommandBlacklist;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.core.command.validator.NormalizedOpenCliArgv;
import fun.fengwk.openclihub.core.command.validator.OpenCliArgvValidator;
import fun.fengwk.openclihub.core.execution.executor.OpenCliExecutionResult;
import fun.fengwk.openclihub.core.execution.executor.OpenCliExecutor;
import fun.fengwk.openclihub.core.execution.repo.HubExecutionRepository;
import fun.fengwk.openclihub.core.execution.runtime.HubDispatchRegistry;
import fun.fengwk.openclihub.core.execution.runtime.HubExecutionConcurrencyMode;
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
import fun.fengwk.openclihub.share.util.HubIds;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Execution orchestration: async submit + DB-backed cancel + optional long-poll get.
 *
 * <p>Scheme B (KISS):
 * <ul>
 *   <li>{@link #submit} validates, persists PENDING, enqueues work, returns immediately,</li>
 *   <li>{@link #cancel} marks PENDING as CANCELLED in DB,</li>
 *   <li>worker CAS PENDING→RUNNING before opencli; cancelled rows never start,</li>
 *   <li>{@link #getById(String, int)} supports long-poll via {@code waitSeconds}.</li>
 * </ul>
 *
 * @author fengwk
 */
@Slf4j
@Service
public class HubExecutionService {

    private static final int MAX_WAIT_SECONDS = 120;
    private static final Set<HubExecutionStatus> TERMINAL = Set.of(
        HubExecutionStatus.SUCCEEDED,
        HubExecutionStatus.FAILED,
        HubExecutionStatus.TIMED_OUT,
        HubExecutionStatus.CANCELLED);

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
    private final Clock clock;

    /**
     * Serialises the local route/persist/enqueue admission boundary. The lock is released
     * immediately after the dispatcher accepts or rejects the task; the actual OpenCLI work
     * always runs outside this critical section.
     */
    private final ReentrantLock admissionLock = new ReentrantLock();

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
        OpenCliHubProperties properties,
        Clock clock) {
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
        this.clock = clock;
    }

    /**
     * Accept an execution: persist PENDING, enqueue on the instance dispatcher, return immediately.
     */
    public HubExecutionDTO submit(HubExecutionRequestDTO request) {
        validateRequest(request);
        NormalizedOpenCliArgv normalized = argvValidator.validate(request.getArgv());
        rejectBlacklisted(normalized.getCanonicalKey());
        HubCommandOutputRule outputRule = resolveEffectiveOutputRule(normalized);
        argvBuilder.assertNoCallerOutputArgument(normalized, outputRule);

        HubExecutionConcurrencyMode concurrencyMode = HubExecutionConcurrencyClassifier.classify(
            normalized.getCommand(), outputRule);

        long timeoutMillis = resolveTimeout(request.getTimeoutMillis());
        HubInstance instance;
        HubExecution execution;
        String executionId;
        RuntimeException dispatchFailure = null;

        admissionLock.lock();
        try {
            instance = router.chooseInstance(
                normalized.getCommand().getSite(), request.getInstanceId());
            final HubInstance admittedInstance = instance;
            final HubExecutionDeadline deadline = HubExecutionDeadline.fromNow(timeoutMillis);

            execution = newExecution(request, normalized, admittedInstance, timeoutMillis);
            persistAdd(execution);
            log.info(
                "Execution submitted id={} command={} site={} instanceId={} instanceCode={} timeoutMillis={}",
                execution.getId(),
                execution.getCommandKey(),
                execution.getSite(),
                admittedInstance.getId(),
                admittedInstance.getCode(),
                timeoutMillis);

            executionId = execution.getId();
            final String admittedExecutionId = executionId;
            try {
                // Enqueue without FutureTask-level deadline abort (that would complete the Future
                // exceptionally without our terminal DB write). Worker checks deadline itself.
                // onQueuedDiscard persists CANCELLED when the queue handle is discarded before
                // running (clear queue, force shutdown, client cancel) so a dropped handle can
                // never leave the DB row PENDING.
                dispatchRegistry.submit(
                    admittedInstance,
                    admittedExecutionId,
                    () -> {
                        try {
                            if (System.nanoTime() >= deadline.deadlineNanos()) {
                                terminalAfterDispatchFailure(
                                    admittedExecutionId,
                                    HubErrorCodes.QUEUE_WAIT_TIMEOUT.asThrowable(
                                        "Execution deadline elapsed while queued"));
                                return null;
                            }
                            runOnInstance(
                                admittedExecutionId,
                                admittedInstance,
                                normalized,
                                outputRule,
                                deadline,
                                concurrencyMode);
                        } catch (RuntimeException ex) {
                            if (isError(ex, HubErrorCodes.EXECUTION_PERSIST_FAILED)) {
                                log.error("Execution persist failed in worker id={}", admittedExecutionId, ex);
                            } else {
                                log.warn(
                                    "Execution worker failed id={} error={}",
                                    admittedExecutionId,
                                    ex.getMessage());
                                terminalAfterDispatchFailure(admittedExecutionId, ex);
                            }
                        }
                        return null;
                    },
                    Long.MAX_VALUE,
                    () -> persistQueuedTaskDiscard(admittedExecutionId));
            } catch (RuntimeException ex) {
                if (isError(ex, HubErrorCodes.EXECUTION_PERSIST_FAILED)) {
                    throw ex;
                }
                dispatchFailure = ex;
            }
        } finally {
            admissionLock.unlock();
        }

        if (dispatchFailure != null) {
            log.warn(
                "Execution enqueue failed id={} command={} instanceId={} error={}",
                execution.getId(),
                execution.getCommandKey(),
                instance.getId(),
                dispatchFailure.getMessage());
            return terminalAfterDispatchFailure(executionId, dispatchFailure);
        }
        return converter.toDTO(execution, List.of());
    }

    /**
     * Backward-compatible helper used by tests: submit then long-poll until terminal or timeout.
     */
    public HubExecutionDTO execute(HubExecutionRequestDTO request) {
        HubExecutionDTO submitted = submit(request);
        long waitSeconds = Math.min(
            MAX_WAIT_SECONDS,
            Math.max(1L, (submitted.getTimeoutMillis() + 60_000L) / 1000L));
        // For long executes, loop long-poll windows until terminal or overall deadline.
        long deadlineMs = System.currentTimeMillis() + submitted.getTimeoutMillis() + 60_000L;
        HubExecutionDTO latest = submitted;
        while (System.currentTimeMillis() < deadlineMs) {
            latest = getById(submitted.getId(), (int) Math.min(waitSeconds, MAX_WAIT_SECONDS));
            if (latest == null || isTerminal(latest.getStatus())) {
                return latest;
            }
        }
        return getById(submitted.getId(), 0);
    }

    public HubExecutionDTO getById(String id) {
        return getById(id, 0);
    }

    /**
     * Load execution detail. When {@code waitSeconds > 0}, block until terminal status or timeout.
     */
    public HubExecutionDTO getById(String id, int waitSeconds) {
        if (!HubIds.isSupported(id)) {
            return null;
        }
        int wait = Math.max(0, Math.min(waitSeconds, MAX_WAIT_SECONDS));
        long deadlineMs = System.currentTimeMillis() + wait * 1000L;
        while (true) {
            HubExecution execution = executionRepository.findById(id);
            if (execution == null) {
                return null;
            }
            if (isTerminal(execution.getStatus()) || wait == 0
                || System.currentTimeMillis() >= deadlineMs) {
                return converter.toDTO(execution, executionResources.scanExisting(id));
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return converter.toDTO(execution, executionResources.scanExisting(id));
            }
        }
    }

    public Page<HubExecutionDTO> page(PageQuery pageQuery, String instanceId) {
        return executionRepository.page(pageQuery, instanceId)
            .map(execution -> converter.toDTO(execution, List.of()));
    }

    /**
     * Cancel a still-queued execution by CAS PENDING → CANCELLED, then release the
     * matching dispatcher queue handle so the slot frees immediately and the worker
     * never attempts a doomed PENDING→RUNNING CAS.
     */
    public HubExecutionDTO cancel(String id) {
        if (!HubIds.isSupported(id)) {
            throw HubErrorCodes.EXECUTION_NOT_FOUND.asThrowable("execution not found: " + id);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (executionRepository.markCancelledIfPending(id, "Cancelled by client", now)) {
            log.info("Execution cancelled id={}", id);
            HubExecution cancelled = executionRepository.findById(id);
            if (cancelled != null) {
                dispatchRegistry.cancelPending(cancelled.getInstanceId(), id);
            }
            return converter.toDTO(cancelled, List.of());
        }
        HubExecution execution = executionRepository.findById(id);
        if (execution == null) {
            throw HubErrorCodes.EXECUTION_NOT_FOUND.asThrowable("execution not found: " + id);
        }
        if (isTerminal(execution.getStatus())) {
            return converter.toDTO(execution, executionResources.scanExisting(id));
        }
        // RUNNING (or unexpected non-pending)
        throw HubErrorCodes.EXECUTION_NOT_CANCELLABLE.asThrowable(
            "Execution is " + execution.getStatus() + " and cannot be cancelled");
    }

    /**
     * Persists a CANCELLED terminal state for an execution whose queue handle was
     * discarded before it could run (instance queue cleared, dispatcher force-shutdown,
     * or client cancel). Invoked from the dispatcher under its submit lock; must never
     * throw so queue-clear/shutdown callers are not broken by a persistence failure.
     * CAS semantics keep the write idempotent against a concurrent cancel or a worker
     * that already won PENDING→RUNNING.
     */
    private void persistQueuedTaskDiscard(String executionId) {
        try {
            if (executionRepository.markCancelledIfPending(
                executionId,
                "Cancelled because the task was discarded from the instance queue",
                LocalDateTime.now(clock))) {
                log.info("Execution cancelled after queue discard id={}", executionId);
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to persist queue-discard cancellation id={} error={}",
                executionId, ex.getMessage());
        }
    }

    private void runOnInstance(
        String executionId,
        HubInstance instance,
        NormalizedOpenCliArgv normalized,
        HubCommandOutputRule outputRule,
        HubExecutionDeadline deadline,
        HubExecutionConcurrencyMode concurrencyMode) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (!executionRepository.markRunningIfPending(executionId, now)) {
            log.info("Skip execution id={} (no longer PENDING; cancelled or raced)", executionId);
            return;
        }
        HubExecution execution = executionRepository.findById(executionId);
        if (execution == null) {
            log.warn("Execution disappeared after markRunning id={}", executionId);
            return;
        }
        execution.markRunning(now);

        try {
            dispatchRegistry.executeGuarded(
                instance.getId(),
                concurrencyMode,
                deadline.deadlineNanos(),
                () -> {
                    executeOpenCli(execution, instance, normalized, outputRule, deadline);
                    return null;
                });
        } catch (RuntimeException ex) {
            OpenCliExecutionResult result = isError(ex, HubErrorCodes.QUEUE_WAIT_TIMEOUT)
                ? timedOutResult(message(ex))
                : failedResult(ex);
            execution.markFinished(result, LocalDateTime.now(clock));
        }
        persistUpdate(execution);
        log.info(
            "Execution finished id={} status={} exitCode={} durationMillis={}",
            execution.getId(),
            execution.getStatus(),
            execution.getExitCode(),
            execution.getDurationMillis());
    }

    private void executeOpenCli(
        HubExecution execution,
        HubInstance instance,
        NormalizedOpenCliArgv normalized,
        HubCommandOutputRule outputRule,
        HubExecutionDeadline deadline) {
        HubExecutionResources.ResourceContext resourceContext = null;
        try {
            long remainingMillis = deadline.remainingMillis();
            if (remainingMillis <= 0) {
                execution.markFinished(
                    timedOutResult("Execution deadline elapsed before OpenCLI start"),
                    LocalDateTime.now(clock));
                return;
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
            execution.markFinished(result, LocalDateTime.now(clock));
        } catch (RuntimeException ex) {
            execution.markFinished(failedResult(ex), LocalDateTime.now(clock));
        } finally {
            HubExecutionResourceGroup group =
                resourceContext == null ? null : resourceContext.getGroup();
            if (resourceContext != null) {
                try {
                    executionResources.scan(group);
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
    }

    private void recordResourceScanFailure(
        HubExecution execution, RuntimeException failure) {
        String scanError = "Failed to scan execution resources: " + message(failure);
        if (execution.getStatus() == HubExecutionStatus.SUCCEEDED) {
            execution.setStatus(HubExecutionStatus.FAILED);
            execution.setErrorMessage(scanError);
            execution.setFinishedAt(LocalDateTime.now(clock));
            execution.setUpdateTime(execution.getFinishedAt());
        } else if (execution.getErrorMessage() == null || execution.getErrorMessage().isBlank()) {
            execution.setErrorMessage(scanError);
        } else {
            execution.setErrorMessage(execution.getErrorMessage() + "; " + scanError);
        }
    }

    private HubExecutionDTO terminalAfterDispatchFailure(String executionId, RuntimeException failure) {
        HubExecution execution = executionRepository.findById(executionId);
        if (execution == null) {
            throw HubErrorCodes.EXECUTION_NOT_FOUND.asThrowable("execution not found: " + executionId);
        }
        if (isTerminal(execution.getStatus())) {
            return converter.toDTO(execution, List.of());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (execution.getStatus() == HubExecutionStatus.PENDING) {
            // CAS so concurrent cancel(PENDING→CANCELLED) always wins over timeout/fail write.
            OpenCliExecutionResult terminal = isError(failure, HubErrorCodes.QUEUE_WAIT_TIMEOUT)
                ? timedOutResult("Execution deadline elapsed while queued")
                : failedResult(failure);
            HubExecutionStatus status = terminal.isTimedOut()
                ? HubExecutionStatus.TIMED_OUT
                : HubExecutionStatus.FAILED;
            executionRepository.markTerminalIfPending(
                executionId,
                status,
                terminal.getErrorMessage(),
                terminal.getExitCode(),
                now);
            execution = executionRepository.findById(executionId);
            return converter.toDTO(execution, List.of());
        }
        if (execution.getStatus() == HubExecutionStatus.RUNNING) {
            if (isError(failure, HubErrorCodes.QUEUE_WAIT_TIMEOUT)) {
                execution.markFinished(timedOutResult("Execution deadline elapsed while queued"), now);
            } else {
                execution.markFinished(failedResult(failure), now);
            }
            persistUpdate(execution);
        }
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
        LocalDateTime now = LocalDateTime.now(clock);
        execution.setQueuedAt(now);
        execution.setCreateTime(now);
        execution.setUpdateTime(now);
        return execution;
    }

    private HubCommandOutputRule resolveEffectiveOutputRule(NormalizedOpenCliArgv normalized) {
        HubCommandOutputRule configured = outputRuleService
            .findByCommandKey(normalized.getCanonicalKey()).orElse(null);
        if (configured != null) {
            return configured;
        }
        return HubManagedOutputArguments.syntheticRule(normalized.getCommand());
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

    private static boolean isTerminal(HubExecutionStatus status) {
        return status != null && TERMINAL.contains(status);
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
}
