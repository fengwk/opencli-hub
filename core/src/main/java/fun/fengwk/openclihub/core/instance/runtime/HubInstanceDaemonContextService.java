package fun.fengwk.openclihub.core.instance.runtime;

import fun.fengwk.openclihub.core.instance.service.HubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliDaemonClient;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliDaemonCommandResponse;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliDaemonException;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliDaemonStatus;
import fun.fengwk.openclihub.core.opencli.daemon.OpenCliProfileSnapshot;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Shared-daemon side of the Instance lifecycle: ensures the daemon is ready and snapshots its
 * connected contexts, waits for the expected-or-unique context of a starting browser, and
 * performs the active-tab bind against a connected profile.
 *
 * <p>The startup paths — {@link #ensureDaemonReady()} and
 * {@link #waitForExpectedOrUniqueContext(String, HubInstance, java.util.Set, HubInstanceRuntime)}
 * — are only ever invoked from {@code create}/{@code start}/{@code restart} inside the
 * {@link HubInstanceStartCoordinator} global start lock, so daemon restart, context snapshot
 * and context discovery never overlap between starts. {@link #bindActiveTab(String, String)}
 * is a different guard: it runs under the per-instance lifecycle lock plus the dispatcher
 * idle guard, never restarts the daemon and only fetches status / issues one bind command.
 *
 * @author fengwk
 */
@Slf4j
@Component
class HubInstanceDaemonContextService {

    private final OpenCliDaemonClient daemonClient;
    private final OpenCliHubProperties properties;
    private final HubInstanceService instanceService;
    private final HubInstanceRuntimeRegistry registry;
    private final HubInstanceRuntimeStarter runtimeStarter;

    HubInstanceDaemonContextService(
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
    Set<String> ensureDaemonReady() {
        try {
            OpenCliDaemonStatus status;
            if (registry.list().isEmpty()) {
                daemonClient.ensureRunning();
                status = daemonClient.fetchStatus();
            } else {
                status = daemonClient.fetchStatus();
                if (!hasValidDaemonPid(status)) {
                    throw new OpenCliDaemonException(
                        "OpenCLI daemon is not ready; refusing to restart the shared daemon "
                            + "while another browser instance is running");
                }
            }
            // Start is refused when the daemon cannot reclaim idle tabs: otherwise the UI
            // would show a new TTL configuration that can never take effect.
            requireDaemonReclaimCapability(status);
            return status == null ? Set.of() : new HashSet<>(status.connectedContextIds());
        } catch (OpenCliDaemonException ex) {
            throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                ex, "failed to ensure OpenCLI daemon: " + ex.getMessage());
        }
    }

    /**
     * Waits (bounded by the browser startup timeout) until the instance's expected contextId
     * is connected with the reclaim capability or exactly one new capability-ready contextId
     * appears. Conflicts with already-bound ids and multiple new ids abort with
     * {@code CONTEXT_ID_CONFLICT} / {@code CONTEXT_ID_AMBIGUOUS}.
     *
     * <p>Force-installed extensions may upgrade after their first connection. Keep polling
     * until the capability appears; at timeout, report the last unsupported profile instead
     * of a generic connection timeout. The expected context retains precedence, and process
     * liveness is checked on every poll.
     */
    void waitForExpectedOrUniqueContext(
        String instanceId, HubInstance instance, Set<String> before, HubInstanceRuntime runtime) {
        long startup = properties.getBrowser().getStartupTimeoutMillis();
        long deadline = System.currentTimeMillis() + startup;
        String expected = instance.getContextId();
        // Preserve the last unsupported handshake for timeout diagnostics across disconnects.
        OpenCliDaemonStatus unsupportedStatus = null;
        String unsupportedContextId = null;
        while (System.currentTimeMillis() < deadline) {
            runtimeStarter.ensureProcessesAlive(runtime);
            OpenCliDaemonStatus status = fetchStatusOrFail();
            Set<String> now = status == null ? Set.of() : new HashSet<>(status.connectedContextIds());
            // Another capable profile must not replace a connected expected profile.
            if (expected != null && now.contains(expected)) {
                if (profileHasReclaimCapability(status, expected)) {
                    runtime.setContextId(expected);
                    return;
                }
                unsupportedStatus = status;
                unsupportedContextId = expected;
                sleepQuietly(properties.getRuntime().getReadinessPollMillis());
                continue;
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
                if (profileHasReclaimCapability(status, chosen)) {
                    runtime.setContextId(chosen);
                    if (expected != null && !expected.equals(chosen)) {
                        log.warn("instance {} expected contextId={} but got a unique new id={}; "
                            + "auto-rebinding", instanceId, expected, chosen);
                    }
                    return;
                }
                unsupportedStatus = status;
                unsupportedContextId = chosen;
            } else if (newIds.size() > 1) {
                throw HubErrorCodes.CONTEXT_ID_AMBIGUOUS.asThrowable(
                    "multiple new contextIds appeared after instance " + instanceId
                        + ": " + newIds);
            }
            sleepQuietly(properties.getRuntime().getReadinessPollMillis());
        }
        if (unsupportedStatus != null && unsupportedContextId != null) {
            throw capabilityMissingAtDeadline(
                unsupportedStatus, unsupportedContextId, startup, instanceId);
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
    void bindActiveTab(String contextId, String session) {
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

    /**
     * Reclaims idle adapter tabs for the given connected profile. Both the daemon and the
     * selected extension profile must advertise {@code adapter-tab-reclaim-v1}; a command
     * response with {@code ok=false} is a failure (never treated as success) so the caller
     * retries on a later sweep. Transport failures propagate as runtime exceptions.
     */
    void reclaimAdapterTabs(String contextId) {
        OpenCliDaemonStatus status = fetchStatusForOperation("reclaim idle adapter tabs");
        requireDaemonReclaimCapability(status);
        if (!isConnectedProfile(status, contextId)) {
            throw HubErrorCodes.INSTANCE_CONTEXT_NOT_CONNECTED.asThrowable(
                "instance context is not connected to the OpenCLI daemon: " + contextId);
        }
        requireProfileReclaimCapability(status, contextId);
        OpenCliDaemonCommandResponse response = daemonClient.reclaimAdapterTabs(contextId);
        if (response == null || !Boolean.TRUE.equals(response.getOk())) {
            throw HubErrorCodes.OPENCLI_EXECUTION_FAILED.asThrowable(
                reclaimFailureMessage(response));
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

    private OpenCliDaemonStatus fetchStatusOrFail() {
        try {
            return daemonClient.fetchStatus();
        } catch (OpenCliDaemonException ex) {
            throw HubErrorCodes.INSTANCE_START_FAILED.asThrowable(
                ex, "daemon status fetch failed: " + ex.getMessage());
        }
    }

    private OpenCliDaemonStatus fetchStatusForOperation(String operation) {
        try {
            return daemonClient.fetchStatus();
        } catch (OpenCliDaemonException ex) {
            throw HubErrorCodes.OPENCLI_EXECUTION_FAILED.asThrowable(
                ex, "failed to fetch OpenCLI daemon status for " + operation + ": " + ex.getMessage());
        }
    }

    private static void requireDaemonReclaimCapability(OpenCliDaemonStatus status) {
        if (!hasCapability(status == null ? null : status.getCapabilities(),
            OpenCliDaemonClient.CAPABILITY_ADAPTER_TAB_RECLAIM_V1)) {
            throw HubErrorCodes.OPENCLI_CAPABILITY_MISSING.asThrowable(
                "OpenCLI daemon does not support "
                    + OpenCliDaemonClient.CAPABILITY_ADAPTER_TAB_RECLAIM_V1
                    + "; upgrade the OpenCLI CLI and Browser Bridge extension");
        }
    }

    private static void requireProfileReclaimCapability(
        OpenCliDaemonStatus status, String contextId) {
        if (!profileHasReclaimCapability(status, contextId)) {
            throw HubErrorCodes.OPENCLI_CAPABILITY_MISSING.asThrowable(
                "Browser Bridge profile " + contextId + " does not support "
                    + OpenCliDaemonClient.CAPABILITY_ADAPTER_TAB_RECLAIM_V1
                    + "; upgrade the OpenCLI CLI and Browser Bridge extension");
        }
    }

    /** Reports the last observed handshake; version is diagnostic, not a capability check. */
    private static RuntimeException capabilityMissingAtDeadline(
        OpenCliDaemonStatus status, String contextId, long startupMillis, String instanceId) {
        OpenCliProfileSnapshot profile = findProfile(status, contextId);
        String version = profile == null || profile.getExtensionVersion() == null
            || profile.getExtensionVersion().isBlank() ? "unknown" : profile.getExtensionVersion();
        List<String> capabilities = profile == null || profile.getCapabilities() == null
            ? List.of() : profile.getCapabilities();
        return HubErrorCodes.OPENCLI_CAPABILITY_MISSING.asThrowable(
            "Browser Bridge profile " + contextId + " did not advertise "
                + OpenCliDaemonClient.CAPABILITY_ADAPTER_TAB_RECLAIM_V1 + " within "
                + startupMillis + " ms (instance=" + instanceId + ", extensionVersion=" + version
                + ", capabilities=" + capabilities + "); upgrade the OpenCLI CLI and "
                + "Browser Bridge extension");
    }

    private static boolean profileHasReclaimCapability(
        OpenCliDaemonStatus status, String contextId) {
        OpenCliProfileSnapshot profile = findProfile(status, contextId);
        return profile != null
            && hasCapability(profile.getCapabilities(),
                OpenCliDaemonClient.CAPABILITY_ADAPTER_TAB_RECLAIM_V1);
    }

    private static OpenCliProfileSnapshot findProfile(OpenCliDaemonStatus status, String contextId) {
        if (status == null || status.getProfiles() == null) {
            return null;
        }
        for (OpenCliProfileSnapshot profile : status.getProfiles()) {
            if (profile != null && contextId.equals(profile.getContextId())) {
                return profile;
            }
        }
        return null;
    }

    private static boolean hasCapability(List<String> capabilities, String capability) {
        return capabilities != null && capabilities.contains(capability);
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

    private static String reclaimFailureMessage(OpenCliDaemonCommandResponse response) {
        StringBuilder message = new StringBuilder("OpenCLI daemon rejected idle adapter tab reclaim");
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
        return message.toString();
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
