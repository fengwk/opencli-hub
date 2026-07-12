package fun.fengwk.openclihub.core.instance.runtime.test;

import fun.fengwk.openclihub.core.instance.service.HubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.instance.service.validation.CatalogWebsiteLookup;
import fun.fengwk.openclihub.core.instance.service.validation.HubInstanceValidator;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import fun.fengwk.openclihub.share.model.instance.HubInstanceUpdateDTO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory test double for {@link HubInstanceService} that mirrors the production
 * validation, state transition and error mapping without any database dependency.
 *
 * @author fengwk
 */
public class InMemoryHubInstanceService implements HubInstanceService {

    private final HubInstanceValidator validator;
    private final AtomicLong idSeq = new AtomicLong(1000L);
    private final Map<Long, HubInstance> rows = new HashMap<>();
    private final Map<String, Long> codeIndex = new HashMap<>();
    private final Map<String, Long> ctxIndex = new HashMap<>();
    private final Object lock = new Object();
    /** When non-null, {@link #create} throws this after recording state changes. */
    private RuntimeException insertFailure;

    public InMemoryHubInstanceService() {
        this(() -> Set.of("bilibili", "chatgpt"));
    }

    public InMemoryHubInstanceService(CatalogWebsiteLookup lookup) {
        this.validator = new HubInstanceValidator(lookup);
    }

    public void simulateInsertFailure(RuntimeException failure) {
        this.insertFailure = failure;
    }

    @Override
    public long reserveId() {
        return idSeq.incrementAndGet();
    }

    @Override
    public void validateAndNormalizeForCreate(HubInstance instance) {
        if (instance == null) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("instance payload is required");
        }
        instance.setCode(validator.validateCode(instance.getCode()));
        instance.setDisplayName(validator.validateDisplayName(instance.getDisplayName()));
        instance.setMaxPending(validator.validateMaxPending(instance.getMaxPending()));
        instance.setWebsites(validator.validateWebsites(instance.getWebsites()));
        instance.setContextId(validator.validateContextId(instance.getContextId()));
        if (instance.getState() == null) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("state is required");
        }
        synchronized (lock) {
            if (codeIndex.containsKey(instance.getCode())) {
                throw HubErrorCodes.INSTANCE_CODE_CONFLICT.asThrowable(
                    "instance code already exists: " + instance.getCode());
            }
            if (instance.getContextId() != null && ctxIndex.containsKey(instance.getContextId())) {
                throw HubErrorCodes.CONTEXT_ID_CONFLICT.asThrowable(
                    "contextId already bound: " + instance.getContextId());
            }
        }
    }

    @Override
    public List<HubInstance> list() {
        synchronized (lock) {
            List<HubInstance> copy = new ArrayList<>(rows.values());
            copy.sort((a, b) -> Long.compare(a.getId(), b.getId()));
            return copy;
        }
    }

    @Override
    public HubInstance get(long id) {
        synchronized (lock) {
            HubInstance row = rows.get(id);
            if (row == null) {
                throw HubErrorCodes.INSTANCE_NOT_FOUND.asThrowable(
                    "instance not found: " + id);
            }
            return copy(row);
        }
    }

    @Override
    public void create(HubInstance instance) {
        validateAndNormalizeForCreate(instance);
        synchronized (lock) {
            if (instance.getId() <= 0) {
                instance.setId(idSeq.incrementAndGet());
            }
            if (rows.containsKey(instance.getId())) {
                throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable(
                    "duplicate id: " + instance.getId());
            }
            if (codeIndex.containsKey(instance.getCode())) {
                throw HubErrorCodes.INSTANCE_CODE_CONFLICT.asThrowable(
                    "instance code already exists: " + instance.getCode());
            }
            if (instance.getContextId() != null && ctxIndex.containsKey(instance.getContextId())) {
                throw HubErrorCodes.CONTEXT_ID_CONFLICT.asThrowable(
                    "contextId already bound: " + instance.getContextId());
            }
            if (insertFailure != null) {
                throw insertFailure;
            }
            rows.put(instance.getId(), copy(instance));
            codeIndex.put(instance.getCode(), instance.getId());
            if (instance.getContextId() != null) {
                ctxIndex.put(instance.getContextId(), instance.getId());
            }
        }
    }

    @Override
    public HubInstance update(long id, HubInstanceUpdateDTO dto) {
        synchronized (lock) {
            HubInstance existing = get(id);
            List<String> normalized = validator.validateEditableProperties(dto);
            existing.setCode(dto.getCode());
            existing.setDisplayName(dto.getDisplayName());
            existing.setWebsites(normalized);
            existing.setMaxPending(dto.getMaxPending());
            rows.put(id, copy(existing));
            return copy(existing);
        }
    }

    @Override
    public void updateState(long id, HubInstanceState newState, String errorMessage) {
        if (newState == null) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("state is required");
        }
        synchronized (lock) {
            HubInstance existing = rows.get(id);
            if (existing == null) {
                throw HubErrorCodes.INSTANCE_NOT_FOUND.asThrowable("instance vanished: " + id);
            }
            existing.setState(newState);
            existing.setStateChangedAt(java.time.LocalDateTime.now());
            if (newState == HubInstanceState.ERROR) {
                String trimmed = errorMessage == null ? null : errorMessage.trim();
                existing.setLastErrorMessage(
                    trimmed == null || trimmed.isEmpty() ? "unspecified error" : trimmed);
            } else {
                existing.setLastErrorMessage(null);
            }
        }
    }

    @Override
    public void bindContextId(long id, String contextId) {
        if (contextId == null) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("contextId is required");
        }
        String normalized = validator.validateContextId(contextId);
        synchronized (lock) {
            HubInstance existing = rows.get(id);
            if (existing == null) {
                throw HubErrorCodes.INSTANCE_NOT_FOUND.asThrowable("instance vanished: " + id);
            }
            Long otherId = ctxIndex.get(normalized);
            if (otherId != null && otherId != id) {
                throw HubErrorCodes.CONTEXT_ID_CONFLICT.asThrowable(
                    "contextId already bound: " + normalized);
            }
            if (existing.getContextId() != null) {
                ctxIndex.remove(existing.getContextId(), id);
            }
            existing.setContextId(normalized);
            ctxIndex.put(normalized, id);
        }
    }

    @Override
    public void deleteById(long id) {
        synchronized (lock) {
            HubInstance existing = rows.remove(id);
            if (existing != null) {
                codeIndex.remove(existing.getCode());
                if (existing.getContextId() != null) {
                    ctxIndex.remove(existing.getContextId(), id);
                }
            }
        }
    }

    private static HubInstance copy(HubInstance source) {
        HubInstance target = new HubInstance();
        target.setId(source.getId());
        target.setCode(source.getCode());
        target.setDisplayName(source.getDisplayName());
        target.setContextId(source.getContextId());
        target.setState(source.getState());
        target.setWebsites(new ArrayList<>(source.getWebsites()));
        target.setMaxPending(source.getMaxPending());
        target.setLastErrorMessage(source.getLastErrorMessage());
        target.setStateChangedAt(source.getStateChangedAt());
        return target;
    }

}
