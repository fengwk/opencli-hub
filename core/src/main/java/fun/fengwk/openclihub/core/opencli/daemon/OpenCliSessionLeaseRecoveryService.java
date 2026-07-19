package fun.fengwk.openclihub.core.opencli.daemon;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Capability-gated, fail-closed Hub recovery orchestrator.
 *
 * <p>Given an exact Hub-owned run owner, this service:
 * <ol>
 *   <li>Fetches daemon status (daemon is the only lease truth source).</li>
 *   <li>Continues only when {@code session-recover-v1} is advertised.</li>
 *   <li>Selects leases whose owner matches exactly, state is {@code ACTIVE}, and
 *       identity fields are complete.</li>
 *   <li>Issues one {@code CANCEL_AND_RESET} CAS request per selected lease using the
 *       exact identity + {@code expectedRunId} from status.</li>
 * </ol>
 *
 * <p>Any daemon error, missing capability, or malformed data is logged and fails closed.
 * Recovery never restarts the daemon, never retries, never touches {@code RECOVERING}
 * leases, and never changes Hub execution terminal status.
 *
 * @author fengwk
 */
@Slf4j
@Component
public class OpenCliSessionLeaseRecoveryService {

    public static final String CAPABILITY_SESSION_RECOVER_V1 = "session-recover-v1";
    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_RECOVERING = "RECOVERING";
    public static final String REASON_EXECUTION_TIMEOUT = "hub_execution_timeout";
    public static final String REASON_EXECUTION_INTERRUPTED = "hub_execution_interrupted";

    private final OpenCliDaemonClient daemonClient;

    public OpenCliSessionLeaseRecoveryService(OpenCliDaemonClient daemonClient) {
        this.daemonClient = Objects.requireNonNull(daemonClient, "daemonClient");
    }

    /**
     * Best-effort recovery for every ACTIVE lease currently owned by {@code owner}.
     * Never throws; failures are logged only.
     */
    public void recoverOwnedActiveLeases(String owner, String reason) {
        if (owner == null || owner.isBlank()) {
            log.warn("Skipping session lease recovery: owner is blank");
            return;
        }
        if (reason == null || reason.isBlank()) {
            log.warn("Skipping session lease recovery for owner={}: reason is blank", owner);
            return;
        }
        final OpenCliDaemonStatus status;
        try {
            status = daemonClient.fetchStatus();
        } catch (OpenCliDaemonException ex) {
            log.warn(
                "Session lease recovery failed closed for owner={}: daemon status unavailable: {}",
                owner,
                ex.getMessage());
            return;
        } catch (RuntimeException ex) {
            log.warn(
                "Session lease recovery failed closed for owner={}: unexpected status error: {}",
                owner,
                ex.getMessage());
            return;
        }
        if (status == null) {
            log.warn("Session lease recovery failed closed for owner={}: status is null", owner);
            return;
        }
        List<String> capabilities = status.getCapabilities();
        if (capabilities == null || !capabilities.contains(CAPABILITY_SESSION_RECOVER_V1)) {
            log.info(
                "Session lease recovery skipped for owner={}: daemon lacks capability {}",
                owner,
                CAPABILITY_SESSION_RECOVER_V1);
            return;
        }
        List<OpenCliSessionLease> leases = status.getSessionLeases();
        if (leases == null || leases.isEmpty()) {
            log.info("Session lease recovery found no leases for owner={}", owner);
            return;
        }
        List<OpenCliSessionLease> targets = selectOwnedActiveLeases(leases, owner);
        if (targets.isEmpty()) {
            log.info(
                "Session lease recovery found no ACTIVE owned leases for owner={} (scanned={})",
                owner,
                leases.size());
            return;
        }
        for (OpenCliSessionLease lease : targets) {
            recoverOne(lease, owner, reason);
        }
    }

    static List<OpenCliSessionLease> selectOwnedActiveLeases(
        List<OpenCliSessionLease> leases, String owner) {
        List<OpenCliSessionLease> selected = new ArrayList<>();
        for (OpenCliSessionLease lease : leases) {
            if (lease == null) {
                continue;
            }
            if (!owner.equals(lease.getOwner())) {
                continue;
            }
            if (!STATE_ACTIVE.equals(lease.getState())) {
                // RECOVERING must stay fenced; never issue another recovery POST for it.
                continue;
            }
            if (!hasCompleteIdentity(lease)) {
                log.warn(
                    "Skipping malformed ACTIVE lease for owner={}: contextId={}, surface={}, session={}, runId={}",
                    owner,
                    lease.getContextId(),
                    lease.getSurface(),
                    lease.getSession(),
                    lease.getRunId());
                continue;
            }
            selected.add(lease);
        }
        return selected;
    }

    private void recoverOne(OpenCliSessionLease lease, String owner, String reason) {
        OpenCliSessionLeaseRecoverRequest request = new OpenCliSessionLeaseRecoverRequest();
        request.setContextId(lease.getContextId());
        request.setSurface(lease.getSurface());
        request.setSession(lease.getSession());
        request.setExpectedRunId(lease.getRunId());
        request.setMode(OpenCliSessionLeaseRecoverRequest.MODE_CANCEL_AND_RESET);
        request.setReason(reason);
        try {
            OpenCliSessionLeaseRecoverResponse response = daemonClient.recoverSessionLease(request);
            log.info(
                "Session lease recovery completed owner={} contextId={} session={} runId={} result={} tabReset={} cancelledPending={}",
                owner,
                lease.getContextId(),
                lease.getSession(),
                lease.getRunId(),
                response == null ? null : response.getResult(),
                response == null ? null : response.getTabReset(),
                response == null ? null : response.getCancelledPending());
        } catch (OpenCliDaemonException ex) {
            log.warn(
                "Session lease recovery failed closed owner={} contextId={} session={} runId={}: {}",
                owner,
                lease.getContextId(),
                lease.getSession(),
                lease.getRunId(),
                ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn(
                "Session lease recovery unexpected failure owner={} contextId={} session={} runId={}: {}",
                owner,
                lease.getContextId(),
                lease.getSession(),
                lease.getRunId(),
                ex.getMessage());
        }
    }

    private static boolean hasCompleteIdentity(OpenCliSessionLease lease) {
        return isPresent(lease.getContextId())
            && isPresent(lease.getSurface())
            && isPresent(lease.getSession())
            && isPresent(lease.getRunId());
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

}
