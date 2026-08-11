package fun.fengwk.openclihub.core.instance.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.openclihub.core.execution.runtime.HubDispatchRegistry;
import fun.fengwk.openclihub.core.instance.runtime.test.InMemoryHubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.opencli.daemon.FakeOpenCliDaemonClient;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.core.settings.service.FakeHubSystemSettingsService;
import fun.fengwk.openclihub.share.model.instance.HubInstanceCreateDTO;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * Verifies the {@link HubInstanceRuntimeApplicationRunner} contract:
 * <ul>
 *   <li>ApplicationRunner returns immediately (the caller thread is NOT blocked).</li>
 *   <li>Recovery runs on a background executor.</li>
 *   <li>All rows are normalised to STARTING before any start attempt.</li>
 *   <li>Failures are isolated; later instances still see their chance.</li>
 *   <li>The executor is shut down on bean destroy.</li>
 * </ul>
 */
class HubInstanceRuntimeApplicationRunnerTest {

    private static final String TEST_EXTENSION_ID = "abcdefghijklmnopabcdefghijklmnop";

    private Path dataDir;
    private Path buildInfoPath;
    private FakeOpenCliDaemonClient daemon;
    private FakeInstanceProcessLauncher launcher;
    private InMemoryHubInstanceService instanceService;
    private HubInstanceAllocationService allocationService;
    private HubInstanceLifecycleService lifecycle;
    private HubInstanceRuntimeRegistry registry;
    private OpenCliHubProperties properties;
    private OrphanInstanceScanner scanner;
    private HubInstanceStartCoordinator startCoordinator;
    private HubInstanceRuntimeApplicationRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = Files.createTempDirectory("m4-runner-test");
        buildInfoPath = dataDir.resolve("opencli").resolve("crx").resolve("build-info.json");
        Files.createDirectories(buildInfoPath.getParent());
        Files.writeString(buildInfoPath, "{\"extensionId\":\"" + TEST_EXTENSION_ID + "\"}");
        properties = new OpenCliHubProperties();
        properties.setDataDir(dataDir.toString());
        properties.getRuntime().setDisplayBase(8400);
        properties.getRuntime().setVncPortBase(6700);
        properties.getRuntime().setVncPortMax(6799);
        properties.getBrowser().setStartupTimeoutMillis(300L);
        properties.getVnc().setStartupTimeoutMillis(300L);
        properties.getRuntime().setReadinessPollMillis(5L);

