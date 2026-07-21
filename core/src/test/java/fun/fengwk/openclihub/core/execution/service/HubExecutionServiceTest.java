package fun.fengwk.openclihub.core.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandArg;
import fun.fengwk.openclihub.core.command.service.HubCommandBlacklistService;
import fun.fengwk.openclihub.core.command.service.HubCommandOutputRuleService;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.core.command.validator.NormalizedOpenCliArgv;
import fun.fengwk.openclihub.core.command.validator.OpenCliArgvValidator;
import fun.fengwk.openclihub.core.execution.FakeOpenCliExecutor;
import fun.fengwk.openclihub.core.execution.repo.HubExecutionRepository;
import fun.fengwk.openclihub.core.execution.runtime.HubDispatchRegistry;
import fun.fengwk.openclihub.core.execution.service.converter.HubExecutionConverter;
import fun.fengwk.openclihub.core.execution.service.model.HubExecution;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.core.resource.model.HubExecutionResourceGroup;
import fun.fengwk.openclihub.core.resource.service.HubResourceLease;
import fun.fengwk.openclihub.core.resource.service.HubResourceLeaseManager;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
import fun.fengwk.openclihub.share.model.execution.HubExecutionDTO;
import fun.fengwk.openclihub.share.model.execution.HubExecutionRequestDTO;
import fun.fengwk.openclihub.share.model.execution.HubExecutionStatus;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import fun.fengwk.openclihub.share.model.resource.HubResourceItemDTO;
import fun.fengwk.openclihub.share.model.resource.HubResourceSource;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the execution state machine, deadline handling and no-retry contract.
 */
class HubExecutionServiceTest {

    @TempDir
    Path tempDir;

    private OpenCliArgvValidator argvValidator;
    private HubCommandBlacklistService blacklistService;
    private HubCommandOutputRuleService outputRuleService;
    private HubExecutionRouter router;
    private HubExecutionResources resources;
    private HubDispatchRegistry dispatchRegistry;
    private InMemoryExecutionRepository repository;
    private FakeOpenCliExecutor executor;
    private OpenCliHubProperties properties;
    private HubInstance instance;
    private NormalizedOpenCliArgv normalized;
    private HubExecutionService service;

    @BeforeEach
    void setUp() {
        argvValidator = mock(OpenCliArgvValidator.class);
        blacklistService = mock(HubCommandBlacklistService.class);
        outputRuleService = mock(HubCommandOutputRuleService.class);
        router = mock(HubExecutionRouter.class);
        resources = mock(HubExecutionResources.class);
        dispatchRegistry = new HubDispatchRegistry();
        repository = new InMemoryExecutionRepository();
        executor = new FakeOpenCliExecutor();
        properties = new OpenCliHubProperties();

        instance = new HubInstance();
        instance.setId("7");
        instance.setCode("primary");
        instance.setContextId("ctx-primary");
        instance.setState(HubInstanceState.RUNNING);
        instance.setWebsites(List.of("bilibili"));
        instance.setMaxPending(3);
        dispatchRegistry.register(instance);

        normalized = normalized(SiteSessionMode.EPHEMERAL);
        when(argvValidator.validate(any())).thenReturn(normalized);
        when(blacklistService.findByCommandKey(normalized.getCanonicalKey()))
            .thenReturn(Optional.empty());
        when(outputRuleService.findByCommandKey(normalized.getCanonicalKey()))
            .thenReturn(Optional.empty());
        when(router.chooseInstance(eq("bilibili"), any())).thenReturn(instance);
        when(resources.scanExisting(anyString())).thenReturn(List.of());
        when(resources.prepare(anyString(), any(), any())).thenAnswer(invocation -> {
            NormalizedOpenCliArgv argv = invocation.getArgument(1);
            HubCommandOutputRule rule = invocation.getArgument(2);
            HubExecutionResourceGroup group = null;
            if (rule != null) {
                Path groupDir = tempDir.resolve("execution-group-" + invocation.getArgument(0));
                java.nio.file.Files.createDirectories(groupDir);
                group = HubExecutionResourceGroup.builder()
                    .executionId(invocation.getArgument(0))
                    .date(LocalDate.now(java.time.ZoneOffset.UTC))
                    .group("execution-" + invocation.getArgument(0))
                    .realPath(groupDir)
                    .build();
            }
            return new HubExecutionResources.ResourceContext(
                argv.getNormalizedArgv(), group, List.of());
        });

        service = new HubExecutionService(
            argvValidator,
            blacklistService,
            outputRuleService,
            router,
            dispatchRegistry,
            repository,
            new HubExecutionArgvBuilder(),
            resources,
            executor,
            new HubExecutionConverter(),
            new ObjectMapper(),
            properties);
    }

