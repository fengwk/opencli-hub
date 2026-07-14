package fun.fengwk.openclihub.core.instance.service;

import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import fun.fengwk.openclihub.share.model.instance.HubInstanceUpdateDTO;
import java.util.List;

/**
 * Public service contract for browser instance data operations.
 *
 * <p>Implementations are expected to be pure CRUD and validation only: no browser, VNC or
 * extension process is started here. Lifecycle and runtime operations live in M4 and must
 * consume this contract as their persistence boundary.
 *
 * @author fengwk
 */
public interface HubInstanceService {

    /**
     * Allocates a new instance id without inserting a row. Used by the M4 lifecycle layer
     * to reserve an id during the synchronous create flow. The id is returned by the same
     * underlying generator that backs {@code create}, so reservations remain unique across retries.
     */
    String reserveId();

    /**
     * Validates and normalizes a prospective create payload and checks current unique-key
     * availability without persisting it. Database constraints remain the final race guard.
     */
    void validateAndNormalizeForCreate(HubInstance instance);

    /**
     * Returns every persisted instance ordered by creation time ascending, then id ascending.
     */
    List<HubInstance> list();

    /**
     * Looks up an instance by primary key.
     *
     * @throws fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode
     *         with {@code INSTANCE_NOT_FOUND} when absent
     */
    HubInstance get(String id);

    /**
     * Persists a fully-formed instance aggregate. Caller is responsible for assigning id,
     * timestamps and any pre-populated fields via {@link HubInstance}.
     *
     * <p>Conflict handling:
     * <ul>
     *   <li>Detected by repository pre-check on {@code code} / {@code contextId}.</li>
     *   <li>Race condition (unique index violation) is mapped to the corresponding
     *       domain error.</li>
     * </ul>
     */
    void create(HubInstance instance);

    /**
     * Updates administrator-editable properties: {@code code}, {@code displayName},
     * {@code websites}, {@code maxPending}. The instance id is required.
     *
     * <p>The persisted state, context id and timestamps are not modified here.
     */
    HubInstance update(String id, HubInstanceUpdateDTO dto);

    /**
     * Internal state transition. Maintains {@code stateChangedAt}; for non-{@code ERROR}
     * transitions {@code lastErrorMessage} is cleared, for {@code ERROR} transitions the
     * provided message is recorded. Intended for M4 lifecycle callers.
     *
     * @param id instance id
     * @param newState target state
     * @param errorMessage optional error description, required when {@code newState == ERROR},
     *                     ignored for other states
     */
    void updateState(String id, HubInstanceState newState, String errorMessage);

    /**
     * Internal context binding. The new {@code contextId} must not be in use by any other
     * persisted instance; otherwise {@code CONTEXT_ID_CONFLICT} is raised.
     */
    void bindContextId(String id, String contextId);

    /**
     * Internal database-only delete. Does not delete any instance directory; that capability
     * is owned by the M4 lifecycle layer.
     */
    void deleteById(String id);

}
