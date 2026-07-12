package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.execution.runtime.HubDispatchRegistry;
import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntime.HubInstanceProcessKind;
import fun.fengwk.openclihub.core.instance.service.HubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliDaemonClient;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliDaemonException;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliDaemonStatus;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.instance.HubInstanceCreateDTO;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Owns the lifecycle of every Hub Instance: create / start / restart / stop / delete and the
 * background startup recovery sweep.
 *
 * <p>The service is a thin orchestrator above:
 * <ul>
 *   <li>{@link HubInstanceService} — pure CRUD: used to validate, read, write DB rows.</li>
 *   <li>{@link HubInstanceRuntimeRegistry} — volatile runtime tracking and lifecycle locks.</li>
 *   <li>{@link InstanceProcessLauncher} — process spawn.</li>
 *   <li>{@link OpenCliDaemonClient} — daemon context discovery.</li>
 * </ul>
 *
 * <p>All work happens inside either the global creation lock (sections: 16.2) or the
 * per-instance lifecycle lock (start / stop / restart / delete).
 *
 * @author fengwk
 */
@Slf4j
@Service
public class HubInstanceLifecycleService implements HubInstanceLifecycleServiceConsumer {

    private final HubInstanceService instanceService;
    private final HubInstanceRuntimeRegistry registry;
    private final InstanceProcessLauncher launcher;
    private final OpenCliDaemonClient daemonClient;
    private final OpenCliHubProperties properties;
    private final ProfileSingletonCleaner singletonCleaner;
    private final HubDispatchRegistry dispatchRegistry;
    private final ReentrantLock creationLock = new ReentrantLock();

    public HubInstanceLifecycleService(
        HubInstanceService instanceService,
        HubInstanceRuntimeRegistry registry,
        InstanceProcessLauncher launcher,
        OpenCliDaemonClient daemonClient,
        OpenCliHubProperties properties,
        ProfileSingletonCleaner singletonCleaner,
        HubDispatchRegistry dispatchRegistry) {
        this.instanceService = instanceService;
        this.registry = registry;
        this.launcher = launcher;
        this.daemonClient = daemonClient;
        this.properties = properties;
        this.singletonCleaner = singletonCleaner;
        this.dispatchRegistry = dispatchRegistry;
    }

    // ---------------------------------------------------------------------------------------
    //  Public API
    // ---------------------------------------------------------------------------------------

