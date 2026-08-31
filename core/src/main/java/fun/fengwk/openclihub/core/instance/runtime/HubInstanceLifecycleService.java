package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.execution.runtime.HubDispatchRegistry;
import fun.fengwk.openclihub.core.instance.service.HubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.instance.HubInstanceCreateDTO;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import fun.fengwk.openclihub.share.model.instance.HubInstanceUpdateDTO;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Owns the lifecycle of every Hub Instance: create / start / restart / stop / delete and the
 * background startup recovery sweep.
 *
 * <p>The service is a state/transaction orchestrator above:
 * <ul>
 *   <li>{@link HubInstanceService} — pure CRUD: used to validate, read, write DB rows.</li>
 *   <li>{@link HubInstanceRuntimeRegistry} — volatile runtime tracking and lifecycle locks.</li>
 *   <li>{@link HubDispatchRegistry} — per-instance dispatch queueing.</li>
 *   <li>{@link HubInstanceStartCoordinator} — the global start/recovery barrier.</li>
 *   <li>{@link HubInstanceFiles} — instance directory ensure/require, {@code .creating}
 *       marker and recursive deletion.</li>
 *   <li>{@link HubInstanceRuntimeStarter} — display/VNC allocation, profile bootstrap and
 *       the 4-process launch with readiness checks.</li>
 *   <li>{@link HubInstanceDaemonContextService} — shared daemon readiness, context wait and
 *       active-tab bind.</li>
 * </ul>
 *
 * <p>All work happens inside either the coordinator's global start lock (sections: 16.2)
 * or the per-instance lifecycle lock (start / stop / restart / delete). Daemon startup,
 * context snapshot and context discovery during create/start/restart are serialized by the
 * same global start lock because the daemon itself is global to the Hub process; the
 * active-tab bind path is guarded only by the per-instance lifecycle lock plus the
 * dispatcher idle guard and never restarts the daemon.
 *
 * @author fengwk
 */
@Slf4j
@Service
public class HubInstanceLifecycleService implements HubInstanceLifecycleServiceConsumer {

    /**
     * Fixed adapter session bound by
     * {@code POST /api/instances/{id}/chatgpt-agent/bind-active-tab}.
     */
    public static final String CHATGPT_AGENT_ADAPTER_SESSION = "site:chatgpt-agent";

    private final HubInstanceService instanceService;
    private final HubInstanceRuntimeRegistry registry;
    private final HubDispatchRegistry dispatchRegistry;
    private final HubInstanceStartCoordinator startCoordinator;
    private final HubInstanceFiles files;
    private final HubInstanceRuntimeStarter runtimeStarter;
    private final HubInstanceDaemonContextService daemonContext;
    private final OpenCliHubProperties properties;
    private final Clock clock;

    public HubInstanceLifecycleService(
        HubInstanceService instanceService,
        HubInstanceRuntimeRegistry registry,
        HubDispatchRegistry dispatchRegistry,
        HubInstanceStartCoordinator startCoordinator,
        HubInstanceFiles files,
        HubInstanceRuntimeStarter runtimeStarter,
        HubInstanceDaemonContextService daemonContext,
        OpenCliHubProperties properties,
        Clock clock) {
        this.instanceService = instanceService;
        this.registry = registry;
        this.dispatchRegistry = dispatchRegistry;
        this.startCoordinator = startCoordinator;
        this.files = files;
        this.runtimeStarter = runtimeStarter;
        this.daemonContext = daemonContext;
        this.properties = properties;
        this.clock = clock;
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
        preset.setProxyMode(dto.getProxyMode());
        preset.setProxyServer(dto.getProxyServer());
        preset.setMaxPending(dto.getMaxPending() == null
            ? properties.getExecution().getDefaultMaxPending()
            : dto.getMaxPending());
        preset.setMaxConcurrency(dto.getMaxConcurrency() == null
            ? properties.getExecution().getDefaultMaxConcurrency()
            : dto.getMaxConcurrency());
        preset.setPriority(dto.getPriority() == null ? 0 : dto.getPriority());
        preset.setState(HubInstanceState.STARTING);
        preset.setStateChangedAt(LocalDateTime.now(clock));
        instanceService.validateAndNormalizeForCreate(preset);
        return startCoordinator.runStart(() -> {
            // Recheck uniqueness inside the global start critical section so two concurrent
            // requests with the same code do not both start browser processes.
            instanceService.validateAndNormalizeForCreate(preset);
            return createUnderLock(preset);
        });
    }

