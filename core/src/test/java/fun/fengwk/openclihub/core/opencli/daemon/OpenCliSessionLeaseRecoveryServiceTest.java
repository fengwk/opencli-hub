package fun.fengwk.openclihub.core.opencli.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for capability-gated, fail-closed session lease recovery selection and CAS
 * request construction. Ensures RECOVERING leases stay fenced and foreign owners are ignored.
 */
class OpenCliSessionLeaseRecoveryServiceTest {

    private static final String OWNER = "opencli-hub:inst-1:exec-9";

    private FakeOpenCliDaemonClient daemon;
    private OpenCliSessionLeaseRecoveryService service;

    @BeforeEach
    void setUp() {
        daemon = new FakeOpenCliDaemonClient();
        service = new OpenCliSessionLeaseRecoveryService(daemon);
    }

    @Test
    void shouldNotPostWhenCapabilityMissing() {
        OpenCliDaemonStatus status = FakeOpenCliDaemonClient.empty();
        status.setPid(1L);
        status.setCapabilities(List.of("session-lease-v1"));
        status.setSessionLeases(List.of(activeLease(OWNER, "run-1")));
        daemon.enqueue(status);

        service.recoverOwnedActiveLeases(OWNER, OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_TIMEOUT);

        assertThat(daemon.recoverRequests()).isEmpty();
        assertThat(daemon.fetchCount()).isEqualTo(1);
        assertThat(daemon.wasRestartInvoked()).isFalse();
    }

    @Test
    void shouldNotPostWhenOwnerDoesNotMatch() {
        OpenCliDaemonStatus status = capableStatus();
        status.setSessionLeases(List.of(
            activeLease("opencli-hub:other:exec-1", "run-foreign"),
            activeLease("cli", "run-cli")));
        daemon.enqueue(status);

        service.recoverOwnedActiveLeases(OWNER, OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_TIMEOUT);

        assertThat(daemon.recoverRequests()).isEmpty();
    }

    @Test
    void shouldPostExactCasRequestForMatchingActiveLease() {
        OpenCliSessionLease owned = activeLease(OWNER, "run-owned");
        owned.setContextId("ctx-1");
        owned.setSurface("adapter");
        owned.setSession("site:chatgpt-agent");
        OpenCliDaemonStatus status = capableStatus();
        status.setSessionLeases(List.of(owned));
        daemon.enqueue(status);

        service.recoverOwnedActiveLeases(OWNER, OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_TIMEOUT);

        assertThat(daemon.recoverRequests()).hasSize(1);
        OpenCliSessionLeaseRecoverRequest request = daemon.recoverRequests().get(0);
        assertThat(request.getContextId()).isEqualTo("ctx-1");
        assertThat(request.getSurface()).isEqualTo("adapter");
        assertThat(request.getSession()).isEqualTo("site:chatgpt-agent");
        assertThat(request.getExpectedRunId()).isEqualTo("run-owned");
        assertThat(request.getMode()).isEqualTo(OpenCliSessionLeaseRecoverRequest.MODE_CANCEL_AND_RESET);
        assertThat(request.getReason()).isEqualTo(OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_TIMEOUT);
        assertThat(daemon.wasRestartInvoked()).isFalse();
    }

    @Test
    void shouldIgnoreRecoveringLeaseWithoutRetry() {
        OpenCliSessionLease recovering = activeLease(OWNER, "run-recovering");
        recovering.setState(OpenCliSessionLeaseRecoveryService.STATE_RECOVERING);
        OpenCliDaemonStatus status = capableStatus();
        status.setSessionLeases(List.of(recovering));
        daemon.enqueue(status);

        service.recoverOwnedActiveLeases(
            OWNER, OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_INTERRUPTED);
        service.recoverOwnedActiveLeases(
            OWNER, OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_INTERRUPTED);

        assertThat(daemon.recoverRequests()).isEmpty();
        assertThat(daemon.fetchCount()).isEqualTo(2);
    }