        daemon = new FakeOpenCliDaemonClient();
        launcher = new FakeInstanceProcessLauncher();
        instanceService = new InMemoryHubInstanceService();
        allocationService = new HubInstanceAllocationService(properties);
        registry = new HubInstanceRuntimeRegistry(launcher, allocationService,
            new FakeUnexpectedExitListener());
        startCoordinator = new HubInstanceStartCoordinator(properties);
        lifecycle = new HubInstanceLifecycleService(
            instanceService, registry, launcher, daemon, properties,
            new FakeHubSystemSettingsService(), new ProfileSingletonCleaner(),
            new ChromeProfileFileAccessBootstrap(new ObjectMapper(), buildInfoPath),
            new HubDispatchRegistry(), startCoordinator, Clock.systemUTC());
        scanner = new OrphanInstanceScanner(properties, instanceService);
        runner = new HubInstanceRuntimeApplicationRunner(lifecycle, scanner, properties,
            startCoordinator);
    }

    @AfterEach
    void tearDown() throws IOException {
        runner.destroy();
        deleteRecursively(dataDir);
    }

    @Test
    void shouldReturnImmediatelyFromApplicationRunner() throws Exception {
        // Seed three rows: A wins, B/C fail. The ApplicationRunner must NOT block the caller.
        String a = seedRow("bilibili-a");
        String b = seedRow("bilibili-b");
        String c = seedRow("bilibili-c");
        instanceService.updateState(a, HubInstanceState.RUNNING, null);
        instanceService.updateState(b, HubInstanceState.RUNNING, null);
        instanceService.updateState(c, HubInstanceState.RUNNING, null);
        daemon.setFirstWinsStrategy("ctx-a");

        long start = System.currentTimeMillis();
        runner.run(new DefaultApplicationArguments(new String[0]));
        long elapsed = System.currentTimeMillis() - start;

        // The run() method just submits work and returns. Allow some overhead but it must
        // NOT block on the 300ms start timeout per instance.
        assertThat(elapsed).isLessThan(100L);

        // Shutting down the owned executor waits for the queued recovery to finish, avoiding
        // timing-based assertions while preserving the run() non-blocking check above.
        runner.destroy();

        assertThat(instanceService.get(a).getState())
            .isEqualTo(HubInstanceState.RUNNING);
        assertThat(instanceService.get(b).getState())
            .isEqualTo(HubInstanceState.ERROR);
        assertThat(instanceService.get(c).getState())
            .isEqualTo(HubInstanceState.ERROR);
    }

    @Test
    void shouldReuseAllocationAfterDelete() {
        // Allocation uniqueness is process-local; release() must hand numbers back.
        HubInstanceAllocationService allocator = new HubInstanceAllocationService(properties);
        HubInstanceAllocationService.Allocation a = allocator.allocate();
        allocator.release(a);
        HubInstanceAllocationService.Allocation b = allocator.allocate();
        assertThat(b.displayNumber).isEqualTo(a.displayNumber);
        assertThat(b.vncPort).isEqualTo(a.vncPort);
    }

    /**
     * The recovery sweep runs inside the coordinator barrier: an API create issued while the
     * sweep is mid-flight waits, and after the sweep finishes exactly one Chrome launch per
     * instance has happened (no duplicate runtime from the racing entry point).
     */
    @Test
    void shouldKeepApiStartBehindRecoveryBarrierUntilSweepFinishes() throws Exception {
        BlockingStartingService blocking = new BlockingStartingService();
        instanceService = blocking;
        lifecycle = new HubInstanceLifecycleService(
            instanceService, registry, launcher, daemon, properties,
            new FakeHubSystemSettingsService(), new ProfileSingletonCleaner(),
            new ChromeProfileFileAccessBootstrap(new ObjectMapper(), buildInfoPath),
            new HubDispatchRegistry(), startCoordinator, Clock.systemUTC());
        runner = new HubInstanceRuntimeApplicationRunner(lifecycle, scanner, properties,
            startCoordinator);
        String recoveredId = seedRow("bilibili-recovered");
        // Recovery consumes fetches 1-2 (snapshot + context poll); the API create consumes
        // fetches 3-4, so its own context appears only after recovery has finished.
        daemon.addConnectedContextAfterFetch("ctx-recovered", 2);
        daemon.addConnectedContextAfterFetch("ctx-api", 4);

        runner.run(new DefaultApplicationArguments(new String[0]));
        // The barrier is declared synchronously: it is active the moment run() returns,
        // even though the recovery task may not have started executing yet.
        assertThat(startCoordinator.isRecoveryInProgress())
            .as("recovery barrier must be announced before run() returns")
            .isTrue();
        assertThat(blocking.startingEntered.await(2, TimeUnit.SECONDS))
            .as("recovery must enter the first instance start")
            .isTrue();

        ExecutorService apiExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<HubInstance> apiCreate = apiExecutor.submit(() -> lifecycle.create(createDto("bilibili-api")));
            Thread.sleep(100);
            assertThat(apiCreate.isDone())
                .as("API create must wait behind the recovery barrier")
                .isFalse();

            blocking.releaseStarting.countDown();
            assertThat(apiCreate.get(2, TimeUnit.SECONDS).getState())
                .isEqualTo(HubInstanceState.RUNNING);
        } finally {
            apiExecutor.shutdownNow();
        }

        // Wait for the sweep itself to finish before asserting global state.
        runner.destroy();

        assertThat(instanceService.get(recoveredId).getState())
            .isEqualTo(HubInstanceState.RUNNING);
        assertThat(launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME))
            .as("recovery + API create must each launch exactly one Chrome")
            .isEqualTo(2);
    }

    /**
     * If the recovery task cannot be submitted (executor already shut down), the barrier
     * declared by {@code run()} must be released immediately and must not block API starts.
     */
    @Test
    void shouldReleaseBarrierWhenRecoverySubmitFails() {
        runner.run(new DefaultApplicationArguments(new String[0]));
        runner.destroy();

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
            .isInstanceOf(RejectedExecutionException.class);
        assertThat(startCoordinator.isRecoveryInProgress())
            .as("submit failure must release the recovery barrier")
            .isFalse();
        assertThat(startCoordinator.runStart(() -> true))
            .as("the coordinator must stay usable after submit failure")
            .isTrue();
    }

    /**
     * Destroying the runner right after {@code run()} (the recovery task may still be queued
     * or already interrupted) must release the barrier — it can never stay active forever.
     */
    @Test
    void shouldReleaseBarrierOnDestroyBeforeRecoveryStarts() {
        runner.run(new DefaultApplicationArguments(new String[0]));
        assertThat(startCoordinator.isRecoveryInProgress()).isTrue();

        runner.destroy();

        assertThat(startCoordinator.isRecoveryInProgress())
            .as("destroy must release the recovery barrier even before the sweep ran")
            .isFalse();
        assertThat(startCoordinator.runStart(() -> true)).isTrue();
    }

    private String seedRow(String code) {
        var inst = new HubInstance();
        inst.setCode(code);
        inst.setDisplayName(code);
        inst.setWebsites(List.of("bilibili"));
        inst.setMaxPending(5);
        inst.setState(HubInstanceState.STOPPED);
        instanceService.create(inst);
        return inst.getId();
    }

    private static HubInstanceCreateDTO createDto(String code) {
        HubInstanceCreateDTO dto = new HubInstanceCreateDTO();
        dto.setCode(code);
        dto.setDisplayName(code + " display");
        dto.setWebsites(List.of("bilibili"));
        dto.setMaxPending(5);
        return dto;
    }

    /**
     * Blocks inside the STARTING state write so tests can hold the recovery sweep mid-start
     * while the coordinator lock is still held.
     */
    private static final class BlockingStartingService extends InMemoryHubInstanceService {

        private final CountDownLatch startingEntered = new CountDownLatch(1);
        private final CountDownLatch releaseStarting = new CountDownLatch(1);

        @Override
        public void updateState(String id, HubInstanceState newState, String errorMessage) {
            if (newState == HubInstanceState.STARTING) {
                startingEntered.countDown();
                try {
                    if (!releaseStarting.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting for test release");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test wait interrupted", ex);
                }
            }
            super.updateState(id, newState, errorMessage);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            var sorted = stream.sorted((a, b) -> b.compareTo(a)).toList();
            for (Path p : sorted) {
                Files.deleteIfExists(p);
            }
        }
    }

}
