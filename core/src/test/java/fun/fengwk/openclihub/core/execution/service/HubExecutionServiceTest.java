package fun.fengwk.openclihub.core.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the M5 execution state machine, deadline handling and no-retry contract.
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
        instance.setId(7L);
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
        when(resources.scanExisting(anyLong())).thenReturn(List.of());
        when(resources.prepare(anyLong(), any(), any())).thenAnswer(invocation -> {
            NormalizedOpenCliArgv argv = invocation.getArgument(1);
            return new HubExecutionResources.ResourceContext(
                argv.getNormalizedArgv(), null, List.of());
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
        assertThat(result.getId()).isPositive();
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
        dispatchRegistry.submit(instance, () -> {
            active.countDown();
            Thread.sleep(120L);
            return null;
        }, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
        assertThat(active.await(1, TimeUnit.SECONDS)).isTrue();

        HubExecutionDTO result = service.execute(request(instance.getId(), 30L));

        assertThat(result.getStatus()).isEqualTo(HubExecutionStatus.TIMED_OUT);
        assertThat(result.getStartedAt()).isNull();
        assertThat(executor.invocationCount()).isZero();
        assertThat(repository.updatedStatuses).containsExactly(HubExecutionStatus.TIMED_OUT);
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
        doReturn(context).when(resources).prepare(anyLong(), any(), any());
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
            .executionId(1L)
            .date(LocalDate.now())
            .group("execution-1")
            .realPath(tempDir.resolve("execution-1"))
            .build();
        HubExecutionResources.ResourceContext context = new HubExecutionResources.ResourceContext(
            normalized.getNormalizedArgv(), group, List.of());
        doReturn(context).when(resources).prepare(anyLong(), any(), eq(rule));
        HubResourceItemDTO item = new HubResourceItemDTO();
        item.setFileName("result.json");
        item.setSource(HubResourceSource.EXECUTION);
        when(resources.scan(group)).thenReturn(List.of(item));

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
    void shouldNotStartOpenCliWhenRunningUpdateFails() {
        repository.failUpdateAt = 1;

        assertThatThrownBy(() -> service.execute(request(instance.getId(), 1_000L)))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.EXECUTION_PERSIST_FAILED.getCode()));
        assertThat(executor.invocationCount()).isZero();
    }

    @Test
    void shouldReportTerminalPersistenceFailureAfterCommandCompletes() {
        repository.failUpdateAt = 2;

        assertThatThrownBy(() -> service.execute(request(instance.getId(), 1_000L)))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.EXECUTION_PERSIST_FAILED.getCode()));
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

    private HubExecutionRequestDTO request(Long instanceId, long timeoutMillis) {
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

        private long nextId = 100L;
        private int addCount;
        private int updateCount;
        private int failUpdateAt;
        private boolean failGenerate;
        private boolean failAdd;
        private final Map<Long, HubExecution> executions = new LinkedHashMap<>();
        private final List<HubExecutionStatus> updatedStatuses = new ArrayList<>();

        @Override
        public long generateId() {
            if (failGenerate) {
                throw new IllegalStateException("id generator unavailable");
            }
            return nextId++;
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
        public HubExecution findById(long id) {
            return executions.get(id);
        }

        @Override
        public Page<HubExecution> page(PageQuery pageQuery, Long instanceId) {
            throw new UnsupportedOperationException();
        }

    }

}