    @Test
    void shouldSkipMalformedIdentityAndStillRecoverValidSibling() {
        OpenCliSessionLease malformed = activeLease(OWNER, "run-bad");
        malformed.setSession(" ");
        OpenCliSessionLease valid = activeLease(OWNER, "run-good");
        valid.setSession("site:ok");
        OpenCliDaemonStatus status = capableStatus();
        status.setSessionLeases(List.of(malformed, valid));
        daemon.enqueue(status);

        service.recoverOwnedActiveLeases(OWNER, OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_TIMEOUT);

        assertThat(daemon.recoverRequests()).hasSize(1);
        assertThat(daemon.recoverRequests().get(0).getExpectedRunId()).isEqualTo("run-good");
        assertThat(daemon.recoverRequests().get(0).getSession()).isEqualTo("site:ok");
    }

    @Test
    void shouldFailClosedWhenDaemonStatusThrows() {
        daemon.enqueue(capableStatus());
        // Replace fetch with a throwing double by subclassing would be heavy; use fail path
        // via a custom client wrapper.
        OpenCliDaemonClient throwing = new OpenCliDaemonClient() {
            @Override
            public OpenCliDaemonStatus fetchStatus() {
                throw new OpenCliDaemonException("status down");
            }

            @Override
            public OpenCliSessionLeaseRecoverResponse recoverSessionLease(
                OpenCliSessionLeaseRecoverRequest request) {
                throw new AssertionError("recover must not be called");
            }

            @Override
            public OpenCliDaemonCommandResponse bindActiveTab(String contextId) {
                throw new AssertionError("bind must not be called");
            }

            @Override
            public void ensureRunning() {
                throw new AssertionError("ensureRunning must not be called");
            }
        };
        OpenCliSessionLeaseRecoveryService local =
            new OpenCliSessionLeaseRecoveryService(throwing);

        local.recoverOwnedActiveLeases(OWNER, OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_TIMEOUT);
        // No exception escapes.
    }

    @Test
    void shouldFailClosedWhenRecoverPostThrowsAndNotRestart() {
        OpenCliDaemonStatus status = capableStatus();
        status.setSessionLeases(List.of(activeLease(OWNER, "run-1")));
        daemon.enqueue(status);
        daemon.failRecoverWith(new OpenCliDaemonException("HTTP 503"));

        service.recoverOwnedActiveLeases(OWNER, OpenCliSessionLeaseRecoveryService.REASON_EXECUTION_TIMEOUT);

        assertThat(daemon.recoverRequests()).hasSize(1);
        assertThat(daemon.wasRestartInvoked()).isFalse();
    }

    @Test
    void selectOwnedActiveLeasesRequiresExactOwnerAndActiveState() {
        OpenCliSessionLease match = activeLease(OWNER, "run-a");
        OpenCliSessionLease recovering = activeLease(OWNER, "run-b");
        recovering.setState(OpenCliSessionLeaseRecoveryService.STATE_RECOVERING);
        OpenCliSessionLease other = activeLease("other", "run-c");
        OpenCliSessionLease blankRun = activeLease(OWNER, " ");
        blankRun.setRunId(" ");

        List<OpenCliSessionLease> selected = OpenCliSessionLeaseRecoveryService.selectOwnedActiveLeases(
            List.of(match, recovering, other, blankRun), OWNER);

        assertThat(selected).containsExactly(match);
    }

    private static OpenCliDaemonStatus capableStatus() {
        OpenCliDaemonStatus status = FakeOpenCliDaemonClient.empty();
        status.setPid(42L);
        status.setCapabilities(List.of("session-lease-v1",
            OpenCliSessionLeaseRecoveryService.CAPABILITY_SESSION_RECOVER_V1));
        return status;
    }

    private static OpenCliSessionLease activeLease(String owner, String runId) {
        OpenCliSessionLease lease = new OpenCliSessionLease();
        lease.setContextId("ctx");
        lease.setSurface("adapter");
        lease.setSession("site:chatgpt");
        lease.setRunId(runId);
        lease.setOwner(owner);
        lease.setPendingCount(1);
        lease.setState(OpenCliSessionLeaseRecoveryService.STATE_ACTIVE);
        return lease;
    }

}
