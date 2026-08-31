package fun.fengwk.openclihub.core.instance.runtime.test;

import fun.fengwk.openclihub.core.instance.service.HubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.instance.service.validation.CatalogWebsiteLookup;
import fun.fengwk.openclihub.core.instance.service.validation.HubInstanceValidator;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import fun.fengwk.openclihub.share.model.instance.HubInstanceUpdateDTO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory test double for {@link HubInstanceService} that mirrors the production
 * validation, state transition and error mapping without any database dependency.
 *
 * @author fengwk
 */
public class InMemoryHubInstanceService implements HubInstanceService {

    private final HubInstanceValidator validator;
    private final Map<String, HubInstance> rows = new HashMap<>();
    private final Map<String, String> codeIndex = new HashMap<>();
    private final Map<String, String> ctxIndex = new HashMap<>();
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
    public String reserveId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public void validateAndNormalizeForCreate(HubInstance instance) {
        if (instance == null) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("instance payload is required");
        }
        instance.setCode(validator.validateCode(instance.getCode()));
        instance.setDisplayName(validator.validateDisplayName(instance.getDisplayName()));
        instance.setMaxPending(validator.validateMaxPending(instance.getMaxPending()));
        instance.setMaxConcurrency(validator.validateMaxConcurrency(instance.getMaxConcurrency()));
        instance.setPriority(validator.validatePriority(instance.getPriority()));
        instance.setWebsites(validator.validateWebsites(instance.getWebsites()));
        var proxy = validator.normalizeInstanceProxy(
            instance.getProxyMode(), instance.getProxyServer());
        instance.setProxyMode(proxy.proxyMode());
        instance.setProxyServer(proxy.proxyServer());
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
            copy.sort(Comparator.comparing(HubInstance::getCreateTime,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(HubInstance::getId));
            return copy;
        }
    }

    @Override
    public HubInstance get(String id) {
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
            if (instance.getId() == null || instance.getId().isBlank()) {
                instance.setId(reserveId());
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
            LocalDateTime now = LocalDateTime.now();
            if (instance.getCreateTime() == null) {
                instance.setCreateTime(now);
            }
            if (instance.getUpdateTime() == null) {
                instance.setUpdateTime(now);
            }
            rows.put(instance.getId(), copy(instance));
            codeIndex.put(instance.getCode(), instance.getId());
            if (instance.getContextId() != null) {
                ctxIndex.put(instance.getContextId(), instance.getId());
            }
        }
    }

    @Override
    public HubInstance update(String id, HubInstanceUpdateDTO dto) {
        synchronized (lock) {
            HubInstance existing = get(id);
            List<String> normalized = validator.validateEditableProperties(dto);
            existing.setCode(dto.getCode());
            existing.setDisplayName(dto.getDisplayName());
            existing.setWebsites(normalized);
            existing.setMaxPending(dto.getMaxPending());
            if (dto.getMaxConcurrency() != null) {
                existing.setMaxConcurrency(dto.getMaxConcurrency());
            }
            existing.setPriority(dto.getPriority() == null ? 0 : dto.getPriority());
            existing.setProxyMode(dto.getProxyMode());
            existing.setProxyServer(dto.getProxyServer());
            existing.setUpdateTime(LocalDateTime.now());
            rows.put(id, copy(existing));
            return copy(existing);
        }
    }

    @Override
    public void updateState(String id, HubInstanceState newState, String errorMessage) {
        if (newState == null) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("state is required");
        }
        synchronized (lock) {
            HubInstance existing = rows.get(id);
            if (existing == null) {
                throw HubErrorCodes.INSTANCE_NOT_FOUND.asThrowable("instance vanished: " + id);
            }
            LocalDateTime now = LocalDateTime.now();
            existing.setState(newState);
            existing.setStateChangedAt(now);
            existing.setUpdateTime(now);
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
    public void bindContextId(String id, String contextId) {
        if (contextId == null) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("contextId is required");
        }
        String normalized = validator.validateContextId(contextId);
        synchronized (lock) {
            HubInstance existing = rows.get(id);
            if (existing == null) {
                throw HubErrorCodes.INSTANCE_NOT_FOUND.asThrowable("instance vanished: " + id);
            }
            String otherId = ctxIndex.get(normalized);
            if (otherId != null && !otherId.equals(id)) {
                throw HubErrorCodes.CONTEXT_ID_CONFLICT.asThrowable(
                    "contextId already bound: " + normalized);
            }
            if (existing.getContextId() != null) {
                ctxIndex.remove(existing.getContextId(), id);
            }
            existing.setContextId(normalized);
            existing.setUpdateTime(LocalDateTime.now());
            ctxIndex.put(normalized, id);
        }
    }

    @Override
    public void deleteById(String id) {
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
        target.setWebsites(source.getWebsites());
        target.setMaxPending(source.getMaxPending());
        target.setMaxConcurrency(source.getMaxConcurrency());
        target.setPriority(source.getPriority());
        target.setProxyMode(source.getProxyMode());
        target.setProxyServer(source.getProxyServer());
        target.setLastErrorMessage(source.getLastErrorMessage());
        target.setStateChangedAt(source.getStateChangedAt());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
        return target;
    }

}
