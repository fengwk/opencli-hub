package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.openclihub.core.instance.service.HubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliDaemonClient;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliDaemonCommandResponse;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliDaemonException;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliDaemonStatus;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Shared-daemon side of the Instance lifecycle: ensures the daemon is ready and snapshots its
 * connected contexts, waits for the expected-or-unique context of a starting browser, and
 * performs the active-tab bind against a connected profile.
 *
 * <p>Every caller runs inside the {@link HubInstanceStartCoordinator} global start lock, so
 * daemon restart, context snapshot and context discovery never overlap between starts.
 *
 * @author fengwk
 */
@Slf4j
@Component
public class HubInstanceDaemonContextService {

    private final OpenCliDaemonClient daemonClient;
    private final OpenCliHubProperties properties;
    private final HubInstanceService instanceService;
    private final HubInstanceRuntimeRegistry registry;
    private final HubInstanceRuntimeStarter runtimeStarter;

    public HubInstanceDaemonContextService(
        OpenCliDaemonClient daemonClient,
        OpenCliHubProperties properties,
        HubInstanceService instanceService,
        HubInstanceRuntimeRegistry registry,
        HubInstanceRuntimeStarter runtimeStarter) {
        this.daemonClient = daemonClient;
        this.properties = properties;
        this.instanceService = instanceService;
        this.registry = registry;
        this.runtimeStarter = runtimeStarter;
    }

    /**
     * Ensures the shared daemon is usable and returns the pre-start context snapshot.
     * A global daemon restart is allowed only when no other runtime is registered.
     *
     * <p>Serialised by the coordinator's global start lock: every caller already runs inside
     * {@link HubInstanceStartCoordinator}, so two starts can never race the daemon.
     */
    public Set<String> ensureDaemonReady() {
        try {
            if (registry.list().isEmpty()) {
                daemonClient.ensureRunning();
                return snapshotContextIds();
            }

            OpenCliDaemonStatus status = daemonClient.fetchStatus();
            if (!hasValidDaemonPid(status)) {
                throw new OpenCliDaemonException(
                    "OpenCLI daemon is not ready; refusing to restart the shared daemon "
                        + "while another browser instance is running");
            }
            return new HashSet<>(status.connectedContextIds());
        } catch (OpenCliDaemonException ex) {
            throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                ex, "failed to ensure OpenCLI daemon: " + ex.getMessage());
        }
    }

    /**
     * Waits (bounded by the browser startup timeout) until the instance's expected contextId
     * is connected or exactly one new contextId appears. Conflicts with already-bound ids and
     * multiple new ids abort with {@code CONTEXT_ID_CONFLICT} / {@code CONTEXT_ID_AMBIGUOUS};
     * a timeout maps to {@code EXTENSION_CONNECT_TIMEOUT}. The chosen context is recorded on
     * the runtime; the process tree is checked for liveness on every poll.
     */
    public void waitForExpectedOrUniqueContext(
        String instanceId, HubInstance instance, Set<String> before, HubInstanceRuntime runtime) {
        long startup = properties.getBrowser().getStartupTimeoutMillis();
        long deadline = System.currentTimeMillis() + startup;
        String expected = instance.getContextId();
        while (System.currentTimeMillis() < deadline) {
            runtimeStarter.ensureProcessesAlive(runtime);
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

    /**
     * Verifies the profile is connected to the daemon, then binds {@code session} to its
     * active tab. Daemon transport failures map to {@code INSTANCE_START_FAILED}; a
     * command-level rejection maps to {@code INSTANCE_TAB_BIND_FAILED} preserving the
     * daemon's error code / message / hint.
     */
    public void bindActiveTab(String contextId, String session) {
        requireConnectedDaemonProfile(contextId);
        OpenCliDaemonCommandResponse response;
        try {
            response = daemonClient.bindActiveTab(contextId, session);
        } catch (OpenCliDaemonException ex) {
            throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                ex, "failed to bind active tab through OpenCLI daemon: " + ex.getMessage());
        }
        if (response == null || !Boolean.TRUE.equals(response.getOk())) {
            throw bindFailure(response);
        }
    }

    private void requireConnectedDaemonProfile(String contextId) {
        OpenCliDaemonStatus status;
        try {
            status = daemonClient.fetchStatus();
        } catch (OpenCliDaemonException ex) {
            throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                ex, "failed to fetch OpenCLI daemon status for bind: " + ex.getMessage());
        }
        if (!isConnectedProfile(status, contextId)) {
            throw HubErrorCodes.INSTANCE_CONTEXT_NOT_CONNECTED.asThrowable(
                "instance context is not connected to the OpenCLI daemon: " + contextId);
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

    private static boolean hasValidDaemonPid(OpenCliDaemonStatus status) {
        return status != null && status.getPid() != null && status.getPid() > 0L;
    }

    private static boolean isConnectedProfile(OpenCliDaemonStatus status, String contextId) {
        if (status == null) {
            return false;
        }
        if (status.getProfiles() != null) {
            for (var profile : status.getProfiles()) {
                if (contextId.equals(profile.getContextId())) {
                    return Boolean.TRUE.equals(profile.getExtensionConnected());
                }
            }
        }
        // Keep compatibility with daemon status snapshots that expose one profile only through
        // the legacy top-level contextId/extensionConnected fields.
        return contextId.equals(status.getContextId())
            && Boolean.TRUE.equals(status.getExtensionConnected());
    }

    private static RuntimeException bindFailure(OpenCliDaemonCommandResponse response) {
        StringBuilder message = new StringBuilder("OpenCLI daemon rejected active tab bind");
        if (response != null && response.getErrorCode() != null
            && !response.getErrorCode().isBlank()) {
            message.append(" [").append(response.getErrorCode()).append(']');
        }
        if (response != null && response.getError() != null && !response.getError().isBlank()) {
            message.append(": ").append(response.getError());
        }
        if (response != null && response.getErrorHint() != null
            && !response.getErrorHint().isBlank()) {
            message.append(" Hint: ").append(response.getErrorHint());
        }
        return HubErrorCodes.INSTANCE_TAB_BIND_FAILED.asThrowable(message.toString());
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