    /**
     * Synchronous create: validate, allocate, snapshot, start, wait for unique new contextId,
     * insert only when RUNNING. Any failure reverses and deletes the on-disk directory
     * without leaving a DB record. See design §16.2.
     */
    public HubInstance create(HubInstanceCreateDTO dto) {
        if (dto == null) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("instance payload is required");
        }
        HubInstance preset = new HubInstance();
        preset.setCode(dto.getCode());
        preset.setDisplayName(dto.getDisplayName());
        preset.setWebsites(dto.getWebsites());
        preset.setMaxPending(dto.getMaxPending() == null
            ? properties.getExecution().getDefaultMaxPending()
            : dto.getMaxPending());
        preset.setState(HubInstanceState.STARTING);
        preset.setStateChangedAt(LocalDateTime.now());
        instanceService.validateAndNormalizeForCreate(preset);
        creationLock.lock();
        try {
            // Recheck uniqueness after entering the global create critical section so two
            // concurrent requests with the same code do not both start browser processes.
            instanceService.validateAndNormalizeForCreate(preset);
            return createUnderLock(preset);
        } finally {
            creationLock.unlock();
        }
    }

    /**
     * Starts an existing instance (no DB insert). Follows the priority rules in design §17.3.
     */
    public HubInstance start(long instanceId) {
        ReentrantLock lock = registry.lifecycleLock(instanceId);
        lock.lock();
        try {
            HubInstance current = loadInstance(instanceId);
            if (registry.contains(instanceId)) {
                throw HubErrorCodes.INSTANCE_BUSY.asThrowable(
                    "instance already has a runtime: " + instanceId);
            }
            instanceService.updateState(instanceId, HubInstanceState.STARTING, null);
            ensureDaemonRunning();
            Set<String> beforeSnapshot = snapshotContextIds();
            HubInstanceRuntime runtime = null;
            boolean registeredRuntime = false;
            try {
                runtime = startRuntime(current);
                waitForExpectedOrUniqueContext(instanceId, current, beforeSnapshot, runtime);
                if (runtime.getContextId() != null) {
                    instanceService.bindContextId(instanceId, runtime.getContextId());
                }
                instanceService.updateState(instanceId, HubInstanceState.RUNNING, null);
                registry.register(runtime);
                registeredRuntime = true;
                registry.unexpectedExitListener().watch(instanceId, runtime);
                // M5: a dispatcher only makes sense once the live runtime is registered,
                // so the M5 router sees consistent (state=RUNNING, context connected,
                // dispatcher registered) state for routing and load calculation.
                dispatchRegistry.register(instanceService.get(instanceId));
                return instanceService.get(instanceId);
            } catch (RuntimeException ex) {
                handleStartFailure(instanceId, runtime, registeredRuntime, ex);
                throw ex;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops an instance: refuses active/pending requests, stops processes in reverse order,
     * unregisters the runtime.
     */
    public void stop(long instanceId) {
        ReentrantLock lock = registry.lifecycleLock(instanceId);
        lock.lock();
        try {
            loadInstance(instanceId);
            HubInstanceRuntimeSnapshot snapshot = dispatchRegistry.getSnapshot(instanceId);
            if (!snapshot.isIdle()) {
                throw HubErrorCodes.INSTANCE_BUSY.asThrowable(
                    "instance has active/pending work: active=" + snapshot.getActiveCount()
                        + " pending=" + snapshot.getPendingCount());
            }
            instanceService.updateState(instanceId, HubInstanceState.STOPPING, null);
            if (!dispatchRegistry.unregisterWhenIdle(instanceId)) {
                instanceService.updateState(instanceId, HubInstanceState.RUNNING, null);
                HubInstanceRuntimeSnapshot raced = dispatchRegistry.getSnapshot(instanceId);
                throw HubErrorCodes.INSTANCE_BUSY.asThrowable(
                    "instance accepted work while stopping: active=" + raced.getActiveCount()
                        + " pending=" + raced.getPendingCount());
            }
            try {
                HubInstanceRuntime runtime = registry.get(instanceId);
                if (runtime != null) {
                    registry.stopProcesses(runtime);
                    registry.unregister(instanceId);
                }
            } finally {
                instanceService.updateState(instanceId, HubInstanceState.STOPPED, null);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Restarts an instance: stop + start, preserving the on-disk Profile.
     */
    public void restart(long instanceId) {
        ReentrantLock lock = registry.lifecycleLock(instanceId);
        lock.lock();
        try {
            stop(instanceId);
            start(instanceId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Hard delete: remove DB row, unregister runtime, then recursively delete the on-disk
     * directory. Active/pending requests cause rejection.
     */
    public void delete(long instanceId) {
        ReentrantLock lock = registry.lifecycleLock(instanceId);
        lock.lock();
        try {
            loadInstance(instanceId);
            HubInstanceRuntimeSnapshot snapshot = dispatchRegistry.getSnapshot(instanceId);
            if (!snapshot.isIdle()) {
                throw HubErrorCodes.INSTANCE_BUSY.asThrowable(
                    "instance is busy; cannot delete: active=" + snapshot.getActiveCount()
                        + " pending=" + snapshot.getPendingCount());
            }
            if (!dispatchRegistry.unregisterWhenIdle(instanceId)) {
                HubInstanceRuntimeSnapshot raced = dispatchRegistry.getSnapshot(instanceId);
                throw HubErrorCodes.INSTANCE_BUSY.asThrowable(
                    "instance accepted work while deleting: active=" + raced.getActiveCount()
                        + " pending=" + raced.getPendingCount());
            }
            HubInstanceRuntime runtime = registry.get(instanceId);
            if (runtime != null) {
                registry.stopProcesses(runtime);
                registry.unregister(instanceId);
            }
            instanceService.deleteById(instanceId);
            deleteInstanceDirectory(instanceId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called by the unexpected-exit watcher when a tracked process dies.
     */
    public void markUnexpectedExit(long instanceId, String reason) {
        ReentrantLock lock = registry.lifecycleLock(instanceId);
        lock.lock();
        try {
            HubInstanceRuntime runtime = registry.get(instanceId);
            if (runtime == null) {
                return;
            }
            registry.stopProcesses(runtime);
            registry.unregister(instanceId);
            dispatchRegistry.unregister(instanceId);
            instanceService.updateState(instanceId, HubInstanceState.ERROR, reason);
        } catch (RuntimeException ex) {
            log.warn("markUnexpectedExit({}) failed: {}", instanceId, ex.getMessage(), ex);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current snapshot for the instance. Returns an absent snapshot when the
     * runtime is not registered.
     */
    public HubInstanceRuntimeSnapshot getSnapshot(long instanceId) {
        HubInstanceRuntime runtime = registry.get(instanceId);
        if (runtime == null) {
            return HubInstanceRuntimeSnapshot.absent();
        }
        HubInstanceRuntimeSnapshot dispatch = dispatchRegistry.getSnapshot(instanceId);
        return new HubInstanceRuntimeSnapshot(
            true,
            runtime.getDisplayNumber(),
            runtime.getVncPort(),
            dispatch.getActiveCount(),
            dispatch.getPendingCount());
    }

    // ---------------------------------------------------------------------------------------
    //  Recovery (used by ApplicationRunner)
    // ---------------------------------------------------------------------------------------

    /**
     * Normalises every persisted instance to {@code STARTING} so a fresh Hub start does not
     * inherit a stale RUNNING.
     */
    public void normalizeAllStatesToStarting() {
        for (HubInstance instance : instanceService.list()) {
            try {
                instanceService.updateState(instance.getId(), HubInstanceState.STARTING, null);
            } catch (RuntimeException ex) {
                log.error("normalise start for instance {} failed: {}",
                    instance.getId(), ex.getMessage());
            }
        }
    }

    /**
     * Single-threaded recovery over the supplied instance list. Failures are isolated and
     * the loop continues — the Hub itself MUST stay healthy even when every Instance fails.
     */
    public void recoverAll(List<HubInstance> orderedById) {
        for (HubInstance instance : orderedById) {
            long id = instance.getId();
            try {
                start(id);
            } catch (RuntimeException ex) {
                log.warn("startup recovery of instance {} failed: {}", id, ex.getMessage());
                if (Thread.currentThread().isInterrupted()) {
                    log.info("startup recovery interrupted after instance {}", id);
                    return;
                }
            }
        }
    }

    /**
     * Returns instances ordered by id ascending — the canonical recovery order.
     */
    public List<HubInstance> listInstancesOrderedById() {
        return instanceService.list();
    }

    // ---------------------------------------------------------------------------------------
    //  Internals
    // ---------------------------------------------------------------------------------------

    private HubInstance createUnderLock(HubInstance preset) {
        // Step 1: reserve an id, but DO NOT insert yet. The repository generator is the
        // source of truth for monotonic, namespace-scoped ids; using it here keeps the row id
        // and the directory id aligned without leaking a half-written row.
        long id;
        try {
            id = instanceService.reserveId();
        } catch (RuntimeException ex) {
            throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                ex, "id reservation failed: " + ex.getMessage());
        }
        HubInstance shadow = preset;
        shadow.setId(id);
        shadow.setState(HubInstanceState.STARTING);
        shadow.setStateChangedAt(LocalDateTime.now());

        Path dir = HubInstanceDirectoryLayout.instanceDir(properties.getDataDir(), id);
        try {
            Files.createDirectories(dir.resolve(HubInstanceDirectoryLayout.DIR_CHROME));
            Files.createDirectories(dir.resolve(HubInstanceDirectoryLayout.DIR_LOGS));
            Files.createDirectories(dir.resolve(HubInstanceDirectoryLayout.DIR_RUNTIME));
            Files.createFile(HubInstanceDirectoryLayout.creatingMarker(properties.getDataDir(), id));
        } catch (IOException ex) {
            cleanupCreateFailureArtifacts(id);
            throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                ex, "create directories failed: " + ex.getMessage());
        }

        HubInstanceRuntime runtime = null;
        boolean registeredRuntime = false;
        boolean rowInserted = false;
        try {
            ensureDaemonRunning();
            Set<String> beforeSnapshot = snapshotContextIds();
            runtime = startRuntime(shadow);
            waitForExpectedOrUniqueContext(id, shadow, beforeSnapshot, runtime);
            shadow.setContextId(runtime.getContextId());
            shadow.setState(HubInstanceState.RUNNING);
            shadow.setStateChangedAt(LocalDateTime.now());
            instanceService.create(shadow);
            rowInserted = true;
            registry.register(runtime);
            registeredRuntime = true;
            registry.unexpectedExitListener().watch(id, runtime);
            // Mirror the start() path: install the per-instance dispatcher once the
            // runtime is registered, otherwise the M5 router cannot route to it.
            dispatchRegistry.register(instanceService.get(id));
            try {
                Files.deleteIfExists(
                    HubInstanceDirectoryLayout.creatingMarker(properties.getDataDir(), id));
            } catch (IOException ex) {
                log.warn("Failed to delete .creating for instance {}: {}", id, ex.getMessage());
            }
            return instanceService.get(id);
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() == null
                ? ex.getClass().getSimpleName() : ex.getMessage();
            // Reverse-order cleanup.
            if (runtime != null) {
                registry.stopProcesses(runtime);
            }
            if (registeredRuntime) {
                registry.unregister(id);
            } else if (runtime != null) {
                registry.allocationService().release(runtime);
            }
            dispatchRegistry.unregisterWhenIdle(id);
            if (rowInserted) {
                try {
                    instanceService.deleteById(id);
                } catch (RuntimeException cleanupEx) {
                    log.warn("failed to delete instance row {} during create rollback: {}",
                        id, cleanupEx.getMessage());
                }
            }
            cleanupCreateFailureArtifacts(id);
            // Preserve the precise domain code (e.g. CONTEXT_ID_AMBIGUOUS, EXTENSION_CONNECT_TIMEOUT)
            // so the web layer can map to the right HTTP status; fall back to
            // INSTANCE_START_FAILED only when the cause isn't a domain error.
            throw propagateOrWrap(ex, reason);
        }
    }

    private static RuntimeException propagateOrWrap(RuntimeException ex, String reason) {
        if (ex instanceof ThrowableConventionErrorCode) {
            return ex;
        }
        if (ex.getCause() instanceof ThrowableConventionErrorCode cause) {
            return cause;
        }
        return HubErrorCodes.INSTANCE_START_FAILED.asThrowable(ex, reason);
    }

    private HubInstanceRuntime startRuntime(HubInstance descriptor) {
        String dataDir = properties.getDataDir();
        long id = descriptor.getId();
        HubInstanceAllocationService.Allocation allocation = registry.allocationService().allocate();
        HubInstanceRuntime runtime = new HubInstanceRuntime();
        runtime.setInstanceId(id);
        runtime.setInstanceCode(descriptor.getCode());
        runtime.setDisplayNumber(allocation.displayNumber);
        runtime.setVncPort(allocation.vncPort);
        runtime.setInstanceDir(HubInstanceDirectoryLayout.instanceDir(dataDir, id).toString());

        Path chromeDir = HubInstanceDirectoryLayout.chromeDir(dataDir, id);
        Path logsDir = HubInstanceDirectoryLayout.logsDir(dataDir, id);
        Path runtimeDir = HubInstanceDirectoryLayout.runtimeDir(dataDir, id);
        Path xvfbLog = HubInstanceDirectoryLayout.xvfbLog(dataDir, id);
        Path openboxLog = HubInstanceDirectoryLayout.openboxLog(dataDir, id);
        Path x11vncLog = HubInstanceDirectoryLayout.x11vncLog(dataDir, id);
        Path chromeLog = HubInstanceDirectoryLayout.chromeLog(dataDir, id);
        try {
            Files.createDirectories(chromeDir);
            Files.createDirectories(logsDir);
            Files.createDirectories(runtimeDir);
            resetLog(xvfbLog);
            resetLog(openboxLog);
            resetLog(x11vncLog);
            resetLog(chromeLog);
            singletonCleaner.cleanStaleSingletons(chromeDir);

            Map<String, String> displayEnv = Map.of("DISPLAY", ":" + allocation.displayNumber);
            InstanceProcessLauncher.LaunchedProcess xvfb = launcher.launchXvfb(
                allocation.displayNumber, xvfbLog);
            recordHandle(runtime, HubInstanceProcessKind.XVFB, xvfb);
            waitForXvfbReady(allocation.displayNumber, xvfb.process);

            InstanceProcessLauncher.LaunchedProcess openbox = launcher.launchOpenbox(
                allocation.displayNumber, openboxLog);
            recordHandle(runtime, HubInstanceProcessKind.OPENBOX, openbox);
            sleepQuietly(properties.getRuntime().getReadinessPollMillis());
            ensureProcessesAlive(runtime);

            InstanceProcessLauncher.LaunchedProcess x11vnc = launcher.launchX11vnc(
                allocation.displayNumber, allocation.vncPort, x11vncLog);
            recordHandle(runtime, HubInstanceProcessKind.X11VNC, x11vnc);
            waitForVncReady(allocation.vncPort, x11vnc.process);

            InstanceProcessLauncher.LaunchedProcess chrome = launcher.launchChrome(
                chromeArgs(dataDir, id), displayEnv, chromeLog);
            recordHandle(runtime, HubInstanceProcessKind.CHROME, chrome);
            runtime.setStartedAtMillis(System.currentTimeMillis());
            return runtime;
        } catch (RuntimeException ex) {
            registry.stopProcesses(runtime);
            registry.allocationService().release(allocation);
            throw ex;
        } catch (IOException ex) {
            registry.stopProcesses(runtime);
            registry.allocationService().release(allocation);
            throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                ex, "create instance directories failed: " + ex.getMessage());
        }
    }

    private static void recordHandle(HubInstanceRuntime runtime,
        HubInstanceProcessKind kind, InstanceProcessLauncher.LaunchedProcess process) {
        runtime.getProcesses().put(kind, process.process);
    }

    private static void ensureProcessesAlive(HubInstanceRuntime runtime) {
        for (Map.Entry<HubInstanceProcessKind, ProcessHandle> entry
            : runtime.getProcesses().entrySet()) {
            if (!entry.getValue().isAlive()) {
                throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                    entry.getKey() + " process exited during instance startup");
            }
        }
    }

    private List<String> chromeArgs(String dataDir, long instanceId) {
        Path chromeDir = HubInstanceDirectoryLayout.chromeDir(dataDir, instanceId);
        List<String> args = new ArrayList<>();
        args.add("--user-data-dir=" + chromeDir.toString());
        args.add("--enable-unsafe-extension-debugging");
        args.add("--no-first-run");
        args.add("--no-default-browser-check");
        args.add("--disable-sync");
        args.add("--disable-popup-blocking");
        args.add("--window-size=" + properties.getBrowser().getScreenWidth()
            + "," + properties.getBrowser().getScreenHeight());
        // NOTE: deliberately NOT passing --load-extension, --disable-extensions-except,
        // --disable-features=DisableLoadExtensionCommandLineSwitch (rejected by Chrome 150),
        // --disable-background-networking or --disable-component-update (would suppress the
        // managed extension install). Extension is force-installed via
        // /etc/opt/chrome/policies/managed; see docs/poc-chrome-extension.md §3 and §10.
        return args;
    }

    private void waitForXvfbReady(int displayNumber, ProcessHandle handle) {
        long deadline = System.currentTimeMillis() + properties.getVnc().getStartupTimeoutMillis();
        Path lock = Path.of("/tmp/.X" + displayNumber + "-lock");
        Path sock = Path.of("/tmp/.X11-unix/X" + displayNumber);
        while (System.currentTimeMillis() < deadline) {
            if (!handle.isAlive()) {
                throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                    "Xvfb exited before becoming ready (display=" + displayNumber + ")");
            }
            if (Files.exists(lock) || Files.exists(sock)) {
                return;
            }
            sleepQuietly(properties.getRuntime().getReadinessPollMillis());
        }
        throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
            "Xvfb did not become ready within " + properties.getVnc().getStartupTimeoutMillis()
                + " ms (display=" + displayNumber + ")");
    }

    private void waitForVncReady(int port, ProcessHandle handle) {
        long deadline = System.currentTimeMillis() + properties.getVnc().getStartupTimeoutMillis();
        while (System.currentTimeMillis() < deadline) {
            if (!handle.isAlive()) {
                throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                    "x11vnc exited before bind (port=" + port + ")");
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 100);
                return;
            } catch (IOException ignored) {
                // not yet bound
            }
            sleepQuietly(properties.getRuntime().getReadinessPollMillis());
        }
        throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
            "x11vnc did not bind 127.0.0.1:" + port + " within "
                + properties.getVnc().getStartupTimeoutMillis() + " ms");
    }

    private void waitForExpectedOrUniqueContext(
        long instanceId, HubInstance instance, Set<String> before, HubInstanceRuntime runtime) {
        long startup = properties.getBrowser().getStartupTimeoutMillis();
        long deadline = System.currentTimeMillis() + startup;
        String expected = instance.getContextId();
        while (System.currentTimeMillis() < deadline) {
            ensureProcessesAlive(runtime);
            Set<String> now = snapshotContextIds();
            if (expected != null && now.contains(expected)) {
                runtime.setContextId(expected);
                return;
            }
            Set<String> newIds = new HashSet<>(now);
            newIds.removeAll(before);
            Set<String> conflicts = new HashSet<>(newIds);
            conflicts.retainAll(activeBoundContextIds());
            if (!conflicts.isEmpty()) {
                throw HubErrorCodes.CONTEXT_ID_CONFLICT.asThrowable(
                    "new contextId is already bound to another instance: " + conflicts);
            }
            if (newIds.size() == 1) {
                String chosen = newIds.iterator().next();
                runtime.setContextId(chosen);
                if (expected != null && !expected.equals(chosen)) {
                    log.warn("instance {} expected contextId={} but got a unique new id={}; "
                        + "auto-rebinding", instanceId, expected, chosen);
                }
                return;
            }
            if (newIds.size() > 1) {
                throw HubErrorCodes.CONTEXT_ID_AMBIGUOUS.asThrowable(
                    "multiple new contextIds appeared after instance " + instanceId
                        + ": " + newIds);
            }
            sleepQuietly(properties.getRuntime().getReadinessPollMillis());
        }
        if (expected != null) {
            throw HubErrorCodes.EXTENSION_CONNECT_TIMEOUT.asThrowable(
                "extension did not connect within " + startup + " ms (instance=" + instanceId + ")");
        }
        throw HubErrorCodes.EXTENSION_CONNECT_TIMEOUT.asThrowable(
            "no unique new contextId observed within " + startup + " ms (instance="
                + instanceId + ")");
    }

    private void ensureDaemonRunning() {
        try {
            daemonClient.ensureRunning();
        } catch (OpenCliDaemonException ex) {
            throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                ex, "failed to ensure OpenCLI daemon: " + ex.getMessage());
        }
    }

    private Set<String> snapshotContextIds() {
        try {
            OpenCliDaemonStatus status = daemonClient.fetchStatus();
            return status == null ? Set.of() : new HashSet<>(status.connectedContextIds());
        } catch (OpenCliDaemonException ex) {
            throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                ex, "daemon status fetch failed: " + ex.getMessage());
        }
    }

    private Set<String> activeBoundContextIds() {
        return instanceService.list().stream()
            .map(HubInstance::getContextId)
            .filter(id -> id != null && !id.isBlank())
            .collect(Collectors.toSet());
    }

    private void handleStartFailure(long instanceId, HubInstanceRuntime runtime,
        boolean registeredRuntime, RuntimeException ex) {
        String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        if (runtime != null) {
            registry.stopProcesses(runtime);
        }
        if (registeredRuntime) {
            registry.unregister(instanceId);
        } else if (runtime != null) {
            registry.allocationService().release(runtime);
        }
        dispatchRegistry.unregister(instanceId);
        try {
            instanceService.updateState(instanceId, HubInstanceState.ERROR, reason);
        } catch (RuntimeException stateEx) {
            log.warn("failed to mark instance {} ERROR after start failure: {}",
                instanceId, stateEx.getMessage());
        }
    }

    private HubInstance loadInstance(long instanceId) {
        return instanceService.get(instanceId);
    }

    private void resetLog(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private void cleanupCreateFailureArtifacts(long instanceId) {
        try {
            Files.deleteIfExists(
                HubInstanceDirectoryLayout.creatingMarker(properties.getDataDir(), instanceId));
        } catch (IOException ex) {
            log.warn("failed to remove .creating marker for instance {}: {}",
                instanceId, ex.getMessage());
        }
        try {
            deleteInstanceDirectory(instanceId);
        } catch (RuntimeException ex) {
            log.warn("failed to remove instance {} directory during rollback: {}",
                instanceId, ex.getMessage());
        }
    }

    private void deleteInstanceDirectory(long instanceId) {
        Path dir = HubInstanceDirectoryLayout.instanceDir(properties.getDataDir(), instanceId);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            deleteRecursively(dir);
        } catch (IOException ex) {
            throw HubErrorCodes.INSTANCE_DELETE_FAILED.asThrowable(
                ex, "delete instance directory failed: " + ex.getMessage());
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // An empty option set means "do not follow symlinks". Symlinks are deleted as entries,
        // never traversed, so they cannot escape the instance root.
        Files.walkFileTree(root, Set.of(),
            Integer.MAX_VALUE,
            new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc)
                    throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                ex, "instance startup interrupted");
        }
    }

}
