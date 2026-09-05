package fun.fengwk.openclihub.core.instance.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import fun.fengwk.openclihub.core.execution.runtime.HubDispatchRegistry;
import fun.fengwk.openclihub.core.instance.runtime.test.InMemoryHubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.opencli.catalog.DefaultOpenCliCommandCatalog;
import fun.fengwk.openclihub.core.opencli.catalog.FileOpenCliCatalogSource;
import fun.fengwk.openclihub.core.opencli.daemon.FakeOpenCliDaemonClient;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliDaemonCommandResponse;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliProfileSnapshot;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.core.settings.service.FakeHubSystemSettingsService;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.instance.HubInstanceCreateDTO;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import fun.fengwk.openclihub.share.model.instance.HubInstanceUpdateDTO;
import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for the runtime lifecycle. Uses fake launcher + fake daemon so no
 * real OS process or HTTP server is needed.
 *
 * <p>Coverage maps to docs/technical-design.md §16.2-19.5 and §17-18.
 */
class HubInstanceLifecycleServiceTest {

    private static final String TEST_EXTENSION_ID = "abcdefghijklmnopabcdefghijklmnop";
    private static final Path CATALOG_FIXTURE = Path.of("src/test/resources/opencli/cli-manifest.json");

    private Path dataDir;
    private Path buildInfoPath;
    private FakeOpenCliDaemonClient daemon;
    private FakeInstanceProcessLauncher launcher;
    private InMemoryHubInstanceService instanceService;
    private HubInstanceRuntimeRegistry registry;
    private HubInstanceLifecycleService lifecycle;
    private HubInstanceAllocationService allocationService;
    private FakeUnexpectedExitListener watcher;
    private OpenCliHubProperties properties;
    private FakeHubSystemSettingsService settingsService;
    private HubDispatchRegistry dispatchRegistry;
    private HubInstanceStartCoordinator startCoordinator;
    private OpenCliCommandCatalog commandCatalog;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = Files.createTempDirectory("m4-runtime-test");
        buildInfoPath = dataDir.resolve("opencli").resolve("crx").resolve("build-info.json");
        Files.createDirectories(buildInfoPath.getParent());
        Files.writeString(buildInfoPath, "{\"extensionId\":\"" + TEST_EXTENSION_ID + "\"}");
        properties = newProps();
        // Allocation service uses real filesystem scanning — pick a base range that we
        // proactively clean below.
        properties.getRuntime().setDisplayBase(8200);
        properties.getRuntime().setVncPortBase(6300);
        properties.getRuntime().setVncPortMax(6399);
        properties.getBrowser().setStartupTimeoutMillis(500L);
        properties.getVnc().setStartupTimeoutMillis(500L);
        properties.getRuntime().setReadinessPollMillis(5L);
        cleanupX11BaseRange(properties.getRuntime().getDisplayBase());
        daemon = new FakeOpenCliDaemonClient();
        launcher = new FakeInstanceProcessLauncher();
        commandCatalog = new DefaultOpenCliCommandCatalog(new FileOpenCliCatalogSource(CATALOG_FIXTURE));
        instanceService = new InMemoryHubInstanceService(commandCatalog::listWebsites);
        allocationService = new HubInstanceAllocationService(properties);
        watcher = new FakeUnexpectedExitListener();
        registry = new HubInstanceRuntimeRegistry(launcher, allocationService, watcher);
        settingsService = new FakeHubSystemSettingsService();
        dispatchRegistry = new HubDispatchRegistry();
        startCoordinator = new HubInstanceStartCoordinator(properties);
        lifecycle = newLifecycle();
    }

    private static void cleanupX11BaseRange(int base) {
        for (int i = 0; i < 64; i++) {
            int n = base + i;
            try {
                Files.deleteIfExists(Path.of("/tmp/.X" + n + "-lock"));
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(Path.of("/tmp/.X11-unix/X" + n));
            } catch (IOException ignored) {
            }
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        registry.stopAll();
        launcher.cleanupSignals();
        cleanupX11Leak();
        deleteRecursively(dataDir);
    }

    /**
     * Removes any /tmp/.X{N}-lock and /tmp/.X11-unix/X{N} that this test process may have
     * written into the configured base range. Without this, repeated tests against a
     * host-shared /tmp would see ghost resources and the allocation service would refuse
     * to advance past them.
     */
    private void cleanupX11Leak() {
        int base = properties.getRuntime().getDisplayBase();
        for (int i = 0; i < 32; i++) {
            int n = base + i;
            try {
                Files.deleteIfExists(Path.of("/tmp/.X" + n + "-lock"));
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(Path.of("/tmp/.X11-unix/X" + n));
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------------------------
    //  CREATE
    // ---------------------------------------------------------------------------------

    @Test
    void shouldCreateInstanceEndToEnd() throws IOException {
        AtomicBoolean bootstrapBeforeChromeLaunch = new AtomicBoolean();
        launcher.setChromeLaunchHook(args -> {
            try {
                assertThat(fileAccessPreference(chromeDirFromArgs(args))).isTrue();
                bootstrapBeforeChromeLaunch.set(true);
            } catch (IOException ex) {
                throw new AssertionError("failed to inspect profile before Chrome launch", ex);
            }
        });
        // The daemon first observes the empty state at the pre-create snapshot (fetch #1).
        // On the second fetch (post-chrome-start) we surface the new context id.
        daemon.addConnectedContextAfterFetch("ctx-success", 2);

        HubInstanceCreateDTO dto = createDto("bilibili-a");
        HubInstance created = lifecycle.create(dto);

        assertThat(bootstrapBeforeChromeLaunch).as("profile bootstrap must precede Chrome launch")
            .isTrue();
        // DB row exists with all fields populated.
        assertThat(created.getId()).matches("[0-9a-f-]{36}");
        assertThat(created.getCode()).isEqualTo("bilibili-a");
        assertThat(created.getState()).isEqualTo(HubInstanceState.RUNNING);
        assertThat(created.getContextId()).isEqualTo("ctx-success");
        assertThat(created.getStateChangedAt()).isNotNull();

        // Runtime registered.
        HubInstanceRuntime runtime = registry.get(created.getId());
        assertThat(runtime).isNotNull();
        assertThat(runtime.getProcesses()).hasSize(4);
        assertThat(runtime.getContextId()).isEqualTo("ctx-success");
        assertThat(watcher.watchedCount()).isEqualTo(1);

        // On-disk directory layout.
        Path dir = dataDir.resolve("instances").resolve(created.getId());
        assertThat(Files.exists(dir.resolve("chrome"))).isTrue();
        assertThat(fileAccessPreference(dir.resolve("chrome"))).isTrue();
        assertThat(Files.exists(dir.resolve("logs"))).isTrue();
        assertThat(Files.exists(dir.resolve(".creating"))).isFalse();
        for (String log : new String[] { "xvfb.log", "openbox.log", "x11vnc.log", "chrome.log" }) {
            assertThat(Files.exists(dir.resolve("logs").resolve(log))).isTrue();
        }

        // Runtime snapshot combines process allocation with dispatcher load.
        HubInstanceRuntimeSnapshot snapshot = lifecycle.getSnapshot(created.getId());
        assertThat(snapshot.isRegistered()).isTrue();
        assertThat(snapshot.getDisplayNumber()).isEqualTo(runtime.getDisplayNumber());
        assertThat(snapshot.getVncPort()).isEqualTo(runtime.getVncPort());
        assertThat(snapshot.getActiveCount()).isZero();
        assertThat(snapshot.getPendingCount()).isZero();

        String chromeCommand = lastChromeCommand();
        assertThat(chromeCommand).contains("--disable-gpu", "--no-proxy-server");
        assertThat(chromeCommand).doesNotContain("--disable-software-rasterizer");
    }

    /** A newly visible RUNNING row must already have runtime and dispatcher registrations. */
    @Test
    void shouldRegisterRuntimeAndDispatcherBeforeCreateRowBecomesVisible() {
        ObservingInstanceService observingService = new ObservingInstanceService();
        useInstanceService(observingService, new HubDispatchRegistry());
        AtomicBoolean observed = new AtomicBoolean();
        observingService.beforeCreate = instance -> {
            assertThat(registry.contains(instance.getId())).isTrue();
            assertThat(dispatchRegistry.getMaxPending(instance.getId()))
                .isEqualTo(instance.getMaxPending());
            assertThat(watcher.watchedCount())
                .as("watcher must not report ERROR before the row exists")
                .isZero();
            observed.set(true);
        };
        daemon.addConnectedContextAfterFetch("ctx-visible-create", 2);

        HubInstance created = lifecycle.create(createDto("bilibili-visible-create"));

        assertThat(observed).isTrue();
        assertThat(created.getState()).isEqualTo(HubInstanceState.RUNNING);
        assertThat(watcher.watchedCount()).isOne();
    }

    /** The existing-row start path also installs runtime resources before publishing RUNNING. */
    @Test
    void shouldRegisterRuntimeAndDispatcherBeforeStartPublishesRunning() {
        ObservingInstanceService observingService = new ObservingInstanceService();
        useInstanceService(observingService, new HubDispatchRegistry());
        String id = seedPersistedInstance("bilibili-visible-start", "ctx-visible-start");
        OpenCliProfileSnapshot profile = new OpenCliProfileSnapshot();
        profile.setContextId("ctx-visible-start");
        profile.setExtensionConnected(true);
        daemon.setProfiles(List.of(profile));
        AtomicBoolean observed = new AtomicBoolean();
        observingService.beforeStateUpdate = (updatedId, state) -> {
            if (state == HubInstanceState.RUNNING) {
                assertThat(registry.contains(updatedId)).isTrue();
                assertThat(dispatchRegistry.getMaxPending(updatedId)).isEqualTo(5);
                observed.set(true);
            }
        };

        lifecycle.start(id);

        assertThat(observed).isTrue();
    }

    /** A process dying after context discovery is caught before create persists RUNNING. */
    @Test
    void shouldEnsureProcessesAliveBeforeCreatePersistsRunning() {
        HubDispatchRegistry killingDispatchRegistry = new HubDispatchRegistry() {
            @Override
            public void register(HubInstance instance) {
                super.register(instance);
                launcher.lastHandle(HubInstanceRuntime.HubInstanceProcessKind.CHROME).kill();
            }
        };
        useInstanceService(new InMemoryHubInstanceService(), killingDispatchRegistry);
        daemon.addConnectedContextAfterFetch("ctx-dies-before-persist", 2);

        assertThatThrownBy(() -> lifecycle.create(createDto("bilibili-dies-before-persist")))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_START_FAILED));

        assertThat(instanceService.list()).isEmpty();
        assertThat(registry.list()).isEmpty();
        assertThat(watcher.watchedCount()).isZero();
    }

    @Test
    void shouldApplyGlobalCustomProxyToInheritedInstance() {
        settingsService.set(HubProxyMode.CUSTOM, " HTTP://Proxy.Example:8080 ");
        String id = seedPersistedInstance("bilibili-global-proxy", "ctx-global-proxy");
        daemon.addConnectedContextAfterFetch("ctx-global-proxy", 2);

        lifecycle.start(id);

        assertThat(lastChromeCommand())
            .contains("--disable-gpu")
            .contains("--proxy-server=http://proxy.example:8080")
            .contains("--proxy-bypass-list=localhost;127.0.0.1;[::1]")
            .doesNotContain("--no-proxy-server", "--disable-software-rasterizer");
    }

    @Test
    void shouldLetInstanceDirectOverrideGlobalCustomProxy() {
        settingsService.set(HubProxyMode.CUSTOM, "http://proxy.example:8080");
        String id = seedPersistedInstance(
            "bilibili-direct-proxy", "ctx-direct-proxy", HubProxyMode.DIRECT, null);
        daemon.addConnectedContextAfterFetch("ctx-direct-proxy", 2);

        lifecycle.start(id);

        assertThat(lastChromeCommand())
            .contains("--disable-gpu", "--no-proxy-server")
            .doesNotContain("--proxy-server=", "--disable-software-rasterizer");
    }

    @Test
    void shouldLetInstanceCustomProxyOverrideGlobalDirect() {
        String id = seedPersistedInstance(
            "bilibili-custom-proxy", "ctx-custom-proxy",
            HubProxyMode.CUSTOM, "socks5://Proxy.Example:1080");
        daemon.addConnectedContextAfterFetch("ctx-custom-proxy", 2);

        lifecycle.start(id);

        assertThat(lastChromeCommand())
            .contains("--disable-gpu", "--proxy-server=socks5://proxy.example:1080")
            .contains("--proxy-bypass-list=localhost;127.0.0.1;[::1]")
            .doesNotContain("--no-proxy-server", "--disable-software-rasterizer");
    }

    @Test
    void shouldApplyChangedGlobalProxyOnlyWhenInstanceIsManuallyRestarted() {
        String id = seedPersistedInstance("bilibili-restart-proxy", "ctx-restart-proxy");
        daemon.addConnectedContextAfterFetch("ctx-restart-proxy", 2);
        lifecycle.start(id);
        assertThat(lastChromeCommand()).contains("--no-proxy-server");
        int chromeLaunches = launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME);

        settingsService.set(HubProxyMode.CUSTOM, "https://proxy.example:8443");

        // Updating persisted settings has no lifecycle callback, so the running Chrome remains.
        assertThat(launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME))
            .isEqualTo(chromeLaunches);
        assertThat(lastChromeCommand()).contains("--no-proxy-server");

        lifecycle.restart(id);

        assertThat(launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME))
            .isEqualTo(chromeLaunches + 1);
        assertThat(lastChromeCommand()).contains("--proxy-server=https://proxy.example:8443");
    }

    @Test
    void shouldValidateCreateBeforeStartingProcesses() {
        HubInstanceCreateDTO dto = createDto("INVALID CODE");

        assertThatThrownBy(() -> lifecycle.create(dto))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_ARGUMENT_INVALID));

        for (HubInstanceRuntime.HubInstanceProcessKind kind
            : HubInstanceRuntime.HubInstanceProcessKind.values()) {
            assertThat(launcher.launchCount(kind)).isZero();
        }
        assertThat(daemon.ensureRunningCalls()).isEmpty();
        assertThat(Files.exists(dataDir.resolve("instances"))).isFalse();
    }

    @Test
    void shouldRejectDuplicateCodeBeforeStartingAnotherRuntime() {
        daemon.addConnectedContextAfterFetch("ctx-first", 2);
        lifecycle.create(createDto("bilibili-duplicate"));
        int chromeLaunches = launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME);

        assertThatThrownBy(() -> lifecycle.create(createDto("bilibili-duplicate")))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_CODE_CONFLICT));

        assertThat(launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME))
            .isEqualTo(chromeLaunches);
    }

    /** A symlinked instances root must fail before lifecycle creation can write outside dataDir. */
    @Test
    void shouldRejectSymlinkInstancesRootBeforeCreatingExternalDirectory() throws IOException {
        Path outside = Files.createDirectories(dataDir.resolve("outside-root"));
        Path sentinel = Files.writeString(outside.resolve("sentinel"), "kept");
        Path rootLink = Files.createSymbolicLink(dataDir.resolve("instances"), outside);

        assertThatThrownBy(() -> lifecycle.create(createDto("bilibili-unsafe-root")))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_START_FAILED));

        assertThat(Files.isSymbolicLink(rootLink)).isTrue();
        assertThat(sentinel).hasContent("kept");
        assertThat(instanceService.list()).isEmpty();
        for (HubInstanceRuntime.HubInstanceProcessKind kind
            : HubInstanceRuntime.HubInstanceProcessKind.values()) {
            assertThat(launcher.launchCount(kind)).isZero();
        }
    }

    /** Broken symlinks and regular files are unsafe roots even though they have no directory target. */
    @Test
    void shouldRejectBrokenSymlinkAndRegularFileInstancesRoots() throws IOException {
        Path missingTarget = dataDir.resolve("missing-instances-root");
        Path rootLink = Files.createSymbolicLink(dataDir.resolve("instances"), missingTarget);

        assertThatThrownBy(() -> lifecycle.create(createDto("bilibili-broken-root")))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_START_FAILED));
        assertThat(Files.isSymbolicLink(rootLink)).isTrue();
        assertThat(Files.exists(missingTarget)).isFalse();

        Files.delete(rootLink);
        Path rootFile = Files.writeString(dataDir.resolve("instances"), "not a directory");
        assertThatThrownBy(() -> lifecycle.create(createDto("bilibili-file-root")))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_START_FAILED));
        assertThat(rootFile).hasContent("not a directory");
    }

    @Test
    void shouldCleanCreateDirectoryWhenDaemonStartupFails() throws IOException {
        daemon.failEnsureWith(new fun.fengwk.openclihub.core.opencli.daemon.OpenCliDaemonException(
            "daemon unavailable"));

        assertThatThrownBy(() -> lifecycle.create(createDto("bilibili-daemon-fail")))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_START_FAILED));

        assertThat(instanceService.list()).isEmpty();
        Path instances = dataDir.resolve("instances");
        if (Files.exists(instances)) {
            try (var stream = Files.list(instances)) {
                assertThat(stream.toList()).isEmpty();
            }
        }
    }

    @Test
    void shouldNotRestartSharedDaemonWhenRestartingOneOfSeveralInstances() {
        String otherId = seedPersistedInstance("bilibili-other-running", "ctx-other-running");
        HubInstanceRuntime otherRuntime = new HubInstanceRuntime();
        otherRuntime.setInstanceId(otherId);
        registry.register(otherRuntime);

        String id = seedPersistedInstance("bilibili-shared-daemon", "ctx-shared-daemon");
        HubInstanceRuntime targetRuntime = new HubInstanceRuntime();
        targetRuntime.setInstanceId(id);
        registry.register(targetRuntime);

        assertThatThrownBy(() -> lifecycle.restart(id))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_START_FAILED));

        // An unhealthy shared daemon must fail the new start instead of disconnecting the
        // already-running browser through `opencli daemon restart`.
        assertThat(daemon.ensureRunningCalls()).isEmpty();
        assertThat(launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME)).isZero();
        assertThat(registry.get(otherId)).isSameAs(otherRuntime);
    }

    @Test
    void shouldReverseOrderCleanupWhenLauncherFails() throws IOException {
        // Fail after Xvfb and openbox have started to verify partial-start rollback.
        launcher.failNextLaunch(HubInstanceRuntime.HubInstanceProcessKind.X11VNC);
        daemon.addConnectedContextAfterFetch("ctx-xyz", 2);

        HubInstanceCreateDTO dto = createDto("bilibili-fail");
        assertThatThrownBy(() -> lifecycle.create(dto))
            .isInstanceOf(ThrowableConventionErrorCode.class);

        // No DB row or runtime; every process started before the failure is stopped.
        assertThat(instanceService.list()).isEmpty();
        assertThat(registry.list()).isEmpty();
        assertThat(launcher.lastHandle(HubInstanceRuntime.HubInstanceProcessKind.XVFB).isAlive())
            .isFalse();
        assertThat(launcher.lastHandle(HubInstanceRuntime.HubInstanceProcessKind.OPENBOX).isAlive())
            .isFalse();
        assertThat(launcher.lastHandle(HubInstanceRuntime.HubInstanceProcessKind.X11VNC).isAlive())
            .isFalse();
        // No directory leftover (numeric only, no DB row -> removed).
        Path instances = dataDir.resolve("instances");
        if (Files.exists(instances)) {
            try (var s = Files.list(instances)) {
                assertThat(s.toList()).isEmpty();
            }
        }
    }

    @Test
    void shouldFailImmediatelyWhenChromeExitsBeforeContextConnects() {
        launcher.failNextLaunch(HubInstanceRuntime.HubInstanceProcessKind.CHROME);

        assertThatThrownBy(() -> lifecycle.create(createDto("bilibili-chrome-exit")))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_START_FAILED));

        assertThat(instanceService.list()).isEmpty();
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void shouldCleanUpArtifactsWhenDbInsertFailsAfterRuntimeRunning() throws IOException {
        // Force the database insert to fail AFTER the runtime is RUNNING. The lifecycle must
        // tear down runtime, dispatcher, processes and directory and leave NO row.
        daemon.addConnectedContextAfterFetch("ctx-insert-fail", 2);
        instanceService.simulateInsertFailure(
            new RuntimeException("simulated insert failure"));

        HubInstanceCreateDTO dto = createDto("bilibili-insfail");
        assertThatThrownBy(() -> lifecycle.create(dto))
            .isInstanceOf(ThrowableConventionErrorCode.class);

        assertThat(instanceService.list()).isEmpty();
        Path instances = dataDir.resolve("instances");
        if (Files.exists(instances)) {
            try (var s = Files.list(instances)) {
                assertThat(s.toList()).isEmpty();
            }
        }
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void shouldReportZeroContextAsTimeout() {
        // daemon never reports a new contextId -> EXTENSION_CONNECT_TIMEOUT must surface
        // to the caller (the create path no longer wraps it in INSTANCE_START_FAILED).
        HubInstanceCreateDTO dto = createDto("bilibili-timeout");
        assertThatThrownBy(() -> lifecycle.create(dto))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.EXTENSION_CONNECT_TIMEOUT));

        assertThat(instanceService.list()).isEmpty();
    }

    @Test
    void shouldReportMultipleContextAsAmbiguous() {
        // daemon reports 2 new ids -> CONTEXT_ID_AMBIGUOUS must surface verbatim.
        daemon.addConnectedContextAfterFetch("ctx-a", 2);
        daemon.addConnectedContextAfterFetch("ctx-b", 2);
        HubInstanceCreateDTO dto = createDto("bilibili-ambig");
        assertThatThrownBy(() -> lifecycle.create(dto))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.CONTEXT_ID_AMBIGUOUS));
    }

    // ---------------------------------------------------------------------------------
    //  START (existing)
    // ---------------------------------------------------------------------------------

    /** Invalid route IDs must not allocate an unbounded lifecycle lock or start OS processes. */
    @Test
    void shouldRejectUnsupportedIdBeforeAllocatingLifecycleState() {
        int lockCount = registry.lifecycleLockCount();
        assertThatThrownBy(() -> lifecycle.start("not-an-id"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_NOT_FOUND));
        assertThat(registry.lifecycleLockCount()).isEqualTo(lockCount);

        for (HubInstanceRuntime.HubInstanceProcessKind kind
            : HubInstanceRuntime.HubInstanceProcessKind.values()) {
            assertThat(launcher.launchCount(kind)).isZero();
        }
    }

    /** Supported but absent ids are rejected before start/stop/delete allocate lifecycle locks. */
    @Test
    void shouldRejectMissingIdBeforeAllocatingLifecycleLock() {
        String missingId = UUID.randomUUID().toString();
        int lockCount = registry.lifecycleLockCount();

        assertThatThrownBy(() -> lifecycle.start(missingId))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.INSTANCE_NOT_FOUND));
        assertThatThrownBy(() -> lifecycle.stop(missingId))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.INSTANCE_NOT_FOUND));
        assertThatThrownBy(() -> lifecycle.delete(missingId))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.INSTANCE_NOT_FOUND));
        assertThatThrownBy(() -> lifecycle.restart(missingId))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.INSTANCE_NOT_FOUND));
        assertThatThrownBy(() -> lifecycle.update(missingId, new HubInstanceUpdateDTO()))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.INSTANCE_NOT_FOUND));

        assertThat(registry.lifecycleLockCount()).isEqualTo(lockCount);
    }

    @Test
    void shouldStartExistingWithExpectedContextId() throws IOException {
        // Seed DB row directly so we can start without going through create().
        String id = seedPersistedInstance("bilibili-existing", "ctx-existing");
        // Pre-snapshot the daemon with the expected id already present (fetch #1).
        OpenCliProfileSnapshot ps = new OpenCliProfileSnapshot();
        ps.setContextId("ctx-existing");
        ps.setExtensionConnected(true);
        daemon.setProfiles(List.of(ps));

        HubInstance started = lifecycle.start(id);
        assertThat(started.getState()).isEqualTo(HubInstanceState.RUNNING);
        assertThat(started.getContextId()).isEqualTo("ctx-existing");
        HubInstanceRuntime runtime = registry.get(id);
        assertThat(runtime).isNotNull();
        assertThat(runtime.getContextId()).isEqualTo("ctx-existing");
    }

    /** Existing Instance directories must not be replaced with symlinks before a restart. */
    @Test
    void shouldRejectSymlinkedInstanceDirectoryBeforeStarting() throws IOException {
        String id = seedPersistedInstance("bilibili-instance-link", "ctx-instance-link");
        Path instancesRoot = Files.createDirectories(dataDir.resolve("instances"));
        Path outside = Files.createDirectories(dataDir.resolve("outside-instance"));
        Path sentinel = Files.writeString(outside.resolve("sentinel"), "kept");
        Path instanceLink = Files.createSymbolicLink(instancesRoot.resolve(id), outside);

        assertThatThrownBy(() -> lifecycle.start(id))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_START_FAILED));

        assertThat(Files.isSymbolicLink(instanceLink)).isTrue();
        assertThat(sentinel).hasContent("kept");
        assertThat(instanceService.get(id).getState()).isEqualTo(HubInstanceState.STOPPED);
    }

    @Test
    void shouldStartExistingWithZeroNewContext() {
        // daemon never reports any new id and instance has no expected -> timeout.
        String id = seedPersistedInstance("bilibili-zero", null);
        assertThatThrownBy(() -> lifecycle.start(id))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.EXTENSION_CONNECT_TIMEOUT));
        assertThat(registry.get(id)).isNull();
        assertThat(instanceService.get(id).getState()).isEqualTo(HubInstanceState.ERROR);
    }

    @Test
    void shouldAutoRebindWhenExpectedMissingAndUniqueNew() {
        // expected = "ctx-expected" but daemon only ever shows "ctx-unique".
        String id = seedPersistedInstance("bilibili-rebind", "ctx-expected");
        daemon.addConnectedContextAfterFetch("ctx-unique", 2);

        HubInstance started = lifecycle.start(id);
        assertThat(started.getState()).isEqualTo(HubInstanceState.RUNNING);
        assertThat(started.getContextId()).isEqualTo("ctx-unique");
    }

    @Test
    void shouldRefuseRebindWhenContextAlreadyBound() {
        String ownerId = seedPersistedInstance("bilibili-owner", "ctx-collision");
        String id = seedPersistedInstance("bilibili-conflict", "ctx-expected");
        // The collision appears only after the before-snapshot, modelling a newly connected
        // Profile whose contextId is already persisted on another instance.
        daemon.addConnectedContextAfterFetch("ctx-collision", 2);

        assertThatThrownBy(() -> lifecycle.start(id))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.CONTEXT_ID_CONFLICT));

        assertThat(instanceService.get(id).getState()).isEqualTo(HubInstanceState.ERROR);
        assertThat(instanceService.get(ownerId).getState()).isEqualTo(HubInstanceState.STOPPED);
        assertThat(instanceService.get(ownerId).getContextId()).isEqualTo("ctx-collision");
    }

    /** Editable updates are serialized with start and cannot overwrite its final RUNNING state. */
    @Test
    void shouldSerializeEditableUpdateWithLifecycleTransition() throws Exception {
        BlockingUpdateStateService blockingService = new BlockingUpdateStateService();
        useInstanceService(blockingService, new HubDispatchRegistry());
        String id = seedPersistedInstance("bilibili-serialized", "ctx-serialized");
        OpenCliProfileSnapshot profile = new OpenCliProfileSnapshot();
        profile.setContextId("ctx-serialized");
        profile.setExtensionConnected(true);
        daemon.setProfiles(List.of(profile));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch updateInvocationStarted = new CountDownLatch(1);
        try {
            Future<HubInstance> startFuture = executor.submit(() -> lifecycle.start(id));
            assertThat(blockingService.startingUpdateEntered.await(1, TimeUnit.SECONDS)).isTrue();

            Future<HubInstance> updateFuture = executor.submit(() -> {
                updateInvocationStarted.countDown();
                return lifecycle.update(id, updateDto(instanceService.get(id), 2));
            });
            assertThat(updateInvocationStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(blockingService.editableUpdateEntered.await(150, TimeUnit.MILLISECONDS))
                .as("editable write must wait behind the lifecycle lock")
                .isFalse();

            blockingService.releaseStartingUpdate.countDown();
            assertThat(startFuture.get(2, TimeUnit.SECONDS).getState())
                .isEqualTo(HubInstanceState.RUNNING);
            assertThat(updateFuture.get(2, TimeUnit.SECONDS).getMaxPending()).isEqualTo(2);

            HubInstance persisted = instanceService.get(id);
            assertThat(persisted.getState()).isEqualTo(HubInstanceState.RUNNING);
            assertThat(persisted.getMaxPending()).isEqualTo(2);
            assertThat(dispatchRegistry.getMaxPending(id)).isEqualTo(2);
        } finally {
            blockingService.releaseStartingUpdate.countDown();
            executor.shutdownNow();
        }
    }

    /** Running dispatchers receive maxPending immediately while proxy changes await restart. */
    @Test
    void shouldPropagateRunningMaxPendingWithoutRestartingChrome() {
        String id = seedPersistedInstance("bilibili-update-running", "ctx-update-running");
        daemon.addConnectedContextAfterFetch("ctx-update-running", 2);
        lifecycle.start(id);
        int chromeLaunches = launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME);

        HubInstanceUpdateDTO update = updateDto(instanceService.get(id), 1);
        update.setDisplayName("updated display");
        update.setWebsites(List.of("chatgpt"));
        update.setProxyMode(HubProxyMode.CUSTOM);
        update.setProxyServer("http://proxy.example:8080");
        HubInstance updated = lifecycle.update(id, update);

        assertThat(updated.getState()).isEqualTo(HubInstanceState.RUNNING);
        assertThat(updated.getWebsites()).containsExactly("chatgpt");
        assertThat(dispatchRegistry.getMaxPending(id)).isOne();
        assertThat(launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME))
            .isEqualTo(chromeLaunches);
        assertThat(lastChromeCommand()).contains("--no-proxy-server");
    }

    @Test
    void shouldBindPersistentSiteActiveTabThroughConnectedProfile() {
        String id = seedPersistedInstance("chatgpt-bind-success", "ctx-bind-success", List.of("chatgpt"));
        OpenCliProfileSnapshot profile = connectedProfile("ctx-bind-success");
        daemon.setProfiles(List.of(profile));

        lifecycle.start(id);
        lifecycle.bindActiveTab(id, "chatgpt");

        assertThat(daemon.bindContextIds()).containsExactly("ctx-bind-success");
        assertThat(daemon.bindSessions())
            .as("the endpoint must bind the fixed adapter session for the persistent site")
            .containsExactly("site:chatgpt");
    }

    @Test
    void shouldBindArbitraryThirdPersistentSiteThroughConnectedProfile() {
        String id = seedPersistedInstance("12306-bind-success", "ctx-bind-12306", List.of("12306"));
        OpenCliProfileSnapshot profile = connectedProfile("ctx-bind-12306");
        daemon.setProfiles(List.of(profile));

        lifecycle.start(id);
        lifecycle.bindActiveTab(id, "12306");

        assertThat(daemon.bindContextIds()).containsExactly("ctx-bind-12306");
        assertThat(daemon.bindSessions())
            .as("the endpoint must bind the fixed adapter session for 12306")
            .containsExactly("site:12306");
    }

    @Test
    void shouldRejectActiveTabBindWhenSiteIsNotEnabledOnInstance() {
        String id = seedPersistedInstance("bind-disabled-site", "ctx-disabled-site", List.of("bilibili"));
        OpenCliProfileSnapshot profile = connectedProfile("ctx-disabled-site");
        daemon.setProfiles(List.of(profile));
        lifecycle.start(id);

        assertThatThrownBy(() -> lifecycle.bindActiveTab(id, "chatgpt"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_WEBSITE_NOT_ENABLED));
        assertThat(daemon.bindContextIds()).isEmpty();
    }

    @Test
    void shouldRejectActiveTabBindWhenSiteIsEphemeralOrUnknownOrBlank() {
        String id = seedPersistedInstance("bind-invalid-site", "ctx-invalid-site", List.of("bilibili", "chatgpt"));
        OpenCliProfileSnapshot profile = connectedProfile("ctx-invalid-site");
        daemon.setProfiles(List.of(profile));
        lifecycle.start(id);

        List<String> invalidSites = Arrays.asList(
            null,
            "",
            "   ",
            "bilibili",
            "unknown-site",
            "site:chatgpt",
            " chatgpt ",
            "../injection",
            "chatgpt/extra"
        );

        for (String invalidSite : invalidSites) {
            assertThatThrownBy(() -> lifecycle.bindActiveTab(id, invalidSite))
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .extracting("code")
                .isEqualTo(prefixed(HubErrorCodes.INSTANCE_ARGUMENT_INVALID));
        }
        assertThat(daemon.bindContextIds()).isEmpty();
    }

    @Test
    void shouldRejectActiveTabBindWhenInstanceIsNotRunning() {
        String id = seedPersistedInstance("chatgpt-bind-stopped", "ctx-bind-stopped", List.of("chatgpt"));

        assertThatThrownBy(() -> lifecycle.bindActiveTab(id, "chatgpt"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_NOT_RUNNING));
        assertThat(daemon.bindContextIds()).isEmpty();
    }

    @Test
    void shouldRejectActiveTabBindWhenDaemonProfileIsDisconnected() {
        String id = seedPersistedInstance("chatgpt-bind-offline", "ctx-bind-offline", List.of("chatgpt"));
        daemon.setProfiles(List.of(connectedProfile("ctx-bind-offline")));
        lifecycle.start(id);
        daemon.clearProfiles();

        assertThatThrownBy(() -> lifecycle.bindActiveTab(id, "chatgpt"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_CONTEXT_NOT_CONNECTED));
        assertThat(daemon.bindContextIds()).isEmpty();
    }

    @Test
    void shouldRejectActiveTabBindWhileInstanceIsBusy() throws Exception {
        String id = seedPersistedInstance("chatgpt-bind-busy", "ctx-bind-busy", List.of("chatgpt"));
        daemon.setProfiles(List.of(connectedProfile("ctx-bind-busy")));
        lifecycle.start(id);
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        ExecutorService submitter = Executors.newSingleThreadExecutor();
        try {
            submitter.submit(() -> dispatchRegistry.dispatch(instanceService.get(id), () -> {
                activeStarted.countDown();
                releaseActive.await(2, TimeUnit.SECONDS);
                return null;
            }, Long.MAX_VALUE));
            assertThat(activeStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> lifecycle.bindActiveTab(id, "chatgpt"))
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .extracting("code")
                .isEqualTo(prefixed(HubErrorCodes.INSTANCE_BUSY));
            assertThat(daemon.bindContextIds()).isEmpty();
        } finally {
            releaseActive.countDown();
            submitter.shutdownNow();
            submitter.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldPreserveDaemonBindFailureAndHint() {
        String id = seedPersistedInstance("chatgpt-bind-failed", "ctx-bind-failed", List.of("chatgpt"));
        daemon.setProfiles(List.of(connectedProfile("ctx-bind-failed")));
        lifecycle.start(id);
        OpenCliDaemonCommandResponse failure = new OpenCliDaemonCommandResponse();
        failure.setId("fake-bind");
        failure.setOk(false);
        failure.setErrorCode("bound_tab_not_found");
        failure.setError("No debuggable tab found");
        failure.setErrorHint("Focus the target Chrome tab/window, then retry bind.");
        daemon.setBindResponse(failure);

        assertThatThrownBy(() -> lifecycle.bindActiveTab(id, "chatgpt"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .hasMessageContaining("No debuggable tab found")
            .hasMessageContaining("Focus the target Chrome tab/window")
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_TAB_BIND_FAILED));
    }

    // ---------------------------------------------------------------------------------

    @Test
    void shouldStopRunningInstanceAndKeepDirectory() throws IOException {
        String id = seedPersistedInstance("bilibili-stop", "ctx-stop");
        daemon.addConnectedContextAfterFetch("ctx-stop", 2);
        lifecycle.start(id);

        lifecycle.stop(id);

        assertThat(instanceService.get(id).getState()).isEqualTo(HubInstanceState.STOPPED);
        assertThat(registry.get(id)).isNull();
        assertThat(watcher.unwatchedCount()).isEqualTo(1);
        Path dir = dataDir.resolve("instances").resolve(id);
        // Profile preserved on stop.
        assertThat(Files.exists(dir.resolve("chrome"))).isTrue();
    }

    @Test
    void shouldRestartInstanceAndPreserveProfile() throws IOException {
        String id = seedPersistedInstance("bilibili-restart", "ctx-restart");
        daemon.addConnectedContextAfterFetch("ctx-restart", 2);
        lifecycle.start(id);
        Path chromeDir = dataDir.resolve("instances").resolve(id).resolve("chrome");
        Files.writeString(chromeDir.resolve("marker.txt"), "kept-across-restart");

        lifecycle.restart(id);

        assertThat(instanceService.get(id).getState()).isEqualTo(HubInstanceState.RUNNING);
        assertThat(fileAccessPreference(chromeDir)).isTrue();
        // Profile marker preserved.
        assertThat(Files.exists(chromeDir.resolve("marker.txt"))).isTrue();
    }

    @Test
    void shouldRejectDeleteOnBusyDispatcher() throws Exception {
        // busy requires a registered dispatcher with non-zero load. We use a separate
        // dispatcher that we can submit a blocking task to; the main thread then asks the
        // lifecycle service to delete and must be told BUSY.
        String id = seedPersistedInstance("bilibili-busy", "ctx-busy");
        HubDispatchRegistry dispatcher = new HubDispatchRegistry();
        HubInstance inst = instanceService.get(id);
        dispatcher.register(inst);

        java.util.concurrent.CountDownLatch blocker = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch taskStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService submitter = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            submitter.submit(() -> dispatcher.dispatch(inst, () -> {
                taskStarted.countDown();
                try {
                    blocker.await();
                } catch (InterruptedException ignored) {
                }
                return null;
            }, Long.MAX_VALUE));
            // Wait for the dispatched task to actually be running.
            assertThat(taskStarted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            sleepQuietly(50);
            HubInstanceLifecycleService busyLifecycle = newLifecycle(instanceService, dispatcher);
            assertThatThrownBy(() -> busyLifecycle.delete(id))
                .isInstanceOf(ThrowableConventionErrorCode.class)
                .extracting("code")
                .isEqualTo(prefixed(HubErrorCodes.INSTANCE_BUSY));
        } finally {
            blocker.countDown();
            submitter.shutdown();
            submitter.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            dispatcher.unregister(id);
        }
    }

    @Test
    void shouldDeleteInstanceAndDirectorySafely() throws IOException {
        String id = seedPersistedInstance("bilibili-delete", "ctx-delete");
        daemon.addConnectedContextAfterFetch("ctx-delete", 2);
        lifecycle.start(id);
        assertThat(registry.lifecycleLockCount()).isOne();

        Path dir = dataDir.resolve("instances").resolve(id);
        // Create a symlink inside the instance dir; delete must not follow it.
        Path target = dataDir.resolve("external.txt");
        Files.writeString(target, "external");
        Path link = dir.resolve("logs").resolve("escape");
        Files.createSymbolicLink(link, target);
        assertThat(Files.exists(link)).isTrue();

        lifecycle.delete(id);

        assertThat(Files.exists(dir)).isFalse();
        // External file still exists (we didn't follow the symlink).
        assertThat(Files.exists(target)).isTrue();
        // DB row gone.
        assertThatThrownBy(() -> instanceService.get(id))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_NOT_FOUND));
        assertThat(registry.lifecycleLockCount()).isZero();
    }

    /** Delete must validate the root before changing state, unregistering dispatch, or deleting rows. */
    @Test
    void shouldRejectSymlinkInstancesRootBeforeDeletingExternalDirectory() throws IOException {
        String id = seedPersistedInstance("bilibili-delete-unsafe-root", "ctx-delete-unsafe-root");
        Path outside = Files.createDirectories(dataDir.resolve("outside-delete-root").resolve(id));
        Path sentinel = Files.writeString(outside.resolve("sentinel"), "kept");
        Path rootLink = Files.createSymbolicLink(dataDir.resolve("instances"), outside.getParent());

        assertThatThrownBy(() -> lifecycle.delete(id))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_DELETE_FAILED));

        assertThat(Files.isSymbolicLink(rootLink)).isTrue();
        assertThat(sentinel).hasContent("kept");
        assertThat(instanceService.get(id).getState()).isEqualTo(HubInstanceState.STOPPED);
    }

    // ---------------------------------------------------------------------------------
    //  RECOVERY (ApplicationRunner path)
    // ---------------------------------------------------------------------------------

    @Test
    void shouldNormalizeAllToStartingAndRecoverInOrder() {
        // Seed three rows to verify recovery follows creation order.
        String a = seedPersistedInstance("bilibili-a", null);
        String b = seedPersistedInstance("bilibili-b", null);
        String c = seedPersistedInstance("bilibili-c", null);
        // Make them all RUNNING so we can confirm normalize changes them.
        instanceService.updateState(a, HubInstanceState.RUNNING, null);
        instanceService.updateState(b, HubInstanceState.RUNNING, null);
        instanceService.updateState(c, HubInstanceState.RUNNING, null);

        List<String> orderSeen = new ArrayList<>();
        lifecycle.normalizeAllStatesToStarting();
        for (HubInstance inst : instanceService.list()) {
            orderSeen.add(inst.getId());
            assertThat(inst.getState()).isEqualTo(HubInstanceState.STARTING);
        }
        assertThat(orderSeen).containsExactly(a, b, c);

        // Three starts back-to-back: each takes its own beforeSnapshot (1 fetch) and one
        // subsequent fetch (2) that reveals the new context. Total 6 fetches -> 3 ctxs.
        daemon.addConnectedContextAfterFetch("ctx-a", 2);
        daemon.addConnectedContextAfterFetch("ctx-b", 4);
        daemon.addConnectedContextAfterFetch("ctx-c", 6);
        lifecycle.recoverAll(instanceService.list());

        assertThat(instanceService.get(a).getState()).isEqualTo(HubInstanceState.RUNNING);
        assertThat(instanceService.get(b).getState()).isEqualTo(HubInstanceState.RUNNING);
        assertThat(instanceService.get(c).getState()).isEqualTo(HubInstanceState.RUNNING);
        assertThat(registry.contains(a)).isTrue();
        assertThat(registry.contains(b)).isTrue();
        assertThat(registry.contains(c)).isTrue();
    }

    @Test
    void shouldIsolateFailureDuringRecovery() {
        // Single-threaded recovery must isolate failures. We use a callback-driven daemon so
        // the result is deterministic regardless of wall-clock poll cadence: only the FIRST
        // start observes a connected context; subsequent starts see no new ids and time out.
        daemon.setFirstWinsStrategy("ctx-a");

        String a = seedPersistedInstance("bilibili-a", null);
        String b = seedPersistedInstance("bilibili-b", null);
        String c = seedPersistedInstance("bilibili-c", null);

        lifecycle.recoverAll(instanceService.list());

        // Recovery is creation-time ordered: the first instance wins the one context, then both later
        // failures are isolated instead of aborting the recovery loop.
        assertThat(instanceService.get(a).getState()).isEqualTo(HubInstanceState.RUNNING);
        assertThat(instanceService.get(b).getState()).isEqualTo(HubInstanceState.ERROR);
        assertThat(instanceService.get(c).getState()).isEqualTo(HubInstanceState.ERROR);
        assertThat(registry.contains(a)).isTrue();
        assertThat(registry.contains(b)).isFalse();
        assertThat(registry.contains(c)).isFalse();
    }

    /**
     * The recovery sweep holds the coordinator barrier: an API start issued while the sweep
     * is mid-flight waits, then runs after the sweep — and both entry points together launch
     * exactly one Chrome per instance.
     */
    @Test
    void shouldHoldApiStartBehindRecoveryBarrierUntilSweepFinishes() throws Exception {
        BlockingUpdateStateService blocking = new BlockingUpdateStateService();
        useInstanceService(blocking, new HubDispatchRegistry());
        String recoveredId = seedPersistedInstance("bilibili-recovered", null);
        String apiId = seedPersistedInstance("bilibili-api", null);
        // Recovery consumes fetches 1-2 (snapshot + context poll); the API start consumes
        // fetches 3-4, so its own context appears only after recovery has finished.
        daemon.addConnectedContextAfterFetch("ctx-recovered", 2);
        daemon.addConnectedContextAfterFetch("ctx-api", 4);

        ExecutorService recoveryExecutor = Executors.newSingleThreadExecutor();
        ExecutorService apiExecutor = Executors.newSingleThreadExecutor();
        try {
            // Mirror the ApplicationRunner: declare the barrier synchronously, then run the
            // sweep asynchronously and release the barrier in finally.
            startCoordinator.beginRecovery();
            Future<Void> recovery = recoveryExecutor.submit(() -> {
                try {
                    // Recover only the blocked instance; the API instance must not be
                    // touched by the sweep so the API start exercises the barrier itself.
                    lifecycle.recoverAll(List.of(instanceService.get(recoveredId)));
                } finally {
                    startCoordinator.completeRecovery();
                }
                return null;
            });
            assertThat(blocking.startingUpdateEntered.await(2, TimeUnit.SECONDS))
                .as("recovery must enter the first instance start")
                .isTrue();

            Future<HubInstance> apiStart = apiExecutor.submit(() -> lifecycle.start(apiId));
            Thread.sleep(100);
            assertThat(apiStart.isDone())
                .as("API start must wait behind the recovery barrier")
                .isFalse();
            assertThat(launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME))
                .as("no Chrome may launch while recovery is blocked before its first start")
                .isZero();

            blocking.releaseStartingUpdate.countDown();
            assertThat(apiStart.get(2, TimeUnit.SECONDS).getState())
                .isEqualTo(HubInstanceState.RUNNING);
            recovery.get(2, TimeUnit.SECONDS);
        } finally {
            blocking.releaseStartingUpdate.countDown();
            recoveryExecutor.shutdownNow();
            apiExecutor.shutdownNow();
        }

        assertThat(instanceService.get(recoveredId).getState()).isEqualTo(HubInstanceState.RUNNING);
        assertThat(instanceService.get(apiId).getState()).isEqualTo(HubInstanceState.RUNNING);
        assertThat(launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME))
            .as("recovery + API start must each launch exactly one Chrome")
            .isEqualTo(2);
    }

    /**
     * Concurrent API starts of the same instance must never launch a duplicate runtime: the
     * coordinator serialises them and the per-instance lock makes the loser fail with
     * INSTANCE_BUSY before touching any process.
     */
    @Test
    void shouldNotLaunchDuplicateRuntimeForConcurrentStartsOfSameInstance() throws Exception {
        String id = seedPersistedInstance("bilibili-concurrent-start", null);
        daemon.addConnectedContextAfterFetch("ctx-concurrent-start", 2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HubInstance> first = executor.submit(() -> lifecycle.start(id));
            Future<HubInstance> second = executor.submit(() -> lifecycle.start(id));

            HubInstance winner = null;
            RuntimeException loser = null;
            for (Future<HubInstance> future : List.of(first, second)) {
                try {
                    HubInstance result = future.get(3, TimeUnit.SECONDS);
                    assertThat(winner).as("only one concurrent start may win").isNull();
                    winner = result;
                } catch (ExecutionException ex) {
                    assertThat(loser).as("only one concurrent start may lose").isNull();
                    loser = (RuntimeException) ex.getCause();
                }
            }
            assertThat(winner).isNotNull();
            assertThat(winner.getState()).isEqualTo(HubInstanceState.RUNNING);
            assertThat(loser).isNotNull();
            assertThat(loser).isInstanceOf(ThrowableConventionErrorCode.class)
                .extracting("code")
                .isEqualTo(prefixed(HubErrorCodes.INSTANCE_BUSY));
        } finally {
            executor.shutdownNow();
        }

        assertThat(launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME))
            .as("the losing start must not launch a second Chrome")
            .isEqualTo(1);
        assertThat(registry.get(id)).isNotNull();
    }

    /**
     * Concurrent API starts of different instances are globally serialised so daemon context
     * discovery never overlaps: both succeed with their own unique context and exactly one
     * Chrome launch each.
     */
    @Test
    void shouldSerializeConcurrentStartsOfDifferentInstances() throws Exception {
        String a = seedPersistedInstance("bilibili-concurrent-a", null);
        String b = seedPersistedInstance("bilibili-concurrent-b", null);
        // Whichever start wins the lock consumes fetches 1-2, the other fetches 3-4.
        daemon.addConnectedContextAfterFetch("ctx-concurrent-a", 2);
        daemon.addConnectedContextAfterFetch("ctx-concurrent-b", 4);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HubInstance> first = executor.submit(() -> lifecycle.start(a));
            Future<HubInstance> second = executor.submit(() -> lifecycle.start(b));
            assertThat(first.get(3, TimeUnit.SECONDS).getState()).isEqualTo(HubInstanceState.RUNNING);
            assertThat(second.get(3, TimeUnit.SECONDS).getState()).isEqualTo(HubInstanceState.RUNNING);
        } finally {
            executor.shutdownNow();
        }

        assertThat(launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME))
            .as("two serialised starts must launch exactly two Chromes")
            .isEqualTo(2);
        assertThat(instanceService.get(a).getContextId()).isEqualTo("ctx-concurrent-a");
        assertThat(instanceService.get(b).getContextId()).isEqualTo("ctx-concurrent-b");
    }

    /** Concurrent restarts of the same instance are serialised; each restart launches Chrome once. */
    @Test
    void shouldSerializeConcurrentRestarts() throws Exception {
        String id = seedPersistedInstance("bilibili-restart-race", "ctx-restart-race");
        daemon.addConnectedContextAfterFetch("ctx-restart-race", 2);
        lifecycle.start(id);
        assertThat(launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME)).isEqualTo(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> first = executor.submit(() -> {
                lifecycle.restart(id);
                return null;
            });
            Future<Void> second = executor.submit(() -> {
                lifecycle.restart(id);
                return null;
            });
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(instanceService.get(id).getState()).isEqualTo(HubInstanceState.RUNNING);
        assertThat(registry.get(id)).isNotNull();
        assertThat(launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME))
            .as("initial start + one Chrome per restart")
            .isEqualTo(3);
    }

    /**
     * A failed start rolls back completely (ERROR state, no runtime, no leftover process) and
     * the same instance can be started again afterwards.
     */
    @Test
    void shouldRetryStartAfterFailureRollback() throws Exception {
        String id = seedPersistedInstance("bilibili-retry", "ctx-retry");
        launcher.failNextLaunch(HubInstanceRuntime.HubInstanceProcessKind.CHROME);

        assertThatThrownBy(() -> lifecycle.start(id))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_START_FAILED));
        assertThat(instanceService.get(id).getState()).isEqualTo(HubInstanceState.ERROR);
        assertThat(registry.get(id)).isNull();
        assertThat(launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME)).isEqualTo(1);

        daemon.addConnectedContextAfterFetch("ctx-retry", 2);
        HubInstance restarted = lifecycle.start(id);
        assertThat(restarted.getState()).isEqualTo(HubInstanceState.RUNNING);
        assertThat(restarted.getContextId()).isEqualTo("ctx-retry");
        assertThat(launcher.launchCount(HubInstanceRuntime.HubInstanceProcessKind.CHROME))
            .as("retry must launch exactly one more Chrome")
            .isEqualTo(2);
        assertThat(registry.get(id)).isNotNull();
    }

    // ---------------------------------------------------------------------------------
    //  UNEXPECTED EXIT
    // ---------------------------------------------------------------------------------

    @Test
    void shouldReportUnexpectedExitAndMarkError() {
        String id = seedPersistedInstance("bilibili-exit", "ctx-exit");
        daemon.addConnectedContextAfterFetch("ctx-exit", 2);
        lifecycle.start(id);
        HubInstanceRuntime runtime = registry.get(id);

        // Simulate the watcher triggering an exit event.
        lifecycle.markUnexpectedExit(id, "chrome died");

        assertThat(runtime.getProcesses().values()).allMatch(handle -> !handle.isAlive());
        assertThat(instanceService.get(id).getState()).isEqualTo(HubInstanceState.ERROR);
        assertThat(instanceService.get(id).getLastErrorMessage()).isEqualTo("chrome died");
        assertThat(registry.get(id)).isNull();
    }

    // ---------------------------------------------------------------------------------
    //  ORPHAN SCANNER
    // ---------------------------------------------------------------------------------

    @Test
    void shouldRemoveCreatingOrphanWithoutDbRow() throws IOException {
        // Simulate a crashed create: a .creating directory with no DB row.
        Path instanceRoot = dataDir.resolve("instances").resolve("9999");
        Files.createDirectories(instanceRoot.resolve("chrome"));
        Files.createDirectories(instanceRoot.resolve("logs"));
        Files.createFile(instanceRoot.resolve(".creating"));

        OrphanInstanceScanner scanner = new OrphanInstanceScanner(properties, instanceService);
        OrphanInstanceScanner.Result result = scanner.scan();

        assertThat(result.creatingOrphanDeleted).isEqualTo(1);
        assertThat(Files.exists(instanceRoot)).isFalse();
    }

    @Test
    void shouldKeepProfileAndDropMarkerWhenDbRowExists() throws IOException {
        String id = seedPersistedInstance("bilibili-create-marker", "ctx-create-marker");
        Path instanceRoot = dataDir.resolve("instances").resolve(id);
        // The orphan scanner depends on the .creating marker being on disk; create it here
        // because seedPersistedInstance writes only the DB row.
        Files.createDirectories(instanceRoot);
        Files.createDirectories(instanceRoot.resolve("chrome"));
        Files.createFile(instanceRoot.resolve(".creating"));
        Files.writeString(instanceRoot.resolve("chrome").resolve("Cookies"), "kept");

        OrphanInstanceScanner scanner = new OrphanInstanceScanner(properties, instanceService);
        OrphanInstanceScanner.Result result = scanner.scan();

        assertThat(result.creatingMarkerRemoved).isEqualTo(1);
        assertThat(Files.exists(instanceRoot.resolve(".creating"))).isFalse();
        // Profile preserved.
        assertThat(Files.exists(instanceRoot.resolve("chrome").resolve("Cookies"))).isTrue();
    }

    @Test
    void shouldRemoveNumericOrphanWithNoDbRow() throws IOException {
        Path instanceRoot = dataDir.resolve("instances").resolve("8888");
        Files.createDirectories(instanceRoot.resolve("chrome"));
        Files.writeString(instanceRoot.resolve("chrome").resolve("Preferences"), "{}");

        OrphanInstanceScanner scanner = new OrphanInstanceScanner(properties, instanceService);
        OrphanInstanceScanner.Result result = scanner.scan();

        assertThat(result.managedOrphanDeleted).isEqualTo(1);
        assertThat(Files.exists(instanceRoot)).isFalse();
    }

    @Test
    void shouldProtectNonNumericDirectoryFromAutoDelete() throws IOException {
        Path orphan = dataDir.resolve("instances").resolve("not-a-number");
        Files.createDirectories(orphan);
        Files.createFile(orphan.resolve(".creating"));

        OrphanInstanceScanner scanner = new OrphanInstanceScanner(properties, instanceService);
        OrphanInstanceScanner.Result result = scanner.scan();

        assertThat(result.unsafeNameProtected).isEqualTo(1);
        // Even a marker cannot authorize deleting a non-numeric administrator directory.
        assertThat(Files.exists(orphan.resolve(".creating"))).isTrue();
    }

    @Test
    void shouldProtectNumericDirectoryOutsideLegacyLongRange() throws IOException {
        Path orphan = dataDir.resolve("instances").resolve("999999999999999999999999999999");
        Files.createDirectories(orphan);

        OrphanInstanceScanner.Result result =
            new OrphanInstanceScanner(properties, instanceService).scan();

        assertThat(result.unsafeNameProtected).isEqualTo(1);
        assertThat(Files.exists(orphan)).isTrue();
    }

    // ---------------------------------------------------------------------------------
    //  PROFILE SINGLETON CLEANER
    // ---------------------------------------------------------------------------------

    @Test
    void shouldRemoveOnlyVolatileSingletonFiles() throws IOException {
        Path profile = Files.createTempDirectory("profile-clean");
        Files.createFile(profile.resolve("SingletonLock"));
        Files.createFile(profile.resolve("SingletonSocket"));
        Files.createFile(profile.resolve("SingletonCookie"));
        Files.writeString(profile.resolve("Cookies"), "keep me");

        int removed = new ProfileSingletonCleaner().cleanStaleSingletons(profile);

        assertThat(removed).isEqualTo(3);
        assertThat(Files.exists(profile.resolve("Cookies"))).isTrue();
    }

    /** A profile directory symlink must not redirect singleton cleanup to an external profile. */
    @Test
    void shouldRejectSymlinkedProfileDirectory() throws IOException {
        Path externalProfile = Files.createDirectories(dataDir.resolve("external-profile"));
        Path singleton = Files.createFile(externalProfile.resolve("SingletonLock"));
        Path profileLink = Files.createSymbolicLink(dataDir.resolve("profile-link"), externalProfile);

        assertThatThrownBy(() -> new ProfileSingletonCleaner().cleanStaleSingletons(profileLink))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(singleton).exists();
    }

    // ---------------------------------------------------------------------------------
    //  ALLOCATION SERVICE
    // ---------------------------------------------------------------------------------

    @Test
    void shouldAllocateUniqueDisplaysAndPorts() {
        HubInstanceAllocationService allocator = new HubInstanceAllocationService(properties);
        HubInstanceAllocationService.Allocation a = allocator.allocate();
        HubInstanceAllocationService.Allocation b = allocator.allocate();
        assertThat(a.displayNumber).isNotEqualTo(b.displayNumber);
        assertThat(a.vncPort).isNotEqualTo(b.vncPort);
    }

    // ---------------------------------------------------------------------------------
    //  HELPERS
    // ---------------------------------------------------------------------------------

    private HubInstanceCreateDTO createDto(String code) {
        HubInstanceCreateDTO dto = new HubInstanceCreateDTO();
        dto.setCode(code);
        dto.setDisplayName(code + " display");
        dto.setWebsites(new java.util.ArrayList<>(List.of("bilibili")));
        dto.setMaxPending(5);
        return dto;
    }

    private HubInstanceUpdateDTO updateDto(HubInstance instance, int maxPending) {
        HubInstanceUpdateDTO dto = new HubInstanceUpdateDTO();
        dto.setCode(instance.getCode());
        dto.setDisplayName(instance.getDisplayName());
        dto.setWebsites(instance.getWebsites());
        dto.setMaxPending(maxPending);
        dto.setProxyMode(instance.getProxyMode());
        dto.setProxyServer(instance.getProxyServer());
        return dto;
    }

    private void useInstanceService(InMemoryHubInstanceService service,
        HubDispatchRegistry newDispatchRegistry) {
        instanceService = service;
        dispatchRegistry = newDispatchRegistry;
        lifecycle = newLifecycle();
    }

    private HubInstanceLifecycleService newLifecycle() {
        return newLifecycle(instanceService, dispatchRegistry);
    }

    private HubInstanceLifecycleService newLifecycle(InMemoryHubInstanceService service,
        HubDispatchRegistry newDispatchRegistry) {
        HubInstanceFiles files = new HubInstanceFiles(properties);
        HubInstanceRuntimeStarter starter = new HubInstanceRuntimeStarter(
            registry, launcher, files, new ProfileSingletonCleaner(),
            newChromeBootstrap(), properties, settingsService);
        HubInstanceDaemonContextService daemonContext = new HubInstanceDaemonContextService(
            daemon, properties, service, registry, starter);
        return new HubInstanceLifecycleService(
            service, registry, newDispatchRegistry, startCoordinator,
            files, starter, daemonContext, commandCatalog, properties, java.time.Clock.systemUTC());
    }

    private OpenCliProfileSnapshot connectedProfile(String contextId) {
        OpenCliProfileSnapshot profile = new OpenCliProfileSnapshot();
        profile.setContextId(contextId);
        profile.setExtensionConnected(true);
        profile.setExtensionVersion("v1.0.22");
        return profile;
    }

    private String seedPersistedInstance(String code, String contextId) {
        return seedPersistedInstance(code, contextId, HubProxyMode.INHERIT, null);
    }

    private String seedPersistedInstance(String code, String contextId, List<String> websites) {
        HubInstance inst = new HubInstance();
        inst.setCode(code);
        inst.setDisplayName(code + " display");
        inst.setWebsites(websites);
        inst.setMaxPending(5);
        inst.setProxyMode(HubProxyMode.INHERIT);
        inst.setState(HubInstanceState.STOPPED);
        inst.setContextId(contextId);
        instanceService.create(inst);
        return inst.getId();
    }

    private String seedPersistedInstance(String code, String contextId,
        HubProxyMode proxyMode, String proxyServer) {
        HubInstance inst = new HubInstance();
        inst.setCode(code);
        inst.setDisplayName(code + " display");
        inst.setWebsites(List.of("bilibili"));
        inst.setMaxPending(5);
        inst.setProxyMode(proxyMode);
        inst.setProxyServer(proxyServer);
        inst.setState(HubInstanceState.STOPPED);
        inst.setContextId(contextId);
        instanceService.create(inst);
        return inst.getId();
    }

    private String lastChromeCommand() {
        return launcher.lastHandle(HubInstanceRuntime.HubInstanceProcessKind.CHROME)
            .info().commandLine().orElseThrow();
    }

    private ChromeProfileFileAccessBootstrap newChromeBootstrap() {
        return new ChromeProfileFileAccessBootstrap(new ObjectMapper(), buildInfoPath);
    }

    private boolean fileAccessPreference(Path chromeDir) throws IOException {
        String pointer = "/extensions/settings/"
            + TEST_EXTENSION_ID + "/newAllowFileAccess";
        return new ObjectMapper().readTree(Files.readString(
            chromeDir.resolve("Default").resolve("Preferences"))).at(pointer).asBoolean();
    }

    private Path chromeDirFromArgs(List<String> args) {
        String prefix = "--user-data-dir=";
        return args.stream()
            .filter(arg -> arg.startsWith(prefix))
            .map(arg -> Path.of(arg.substring(prefix.length())))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Chrome user-data-dir argument is missing"));
    }

    private OpenCliHubProperties newProps() {
        OpenCliHubProperties props = new OpenCliHubProperties();
        props.setDataDir(dataDir.toString());
        return props;
    }

    private static String prefixed(HubErrorCodes code) {
        return code.getDomain() + "." + code.name();
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

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static class ObservingInstanceService extends InMemoryHubInstanceService {

        private Consumer<HubInstance> beforeCreate;
        private BiConsumer<String, HubInstanceState> beforeStateUpdate;

        @Override
        public void create(HubInstance instance) {
            if (beforeCreate != null) {
                beforeCreate.accept(instance);
            }
            super.create(instance);
        }

        @Override
        public void updateState(String id, HubInstanceState newState, String errorMessage) {
            if (beforeStateUpdate != null) {
                beforeStateUpdate.accept(id, newState);
            }
            super.updateState(id, newState, errorMessage);
        }

    }

    private static final class BlockingUpdateStateService extends ObservingInstanceService {

        private final CountDownLatch startingUpdateEntered = new CountDownLatch(1);
        private final CountDownLatch releaseStartingUpdate = new CountDownLatch(1);
        private final CountDownLatch editableUpdateEntered = new CountDownLatch(1);

        @Override
        public void updateState(String id, HubInstanceState newState, String errorMessage) {
            if (newState == HubInstanceState.STARTING) {
                startingUpdateEntered.countDown();
                await(releaseStartingUpdate);
            }
            super.updateState(id, newState, errorMessage);
        }

        @Override
        public HubInstance update(String id, HubInstanceUpdateDTO dto) {
            editableUpdateEntered.countDown();
            return super.update(id, dto);
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for test release");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test wait interrupted", ex);
            }
        }

    }

}
