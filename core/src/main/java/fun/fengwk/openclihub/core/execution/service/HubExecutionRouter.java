package fun.fengwk.openclihub.core.execution.service;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.execution.runtime.HubDispatchRegistry;
import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntime;
import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntimeRegistry;
import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntimeSnapshot;
import fun.fengwk.openclihub.core.instance.service.HubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Picks the {@link HubInstance} that will run a synchronization execution. Mirrors the
 * contract from {@code docs/technical-design.md §20}:
 * <ul>
 *   <li><b>Explicit instanceId</b> — strict, no failover.</li>
 *   <li><b>Automatic</b> — least-busy among candidates; ties broken by higher priority, then ascending id.</li>
 * </ul>
 *
 * <p>Candidate criteria mirror the design §20.1 list and are evaluated against BOTH the
 * persisted {@link HubInstance} (state, websites, persisted contextId) AND the live
 * {@link HubInstanceRuntime} (live contextId, dispatcher registered, queue capacity). The
 * live runtime's {@code contextId} must be non-null AND match the persisted one — a stale
 * snapshot of an instance whose extension has rebounded since the last persist will be
 * rejected.
 *
 * @author fengwk
 */
@Component
public class HubExecutionRouter {

    private final HubInstanceService instanceService;
    private final HubInstanceRuntimeRegistry runtimeRegistry;
    private final HubDispatchRegistry dispatchRegistry;

    public HubExecutionRouter(HubInstanceService instanceService,
                              HubInstanceRuntimeRegistry runtimeRegistry,
                              HubDispatchRegistry dispatchRegistry) {
        if (instanceService == null) {
            throw new IllegalArgumentException("instanceService must not be null");
        }
        if (runtimeRegistry == null) {
            throw new IllegalArgumentException("runtimeRegistry must not be null");
        }
        if (dispatchRegistry == null) {
            throw new IllegalArgumentException("dispatchRegistry must not be null");
        }
        this.instanceService = instanceService;
        this.runtimeRegistry = runtimeRegistry;
        this.dispatchRegistry = dispatchRegistry;
    }

    /**
     * Choose the {@link HubInstance} that will run the command. {@code explicitInstanceId}
     * may be {@code null} for automatic routing.
     */
    public HubInstance chooseInstance(String site, String explicitInstanceId) {
        if (site == null || site.isBlank()) {
            throw HubErrorCodes.INVALID_EXECUTION_REQUEST.asThrowable("site must not be blank");
        }
        if (explicitInstanceId != null) {
            return chooseExplicit(explicitInstanceId, site);
        }
        return chooseAutomatic(site);
    }

    private HubInstance chooseExplicit(String explicitInstanceId, String site) {
        HubInstance instance = instanceService.get(explicitInstanceId);
        if (instance.getState() != HubInstanceState.RUNNING) {
            throw HubErrorCodes.INSTANCE_NOT_RUNNING.asThrowable(
                "Specified instance is not RUNNING: " + explicitInstanceId);
        }
        if (!instance.supportsWebsite(site)) {
            throw HubErrorCodes.INSTANCE_WEBSITE_NOT_ENABLED.asThrowable(
                "Specified instance does not support site: " + site);
        }
        CandidateCheck check = checkCandidate(instance, site);
        if (check != null) {
            throw check.toThrowable();
        }
        return instance;
    }

    private HubInstance chooseAutomatic(String site) {
        List<HubInstance> all;
        try {
            all = instanceService.list();
        } catch (RuntimeException ex) {
            throw HubErrorCodes.NO_INSTANCE_AVAILABLE.asThrowable(ex,
                "Failed to list instances: " + ex.getMessage());
        }
        HubInstance chosen = null;
        int chosenLoad = Integer.MAX_VALUE;
        int chosenPriority = Integer.MIN_VALUE;
        for (HubInstance instance : all) {
            if (checkCandidate(instance, site) != null) {
                continue;
            }
            int load = loadOf(instance);
            int priority = instance.getPriority();
            if (chosen == null
                || load < chosenLoad
                || (load == chosenLoad && priority > chosenPriority)
                || (load == chosenLoad && priority == chosenPriority
                    && instance.getId().compareTo(chosen.getId()) < 0)) {
                chosen = instance;
                chosenLoad = load;
                chosenPriority = priority;
            }
        }
        if (chosen == null) {
            throw HubErrorCodes.NO_INSTANCE_AVAILABLE.asThrowable(
                "No instance available for site: " + site);
        }
        return chosen;
    }

