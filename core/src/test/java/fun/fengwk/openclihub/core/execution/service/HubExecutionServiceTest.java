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
import static org.mockito.Mockito.reset;
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
import fun.fengwk.openclihub.core.execution.runtime.HubExecutionConcurrencyMode;
import fun.fengwk.openclihub.core.execution.service.converter.HubExecutionConverter;
import fun.fengwk.openclihub.core.execution.service.model.HubExecution;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.core.resource.model.HubExecutionResourceGroup;
import fun.fengwk.openclihub.core.resource.service.HubResourceLease;
import fun.fengwk.openclihub.core.resource.service.HubResourceLeaseManager;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.command.HubCommandAccess;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
import fun.fengwk.openclihub.share.model.execution.HubExecutionDTO;
import fun.fengwk.openclihub.share.model.execution.HubExecutionRequestDTO;
import fun.fengwk.openclihub.share.model.execution.HubExecutionStatus;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import fun.fengwk.openclihub.share.model.resource.HubResourceItemDTO;
import fun.fengwk.openclihub.share.model.resource.HubResourceSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
        dispatchRegistry = new AdmissionTestDispatchRegistry();
        repository = new InMemoryExecutionRepository();
        executor = new FakeOpenCliExecutor();
        properties = new OpenCliHubProperties();
        // Disable start stagger in shared setup so regression suite executes fast without sleeps
        properties.getExecution().setParallelStartStaggerMinMillis(0L);
        properties.getExecution().setParallelStartStaggerMaxMillis(0L);
        // Anchor the resource root at the temp dir so the final-argv defense accepts the
        // temp-dir execution groups this test's prepare() mock fabricates.
        properties.getResource().setRootDir(tempDir.toString());

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
            new HubExecutionArgvBuilder(new HubLocalPathGuard(properties)),
            resources,
            executor,
            new HubExecutionConverter(),
            new ObjectMapper(),
            properties,
            Clock.systemUTC(),
            new HubExecutionStartStagger(properties));
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
        // Audit-time rule: on insert createTime equals the queue time; on the final update
        // updateTime equals the finish time (gmt_modified mirrors finished_at).
        HubExecution saved = repository.findById(result.getId());
        assertThat(saved.getCreateTime()).isEqualTo(saved.getQueuedAt());
        assertThat(saved.getUpdateTime()).isEqualTo(saved.getFinishedAt());
    }

    /**
     * Holds the first persistence call after routing, then keeps its dispatcher submission
     * until the worker is visible. This makes the route/persist/enqueue gap deterministic:
     * a correct admission lock blocks the second route, while the old implementation observes
     * the same zero load and assigns both submissions to the first instance.
     */
    @Test
    void shouldSerializeConcurrentAutomaticAdmissionAcrossRoutePersistAndEnqueue() throws Exception {
        HubInstance secondary = new HubInstance();
        secondary.setId("8");
        secondary.setCode("secondary");
        secondary.setContextId("ctx-secondary");
        secondary.setState(HubInstanceState.RUNNING);
        secondary.setWebsites(List.of("bilibili"));
        secondary.setMaxPending(3);
        dispatchRegistry.register(secondary);

        CountDownLatch firstAddEntered = new CountDownLatch(1);
        CountDownLatch allowFirstAdd = new CountDownLatch(1);
        repository.blockNextAdd(firstAddEntered, allowFirstAdd);

        CountDownLatch firstWorkerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        CountDownLatch workersFinished = new CountDownLatch(2);
        ((AdmissionTestDispatchRegistry) dispatchRegistry)
            .waitForFirstSubmissionToReachWorker(firstWorkerStarted);
        executor.setBehavior(() -> {
            firstWorkerStarted.countDown();
            try {
                if (!releaseWorkers.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("workers were not released");
                }
                return FakeOpenCliExecutor.Behaviour.successJson("{\"ok\":true}");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for worker release", ex);
            } finally {
                workersFinished.countDown();
            }
        });

        reset(router);
        AtomicBoolean firstRoute = new AtomicBoolean();
        CountDownLatch secondRouteEntered = new CountDownLatch(1);
        when(router.chooseInstance(eq("bilibili"), org.mockito.ArgumentMatchers.isNull()))
            .thenAnswer(invocation -> {
                if (firstRoute.compareAndSet(false, true)) {
                    return instance;
                }
                secondRouteEntered.countDown();
                return dispatchRegistry.getRoutingLoad(instance.getId()) == 0
                    ? instance : secondary;
            });

        AtomicReference<HubExecutionDTO> firstResult = new AtomicReference<>();
        AtomicReference<HubExecutionDTO> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                firstResult.set(service.submit(request(null, 30_000L)));
            } catch (Throwable ex) {
                firstFailure.set(ex);
            }
        }, "admission-first");
        Thread second = new Thread(() -> {
            try {
                secondResult.set(service.submit(request(null, 30_000L)));
            } catch (Throwable ex) {
                secondFailure.set(ex);
            }
        }, "admission-second");

        try {
            first.start();
            assertThat(firstAddEntered.await(2, TimeUnit.SECONDS)).isTrue();

            second.start();
            assertThat(secondRouteEntered.await(1, TimeUnit.SECONDS))
                .as("second route must remain behind the first route/persist/enqueue admission")
                .isFalse();

            allowFirstAdd.countDown();
            first.join(2_000L);
            second.join(2_000L);
            assertThat(first.isAlive()).isFalse();
            assertThat(second.isAlive()).isFalse();
            assertThat(firstFailure.get()).isNull();
            assertThat(secondFailure.get()).isNull();
            assertThat(firstResult.get()).isNotNull();
            assertThat(secondResult.get()).isNotNull();
            assertThat(List.of(firstResult.get(), secondResult.get()))
                .extracting(HubExecutionDTO::getInstanceId)
                .containsExactlyInAnyOrder(instance.getId(), secondary.getId());
        } finally {
            allowFirstAdd.countDown();
            releaseWorkers.countDown();
            first.join(2_000L);
            second.join(2_000L);
            workersFinished.await(2, TimeUnit.SECONDS);
            dispatchRegistry.unregister(secondary.getId());
        }
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
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch allowWorkerCompletion = new CountDownLatch(1);
        executor.setBehavior(() -> {
            workerEntered.countDown();
            try {
                allowWorkerCompletion.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("worker interrupted while test was coordinating", ex);
            }
            return FakeOpenCliExecutor.Behaviour.successJson("{\"ok\":true}");
        });
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
        try {
            assertThat(workerEntered.await(2L, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
        } finally {
            allowWorkerCompletion.countDown();
        }

        caller.join(5_000L);
        // Caller interruption must not cancel already accepted work. The compatibility
        // helper can observe either the running snapshot or the completed terminal snapshot.
        assertThat(caller.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(result.get()).isNotNull();
        assertThat(result.get().getStatus())
            .isIn(HubExecutionStatus.RUNNING, HubExecutionStatus.SUCCEEDED);
        assertThat(interruptRestored).isTrue();
        HubExecutionDTO completed = service.getById(result.get().getId(), 2);
        assertThat(completed).isNotNull();
        assertThat(completed.getStatus()).isEqualTo(HubExecutionStatus.SUCCEEDED);
        assertThat(repository.updatedStatuses)
            .containsExactly(HubExecutionStatus.RUNNING, HubExecutionStatus.SUCCEEDED);
    }

    // ---------------------------------------------------------------------------------------
    //  Cancel / clear-queue / shutdown persistence and concurrency contracts
    // ---------------------------------------------------------------------------------------

    /**
     * A queued execution cancelled through the service must persist CANCELLED and release
     * its dispatcher queue handle synchronously; the worker must never run OpenCLI.
     */
    @Test
    void shouldCancelQueuedExecutionPersistCancelledAndReleaseQueueSlot() throws Exception {
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        dispatchRegistry.submit(instance, () -> {
            active.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        assertThat(active.await(1, TimeUnit.SECONDS)).isTrue();

        HubExecutionDTO submitted = service.submit(request(instance.getId(), 30_000L));
        assertThat(submitted.getStatus()).isEqualTo(HubExecutionStatus.PENDING);
        assertThat(dispatchRegistry.getSnapshot(instance.getId()).getPendingCount()).isOne();

        HubExecutionDTO cancelled = service.cancel(submitted.getId());
        assertThat(cancelled.getStatus()).isEqualTo(HubExecutionStatus.CANCELLED);
        // Queue handle released synchronously: the slot is free and OpenCLI never starts.
        assertThat(dispatchRegistry.getSnapshot(instance.getId()).getPendingCount()).isZero();
        assertThat(executor.invocationCount()).isZero();

        release.countDown();
        Thread.sleep(150L);
        HubExecutionDTO latest = service.getById(submitted.getId(), 0);
        assertThat(latest.getStatus()).isEqualTo(HubExecutionStatus.CANCELLED);
        assertThat(repository.updatedStatuses).doesNotContain(HubExecutionStatus.RUNNING);
    }

    /**
     * clear-queue keeps its cleared-count semantics and persists every discarded row
     * CANCELLED through the service, leaving no queued handle or DB PENDING behind.
     */
    @Test
    void shouldPersistCancelledWhenQueueCleared() throws Exception {
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        dispatchRegistry.submit(instance, () -> {
            active.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        assertThat(active.await(1, TimeUnit.SECONDS)).isTrue();

        HubExecutionDTO first = service.submit(request(instance.getId(), 30_000L));
        HubExecutionDTO second = service.submit(request(instance.getId(), 30_000L));
        assertThat(dispatchRegistry.getSnapshot(instance.getId()).getPendingCount()).isEqualTo(2);

        int cleared = dispatchRegistry.clearPending(instance.getId());
        assertThat(cleared).isEqualTo(2);
        assertThat(dispatchRegistry.getSnapshot(instance.getId()).getPendingCount()).isZero();
        assertThat(service.getById(first.getId(), 0).getStatus())
            .isEqualTo(HubExecutionStatus.CANCELLED);
        assertThat(service.getById(second.getId(), 0).getStatus())
            .isEqualTo(HubExecutionStatus.CANCELLED);
        assertThat(executor.invocationCount()).isZero();

        release.countDown();
        Thread.sleep(150L);
        assertThat(service.getById(first.getId(), 0).getStatus())
            .isEqualTo(HubExecutionStatus.CANCELLED);
        assertThat(service.getById(second.getId(), 0).getStatus())
            .isEqualTo(HubExecutionStatus.CANCELLED);
    }

    /**
     * Force dispatcher teardown (unexpected exit / app shutdown) must persist the dropped
     * queued execution CANCELLED instead of leaving a DB PENDING row.
     */
    @Test
    void shouldPersistCancelledWhenDispatcherShutsDown() throws Exception {
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        dispatchRegistry.submit(instance, () -> {
            active.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return null;
        }, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        assertThat(active.await(1, TimeUnit.SECONDS)).isTrue();

        HubExecutionDTO queued = service.submit(request(instance.getId(), 30_000L));
        assertThat(dispatchRegistry.getSnapshot(instance.getId()).getPendingCount()).isOne();

        dispatchRegistry.unregister(instance.getId());
        assertThat(dispatchRegistry.getSnapshot(instance.getId()).isRegistered()).isFalse();
        assertThat(service.getById(queued.getId(), 0).getStatus())
            .isEqualTo(HubExecutionStatus.CANCELLED);
        assertThat(executor.invocationCount()).isZero();

        release.countDown();
    }

    /**
     * Cancel loses the PENDING→CANCELLED race once the worker has CASed PENDING→RUNNING:
     * it is refused, and the running execution still completes to a terminal state.
     */
    @Test
    void shouldRejectCancelAfterWorkerStartedRunning() throws Exception {
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.setBehavior(() -> {
            workerEntered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return FakeOpenCliExecutor.Behaviour.successJson("{\"ok\":true}");
        });

        HubExecutionDTO submitted = service.submit(request(instance.getId(), 30_000L));
        // The worker has already CASed PENDING->RUNNING once it entered the fake executor.
        assertThat(workerEntered.await(2, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.cancel(submitted.getId()))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.EXECUTION_NOT_CANCELLABLE.getCode()));

        release.countDown();
        HubExecutionDTO completed = service.getById(submitted.getId(), 3);
        assertThat(completed.getStatus()).isEqualTo(HubExecutionStatus.SUCCEEDED);
        assertThat(repository.updatedStatuses)
            .containsExactly(HubExecutionStatus.RUNNING, HubExecutionStatus.SUCCEEDED);
    }

    /**
     * A long-poll getById must be woken by the terminal row shortly after completion,
     * not wait out the requested waitSeconds window.
     */
    @Test
    void shouldWakeLongPollWhenExecutionCompletes() throws Exception {
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        dispatchRegistry.submit(instance, () -> {
            active.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        assertThat(active.await(1, TimeUnit.SECONDS)).isTrue();

        executor.setBehavior(() -> FakeOpenCliExecutor.Behaviour.successJson("{\"ok\":true}"));
        HubExecutionDTO submitted = service.submit(request(instance.getId(), 30_000L));
        assertThat(service.getById(submitted.getId(), 0).getStatus())
            .isEqualTo(HubExecutionStatus.PENDING);

        CountDownLatch pollEntered = new CountDownLatch(1);
        AtomicReference<HubExecutionDTO> polled = new AtomicReference<>();
        Thread poller = new Thread(() -> {
            pollEntered.countDown();
            polled.set(service.getById(submitted.getId(), 30));
        });
        poller.setDaemon(true);
        poller.start();
        assertThat(pollEntered.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(250L); // let the long-poll observe the PENDING row at least once

        long startNanos = System.nanoTime();
        release.countDown();
        poller.join(10_000L);
        assertThat(poller.isAlive()).isFalse();
        assertThat(polled.get()).isNotNull();
        assertThat(polled.get().getStatus()).isEqualTo(HubExecutionStatus.SUCCEEDED);
        // Woken by the completed row, not by the 30s wait window.
        assertThat(System.nanoTime() - startNanos).isLessThan(TimeUnit.SECONDS.toNanos(8));
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
        command.setBrowser(true);
        command.setAccess(HubCommandAccess.READ);
        command.setDefaultWindowMode("background");
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

    /**
     * Gate timeout: when the concurrency gate cannot be acquired before the deadline elapses,
     * the execution is marked as TIMED_OUT and the process executor is never invoked.
     */
    @Test
    void shouldPersistTimedOutAndNeverInvokeExecutorWhenGateWaitTimesOut() throws Exception {
        CountDownLatch gateHeld = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);

        Thread holder = new Thread(() ->
            dispatchRegistry.executeGuarded(
                instance.getId(),
                HubExecutionConcurrencyMode.EXCLUSIVE,
                Long.MAX_VALUE,
                () -> {
                gateHeld.countDown();
                releaseGate.await(5, TimeUnit.SECONDS);
                return null;
            }));
        holder.start();
        assertThat(gateHeld.await(2, TimeUnit.SECONDS)).isTrue();

        try {
            executor.setBehavior(() -> FakeOpenCliExecutor.Behaviour.successJson("[{\"ok\":true}]"));

            HubExecutionRequestDTO request = new HubExecutionRequestDTO();
            request.setInstanceId(instance.getId());
            request.setArgv(List.of("bilibili", "hot"));
            request.setTimeoutMillis(80L); // Short deadline

            HubExecutionDTO submitted = service.submit(request);
            assertThat(submitted.getStatus()).isEqualTo(HubExecutionStatus.PENDING);

            // Wait for worker to attempt gate acquisition and time out
            HubExecutionDTO terminal = service.getById(submitted.getId(), 3);

            assertThat(terminal).isNotNull();
            assertThat(terminal.getStatus()).isEqualTo(HubExecutionStatus.TIMED_OUT);
            assertThat(terminal.getExitCode()).isEqualTo(124);
            assertThat(terminal.getErrorMessage()).contains("concurrency gate");
            assertThat(executor.invocationCount()).as("OpenCLI executor must never be invoked on gate timeout").isZero();
            verify(resources, never()).prepare(anyString(), any(), any());
        } finally {
            releaseGate.countDown();
            holder.join(2000);
        }
    }

    /**
     * Parallel safe commands can execute concurrently on the same instance when maxConcurrency allows.
     */
    @Test
    void shouldExecuteParallelSafeCommandsConcurrentlyOnServiceLayer() throws Exception {
        dispatchRegistry.unregister(instance.getId());
        instance.setMaxConcurrency(2);
        instance.setMaxPending(2);
        dispatchRegistry.register(instance);

        CountDownLatch twoInExecutor = new CountDownLatch(2);
        CountDownLatch releaseExecutor = new CountDownLatch(1);

        executor.setBehavior(() -> {
            twoInExecutor.countDown();
            try {
                releaseExecutor.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FakeOpenCliExecutor.Behaviour.successJson("[{\"parallel\":true}]");
        });

        HubExecutionRequestDTO req1 = request(instance.getId(), 5000L);
        HubExecutionRequestDTO req2 = request(instance.getId(), 5000L);

        HubExecutionDTO exec1 = service.submit(req1);
        HubExecutionDTO exec2 = service.submit(req2);

        assertThat(twoInExecutor.await(2, TimeUnit.SECONDS))
            .as("Both parallel-safe executions run concurrently inside executor")
            .isTrue();

        releaseExecutor.countDown();

        HubExecutionDTO res1 = service.getById(exec1.getId(), 2);
        HubExecutionDTO res2 = service.getById(exec2.getId(), 2);

        assertThat(res1.getStatus()).isEqualTo(HubExecutionStatus.SUCCEEDED);
        assertThat(res2.getStatus()).isEqualTo(HubExecutionStatus.SUCCEEDED);
    }

    /**
     * Ephemeral WRITE commands with managed output run concurrently under the instance
     * capacity limit while their per-execution output directories stay isolated.
     */
    @Test
    void shouldExecuteEphemeralWriteWithManagedOutputConcurrently() throws Exception {
        dispatchRegistry.unregister(instance.getId());
        instance.setMaxConcurrency(2);
        instance.setMaxPending(2);
        dispatchRegistry.register(instance);

        OpenCliCommandArg op = new OpenCliCommandArg();
        op.setName("op");
        op.setType("string");
        op.setValueRequired(true);
        op.setHelp("Output directory");
        normalized.getCommand().setArgs(List.of(op));
        normalized.getCommand().setAccess(HubCommandAccess.WRITE);
        normalized.getCommand().setSiteSession(SiteSessionMode.EPHEMERAL);

        CountDownLatch twoInExecutor = new CountDownLatch(2);
        CountDownLatch releaseExecutor = new CountDownLatch(1);
        executor.setBehavior(() -> {
            twoInExecutor.countDown();
            try {
                releaseExecutor.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return FakeOpenCliExecutor.Behaviour.successJson("[{\"parallel\":true}]");
        });
        when(resources.scan(any())).thenReturn(List.of());

        HubExecutionDTO exec1 = service.submit(request(instance.getId(), 5_000L));
        HubExecutionDTO exec2 = service.submit(request(instance.getId(), 5_000L));

        try {
            assertThat(twoInExecutor.await(2, TimeUnit.SECONDS))
                .as("Both ephemeral write output commands reach OpenCLI concurrently")
                .isTrue();
        } finally {
            releaseExecutor.countDown();
        }

        assertThat(service.getById(exec1.getId(), 2).getStatus())
            .isEqualTo(HubExecutionStatus.SUCCEEDED);
        assertThat(service.getById(exec2.getId(), 2).getStatus())
            .isEqualTo(HubExecutionStatus.SUCCEEDED);

        List<String> outputPaths = executor.invocations().stream()
            .map(invocation -> {
                int opIndex = invocation.argv.indexOf("--op");
                assertThat(opIndex).isGreaterThanOrEqualTo(0);
                return invocation.argv.get(opIndex + 1);
            })
            .toList();
        assertThat(outputPaths).hasSize(2);
        assertThat(outputPaths).doesNotHaveDuplicates();
        assertThat(outputPaths).allSatisfy(path ->
            assertThat(Path.of(path).toAbsolutePath().normalize())
                .startsWith(tempDir.toAbsolutePath().normalize()));
    }

    /** Persistent-session commands remain serialized even when the instance has two workers. */
    @Test
    void shouldSerializeExclusiveCommandsOnServiceLayer() throws Exception {
        dispatchRegistry.unregister(instance.getId());
        instance.setMaxConcurrency(2);
        instance.setMaxPending(2);
        dispatchRegistry.register(instance);
        normalized.getCommand().setSiteSession(SiteSessionMode.PERSISTENT);

        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.setBehavior(() -> {
            int current = active.incrementAndGet();
            maxActive.updateAndGet(previous -> Math.max(previous, current));
            firstEntered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            return FakeOpenCliExecutor.Behaviour.successJson("[{\"exclusive\":true}]");
        });

        HubExecutionDTO first = service.submit(request(instance.getId(), 5_000L));
        HubExecutionDTO second = service.submit(request(instance.getId(), 5_000L));
        assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100L);
        assertThat(executor.invocationCount())
            .as("the second exclusive command must wait outside OpenCLI")
            .isOne();

        release.countDown();
        assertThat(service.getById(first.getId(), 2).getStatus())
            .isEqualTo(HubExecutionStatus.SUCCEEDED);
        assertThat(service.getById(second.getId(), 2).getStatus())
            .isEqualTo(HubExecutionStatus.SUCCEEDED);
        assertThat(maxActive).hasValue(1);
    }

    /**
     * Intent: Verify that PARALLEL_SAFE executions pass through the HubExecutionStartStagger
     * coordinator before the OpenCLI executor starts.
     * Effectiveness: Injects a recording coordinator with zero sleep, executes a PARALLEL_SAFE
     * request, and asserts that the stagger coordinator was invoked with the command's site
     * and PARALLEL_SAFE concurrency mode.
     */
    @Test
    void shouldIntegrateStartStaggerForParallelSafeExecution() {
        AtomicInteger staggerCalls = new AtomicInteger(0);
        AtomicReference<HubExecutionConcurrencyMode> observedMode = new AtomicReference<>();
        AtomicReference<String> observedSite = new AtomicReference<>();

        HubExecutionStartStagger recordingStagger = new HubExecutionStartStagger(
            0L,
            0L,
            System::nanoTime,
            (min, max) -> 0L,
            nanos -> {}) {
            @Override
            public <T> T execute(
                String site,
                HubExecutionConcurrencyMode mode,
                long deadlineNanos,
                Callable<T> action) {
                staggerCalls.incrementAndGet();
                observedSite.set(site);
                observedMode.set(mode);
                return super.execute(site, mode, deadlineNanos, action);
            }
        };

        HubExecutionService customService = new HubExecutionService(
            argvValidator,
            blacklistService,
            outputRuleService,
            router,
            dispatchRegistry,
            repository,
            new HubExecutionArgvBuilder(new HubLocalPathGuard(properties)),
            resources,
            executor,
            new HubExecutionConverter(),
            new ObjectMapper(),
            properties,
            Clock.systemUTC(),
            recordingStagger);

        HubExecutionDTO dto = customService.execute(request("7", 1000L));
        assertThat(dto.getStatus()).isEqualTo(HubExecutionStatus.SUCCEEDED);
        assertThat(staggerCalls.get()).isEqualTo(1);
        assertThat(observedSite.get()).isEqualTo("bilibili");
        assertThat(observedMode.get()).isEqualTo(HubExecutionConcurrencyMode.PARALLEL_SAFE);
    }

    /**
     * Intent: Verify that EXCLUSIVE executions bypass the start stagger coordination logic,
     * maintaining untouched serial execution behavior without any stagger queueing.
     * Effectiveness: Configures a PERSISTENT command (classified as EXCLUSIVE), injects a coordinator
     * that asserts no stagger delay is applied, and verifies execution completes under EXCLUSIVE mode
     * without retaining stagger site coordination state.
     */
    @Test
    void shouldBypassStartStaggerForExclusiveExecution() {
        NormalizedOpenCliArgv persistentArgv = normalized(SiteSessionMode.PERSISTENT);
        when(argvValidator.validate(any())).thenReturn(persistentArgv);
        when(blacklistService.findByCommandKey(persistentArgv.getCanonicalKey()))
            .thenReturn(Optional.empty());
        when(outputRuleService.findByCommandKey(persistentArgv.getCanonicalKey()))
            .thenReturn(Optional.empty());

        AtomicReference<HubExecutionConcurrencyMode> observedMode = new AtomicReference<>();
        HubExecutionStartStagger trackingStagger = new HubExecutionStartStagger(
            3000L,
            5000L,
            System::nanoTime,
            (min, max) -> TimeUnit.SECONDS.toNanos(4),
            nanos -> { throw new AssertionError("Sleeper must not be called when bypassing stagger"); }) {
            @Override
            public <T> T execute(
                String site,
                HubExecutionConcurrencyMode mode,
                long deadlineNanos,
                Callable<T> action) {
                observedMode.set(mode);
                return super.execute(site, mode, deadlineNanos, action);
            }
        };

        HubExecutionService customService = new HubExecutionService(
            argvValidator,
            blacklistService,
            outputRuleService,
            router,
            dispatchRegistry,
            repository,
            new HubExecutionArgvBuilder(new HubLocalPathGuard(properties)),
            resources,
            executor,
            new HubExecutionConverter(),
            new ObjectMapper(),
            properties,
            Clock.systemUTC(),
            trackingStagger);

        HubExecutionDTO dto = customService.execute(request("7", 1000L));
        assertThat(dto.getStatus()).isEqualTo(HubExecutionStatus.SUCCEEDED);
        assertThat(observedMode.get()).isEqualTo(HubExecutionConcurrencyMode.EXCLUSIVE);
        assertThat(trackingStagger.inFlightCount("bilibili")).isEqualTo(0);
        assertThat(trackingStagger.activeSiteCount()).isEqualTo(0);
    }

    /**
     * Intent: Verify that if start stagger wait exceeds the execution deadline,
     * the execution is marked TIMED_OUT with exit code 124, and OpenCLI is never executed.
     * Effectiveness: Injects a coordinator with an unreachable stagger slot, submits an execution,
     * asserts that the execution terminates as TIMED_OUT without invoking executor, and releases reservation.
     */
    @Test
    void shouldMarkExecutionTimedOutWhenStaggerWaitExceedsDeadline() {
        // Coordinator where any subsequent stagger delay is 1 hour in the future
        HubExecutionStartStagger timeoutStagger = new HubExecutionStartStagger(
            3000L,
            5000L,
            System::nanoTime,
            (min, max) -> TimeUnit.HOURS.toNanos(1),
            nanos -> {});

        HubExecutionService customService = new HubExecutionService(
            argvValidator,
            blacklistService,
            outputRuleService,
            router,
            dispatchRegistry,
            repository,
            new HubExecutionArgvBuilder(new HubLocalPathGuard(properties)),
            resources,
            executor,
            new HubExecutionConverter(),
            new ObjectMapper(),
            properties,
            Clock.systemUTC(),
            timeoutStagger);

        // Keep one execution active in stagger so the next execution overlapping with it gets delayed
        timeoutStagger.execute("bilibili", HubExecutionConcurrencyMode.PARALLEL_SAFE, Long.MAX_VALUE, () -> {
            // Second execution submitted with 500ms timeout; its reserved slot is 1 hour away,
            // which immediately exceeds deadline at reservation
            HubExecutionDTO dto = customService.execute(request("7", 500L));
            assertThat(dto.getStatus()).isEqualTo(HubExecutionStatus.TIMED_OUT);
            assertThat(dto.getExitCode()).isEqualTo(124);
        });

        // Executor was never called for the timed out execution
        assertThat(executor.invocations()).isEmpty();
        assertThat(timeoutStagger.inFlightCount("bilibili")).isEqualTo(0);
        assertThat(timeoutStagger.activeSiteCount()).isEqualTo(0);
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
        command.setBrowser(true);
        command.setAccess(HubCommandAccess.READ);
        command.setDefaultWindowMode("background");
        command.setSiteSession(mode);
        return new NormalizedOpenCliArgv(
            command,
            "bilibili/hot",
            List.of(),
            new LinkedHashMap<>(),
            List.of("bilibili", "hot"));
    }

    private static final class AdmissionTestDispatchRegistry extends HubDispatchRegistry {

        private volatile CountDownLatch firstWorkerStarted;

        void waitForFirstSubmissionToReachWorker(CountDownLatch workerStarted) {
            this.firstWorkerStarted = workerStarted;
        }

        @Override
        public <T> Future<T> submit(
            HubInstance instance,
            String executionId,
            Callable<T> task,
            long deadlineNanos,
            Runnable onQueuedDiscard) {
            Future<T> future = super.submit(
                instance, executionId, task, deadlineNanos, onQueuedDiscard);
            CountDownLatch workerStarted = this.firstWorkerStarted;
            if (workerStarted != null) {
                try {
                    if (!workerStarted.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("worker did not start after dispatch acceptance");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting for worker start", ex);
                }
            }
            return future;
        }

    }

    private static final class InMemoryExecutionRepository implements HubExecutionRepository {

        private int addCount;
        private int updateCount;
        private int findCount;
        private int failUpdateAt;
        private boolean failGenerate;
        private boolean failAdd;
        private CountDownLatch addEntered;
        private CountDownLatch allowAdd;
        private boolean blockNextAdd;
        private final Map<String, HubExecution> executions = new LinkedHashMap<>();
        private final List<HubExecutionStatus> updatedStatuses = new CopyOnWriteArrayList<>();

        private void blockNextAdd(CountDownLatch addEntered, CountDownLatch allowAdd) {
            this.addEntered = addEntered;
            this.allowAdd = allowAdd;
            this.blockNextAdd = true;
        }

        @Override
        public synchronized String generateId() {
            if (failGenerate) {
                throw new IllegalStateException("id generator unavailable");
            }
            return UUID.randomUUID().toString();
        }

        @Override
        public synchronized boolean add(HubExecution execution) {
            addCount++;
            if (failAdd) {
                return false;
            }
            if (blockNextAdd) {
                blockNextAdd = false;
                addEntered.countDown();
                try {
                    if (!allowAdd.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("first execution insert was not released");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting for first insert", ex);
                }
            }
            executions.put(execution.getId(), snapshot(execution));
            return true;
        }

        @Override
        public synchronized boolean update(HubExecution execution) {
            updateCount++;
            if (failUpdateAt == updateCount) {
                return false;
            }
            updatedStatuses.add(execution.getStatus());
            executions.put(execution.getId(), snapshot(execution));
            return true;
        }

        @Override
        public synchronized HubExecution findById(String id) {
            findCount++;
            HubExecution execution = executions.get(id);
            return execution == null ? null : snapshot(execution);
        }

        @Override
        public synchronized Page<HubExecution> page(PageQuery pageQuery, String instanceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized boolean markRunningIfPending(String id, java.time.LocalDateTime startedAt) {
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
        public synchronized boolean markCancelledIfPending(
            String id, String errorMessage, java.time.LocalDateTime finishedAt) {
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
        public synchronized boolean markTerminalIfPending(
            String id,
            HubExecutionStatus status,
            String errorMessage,
            Integer exitCode,
            java.time.LocalDateTime finishedAt) {
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

        private static HubExecution snapshot(HubExecution source) {
            HubExecution target = new HubExecution();
            target.setId(source.getId());
            target.setInstanceId(source.getInstanceId());
            target.setInstanceCode(source.getInstanceCode());
            target.setCommandKey(source.getCommandKey());
            target.setSite(source.getSite());
            target.setSiteSession(source.getSiteSession());
            target.setArgv(source.getArgv());
            target.setReuseInstance(source.isReuseInstance());
            target.setStatus(source.getStatus());
            target.setExitCode(source.getExitCode());
            target.setStdout(source.getStdout());
            target.setStdoutTruncated(source.isStdoutTruncated());
            target.setStderr(source.getStderr());
            target.setStderrTruncated(source.isStderrTruncated());
            target.setErrorMessage(source.getErrorMessage());
            target.setTimeoutMillis(source.getTimeoutMillis());
            target.setQueuedAt(source.getQueuedAt());
            target.setStartedAt(source.getStartedAt());
            target.setFinishedAt(source.getFinishedAt());
            target.setCreateTime(source.getCreateTime());
            target.setUpdateTime(source.getUpdateTime());
            return target;
        }

    }

}