    @AfterEach
    void tearDown() {
        dispatchRegistry.unregister(instance.getId());
    }

    @Test
    void shouldRejectUnsupportedInstanceIdBeforeCommandValidation() {
        assertThatThrownBy(() -> service.execute(request("not-an-id", 1_000L)))
            .isInstanceOf(ThrowableConventionErrorCode.class);

        verify(argvValidator, never()).validate(any());
        assertThat(repository.addCount).isZero();
    }

    /** Malformed detail IDs return not-found without reaching persistence or resource paths. */
    @Test
    void shouldTreatUnsupportedDetailIdAsNotFoundWithoutRepositoryLookup() {
        assertThat(service.getById("not-an-id")).isNull();

        assertThat(repository.findCount).isZero();
    }

    @Test
    void shouldPersistPendingRunningAndSucceededExactlyOnce() {
        executor.setBehavior(() -> FakeOpenCliExecutor.Behaviour.successJson("{\"items\":[]}"));

        HubExecutionDTO result = service.execute(request(instance.getId(), 1_000L));

        assertThat(result.getStatus()).isEqualTo(HubExecutionStatus.SUCCEEDED);
        assertThat(result.getInstanceId()).isEqualTo(instance.getId());
        assertThat(result.isReuseInstance()).isFalse();
        assertThat(repository.addCount).isOne();
        assertThat(repository.updatedStatuses)
            .containsExactly(HubExecutionStatus.RUNNING, HubExecutionStatus.SUCCEEDED);
        assertThat(executor.invocationCount()).isOne();
        assertThat(executor.invocations().get(0).argv)
            .containsExactly("--profile", "ctx-primary", "bilibili", "hot", "--format", "json");
    }

    @Test
    void shouldReturnFailedTerminalDtoForNonZeroExitWithoutRetryOrFailover() {
        executor.setBehavior(() -> FakeOpenCliExecutor.Behaviour.failure(17, "adapter failed"));

        HubExecutionDTO result = service.execute(request(instance.getId(), 1_000L));

        assertThat(result.getStatus()).isEqualTo(HubExecutionStatus.FAILED);
        assertThat(result.getExitCode()).isEqualTo(17);
        assertThat(result.getStderr()).isEqualTo("adapter failed");
        assertThat(executor.invocationCount()).isOne();
        verify(router, times(1)).chooseInstance("bilibili", instance.getId());
    }

    @Test
    void shouldRejectInvalidJsonAsFailedTerminalDto() {
        executor.setBehavior(() -> FakeOpenCliExecutor.Behaviour.invalidJson("not-json"));

        HubExecutionDTO result = service.execute(request(instance.getId(), 1_000L));

        assertThat(result.getStatus()).isEqualTo(HubExecutionStatus.FAILED);
        assertThat(result.getErrorMessage())
            .contains(HubErrorCodes.OPENCLI_INVALID_JSON_OUTPUT.getCode());
        assertThat(result.getId()).matches("[0-9a-f-]{36}");
    }

    @Test
    void shouldRejectEmptyStdoutAsInvalidJson() {
        executor.setBehavior(() -> FakeOpenCliExecutor.Behaviour.successJson("   "));

        HubExecutionDTO result = service.execute(request(instance.getId(), 1_000L));

        assertThat(result.getStatus()).isEqualTo(HubExecutionStatus.FAILED);
        assertThat(result.getErrorMessage())
            .contains(HubErrorCodes.OPENCLI_INVALID_JSON_OUTPUT.getCode());
    }