    /**
     * @return {@code null} when {@code instance} satisfies every candidate criterion; a
     *         non-null {@link CandidateCheck} carries the precise failure reason so the
     *         caller can rethrow the matching domain error.
     */
    private CandidateCheck checkCandidate(HubInstance instance, String site) {
        if (instance.getState() != HubInstanceState.RUNNING) {
            return CandidateCheck.notRunning;
        }
        if (!instance.supportsWebsite(site)) {
            return CandidateCheck.websiteNotEnabled;
        }
        HubInstanceRuntime runtime = runtimeRegistry.get(instance.getId());
        if (runtime == null) {
            return CandidateCheck.runtimeAbsent;
        }
        if (runtime.getContextId() == null || runtime.getContextId().isBlank()) {
            return CandidateCheck.contextOffline;
        }
        String persistedContextId = instance.getContextId();
        if (persistedContextId == null || persistedContextId.isBlank()) {
            return CandidateCheck.contextOffline;
        }
        if (!persistedContextId.equals(runtime.getContextId())) {
            // The extension has rebound since the last persist; refuse until the lifecycle
            // layer observes and re-binds the contextId.
            return CandidateCheck.contextStale;
        }
        HubInstanceRuntimeSnapshot dispatch = dispatchRegistry.getSnapshot(instance.getId());
        if (!dispatch.isRegistered()) {
            return CandidateCheck.dispatcherAbsent;
        }
        if (dispatch.getPendingCount() >= dispatchRegistry.getMaxPending(instance.getId())) {
            return CandidateCheck.queueFull;
        }
        return null;
    }

    /**
     * Current load for an instance (active + pending). Used by automatic routing's
     * least-busy selection and verified by the explicit path via the same candidate
     * check above.
     */
    private int loadOf(HubInstance instance) {
        return dispatchRegistry.getSnapshot(instance.getId()).getLoad();
    }

    private enum CandidateCheck {
        notRunning {
            @Override ThrowableConventionErrorCode toThrowable() {
                return HubErrorCodes.INSTANCE_NOT_RUNNING.asThrowable("instance not RUNNING");
            }
        },
        websiteNotEnabled {
            @Override ThrowableConventionErrorCode toThrowable() {
                return HubErrorCodes.INSTANCE_WEBSITE_NOT_ENABLED.asThrowable(
                    "website not enabled on instance");
            }
        },
        runtimeAbsent {
            @Override ThrowableConventionErrorCode toThrowable() {
                return HubErrorCodes.INSTANCE_RUNTIME_NOT_FOUND.asThrowable(
                    "instance runtime is not registered");
            }
        },
        contextOffline {
            @Override ThrowableConventionErrorCode toThrowable() {
                return HubErrorCodes.INSTANCE_CONTEXT_NOT_CONNECTED.asThrowable(
                    "instance context is offline");
            }
        },
        contextStale {
            @Override ThrowableConventionErrorCode toThrowable() {
                return HubErrorCodes.INSTANCE_CONTEXT_NOT_CONNECTED.asThrowable(
                    "instance live contextId disagrees with persisted contextId");
            }
        },
        dispatcherAbsent {
            @Override ThrowableConventionErrorCode toThrowable() {
                return HubErrorCodes.INSTANCE_RUNTIME_NOT_FOUND.asThrowable(
                    "instance dispatcher is not registered");
            }
        },
        queueFull {
            @Override ThrowableConventionErrorCode toThrowable() {
                return HubErrorCodes.INSTANCE_QUEUE_FULL.asThrowable(
                    "instance pending queue is full");
            }
        };

        abstract ThrowableConventionErrorCode toThrowable();
    }

}
