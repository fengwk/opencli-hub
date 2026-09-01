package fun.fengwk.openclihub.core.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.execution.runtime.HubDispatchRegistry;
import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntime;
import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntimeRegistry;
import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntimeSnapshot;
import fun.fengwk.openclihub.core.instance.runtime.UnexpectedExitListener;
import fun.fengwk.openclihub.core.instance.runtime.test.InMemoryHubInstanceService;
import fun.fengwk.openclihub.core.instance.service.HubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.instance.service.validation.CatalogWebsiteLookup;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HubExecutionRouter}. The router is the single source of truth
 * for "which Instance runs this command"; the contract it pins comes from
 * {@code docs/technical-design.md §20}.
 *
 * <p>Coverage:
 * <ul>
 *   <li>explicit instanceId — state, websites, runtime, live context, queue capacity, no failover,</li>
 *   <li>automatic — least-busy + priority/id tie-breaks, candidates, no-instance fallback,</li>
 *   <li>candidate rejection — runtime absent, context offline, stale contextId mismatch, queue full.</li>
 * </ul>
 */
class HubExecutionRouterTest {

    private AtomicLong runtimeSeq;
    private FakeInstanceService instanceService;
    private HubInstanceRuntimeRegistry runtimeRegistry;
    private HubDispatchRegistry dispatchRegistry;
    private HubExecutionRouter router;

    @BeforeEach
    void setUp() {
        runtimeSeq = new AtomicLong(0);
        instanceService = new FakeInstanceService();
        // The runtime registry requires a launcher / allocation service / exit listener
        // because its constructor wires them; tests only use the get/contains surface.
        runtimeRegistry = new HubInstanceRuntimeRegistry(
            new NoopInstanceProcessLauncher(), new NoopAllocationService(), new NoopExitListener());
        dispatchRegistry = new HubDispatchRegistry();
        router = new HubExecutionRouter(instanceService, runtimeRegistry, dispatchRegistry);
    }

    /**
     * Single running instance should be picked; verify the chosen instance matches the
     * only RUNNING one and that EXECUTION's websites include the requested site.
     */
    @Test
    void shouldPickTheOnlyRunningInstanceAutomatically() throws Exception {
        HubInstance a = persist("a", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-a");
        registerRuntime(a, "ctx-a");

        HubInstance chosen = router.chooseInstance("bilibili", null);

        assertThat(chosen.getId()).isEqualTo(a.getId());
    }

    /**
     * State != RUNNING must be excluded.
     */
    @Test
    void shouldSkipInstancesThatAreNotRunning() throws Exception {
        HubInstance a = persist("a", List.of("bilibili"), HubInstanceState.STOPPED, "ctx-a");
        persist("b", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-b");

        assertThatThrownBy(() -> router.chooseInstance("bilibili", null))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.NO_INSTANCE_AVAILABLE.getCode()));
    }

    /**
     * Website mismatch is excluded from automatic and fails explicitly for the explicit
     * path with INSTANCE_WEBSITE_NOT_ENABLED.
     */
    @Test
    void shouldSkipInstancesThatDoNotSupportTheRequestedSite() throws Exception {
        HubInstance a = persist("a", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-a");
        HubInstance b = persist("b", List.of("chatgpt"), HubInstanceState.RUNNING, "ctx-b");
        registerRuntime(a, "ctx-a");
        registerRuntime(b, "ctx-b");

        HubInstance chosen = router.chooseInstance("chatgpt", null);
        assertThat(chosen.getCode()).isEqualTo("b");
    }

    @Test
    void shouldThrowWebsiteNotEnabledForExplicitMismatch() throws Exception {
        HubInstance a = persist("a", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-a");
        assertThatThrownBy(() -> router.chooseInstance("chatgpt", a.getId()))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.INSTANCE_WEBSITE_NOT_ENABLED.getCode()));
    }