    @Test
    void shouldReturnFailedTerminalDtoWhenExecutorThrows() {
        executor.setBehavior(() -> FakeOpenCliExecutor.Behaviour.throwsOnStart(
            new IllegalStateException("cannot spawn")));

        HubExecutionDTO result = service.execute(request(instance.getId(), 1_000L));

        assertThat(result.getStatus()).isEqualTo(HubExecutionStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("cannot spawn");
        assertThat(repository.updatedStatuses)
            .containsExactly(HubExecutionStatus.RUNNING, HubExecutionStatus.FAILED);
    }

    @Test
    void shouldTimeOutInQueueWithoutRunningOrStartingOpenCli() throws Exception {
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        dispatchRegistry.submit(instance, () -> {
            active.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        assertThat(active.await(1, TimeUnit.SECONDS)).isTrue();

        // Short business timeout: expires while queued behind the blocker.
        HubExecutionDTO submitted = service.submit(request(instance.getId(), 50L));
        Thread.sleep(80L); // ensure deadline nanos is in the past before the worker dequeues
        release.countDown();

        HubExecutionDTO result = null;
        for (int i = 0; i < 100; i++) {
            result = service.getById(submitted.getId(), 0);
            if (result != null && result.getStatus() != HubExecutionStatus.PENDING) {
                break;
            }
            Thread.sleep(20L);
        }

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(HubExecutionStatus.TIMED_OUT);
        assertThat(result.getStartedAt()).isNull();
        assertThat(executor.invocationCount()).isZero();
        assertThat(repository.updatedStatuses).contains(HubExecutionStatus.TIMED_OUT);
    }

    @Test
    void shouldPersistRunningExecutionTimeoutAsTerminalDto() {
        executor.setBehavior(() -> FakeOpenCliExecutor.Behaviour.slow(300L, "{\"ok\":true}"));

        HubExecutionDTO result = service.execute(request(instance.getId(), 100L));

        assertThat(result.getStatus()).isEqualTo(HubExecutionStatus.TIMED_OUT);
        assertThat(result.getStartedAt()).isNotNull();
        assertThat(result.getFinishedAt()).isNotNull();
        assertThat(executor.invocationCount()).isOne();
        assertThat(repository.updatedStatuses)
            .containsExactly(HubExecutionStatus.RUNNING, HubExecutionStatus.TIMED_OUT);
    }

    @Test
    void shouldSetReuseInstanceOnlyForAutomaticallyRoutedPersistentCommand() {
        normalized = normalized(SiteSessionMode.PERSISTENT);
        when(argvValidator.validate(any())).thenReturn(normalized);
        when(blacklistService.findByCommandKey(normalized.getCanonicalKey()))
            .thenReturn(Optional.empty());
        when(outputRuleService.findByCommandKey(normalized.getCanonicalKey()))
            .thenReturn(Optional.empty());

        HubExecutionDTO automatic = service.execute(request(null, 1_000L));
        HubExecutionDTO explicit = service.execute(request(instance.getId(), 1_000L));

        assertThat(automatic.isReuseInstance()).isTrue();
        assertThat(explicit.isReuseInstance()).isFalse();
    }

    @Test
    void shouldReleaseInputLeaseWhenExecutionFails() {
        HubResourceLeaseManager leaseManager = new HubResourceLeaseManager();
        HubResourceLease lease = leaseManager.acquire(tempDir.resolve("input.txt"), "execution-test");
        HubExecutionResources.ResourceContext context = new HubExecutionResources.ResourceContext(
            normalized.getNormalizedArgv(), null, List.of(lease));
        doReturn(context).when(resources).prepare(anyString(), any(), any());
        executor.setBehavior(() -> FakeOpenCliExecutor.Behaviour.throwsOnStart(
            new IllegalStateException("spawn failed")));

        HubExecutionDTO result = service.execute(request(instance.getId(), 1_000L));

        assertThat(result.getStatus()).isEqualTo(HubExecutionStatus.FAILED);
        assertThat(leaseManager.heldPathCount()).isZero();
    }

    @Test
    void shouldReturnScannedOutputResources() {
        HubCommandOutputRule rule = new HubCommandOutputRule();
        rule.setCommandKey(normalized.getCanonicalKey());
        rule.setArgumentName("output");
        rule.setTargetType(HubCommandOutputTargetType.DIRECTORY);
        when(outputRuleService.findByCommandKey(normalized.getCanonicalKey()))
            .thenReturn(Optional.of(rule));

        HubExecutionResourceGroup group = HubExecutionResourceGroup.builder()
            .executionId("1")
            .date(LocalDate.now())
            .group("execution-1")
            .realPath(tempDir.resolve("execution-1"))
            .build();
        HubExecutionResources.ResourceContext context = new HubExecutionResources.ResourceContext(
            normalized.getNormalizedArgv(), group, List.of());
        doReturn(context).when(resources).prepare(anyString(), any(), eq(rule));
        HubResourceItemDTO item = new HubResourceItemDTO();
        item.setFileName("result.json");
        item.setSource(HubResourceSource.EXECUTION);
        when(resources.scan(group)).thenReturn(List.of(item));
        // Detail/long-poll path loads resources via scanExisting after terminal status.
        when(resources.scanExisting(anyString())).thenReturn(List.of(item));

        HubExecutionDTO result = service.execute(request(instance.getId(), 1_000L));

        assertThat(result.getStatus()).isEqualTo(HubExecutionStatus.SUCCEEDED);
        assertThat(result.getResources()).extracting(HubResourceItemDTO::getFileName)
            .containsExactly("result.json");
        assertThat(executor.invocations().get(0).argv)
            .containsSubsequence("--output", group.getRealPath().toString());
    }

    @Test
    void shouldIncludePersistedResourcesInExecutionDetail() {
        HubExecutionDTO executed = service.execute(request(instance.getId(), 1_000L));
        HubResourceItemDTO item = new HubResourceItemDTO();
        item.setFileName("history.json");
        when(resources.scanExisting(executed.getId())).thenReturn(List.of(item));

        HubExecutionDTO detail = service.getById(executed.getId());

        assertThat(detail.getResources()).extracting(HubResourceItemDTO::getFileName)
            .containsExactly("history.json");
    }

    @Test
    void shouldFailTerminalResultWhenResourceScanFails() {
        doThrow(new IllegalStateException("scan failed")).when(resources).scan(null);

        HubExecutionDTO result = service.execute(request(instance.getId(), 1_000L));

        assertThat(result.getStatus()).isEqualTo(HubExecutionStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("scan failed");
        assertThat(result.getStdout()).isEqualTo("{}");
    }

    @Test
    void shouldMapExecutionIdGenerationFailureToPersistenceError() {
        repository.failGenerate = true;

        assertThatThrownBy(() -> service.execute(request(instance.getId(), 1_000L)))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.EXECUTION_PERSIST_FAILED.getCode()));
        assertThat(repository.addCount).isZero();
        assertThat(executor.invocationCount()).isZero();
    }

    @Test
    void shouldNotStartExecutionWhenPendingInsertFails() {
        repository.failAdd = true;

        assertThatThrownBy(() -> service.execute(request(instance.getId(), 1_000L)))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.EXECUTION_PERSIST_FAILED.getCode()));
        assertThat(executor.invocationCount()).isZero();
        assertThat(repository.updatedStatuses).isEmpty();
    }

