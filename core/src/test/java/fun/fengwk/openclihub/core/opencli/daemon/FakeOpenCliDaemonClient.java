package fun.fengwk.openclihub.core.opencli.daemon;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test double for {@link OpenCliDaemonClient}. Behaviour can be flipped per phase using the
 * return hooks (e.g. simulate missing /status to drive {@code EXTENSION_CONNECT_TIMEOUT}).
 *
 * <p>Supports a deferred-add mechanism: tests schedule context additions against a
 * fetch-count threshold so the new profile only becomes visible to the lifecycle AFTER the
 * initial pre-create snapshot has been taken.
 *
 * @author fengwk
 */
public class FakeOpenCliDaemonClient implements OpenCliDaemonClient {

    private final AtomicReference<OpenCliDaemonStatus> nextStatus = new AtomicReference<>(empty());
    private final AtomicBoolean restartInvoked = new AtomicBoolean();
    private final AtomicInteger fetchCount = new AtomicInteger();
    private final List<DeferredAdd> deferredAdds = new ArrayList<>();
    private final List<String> ensureRunningCalls = new ArrayList<>();
    private final List<OpenCliSessionLeaseRecoverRequest> recoverRequests = new ArrayList<>();
    private RuntimeException ensureFailure;
    private RuntimeException recoverFailure;
    private OpenCliSessionLeaseRecoverResponse recoverResponse = defaultRecoverResponse();
    private OpenCliDaemonCommandResponse bindResponse = defaultBindResponse();
    private final List<String> bindContextIds = new ArrayList<>();
    /** When set, the daemon exposes the named context on the SECOND fetch and clears it after. */
    private String firstWinsContextId;
    private boolean firstWinsFired = false;

    public FakeOpenCliDaemonClient() {
    }

    public FakeOpenCliDaemonClient(OpenCliDaemonStatus initial) {
        nextStatus.set(initial == null ? empty() : initial);
    }

    public void enqueue(OpenCliDaemonStatus status) {
        nextStatus.set(status == null ? empty() : status);
    }

    public void setProfiles(List<OpenCliProfileSnapshot> profiles) {
        OpenCliDaemonStatus status = nextStatus.get();
        if (status == null) {
            status = empty();
        }
        status.setProfiles(profiles == null ? List.of() : profiles);
        nextStatus.set(status);
    }

    public boolean wasRestartInvoked() {
        return restartInvoked.get();
    }

    public List<String> ensureRunningCalls() {
        return List.copyOf(ensureRunningCalls);
    }

    public void failEnsureWith(RuntimeException failure) {
        this.ensureFailure = failure;
    }

    @Override
    public OpenCliDaemonStatus fetchStatus() {
        int count = fetchCount.incrementAndGet();
        // Apply any deferred additions whose threshold has been reached.
        for (DeferredAdd add : new ArrayList<>(deferredAdds)) {
            if (count >= add.activateOnFetch) {
                applyAdd(add);
                deferredAdds.remove(add);
            }
        }
        // First-wins: on the second call, expose the named context; on the third call,
        // remove it so subsequent calls see no new ids.
        if (firstWinsContextId != null) {
            if (count == 2) {
                addConnectedContext(firstWinsContextId);
                firstWinsFired = true;
            } else if (count >= 3 && firstWinsFired) {
                OpenCliDaemonStatus snap = nextStatus.get();
                if (snap != null && snap.getProfiles() != null) {
                    snap.setProfiles(snap.getProfiles().stream()
                        .filter(p -> !firstWinsContextId.equals(p.getContextId()))
                        .toList());
                }
            }
        }
        return nextStatus.get();
    }

    public void setFirstWinsStrategy(String contextId) {
        this.firstWinsContextId = contextId;
        this.firstWinsFired = false;
    }

    @Override
    public OpenCliSessionLeaseRecoverResponse recoverSessionLease(
        OpenCliSessionLeaseRecoverRequest request) {
        recoverRequests.add(request);
        if (recoverFailure != null) {
            throw recoverFailure;
        }
        return recoverResponse;
    }

    @Override
    public OpenCliDaemonCommandResponse bindActiveTab(String contextId) {
        bindContextIds.add(contextId);
        return bindResponse;
    }

    public void setBindResponse(OpenCliDaemonCommandResponse response) {
        this.bindResponse = response == null ? defaultBindResponse() : response;
    }

    public List<String> bindContextIds() {
        return List.copyOf(bindContextIds);
    }

    public void setRecoverResponse(OpenCliSessionLeaseRecoverResponse response) {
        this.recoverResponse = response == null ? defaultRecoverResponse() : response;
    }

    public void failRecoverWith(RuntimeException failure) {
        this.recoverFailure = failure;
    }

    public List<OpenCliSessionLeaseRecoverRequest> recoverRequests() {
        return List.copyOf(recoverRequests);
    }

    @Override
    public void ensureRunning() {
        ensureRunningCalls.add("ensureRunning");
        restartInvoked.set(true);
        if (ensureFailure != null) {
            throw ensureFailure;
        }
    }

    /** Lifts the current {@code /status} snapshot up to expose a single new context id. */
    public void addConnectedContext(String contextId) {
        OpenCliDaemonStatus snapshot = nextStatus.get();
        if (snapshot == null) {
            snapshot = empty();
        }
        List<OpenCliProfileSnapshot> profiles = new ArrayList<>(snapshot.getProfiles());
        OpenCliProfileSnapshot ps = new OpenCliProfileSnapshot();
        ps.setContextId(contextId);
        ps.setExtensionConnected(true);
        ps.setExtensionVersion("v1.0.22");
        profiles.add(ps);
        snapshot.setProfiles(profiles);
        nextStatus.set(snapshot);
    }

    /**
     * Schedules a context addition that takes effect on the n-th {@link #fetchStatus} call
     * (where the very first call is 1). Use this in tests that need to model "the daemon
     * reports empty before, then exposes a new context id after Chrome starts".
     */
    public void addConnectedContextAfterFetch(String contextId, int activateOnFetch) {
        deferredAdds.add(new DeferredAdd(contextId, activateOnFetch));
    }

    public void clearProfiles() {
        OpenCliDaemonStatus snapshot = nextStatus.get();
        if (snapshot == null) {
            snapshot = empty();
        } else {
            snapshot.setProfiles(List.of());
        }
        nextStatus.set(snapshot);
    }

    public int fetchCount() {
        return fetchCount.get();
    }

    private void applyAdd(DeferredAdd add) {
        addConnectedContext(add.contextId);
    }

    public static OpenCliDaemonStatus empty() {
        OpenCliDaemonStatus status = new OpenCliDaemonStatus();
        status.setPid(0L);
        status.setProfiles(List.of());
        status.setCapabilities(List.of());
        status.setSessionLeases(List.of());
        return status;
    }

    private static OpenCliSessionLeaseRecoverResponse defaultRecoverResponse() {
        OpenCliSessionLeaseRecoverResponse response = new OpenCliSessionLeaseRecoverResponse();
        response.setOk(true);
        response.setResult("RECOVERED");
        response.setTabReset(true);
        response.setCancelledPending(0);
        return response;
    }

    private static OpenCliDaemonCommandResponse defaultBindResponse() {
        OpenCliDaemonCommandResponse response = new OpenCliDaemonCommandResponse();
        response.setId("fake-bind");
        response.setOk(true);
        return response;
    }

    private static final class DeferredAdd {
        final String contextId;
        final int activateOnFetch;
        DeferredAdd(String contextId, int activateOnFetch) {
            this.contextId = contextId;
            this.activateOnFetch = activateOnFetch;
        }
    }

}