    /**
     * When load is equal, higher priority wins (default 0).
     */
    @Test
    void shouldPreferHigherPriorityWhenLoadIsEqual() throws Exception {
        HubInstance low = persist("low", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-low", 5, 0);
        HubInstance high = persist("high", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-high", 5, 10);
        registerRuntime(low, "ctx-low");
        registerRuntime(high, "ctx-high");

        HubInstance chosen = router.chooseInstance("bilibili", null);
        assertThat(chosen.getCode()).isEqualTo("high");
    }

    @Test
    void shouldSelectTheInstanceWithLowerAdmissionLoad() throws Exception {
        HubInstance a = persist("a", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-a");
        HubInstance b = persist("b", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-b");
        registerRuntime(a, "ctx-a");
        registerRuntime(b, "ctx-b");

        // Give B more accepted work so least-busy routing picks A.
        busy(a, 2);
        busy(b, 5);

        HubInstance chosen = router.chooseInstance("bilibili", null);
        assertThat(chosen.getId()).isEqualTo(a.getId());
    }

    /**
     * The dispatcher acceptance-window test proves the counter is non-zero while executor
     * metrics are both zero; this test pins that routing uses the counter rather than the
     * runtime display snapshot's active + pending load.
     */
    @Test
    void shouldRouteUsingAcceptedLoadBeforeExecutorMetricsExposeTask() throws Exception {
        HubInstance accepted = persist(
            "accepted", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-accepted");
        HubInstance idle = persist(
            "idle", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-idle");
        registerRuntime(accepted, "ctx-accepted");
        registerRuntime(idle, "ctx-idle");

        HubDispatchRegistry routingRegistry = mock(HubDispatchRegistry.class);
        HubInstanceRuntimeSnapshot emptyMetrics = new HubInstanceRuntimeSnapshot(
            true, null, null, 0, 0);
        when(routingRegistry.getSnapshot(accepted.getId())).thenReturn(emptyMetrics);
        when(routingRegistry.getSnapshot(idle.getId())).thenReturn(emptyMetrics);
        when(routingRegistry.getTotalCapacity(accepted.getId())).thenReturn(6);
        when(routingRegistry.getTotalCapacity(idle.getId())).thenReturn(6);
        when(routingRegistry.getRoutingLoad(accepted.getId())).thenReturn(1);
        when(routingRegistry.getRoutingLoad(idle.getId())).thenReturn(0);

        HubExecutionRouter routingRouter = new HubExecutionRouter(
            instanceService, runtimeRegistry, routingRegistry);

        assertThat(routingRouter.chooseInstance("bilibili", null).getId())
            .isEqualTo(idle.getId());
        verify(routingRegistry).getRoutingLoad(accepted.getId());
        verify(routingRegistry).getRoutingLoad(idle.getId());
    }

    /**
     * Tie-break by ascending instance id (deterministic). Both instances have identical
     * load, so the smaller id wins.
     */
    @Test
    void shouldBreakTiesByAscendingInstanceId() throws Exception {
        HubInstance lower = persist("lower", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-lower");
        HubInstance higher = persist("higher", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-higher");
        // Force persistence ordering: persist order already gives lower id first.
        registerRuntime(lower, "ctx-lower");
        registerRuntime(higher, "ctx-higher");
        busy(lower, 1);
        busy(higher, 1);

        HubInstance chosen = router.chooseInstance("bilibili", null);
        assertThat(chosen.getId()).isEqualTo(lower.getId());
    }

    /**
     * Explicit instance failing any candidate check raises a typed 4xx and never
     * falls back to automatic selection.
     */
    @Test
    void shouldNotFailOverWhenExplicitInstanceHasNoRuntime() throws Exception {
        HubInstance a = persist("a", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-a");
        // No runtime registration.
        HubInstance b = persist("b", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-b");
        registerRuntime(b, "ctx-b");

        assertThatThrownBy(() -> router.chooseInstance("bilibili", a.getId()))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.INSTANCE_RUNTIME_NOT_FOUND.getCode()));
    }

    @Test
    void shouldRejectRuntimeWithoutRegisteredDispatcher() throws Exception {
        HubInstance instance = persist(
            "a", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-a");
        registerRuntime(instance, "ctx-a");
        dispatchRegistry.unregister(instance.getId());

        assertThatThrownBy(() -> router.chooseInstance("bilibili", instance.getId()))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.INSTANCE_RUNTIME_NOT_FOUND.getCode()));
    }

    @Test
    void shouldThrowContextOfflineWhenLiveContextIsBlank() throws Exception {
        HubInstance a = persist("a", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-a");
        registerRuntime(a, "");

        assertThatThrownBy(() -> router.chooseInstance("bilibili", a.getId()))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.INSTANCE_CONTEXT_NOT_CONNECTED.getCode()));
    }

    @Test
    void shouldRejectStaleContextIdWhenLiveDiffersFromPersisted() throws Exception {
        HubInstance a = persist("a", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-a");
        registerRuntime(a, "ctx-a-rebound");

        assertThatThrownBy(() -> router.chooseInstance("bilibili", a.getId()))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.INSTANCE_CONTEXT_NOT_CONNECTED.getCode()));
    }

    @Test
    void shouldRejectQueueFullForExplicit() throws Exception {
        HubInstance a = persist("a", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-a", 1);
        registerRuntime(a, "ctx-a");
        dispatchRegistry.register(a);
        // Drive the worker via the non-blocking submit so the test thread is not
        // blocked inside dispatch(...). A long sleep keeps the worker active long
        // enough for the test to fill the queue and assert before the active task ends.
        dispatchRegistry.submit(a,
            () -> { Thread.sleep(1500); return "active"; }, Long.MAX_VALUE);
        try {
            for (int i = 0; i < 200 && dispatchRegistry.getSnapshot(a.getId())
                .getActiveCount() == 0; i++) {
                Thread.sleep(5);
            }
            dispatchRegistry.submit(a, () -> "queued", Long.MAX_VALUE);
            assertThat(dispatchRegistry.getSnapshot(a.getId()).getPendingCount())
                .as("queue should report one pending task ahead of the worker")
                .isOne();
            assertThatThrownBy(() -> router.chooseInstance("bilibili", a.getId()))
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                    .isEqualTo(HubErrorCodes.INSTANCE_QUEUE_FULL.getCode()));
        } finally {
            dispatchRegistry.unregister(a.getId());
        }
    }

    /**
     * Equal accepted load is a routing tie even when instances have different concurrency limits.
     * Priority must therefore decide the winner instead of maxConcurrency weighting the load.
     */
    @Test
    void shouldTreatEqualAdmissionLoadAsTieRegardlessOfMaxConcurrency() throws Exception {
        HubInstance a = persist(
            "a", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-a", 2, 5, 10);
        HubInstance b = persist(
            "b", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-b", 4, 5, 0);
        registerRuntime(a, "ctx-a");
        registerRuntime(b, "ctx-b");

        busy(a, 1);
        busy(b, 1);

        HubInstance chosen = router.chooseInstance("bilibili", null);
        assertThat(chosen.getId())
            .as("equal accepted load must fall through to priority regardless of maxConcurrency")
            .isEqualTo(a.getId());
    }

    /**
     * Raw accepted load remains the primary routing key even when normalization by
     * maxConcurrency would have preferred the more heavily loaded instance.
     */
    @Test
    void shouldPreferLowerAdmissionLoadRegardlessOfMaxConcurrency() throws Exception {
        HubInstance highConcurrency = persist(
            "high-concurrency", List.of("bilibili"), HubInstanceState.RUNNING,
            "ctx-high-concurrency", 4, 5, 0);
        HubInstance lowConcurrency = persist(
            "low-concurrency", List.of("bilibili"), HubInstanceState.RUNNING,
            "ctx-low-concurrency", 1, 5, 0);
        registerRuntime(highConcurrency, "ctx-high-concurrency");
        registerRuntime(lowConcurrency, "ctx-low-concurrency");

        HubDispatchRegistry routingRegistry = mock(HubDispatchRegistry.class);
        HubInstanceRuntimeSnapshot registered = new HubInstanceRuntimeSnapshot(
            true, null, null, 0, 0);
        when(routingRegistry.getSnapshot(highConcurrency.getId())).thenReturn(registered);
        when(routingRegistry.getSnapshot(lowConcurrency.getId())).thenReturn(registered);
        when(routingRegistry.getTotalCapacity(highConcurrency.getId())).thenReturn(9);
        when(routingRegistry.getTotalCapacity(lowConcurrency.getId())).thenReturn(6);
        when(routingRegistry.getRoutingLoad(highConcurrency.getId())).thenReturn(2);
        when(routingRegistry.getRoutingLoad(lowConcurrency.getId())).thenReturn(1);
        // The removed normalized comparator would prefer 2/4 over 1/1.
        when(routingRegistry.getMaxConcurrency(highConcurrency.getId())).thenReturn(4);
        when(routingRegistry.getMaxConcurrency(lowConcurrency.getId())).thenReturn(1);
        HubExecutionRouter routingRouter = new HubExecutionRouter(
            instanceService, runtimeRegistry, routingRegistry);

        HubInstance chosen = routingRouter.chooseInstance("bilibili", null);
        assertThat(chosen.getId())
            .as("one accepted task is less busy than two, regardless of maxConcurrency")
            .isEqualTo(lowConcurrency.getId());
    }

    /** Full checks use the live dispatcher capacity after a hot limit update. */
    @Test
    void shouldRejectUsingHotUpdatedDispatcherCapacity() throws Exception {
        HubInstance instance = persist(
            "a", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-a");
        registerRuntime(instance, "ctx-a");
        dispatchRegistry.updateLimits(instance.getId(), 1, 0);
        busy(instance, 1);

        assertThatThrownBy(() -> router.chooseInstance("bilibili", instance.getId()))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.INSTANCE_QUEUE_FULL.getCode()));
    }

    /**
     * Automatic routing returns INSTANCE_QUEUE_FULL (429) when eligible instances are full.
     * when otherwise-eligible candidates exist but all are full.
     */
    @Test
    void shouldReturnQueueFullWhenAllOtherwiseEligibleCandidatesAreFullInAutomaticRouting() throws Exception {
        HubInstance a = persist("a", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-a", 1, 0, 0);
        HubInstance b = persist("b", List.of("bilibili"), HubInstanceState.RUNNING, "ctx-b", 1, 0, 0);
        registerRuntime(a, "ctx-a");
        registerRuntime(b, "ctx-b");

        busy(a, 1); // Capacity is 1 + 0 = 1, so load=1 is full
        busy(b, 1); // Capacity is 1 + 0 = 1, so load=1 is full

        assertThatThrownBy(() -> router.chooseInstance("bilibili", null))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.INSTANCE_QUEUE_FULL.getCode()));
    }

    /**
     * When there are no eligible instances configured for a website at all, return NO_INSTANCE_AVAILABLE.
     */
    @Test
    void shouldReturnNoInstanceAvailableWhenNoEligibleInstanceExists() {
        assertThatThrownBy(() -> router.chooseInstance("unknown-site", null))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(t -> assertThat(((ThrowableConventionErrorCode) t).getCode())
                .isEqualTo(HubErrorCodes.NO_INSTANCE_AVAILABLE.getCode()));
    }

    private HubInstance persist(String code, List<String> websites, HubInstanceState state, String ctx) {
        return persist(code, websites, state, ctx, 1, 5, 0);
    }

    private HubInstance persist(String code, List<String> websites, HubInstanceState state, String ctx, int maxPending) {
        return persist(code, websites, state, ctx, 1, maxPending, 0);
    }

    private HubInstance persist(String code, List<String> websites, HubInstanceState state, String ctx,
                                int maxPending, int priority) {
        return persist(code, websites, state, ctx, 1, maxPending, priority);
    }

    private HubInstance persist(String code, List<String> websites, HubInstanceState state, String ctx,
                                int maxConcurrency, int maxPending, int priority) {
        HubInstance instance = new HubInstance();
        instance.setId(Long.toString(runtimeSeq.incrementAndGet()));
        instance.setCode(code);
        instance.setDisplayName(code);
        instance.setState(state);
        instance.setContextId(ctx);
        instance.setWebsites(websites);
        instance.setMaxConcurrency(maxConcurrency);
        instance.setMaxPending(maxPending);
        instance.setPriority(priority);
        instance.setStateChangedAt(java.time.LocalDateTime.now());
        // Persist via InMemoryHubInstanceService so candidate lookups work.
        InMemoryHubInstanceService inMem = (InMemoryHubInstanceService) instanceService;
        inMem.create(instance);
        return instance;
    }

    private void registerRuntime(HubInstance instance, String liveContextId) {
        HubInstanceRuntime runtime = new HubInstanceRuntime();
        runtime.setInstanceId(instance.getId());
        runtime.setInstanceCode(instance.getCode());
        runtime.setDisplayNumber(99);
        runtime.setVncPort(5900);
        runtime.setContextId(liveContextId);
        runtimeRegistry.register(runtime);
        dispatchRegistry.register(instance);
    }

    private void busy(HubInstance instance, int load) {
        // Inject accepted work through the dispatch registry directly to simulate routing load.
        // We piggy-back on a Thread that holds the worker in active state (sleep), then
        // use non-blocking submit() for the remaining work.
        dispatchRegistry.register(instance);
        Thread filler = new Thread(() -> {
            try {
                dispatchRegistry.dispatch(instance,
                    () -> { try { Thread.sleep(800); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return "active"; }, Long.MAX_VALUE);
            } catch (RuntimeException ignored) {
                // best-effort: the active worker may not have been drained yet
            }
        });
        filler.setDaemon(true);
        filler.start();
        // Poll until the active worker has been observed, then push `load-1` more tasks.
        try {
            for (int i = 0; i < 100 && dispatchRegistry.getSnapshot(instance.getId())
                .getActiveCount() == 0; i++) {
                Thread.sleep(5);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        for (int idx = 0; idx < load - 1; idx++) {
            final int captured = idx;
            try {
                dispatchRegistry.submit(instance, () -> "queued-" + captured, Long.MAX_VALUE);
            } catch (RuntimeException ex) {
                // Queue is full — stop once we exhaust capacity.
                break;
            }
        }
    }

    /**
     * Minimal in-memory instance service backed by the legacy test double. We extend it
     * with the {@link HubInstanceService} contract required by the router and provide a
     * dedicated surface so {@code persist(...)} can inject runtime/queue capacity.
     */
    static class FakeInstanceService extends InMemoryHubInstanceService {
        FakeInstanceService() {
            super((CatalogWebsiteLookup) () -> Set.of("bilibili", "chatgpt"));
        }
    }

    /** No-op launcher that never spawns a process. */
    static class NoopInstanceProcessLauncher implements fun.fengwk.openclihub.core.instance.runtime.InstanceProcessLauncher {
        @Override
        public LaunchedProcess launchXvfb(int displayNumber, java.nio.file.Path logPath) {
            throw new UnsupportedOperationException();
        }
        @Override
        public LaunchedProcess launchOpenbox(int displayNumber, java.nio.file.Path logPath) {
            throw new UnsupportedOperationException();
        }
        @Override
        public LaunchedProcess launchX11vnc(int displayNumber, int port, java.nio.file.Path logPath) {
            throw new UnsupportedOperationException();
        }
        @Override
        public LaunchedProcess launchChrome(List<String> extraArgs, java.util.Map<String, String> env, java.nio.file.Path logPath) {
            throw new UnsupportedOperationException();
        }
        @Override
        public void stop(ProcessHandle handle) {
            throw new UnsupportedOperationException();
        }
    }

    static class NoopAllocationService extends fun.fengwk.openclihub.core.instance.runtime.HubInstanceAllocationService {
        NoopAllocationService() {
            super(new fun.fengwk.openclihub.core.property.OpenCliHubProperties());
        }
        @Override
        public Allocation allocate() {
            throw new UnsupportedOperationException();
        }
        @Override
        public void release(Allocation allocation) {
        }
    }

    static class NoopExitListener implements UnexpectedExitListener {
        @Override
        public void watch(String instanceId, HubInstanceRuntime runtime) { }
        @Override
        public void unwatch(String instanceId) { }
    }

}