    @Test
    void shouldNotStartOpenCliWhenRunningUpdateFails() throws Exception {
        repository.failUpdateAt = 1;

        HubExecutionDTO submitted = service.submit(request(instance.getId(), 1_000L));
        // Worker skips body when CAS PENDING->RUNNING fails; stays PENDING (or unchanged).
        Thread.sleep(100L);
        assertThat(executor.invocationCount()).isZero();
        HubExecutionDTO latest = service.getById(submitted.getId(), 0);
        assertThat(latest.getStatus()).isEqualTo(HubExecutionStatus.PENDING);
    }

    @Test
    void shouldReportTerminalPersistenceFailureAfterCommandCompletes() throws Exception {
        // markRunning succeeds (update #1); final persistUpdate fails (update #2) inside worker.
        repository.failUpdateAt = 2;

        service.submit(request(instance.getId(), 1_000L));
        Thread.sleep(150L);
        // Async worker runs opencli even if the final persistUpdate fails.
        assertThat(executor.invocationCount()).isOne();
    }

    @Test
    void shouldContinueAcceptedExecutionWhenCallerThreadIsInterrupted() throws Exception {
        executor.setBehavior(() -> FakeOpenCliExecutor.Behaviour.slow(100L, "{\"ok\":true}"));
        AtomicReference<HubExecutionDTO> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                result.set(service.execute(request(instance.getId(), 1_000L)));
            } catch (Throwable ex) {
                failure.set(ex);
            } finally {
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.start();
        for (int i = 0; i < 100 && executor.invocationCount() == 0; i++) {
            Thread.sleep(5L);
        }
        caller.interrupt();
        caller.join(2_000L);

        assertThat(failure.get()).isNull();
        assertThat(result.get()).isNotNull();
        assertThat(result.get().getStatus()).isEqualTo(HubExecutionStatus.SUCCEEDED);
        assertThat(interruptRestored).isTrue();
        assertThat(repository.updatedStatuses)
            .containsExactly(HubExecutionStatus.RUNNING, HubExecutionStatus.SUCCEEDED);
    }