    /**
     * Updates editable properties under the same per-instance lock as every lifecycle
     * transition, then propagates the queue limit to an already-running dispatcher.
     */
    public HubInstance update(String instanceId, HubInstanceUpdateDTO dto) {
        ReentrantLock lock = lockExistingInstance(instanceId);
        try {
            loadInstance(instanceId);
            HubInstance updated = instanceService.update(instanceId, dto);
            dispatchRegistry.updateMaxPending(instanceId, updated.getMaxPending());
            return updated;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Starts an existing instance (no DB insert). Follows the priority rules in design §17.3.
     */
    public HubInstance start(String instanceId) {
        return startCoordinator.runStart(() -> {
            ReentrantLock lock = lockExistingInstance(instanceId);
            try {
                HubInstance current = loadInstance(instanceId);
                return startUnderLock(instanceId, current);
            } finally {
                lock.unlock();
            }
        });
    }

    /**
     * Stops an instance: refuses active/pending requests, stops processes in reverse order,
     * unregisters the runtime.
     */
    public void stop(String instanceId) {
        ReentrantLock lock = lockExistingInstance(instanceId);
        try {
            loadInstance(instanceId);
            stopUnderLock(instanceId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Restarts an instance: stop + start, preserving the on-disk Profile.
     */
    public void restart(String instanceId) {
        startCoordinator.runStart(() -> {
            ReentrantLock lock = lockExistingInstance(instanceId);
            try {
                loadInstance(instanceId);
                stopUnderLock(instanceId);
                startUnderLock(instanceId, loadInstance(instanceId));
            } finally {
                lock.unlock();
            }
            return null;
        });
    }

    /**
     * Cancel all pending queue tasks for the instance. Waiting execute callers receive
     * {@code INSTANCE_QUEUE_CLEARED}. The currently running task (if any) is not interrupted.
     *
     * @return number of pending tasks cleared
     */
    public int clearPendingQueue(String instanceId) {
        loadInstance(instanceId);
        return dispatchRegistry.clearPending(instanceId);
    }

    /**
     * Binds the fixed chatgpt-agent adapter session (see {@link #CHATGPT_AGENT_ADAPTER_SESSION})
     * to the tab currently focused in the instance's VNC browser. The dispatcher idle guard
     * remains held for the complete daemon round trip, so no execution can be accepted while
     * the tab is being replaced.
     */
    public void bindActiveTab(String instanceId) {
        ReentrantLock lock = lockExistingInstance(instanceId);
        try {
            HubInstance instance = loadInstance(instanceId);
            if (!instance.isRunning()) {
                throw HubErrorCodes.INSTANCE_NOT_RUNNING.asThrowable(
                    "instance is not RUNNING: " + instanceId);
            }
            HubInstanceRuntime runtime = registry.get(instanceId);
            if (runtime == null) {
                throw HubErrorCodes.INSTANCE_RUNTIME_NOT_FOUND.asThrowable(
                    "instance runtime is not registered: " + instanceId);
            }
            String contextId = runtime.getContextId();
            if (contextId == null || contextId.isBlank()
                || instance.getContextId() == null
                || !instance.getContextId().equals(contextId)) {
                throw HubErrorCodes.INSTANCE_CONTEXT_NOT_CONNECTED.asThrowable(
                    "instance live contextId is unavailable or stale: " + instanceId);
            }
            dispatchRegistry.executeWhenIdle(instance, () -> {
                daemonContext.bindActiveTab(contextId, CHATGPT_AGENT_ADAPTER_SESSION);
                return null;
            });
        } finally {
            lock.unlock();
        }
    }

    /**
     * Hard delete: remove DB row, unregister runtime, then recursively delete the on-disk
     * directory. Active/pending requests cause rejection.
     */
    public void delete(String instanceId) {
        ReentrantLock lock = lockExistingInstance(instanceId);
        boolean rowDeleted = false;
        try {
            loadInstance(instanceId);
            files.requireSafeDirectories(instanceId, HubErrorCodes.INSTANCE_DELETE_FAILED);
            HubInstanceRuntimeSnapshot snapshot = dispatchRegistry.getSnapshot(instanceId);
            if (!snapshot.isIdle()) {
                throw HubErrorCodes.INSTANCE_BUSY.asThrowable(
                    "instance is busy; cannot delete: active=" + snapshot.getActiveCount()
                        + " pending=" + snapshot.getPendingCount());
            }
            instanceService.updateState(instanceId, HubInstanceState.STOPPING, null);
            if (!dispatchRegistry.unregisterWhenIdle(instanceId)) {
                instanceService.updateState(instanceId, HubInstanceState.RUNNING, null);
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
            rowDeleted = true;
            files.deleteInstanceDirectory(instanceId);
        } finally {
            lock.unlock();
            if (rowDeleted) {
                registry.removeLifecycleLock(instanceId, lock);
            }
        }
    }

    /**
     * Called by the unexpected-exit watcher when a tracked process dies.
     */
    public void markUnexpectedExit(String instanceId, String reason) {
        ReentrantLock lock = null;
        try {
            lock = lockExistingInstance(instanceId);
            loadInstance(instanceId);
            HubInstanceRuntime runtime = registry.get(instanceId);
            if (runtime == null) {
                return;
            }
            try {
                instanceService.updateState(instanceId, HubInstanceState.ERROR, reason);
            } catch (RuntimeException stateEx) {
                log.warn("failed to mark unexpectedly exited instance {} ERROR: {}",
                    instanceId, stateEx.getMessage());
            }
            registry.stopProcesses(runtime);
            dispatchRegistry.unregister(instanceId);
            registry.unregister(instanceId);
        } catch (RuntimeException ex) {
            log.warn("markUnexpectedExit({}) failed: {}", instanceId, ex.getMessage(), ex);
        } finally {
            if (lock != null) {
                lock.unlock();
            }
        }
    }

    /**
     * Returns the current snapshot for the instance. Returns an absent snapshot when the
     * runtime is not registered.
     */
    public HubInstanceRuntimeSnapshot getSnapshot(String instanceId) {
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
            ReentrantLock lock = null;
            try {
                lock = lockExistingInstance(instance.getId());
                loadInstance(instance.getId());
                instanceService.updateState(instance.getId(), HubInstanceState.STARTING, null);
            } catch (RuntimeException ex) {
                log.error("normalise start for instance {} failed: {}",
                    instance.getId(), ex.getMessage());
            } finally {
                if (lock != null) {
                    lock.unlock();
                }
            }
        }
    }

    /**
     * Single-threaded recovery over the supplied instance list. Failures are isolated and
     * the loop continues — the Hub itself MUST stay healthy even when every Instance fails.
     *
     * <p>Called from inside the {@link HubInstanceStartCoordinator} recovery barrier
     * (declared by {@link HubInstanceStartCoordinator#beginRecovery()}); each start
     * serialises on the coordinator's global start lock without waiting on the barrier.
     */
    public void recoverAll(List<HubInstance> orderedByCreationTime) {
        for (HubInstance instance : orderedByCreationTime) {
            String id = instance.getId();
            try {
                startCoordinator.runRecoveryStart(() -> {
                    ReentrantLock lock = lockExistingInstance(id);
                    try {
                        return startUnderLock(id, loadInstance(id));
                    } finally {
                        lock.unlock();
                    }
                });
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
     * Returns instances ordered by creation time ascending, then id ascending — the canonical recovery order.
     */
    public List<HubInstance> listInstancesOrderedByCreationTime() {
        return instanceService.list();
    }

    // ---------------------------------------------------------------------------------------
    //  Internals
    // ---------------------------------------------------------------------------------------

    private HubInstance startUnderLock(String instanceId, HubInstance current) {
        if (registry.contains(instanceId)) {
            throw HubErrorCodes.INSTANCE_BUSY.asThrowable(
                "instance already has a runtime: " + instanceId);
        }
        files.requireSafeDirectories(instanceId, HubErrorCodes.INSTANCE_START_FAILED);
        HubInstanceRuntime runtime = null;
        boolean registeredRuntime = false;
        try {
            instanceService.updateState(instanceId, HubInstanceState.STARTING, null);
            Set<String> beforeSnapshot = daemonContext.ensureDaemonReady();
            runtime = runtimeStarter.start(current);
            daemonContext.waitForExpectedOrUniqueContext(instanceId, current, beforeSnapshot, runtime);
            if (runtime.getContextId() != null) {
                instanceService.bindContextId(instanceId, runtime.getContextId());
            }
            registry.register(runtime);
            registeredRuntime = true;
            dispatchRegistry.register(instanceService.get(instanceId));
            registry.unexpectedExitListener().watch(instanceId, runtime);
            runtimeStarter.ensureProcessesAlive(runtime);
            instanceService.updateState(instanceId, HubInstanceState.RUNNING, null);
            log.info(
                "Instance started id={} code={} contextId={} display={} vncPort={}",
                instanceId,
                current.getCode(),
                runtime.getContextId(),
                runtime.getDisplayNumber(),
                runtime.getVncPort());
            return instanceService.get(instanceId);
        } catch (RuntimeException ex) {
            handleStartFailure(instanceId, runtime, registeredRuntime, ex);
            throw ex;
        }
    }

    private void stopUnderLock(String instanceId) {
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
            log.info("Instance stopped id={}", instanceId);
        }
    }

    private HubInstance createUnderLock(HubInstance preset) {
        // Step 1: reserve an id, but DO NOT insert yet. The repository generator is the
        // source of truth for UUID ids; using it here keeps the row id
        // and the directory id aligned without leaking a half-written row.
        String id;
        try {
            id = instanceService.reserveId();
        } catch (RuntimeException ex) {
            throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                ex, "id reservation failed: " + ex.getMessage());
        }
        HubInstance shadow = preset;
        shadow.setId(id);
        shadow.setState(HubInstanceState.STARTING);
        shadow.setStateChangedAt(LocalDateTime.now(clock));

        try {
            files.ensureDirectories(id);
            files.createCreatingMarker(id);
        } catch (IOException ex) {
            files.cleanupCreateFailureArtifacts(id);
            throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                ex, "create directories failed: " + ex.getMessage());
        }

        HubInstanceRuntime runtime = null;
        boolean registeredRuntime = false;
        boolean rowInserted = false;
        try {
            Set<String> beforeSnapshot = daemonContext.ensureDaemonReady();
            runtime = runtimeStarter.start(shadow);
            daemonContext.waitForExpectedOrUniqueContext(id, shadow, beforeSnapshot, runtime);
            shadow.setContextId(runtime.getContextId());
            shadow.setState(HubInstanceState.RUNNING);
            shadow.setStateChangedAt(LocalDateTime.now(clock));
            registry.register(runtime);
            registeredRuntime = true;
            dispatchRegistry.register(shadow);
            runtimeStarter.ensureProcessesAlive(runtime);
            instanceService.create(shadow);
            rowInserted = true;
            // Do not arm the watcher until the row exists: an immediate callback must never
            // race create rollback by trying to mark a non-existent instance ERROR.
            registry.unexpectedExitListener().watch(id, runtime);
            try {
                files.deleteCreatingMarker(id);
            } catch (IOException ex) {
                log.warn("Failed to delete .creating for instance {}: {}", id, ex.getMessage());
            }
            return instanceService.get(id);
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() == null
                ? ex.getClass().getSimpleName() : ex.getMessage();
            // Reverse-order cleanup.
            if (rowInserted) {
                registry.unexpectedExitListener().unwatch(id);
                try {
                    instanceService.deleteById(id);
                } catch (RuntimeException cleanupEx) {
                    log.warn("failed to delete instance row {} during create rollback: {}",
                        id, cleanupEx.getMessage());
                }
            }
            if (runtime != null) {
                registry.stopProcesses(runtime);
            }
            dispatchRegistry.unregister(id);
            if (registeredRuntime) {
                registry.unregister(id);
            } else if (runtime != null) {
                runtimeStarter.releaseAllocation(runtime);
            }
            files.cleanupCreateFailureArtifacts(id);
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

    private void handleStartFailure(String instanceId, HubInstanceRuntime runtime,
        boolean registeredRuntime, RuntimeException ex) {
        String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        try {
            instanceService.updateState(instanceId, HubInstanceState.ERROR, reason);
        } catch (RuntimeException stateEx) {
            log.warn("failed to mark instance {} ERROR after start failure: {}",
                instanceId, stateEx.getMessage());
        }
        if (runtime != null) {
            registry.stopProcesses(runtime);
        }
        dispatchRegistry.unregister(instanceId);
        if (registeredRuntime) {
            registry.unregister(instanceId);
        } else if (runtime != null) {
            runtimeStarter.releaseAllocation(runtime);
        }
    }

    private HubInstance loadInstance(String instanceId) {
        return instanceService.get(instanceId);
    }

    private ReentrantLock lockExistingInstance(String instanceId) {
        // Validate existence before allocating a permanent lock entry, then callers reload
        // after acquiring the lock to close the delete/update TOCTOU window.
        loadInstance(instanceId);
        ReentrantLock lock = registry.lifecycleLock(instanceId);
        lock.lock();
        return lock;
    }

}
