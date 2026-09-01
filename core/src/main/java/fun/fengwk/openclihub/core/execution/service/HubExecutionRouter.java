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
 * Picks the {@link HubInstance} that will run an asynchronous execution task.
 *
 * <p>Routing semantics:
 * <ul>
 *   <li><b>Explicit instanceId</b> — strict, no failover.</li>
 *   <li><b>Automatic</b> — lowest accepted non-terminal task count;
 *       ties broken by higher priority, then ascending id.</li>
 *   <li><b>Queue full handling</b> — when otherwise-eligible candidates exist but all are full,
 *       automatic routing returns {@code INSTANCE_QUEUE_FULL} instead of {@code NO_INSTANCE_AVAILABLE}.</li>
 * </ul>
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
        CandidateCheck check = checkCandidateEligibility(instance, site);
        if (check != null) {
            throw check.toThrowable();
        }
        if (isInstanceFull(instance)) {
            throw CandidateCheck.queueFull.toThrowable();
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
        boolean hasFullCandidate = false;

        for (HubInstance instance : all) {
            if (checkCandidateEligibility(instance, site) != null) {
                continue;
            }
            int load = loadOf(instance);
            if (isInstanceFull(instance, load)) {
                hasFullCandidate = true;
                continue;
            }
            if (isBetterCandidate(instance, load, chosen, chosenLoad)) {
                chosen = instance;
                chosenLoad = load;
            }
        }
        if (chosen == null) {
            if (hasFullCandidate) {
                throw HubErrorCodes.INSTANCE_QUEUE_FULL.asThrowable(
                    "All candidate instances for site " + site + " are full");
            }
            throw HubErrorCodes.NO_INSTANCE_AVAILABLE.asThrowable(
                "No instance available for site: " + site);
        }
        return chosen;
    }

    private boolean isBetterCandidate(
        HubInstance candidate, int candidateLoad,
        HubInstance chosen, int chosenLoad) {
        if (chosen == null) {
            return true;
        }
        if (candidateLoad != chosenLoad) {
            return candidateLoad < chosenLoad;
        }
        if (candidate.getPriority() != chosen.getPriority()) {
            return candidate.getPriority() > chosen.getPriority();
        }
        return candidate.getId().compareTo(chosen.getId()) < 0;
    }

    private boolean isInstanceFull(HubInstance instance) {
        return isInstanceFull(instance, loadOf(instance));
    }

    private boolean isInstanceFull(HubInstance instance, int load) {
        return load >= dispatchRegistry.getTotalCapacity(instance.getId());
    }

    /**
     * @return {@code null} when {@code instance} satisfies every candidate eligibility criterion;
     *         a non-null {@link CandidateCheck} carries the precise failure reason.
     */
    private CandidateCheck checkCandidateEligibility(HubInstance instance, String site) {
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
        return null;
    }

    /**
     * Current admission load for an instance. This is the exact number of accepted,
     * non-terminal tasks, including the handoff window that is not yet visible in the
     * runtime snapshot's active/pending metrics.
     */
    private int loadOf(HubInstance instance) {
        return dispatchRegistry.getRoutingLoad(instance.getId());
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
                    "instance queue is full");
            }
        };

        abstract ThrowableConventionErrorCode toThrowable();
    }

}