    /** Commands declaring --op are auto-managed so callers do not supply container paths. */
    @Test
    void shouldAutoManageOpArgumentAsExecutionResourceDirectory() {
        OpenCliCommandArg op = new OpenCliCommandArg();
        op.setName("op");
        op.setType("string");
        op.setRequired(false);
        op.setValueRequired(true);
        op.setPositional(false);

        OpenCliCommand command = new OpenCliCommand();
        command.setSite("chatgpt-agent");
        command.setName("ask");
        command.setSiteSession(SiteSessionMode.PERSISTENT);
        command.setArgs(List.of(op));
        command.setCommandKey("chatgpt-agent/ask");

        NormalizedOpenCliArgv ask = new NormalizedOpenCliArgv(
            command,
            "chatgpt-agent/ask",
            List.of("hi"),
            new LinkedHashMap<>(),
            List.of("chatgpt-agent", "ask", "hi"));
        when(argvValidator.validate(any())).thenReturn(ask);
        when(blacklistService.findByCommandKey("chatgpt-agent/ask")).thenReturn(Optional.empty());
        when(outputRuleService.findByCommandKey("chatgpt-agent/ask")).thenReturn(Optional.empty());
        instance.setWebsites(List.of("chatgpt-agent"));
        when(router.chooseInstance(eq("chatgpt-agent"), any())).thenReturn(instance);
        executor.setBehavior(() -> FakeOpenCliExecutor.Behaviour.successJson("[{\"text\":\"ok\",\"downloads\":\"[]\"}]"));
        when(resources.scan(any())).thenReturn(List.of());

        HubExecutionRequestDTO request = new HubExecutionRequestDTO();
        request.setArgv(List.of("chatgpt-agent", "ask", "hi"));
        request.setTimeoutMillis(5_000L);
        HubExecutionDTO dto = service.execute(request);
        assertThat(dto.getStatus()).isEqualTo(HubExecutionStatus.SUCCEEDED);
        verify(resources).prepare(anyString(), any(), org.mockito.ArgumentMatchers.argThat(rule ->
            rule != null
                && "op".equals(rule.getArgumentName())
                && rule.getTargetType() == HubCommandOutputTargetType.DIRECTORY));
        verify(resources).scan(any());
    }


    @Test
    void shouldAutoManageOutputDirectoryArgument() {
        OpenCliCommandArg output = new OpenCliCommandArg();
        output.setName("output");
        output.setType("string");
        output.setRequired(false);
        output.setValueRequired(true);
        output.setPositional(false);
        output.setHelp("Output directory");

        OpenCliCommand command = new OpenCliCommand();
        command.setSite("xiaohongshu");
        command.setName("download");
        command.setSiteSession(SiteSessionMode.EPHEMERAL);
        command.setArgs(List.of(output));
        command.setCommandKey("xiaohongshu/download");

        NormalizedOpenCliArgv download = new NormalizedOpenCliArgv(
            command,
            "xiaohongshu/download",
            List.of(),
            new LinkedHashMap<>(),
            List.of("xiaohongshu", "download"));
        when(argvValidator.validate(any())).thenReturn(download);
        when(blacklistService.findByCommandKey("xiaohongshu/download")).thenReturn(Optional.empty());
        when(outputRuleService.findByCommandKey("xiaohongshu/download")).thenReturn(Optional.empty());
        instance.setWebsites(List.of("xiaohongshu"));
        when(router.chooseInstance(eq("xiaohongshu"), any())).thenReturn(instance);
        executor.setBehavior(() -> FakeOpenCliExecutor.Behaviour.successJson("[{\"ok\":true}]"));
        when(resources.scan(any())).thenReturn(List.of());

        HubExecutionRequestDTO request = new HubExecutionRequestDTO();
        request.setArgv(List.of("xiaohongshu", "download"));
        request.setTimeoutMillis(5_000L);
        HubExecutionDTO dto = service.execute(request);
        assertThat(dto.getStatus()).isEqualTo(HubExecutionStatus.SUCCEEDED);
        verify(resources).prepare(anyString(), any(), org.mockito.ArgumentMatchers.argThat(rule ->
            rule != null
                && "output".equals(rule.getArgumentName())
                && rule.getTargetType() == HubCommandOutputTargetType.DIRECTORY));
    }

