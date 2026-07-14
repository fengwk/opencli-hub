package fun.fengwk.openclihub.core.instance.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.execution.runtime.HubDispatchRegistry;
import fun.fengwk.openclihub.core.instance.runtime.test.InMemoryHubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.opencli.daemon.FakeOpenCliDaemonClient;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliProfileSnapshot;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.instance.HubInstanceCreateDTO;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for the M4 runtime lifecycle. Uses fake launcher + fake daemon so no
 * real OS process or HTTP server is needed.
 *
 * <p>Coverage maps to docs/technical-design.md §16.2-19.5 and §17-18.
 */
class HubInstanceLifecycleServiceTest {

    private Path dataDir;
    private FakeOpenCliDaemonClient daemon;
    private FakeInstanceProcessLauncher launcher;
    private InMemoryHubInstanceService instanceService;
    private HubInstanceRuntimeRegistry registry;
    private HubInstanceLifecycleService lifecycle;
    private HubInstanceAllocationService allocationService;
    private FakeUnexpectedExitListener watcher;
    private OpenCliHubProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = Files.createTempDirectory("m4-runtime-test");
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
        instanceService = new InMemoryHubInstanceService();
        allocationService = new HubInstanceAllocationService(properties);
        watcher = new FakeUnexpectedExitListener();
        registry = new HubInstanceRuntimeRegistry(launcher, allocationService, watcher);
        lifecycle = new HubInstanceLifecycleService(
            instanceService, registry, launcher, daemon, properties,
            new ProfileSingletonCleaner(), new HubDispatchRegistry());
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
        // The daemon first observes the empty state at the pre-create snapshot (fetch #1).
        // On the second fetch (post-chrome-start) we surface the new context id.
        daemon.addConnectedContextAfterFetch("ctx-success", 2);

        HubInstanceCreateDTO dto = createDto("bilibili-a");
        HubInstance created = lifecycle.create(dto);

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
        assertThatThrownBy(() -> lifecycle.start("not-an-id"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.INSTANCE_NOT_FOUND));

        for (HubInstanceRuntime.HubInstanceProcessKind kind
            : HubInstanceRuntime.HubInstanceProcessKind.values()) {
            assertThat(launcher.launchCount(kind)).isZero();
        }
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

    // ---------------------------------------------------------------------------------
    //  STOP / RESTART / DELETE
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
            HubInstanceLifecycleService busyLifecycle = new HubInstanceLifecycleService(
                instanceService, registry, launcher, daemon, properties,
                new ProfileSingletonCleaner(), dispatcher);
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

    private String seedPersistedInstance(String code, String contextId) {
        HubInstance inst = new HubInstance();
        inst.setCode(code);
        inst.setDisplayName(code + " display");
        inst.setWebsites(List.of("bilibili"));
        inst.setMaxPending(5);
        inst.setState(HubInstanceState.STOPPED);
        inst.setContextId(contextId);
        instanceService.create(inst);
        return inst.getId();
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

}
