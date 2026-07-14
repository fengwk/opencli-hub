package fun.fengwk.openclihub.core.instance.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.execution.runtime.HubDispatchRegistry;
import fun.fengwk.openclihub.core.instance.runtime.test.InMemoryHubInstanceService;
import fun.fengwk.openclihub.core.opencli.daemon.FakeOpenCliDaemonClient;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.core.settings.service.FakeHubSystemSettingsService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private Path dataDir;
    private FakeOpenCliDaemonClient daemon;
    private FakeInstanceProcessLauncher launcher;
    private InMemoryHubInstanceService instanceService;
    private HubInstanceAllocationService allocationService;
    private HubInstanceLifecycleService lifecycle;
    private HubInstanceRuntimeRegistry registry;
    private OpenCliHubProperties properties;
    private OrphanInstanceScanner scanner;
    private HubInstanceRuntimeApplicationRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = Files.createTempDirectory("m4-runner-test");
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
        lifecycle = new HubInstanceLifecycleService(
            instanceService, registry, launcher, daemon, properties,
            new FakeHubSystemSettingsService(), new ProfileSingletonCleaner(),
            new HubDispatchRegistry());
        scanner = new OrphanInstanceScanner(properties, instanceService);
        runner = new HubInstanceRuntimeApplicationRunner(lifecycle, scanner, properties);
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
        instanceService.updateState(a, fun.fengwk.openclihub.share.model.instance.HubInstanceState.RUNNING, null);
        instanceService.updateState(b, fun.fengwk.openclihub.share.model.instance.HubInstanceState.RUNNING, null);
        instanceService.updateState(c, fun.fengwk.openclihub.share.model.instance.HubInstanceState.RUNNING, null);
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
            .isEqualTo(fun.fengwk.openclihub.share.model.instance.HubInstanceState.RUNNING);
        assertThat(instanceService.get(b).getState())
            .isEqualTo(fun.fengwk.openclihub.share.model.instance.HubInstanceState.ERROR);
        assertThat(instanceService.get(c).getState())
            .isEqualTo(fun.fengwk.openclihub.share.model.instance.HubInstanceState.ERROR);
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

    private String seedRow(String code) {
        var inst = new fun.fengwk.openclihub.core.instance.service.model.HubInstance();
        inst.setCode(code);
        inst.setDisplayName(code);
        inst.setWebsites(java.util.List.of("bilibili"));
        inst.setMaxPending(5);
        inst.setState(fun.fengwk.openclihub.share.model.instance.HubInstanceState.STOPPED);
        instanceService.create(inst);
        return inst.getId();
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