    private HubExecutionRequestDTO request(String instanceId, long timeoutMillis) {
        HubExecutionRequestDTO request = new HubExecutionRequestDTO();
        request.setInstanceId(instanceId);
        request.setArgv(List.of("bilibili", "hot"));
        request.setTimeoutMillis(timeoutMillis);
        return request;
    }

    private static NormalizedOpenCliArgv normalized(SiteSessionMode mode) {
        OpenCliCommand command = new OpenCliCommand();
        command.setSite("bilibili");
        command.setName("hot");
        command.setSiteSession(mode);
        return new NormalizedOpenCliArgv(
            command,
            "bilibili/hot",
            List.of(),
            new LinkedHashMap<>(),
            List.of("bilibili", "hot"));
    }

    private static final class InMemoryExecutionRepository implements HubExecutionRepository {

        private int addCount;
        private int updateCount;
        private int findCount;
        private int failUpdateAt;
        private boolean failGenerate;
        private boolean failAdd;
        private final Map<String, HubExecution> executions = new LinkedHashMap<>();
        private final List<HubExecutionStatus> updatedStatuses = new ArrayList<>();

        @Override
        public String generateId() {
            if (failGenerate) {
                throw new IllegalStateException("id generator unavailable");
            }
            return UUID.randomUUID().toString();
        }

        @Override
        public boolean add(HubExecution execution) {
            addCount++;
            if (failAdd) {
                return false;
            }
            executions.put(execution.getId(), execution);
            return true;
        }

        @Override
        public boolean update(HubExecution execution) {
            updateCount++;
            if (failUpdateAt == updateCount) {
                return false;
            }
            updatedStatuses.add(execution.getStatus());
            executions.put(execution.getId(), execution);
            return true;
        }

        @Override
        public HubExecution findById(String id) {
            findCount++;
            return executions.get(id);
        }

        @Override
        public Page<HubExecution> page(PageQuery pageQuery, String instanceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean markRunningIfPending(String id, java.time.LocalDateTime startedAt) {
            HubExecution execution = executions.get(id);
            if (execution == null || execution.getStatus() != HubExecutionStatus.PENDING) {
                return false;
            }
            updateCount++;
            if (failUpdateAt == updateCount) {
                return false;
            }
            execution.markRunning(startedAt);
            updatedStatuses.add(HubExecutionStatus.RUNNING);
            return true;
        }

        @Override
        public boolean markCancelledIfPending(String id, String errorMessage, java.time.LocalDateTime finishedAt) {
            HubExecution execution = executions.get(id);
            if (execution == null || execution.getStatus() != HubExecutionStatus.PENDING) {
                return false;
            }
            updateCount++;
            if (failUpdateAt == updateCount) {
                return false;
            }
            execution.setStatus(HubExecutionStatus.CANCELLED);
            execution.setErrorMessage(errorMessage);
            execution.setFinishedAt(finishedAt);
            updatedStatuses.add(HubExecutionStatus.CANCELLED);
            return true;
        }

        @Override
        public boolean markTerminalIfPending(String id, HubExecutionStatus status, String errorMessage,
                                             Integer exitCode, java.time.LocalDateTime finishedAt) {
            HubExecution execution = executions.get(id);
            if (execution == null || execution.getStatus() != HubExecutionStatus.PENDING) {
                return false;
            }
            updateCount++;
            if (failUpdateAt == updateCount) {
                return false;
            }
            execution.setStatus(status);
            execution.setErrorMessage(errorMessage);
            execution.setExitCode(exitCode);
            execution.setFinishedAt(finishedAt);
            updatedStatuses.add(status);
            return true;
        }

    }

}
