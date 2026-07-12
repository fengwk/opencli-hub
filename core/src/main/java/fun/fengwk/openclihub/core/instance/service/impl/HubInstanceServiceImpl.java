package fun.fengwk.openclihub.core.instance.service.impl;

import fun.fengwk.openclihub.core.instance.repo.HubInstanceRepository;
import fun.fengwk.openclihub.core.instance.service.HubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.instance.service.validation.HubInstanceValidator;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import fun.fengwk.openclihub.share.model.instance.HubInstanceUpdateDTO;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Pure data implementation of {@link HubInstanceService}: validation, persistence and
 * state bookkeeping only.
 *
 * <p>No browser / VNC / extension processes are touched here. M4 lifecycle code consumes
 * this service as its persistence boundary and is responsible for the runtime side effects.
 *
 * @author fengwk
 */
@Slf4j
@Service
public class HubInstanceServiceImpl implements HubInstanceService {

    private final HubInstanceRepository repository;
    private final HubInstanceValidator validator;

    public HubInstanceServiceImpl(HubInstanceRepository repository, HubInstanceValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    @Override
    public List<HubInstance> list() {
        return repository.listAll();
    }

    @Override
    public HubInstance get(long id) {
        HubInstance instance = repository.findById(id);
        if (instance == null) {
            throw HubErrorCodes.INSTANCE_NOT_FOUND.asThrowable("instance not found: " + id);
        }
        return instance;
    }

    @Override
    public void create(HubInstance instance) {
        if (instance == null) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("instance payload is required");
        }
        // Same normalization contract as update() so create and update share one set of rules:
        // trimmed code / displayName / contextId, validated maxPending range, normalized websites.
        instance.setCode(validator.validateCode(instance.getCode()));
        instance.setDisplayName(validator.validateDisplayName(instance.getDisplayName()));
        instance.setMaxPending(validator.validateMaxPending(instance.getMaxPending()));
        instance.setWebsites(validator.validateWebsites(instance.getWebsites()));
        instance.setContextId(validator.validateContextId(instance.getContextId()));
        if (instance.getState() == null) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("state is required");
        }
        if (instance.getId() <= 0) {
            instance.setId(repository.generateId());
        }
        if (instance.getStateChangedAt() == null) {
            instance.setStateChangedAt(LocalDateTime.now());
        }

        // Pre-check for races; the database unique index is the ultimate guard.
        if (repository.findByCode(instance.getCode()) != null) {
            throw HubErrorCodes.INSTANCE_CODE_CONFLICT.asThrowable(
                "instance code already exists: " + instance.getCode());
        }
        if (instance.getContextId() != null
            && repository.findByContextId(instance.getContextId()) != null) {
            throw HubErrorCodes.CONTEXT_ID_CONFLICT.asThrowable(
                "contextId already bound: " + instance.getContextId());
        }

        try {
            boolean inserted = repository.add(instance);
            if (!inserted) {
                throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable(
                    "instance insert affected 0 rows: " + instance.getId());
            }
        } catch (DuplicateKeyException ex) {
            // A concurrent caller won the race; surface a stable domain error.
            if (ex.getMostSpecificCause() != null
                && String.valueOf(ex.getMostSpecificCause().getMessage()).toUpperCase()
                    .contains("CONTEXT_ID")) {
                throw HubErrorCodes.CONTEXT_ID_CONFLICT.asThrowable(ex,
                    "contextId already bound: " + instance.getContextId());
            }
            throw HubErrorCodes.INSTANCE_CODE_CONFLICT.asThrowable(ex,
                "instance code already exists: " + instance.getCode());
        }
    }

    @Override
    public HubInstance update(long id, HubInstanceUpdateDTO dto) {
        HubInstance existing = get(id);
        // validateEditableProperties validates every editable field and writes back
        // normalized values (trimmed displayName, validated maxPending) to the DTO.
        List<String> normalizedWebsites = validator.validateEditableProperties(dto);

        existing.setCode(dto.getCode());
        existing.setDisplayName(dto.getDisplayName());
        existing.setWebsites(normalizedWebsites);
        existing.setMaxPending(dto.getMaxPending());

        // Pre-check code uniqueness excluding the row being updated.
        HubInstance byCode = repository.findByCode(existing.getCode());
        if (byCode != null && byCode.getId() != existing.getId()) {
            throw HubErrorCodes.INSTANCE_CODE_CONFLICT.asThrowable(
                "instance code already exists: " + existing.getCode());
        }

        try {
            boolean updated = repository.update(existing);
            if (!updated) {
                throw HubErrorCodes.INSTANCE_NOT_FOUND.asThrowable(
                    "instance vanished during update: " + id);
            }
        } catch (DuplicateKeyException ex) {
            throw HubErrorCodes.INSTANCE_CODE_CONFLICT.asThrowable(ex,
                "instance code already exists: " + existing.getCode());
        }
        return existing;
    }

    @Override
    public void updateState(long id, HubInstanceState newState, String errorMessage) {
        if (newState == null) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("state is required");
        }
        HubInstance existing = get(id);
        LocalDateTime now = LocalDateTime.now();
        existing.setState(newState);
        existing.setStateChangedAt(now);
        if (newState == HubInstanceState.ERROR) {
            // Trim to avoid storing accidental padding; design does not impose a length cap.
            String trimmed = errorMessage == null ? null : errorMessage.trim();
            existing.setLastErrorMessage(
                trimmed == null || trimmed.isEmpty() ? "unspecified error" : trimmed);
        } else {
            existing.setLastErrorMessage(null);
        }
        try {
            boolean updated = repository.update(existing);
            if (!updated) {
                throw HubErrorCodes.INSTANCE_NOT_FOUND.asThrowable(
                    "instance vanished during state update: " + id);
            }
        } catch (DuplicateKeyException ex) {
            // State update touches code too via updateById, but state transitions do not
            // change code/contextId; if a duplicate surfaces here it is unexpected.
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable(ex,
                "state update conflicted: " + id);
        }
    }

    @Override
    public void bindContextId(long id, String contextId) {
        if (contextId == null) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable("contextId is required");
        }
        // validateContextId rejects blank input and returns the trimmed canonical value.
        String normalized = validator.validateContextId(contextId);
        HubInstance existing = get(id);
        HubInstance byCtx = repository.findByContextId(normalized);
        if (byCtx != null && byCtx.getId() != existing.getId()) {
            throw HubErrorCodes.CONTEXT_ID_CONFLICT.asThrowable(
                "contextId already bound: " + normalized);
        }
        existing.setContextId(normalized);
        try {
            boolean updated = repository.update(existing);
            if (!updated) {
                throw HubErrorCodes.INSTANCE_NOT_FOUND.asThrowable(
                    "instance vanished during contextId binding: " + id);
            }
        } catch (DuplicateKeyException ex) {
            throw HubErrorCodes.CONTEXT_ID_CONFLICT.asThrowable(ex,
                "contextId already bound: " + normalized);
        }
    }

    @Override
    public void deleteById(long id) {
        HubInstance existing = repository.findById(id);
        if (existing == null) {
            // Treat as idempotent: a caller (lifecycle layer) may retry safely.
            log.debug("deleteById no-op, instance not found: {}", id);
            return;
        }
        boolean deleted = repository.deleteById(id);
        if (!deleted) {
            throw HubErrorCodes.INSTANCE_ARGUMENT_INVALID.asThrowable(
                "instance delete affected 0 rows: " + id);
        }
    }

}
