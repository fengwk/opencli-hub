package fun.fengwk.openclihub.core.instance.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.instance.repo.HubInstanceRepository;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.instance.service.validation.CatalogWebsiteLookup;
import fun.fengwk.openclihub.core.instance.service.validation.HubInstanceValidator;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import fun.fengwk.openclihub.share.model.instance.HubInstanceUpdateDTO;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/**
 * Unit tests for the pure CRUD service layer. The {@link HubInstanceRepository} is mocked so
 * these tests stay fast and exercise service-level contract (validation order, conflict
 * mapping, race-condition handling, state bookkeeping) without an actual database.
 */
class HubInstanceServiceImplTest {

    private HubInstanceRepository repository;
    private HubInstanceValidator validator;
    private HubInstanceServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(HubInstanceRepository.class);
        CatalogWebsiteLookup lookup = () -> Set.of("bilibili", "chatgpt");
        validator = new HubInstanceValidator(lookup);
        service = new HubInstanceServiceImpl(repository, validator);
    }

    @Test
    void shouldListAllInstancesFromRepository() {
        // list() is a pass-through; verifies no extra validation or filtering is injected.
        HubInstance a = newInstance("1", "a");
        HubInstance b = newInstance("2", "b");
        doReturn(List.of(a, b)).when(repository).listAll();

        List<HubInstance> result = service.list();

        assertThat(result).containsExactly(a, b);
    }

    @Test
    void shouldThrowNotFoundWhenGetMissingInstance() {
        // A missing row must surface INSTANCE_NOT_FOUND so the web layer maps to 404.
        doReturn(null).when(repository).findById("404");

        assertThatThrownBy(() -> service.get("404"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.INSTANCE_NOT_FOUND));
    }

    /** Unsupported path IDs short-circuit before SQL and retain the stable not-found contract. */
    @Test
    void shouldRejectUnsupportedIdBeforeRepositoryLookup() {
        assertThatThrownBy(() -> service.get("not-an-id"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.INSTANCE_NOT_FOUND));

        verify(repository, never()).findById(anyString());
    }

    @Test
    void shouldCreateInstanceAndFillTimestamps() {
        // Service auto-generates id when caller leaves it empty and stamps stateChangedAt.
        doReturn(UUID.randomUUID().toString()).when(repository).generateId();
        doReturn(null).when(repository).findByCode(any());
        doReturn(null).when(repository).findByContextId(any());
        doReturn(true).when(repository).add(any());

        HubInstance instance = new HubInstance();
        instance.setCode("bilibili-a");
        instance.setDisplayName("Bilibili A");
        instance.setContextId("ctx-1");
        instance.setState(HubInstanceState.RUNNING);
        instance.setWebsites(List.of("bilibili"));
        instance.setMaxPending(5);

        service.create(instance);

        ArgumentCaptor<HubInstance> captor = ArgumentCaptor.forClass(HubInstance.class);
        verify(repository, times(1)).add(captor.capture());
        HubInstance saved = captor.getValue();
        assertThat(saved.getId()).matches("[0-9a-f-]{36}");
        assertThat(saved.getWebsites()).containsExactly("bilibili");
        assertThat(saved.getStateChangedAt()).isNotNull();
    }

    @Test
    void shouldRejectCreateWhenCodeAlreadyExists() {
        // Pre-check short-circuits before insert so a known conflict never hits SQL.
        HubInstance existing = newInstance("1", "bilibili-a");
        doReturn(existing).when(repository).findByCode("bilibili-a");

        HubInstance instance = new HubInstance();
        instance.setCode("bilibili-a");
        instance.setDisplayName("Bilibili A");
        instance.setState(HubInstanceState.RUNNING);
        instance.setWebsites(List.of("bilibili"));
        instance.setMaxPending(5);

        assertThatThrownBy(() -> service.create(instance))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.INSTANCE_CODE_CONFLICT));

        verify(repository, never()).add(any());
    }

    @Test
    void shouldMapUniqueConstraintViolationToContextIdConflict() {
        // Race-condition fallback: even if the pre-check passes, the database unique
        // constraint on context_id must be translated to CONTEXT_ID_CONFLICT.
        doReturn(null).when(repository).findByCode(any());
        doReturn(null).when(repository).findByContextId(any());
        doThrow(new DuplicateKeyException("uk_hub_instance_context_id",
            new RuntimeException("Unique index violation: UK_HUB_INSTANCE_CONTEXT_ID")))
            .when(repository).add(any());

        HubInstance instance = new HubInstance();
        instance.setCode("bilibili-b");
        instance.setDisplayName("Bilibili B");
        instance.setContextId("ctx-2");
        instance.setState(HubInstanceState.RUNNING);
        instance.setWebsites(List.of("bilibili"));
        instance.setMaxPending(5);

        assertThatThrownBy(() -> service.create(instance))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.CONTEXT_ID_CONFLICT));
    }

    @Test
    void shouldUpdateEditablePropertiesAndRejectCodeConflict() {
        // Renaming to a code already used by another row must fail with INSTANCE_CODE_CONFLICT.
        HubInstance existing = newInstance("1", "old-code");
        existing.setState(HubInstanceState.RUNNING);
        HubInstance other = newInstance("2", "new-code");
        doReturn(existing).when(repository).findById("1");
        doReturn(other).when(repository).findByCode("new-code");
        doReturn(true).when(repository).update(any());

        HubInstanceUpdateDTO dto = new HubInstanceUpdateDTO();
        dto.setCode("new-code");
        dto.setDisplayName("Renamed");
        dto.setWebsites(List.of("bilibili", "chatgpt"));
        dto.setMaxPending(3);

        assertThatThrownBy(() -> service.update("1", dto))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.INSTANCE_CODE_CONFLICT));

        verify(repository, never()).update(any());
    }

    @Test
    void shouldAllowUpdateKeepingSameCode() {
        // Keeping the same code is allowed: pre-check must skip the row itself.
        HubInstance existing = newInstance("1", "kept-code");
        existing.setState(HubInstanceState.RUNNING);
        doReturn(existing).when(repository).findById("1");
        // Simulating repository.findByCode returning self row.
        doReturn(existing).when(repository).findByCode("kept-code");
        doReturn(true).when(repository).update(any());

        HubInstanceUpdateDTO dto = new HubInstanceUpdateDTO();
        dto.setCode("kept-code");
        dto.setDisplayName("Renamed");
        dto.setWebsites(List.of("chatgpt"));
        dto.setMaxPending(10);

        HubInstance result = service.update("1", dto);

        assertThat(result.getWebsites()).containsExactly("chatgpt");
        assertThat(result.getDisplayName()).isEqualTo("Renamed");
        assertThat(result.getMaxPending()).isEqualTo(10);
    }

    @Test
    void shouldMaintainStateAndClearErrorOnNormalTransition() {
        // Transitioning out of an error state must clear lastErrorMessage.
        HubInstance existing = newInstance("1", "code");
        existing.setState(HubInstanceState.STARTING);
        existing.setLastErrorMessage("prev error");
        doReturn(existing).when(repository).findById("1");
        doReturn(true).when(repository).update(any());

        service.updateState("1", HubInstanceState.RUNNING, "ignored");

        ArgumentCaptor<HubInstance> captor = ArgumentCaptor.forClass(HubInstance.class);
        verify(repository).update(captor.capture());
        HubInstance saved = captor.getValue();
        assertThat(saved.getState()).isEqualTo(HubInstanceState.RUNNING);
        assertThat(saved.getLastErrorMessage()).isNull();
        assertThat(saved.getStateChangedAt()).isNotNull();
    }

    @Test
    void shouldPreserveErrorMessageOnErrorState() {
        // Moving into ERROR must record the supplied message verbatim for UI/log scraping.
        HubInstance existing = newInstance("1", "code");
        existing.setState(HubInstanceState.STARTING);
        doReturn(existing).when(repository).findById("1");
        doReturn(true).when(repository).update(any());

        service.updateState("1", HubInstanceState.ERROR, "boom");

        ArgumentCaptor<HubInstance> captor = ArgumentCaptor.forClass(HubInstance.class);
        verify(repository).update(captor.capture());
        assertThat(captor.getValue().getLastErrorMessage()).isEqualTo("boom");
    }

    @Test
    void shouldBindContextIdAndDetectConflict() {
        // Internal context binding (used by M4 lifecycle) must reject a context already
        // bound to another instance.
        HubInstance existing = newInstance("1", "code");
        existing.setContextId("old");
        HubInstance other = newInstance("2", "other");
        other.setContextId("new-ctx");
        doReturn(existing).when(repository).findById("1");
        doReturn(other).when(repository).findByContextId("new-ctx");

        assertThatThrownBy(() -> service.bindContextId("1", "new-ctx"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.CONTEXT_ID_CONFLICT));

        verify(repository, never()).update(any());
    }

    @Test
    void shouldRejectBlankContextIdBinding() {
        // Blank context ids are invalid input; we must not even query the repository.
        assertThatThrownBy(() -> service.bindContextId("1", " "))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.INSTANCE_ARGUMENT_INVALID));

        verify(repository, never()).findByContextId(any());
    }

    @Test
    void shouldDeleteExistingInstance() {
        // deleteById is the database-only delete path used by M4 lifecycle.
        HubInstance existing = newInstance("1", "code");
        doReturn(existing).when(repository).findById("1");
        doReturn(true).when(repository).deleteById("1");

        service.deleteById("1");

        verify(repository).deleteById("1");
    }

    @Test
    void shouldMakeDeleteIdempotentWhenInstanceMissing() {
        // Missing row must be treated as no-op so M4 retries are safe.
        doReturn(null).when(repository).findById(anyString());

        service.deleteById("404");

        verify(repository, never()).deleteById(anyString());
    }

    @Test
    void shouldTrimAndPersistCodeOnCreate() {
        // Caller-supplied code with surrounding whitespace must be persisted in canonical form.
        doReturn(UUID.randomUUID().toString()).when(repository).generateId();
        doReturn(null).when(repository).findByCode(any());
        doReturn(true).when(repository).add(any());

        HubInstance instance = new HubInstance();
        instance.setCode("  bilibili-a  ");
        instance.setDisplayName("Bilibili A");
        instance.setState(HubInstanceState.RUNNING);
        instance.setWebsites(List.of("bilibili"));
        instance.setMaxPending(5);

        service.create(instance);

        ArgumentCaptor<HubInstance> captor = ArgumentCaptor.forClass(HubInstance.class);
        verify(repository).add(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo("bilibili-a");
    }

    @Test
    void shouldTrimAndPersistContextIdOnCreate() {
        // contextId set on create must also be trimmed before uniqueness check and insert.
        doReturn(UUID.randomUUID().toString()).when(repository).generateId();
        doReturn(null).when(repository).findByCode(any());
        doReturn(null).when(repository).findByContextId(any());
        doReturn(true).when(repository).add(any());

        HubInstance instance = new HubInstance();
        instance.setCode("bilibili-x");
        instance.setDisplayName("X");
        instance.setContextId("  ctx-x  ");
        instance.setState(HubInstanceState.RUNNING);
        instance.setWebsites(List.of("bilibili"));
        instance.setMaxPending(5);

        service.create(instance);

        ArgumentCaptor<HubInstance> captor = ArgumentCaptor.forClass(HubInstance.class);
        verify(repository).add(captor.capture());
        assertThat(captor.getValue().getContextId()).isEqualTo("ctx-x");
    }

    @Test
    void shouldRejectCreateWhenDisplayNameMissing() {
        // create must enforce displayName == required, matching the update contract.
        HubInstance instance = new HubInstance();
        instance.setCode("bilibili-z");
        instance.setDisplayName(null);
        instance.setState(HubInstanceState.RUNNING);
        instance.setWebsites(List.of("bilibili"));
        instance.setMaxPending(5);

        assertThatThrownBy(() -> service.create(instance))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.INSTANCE_ARGUMENT_INVALID));

        verify(repository, never()).add(any());
    }

    @Test
    void shouldRejectCreateWhenMaxPendingAboveUpperBound() {
        // create must enforce the full 1..50 range, not just >0.
        HubInstance instance = new HubInstance();
        instance.setCode("bilibili-m");
        instance.setDisplayName("M");
        instance.setState(HubInstanceState.RUNNING);
        instance.setWebsites(List.of("bilibili"));
        instance.setMaxPending(51);

        assertThatThrownBy(() -> service.create(instance))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.INSTANCE_ARGUMENT_INVALID));

        verify(repository, never()).add(any());
    }

    @Test
    void shouldTrimContextIdOnBind() {
        // bindContextId must query and persist the trimmed canonical value.
        HubInstance existing = newInstance("1", "code");
        doReturn(existing).when(repository).findById("1");
        doReturn(null).when(repository).findByContextId("ctx-y");
        doReturn(true).when(repository).update(any());

        service.bindContextId("1", "  ctx-y  ");

        ArgumentCaptor<HubInstance> captor = ArgumentCaptor.forClass(HubInstance.class);
        verify(repository).update(captor.capture());
        assertThat(captor.getValue().getContextId()).isEqualTo("ctx-y");
        // The repository must be queried with the trimmed value so uniqueness checks are exact.
        verify(repository).findByContextId("ctx-y");
    }

    private HubInstance newInstance(String id, String code) {
        HubInstance inst = new HubInstance();
        inst.setId(id);
        inst.setCode(code);
        inst.setDisplayName("Name");
        inst.setState(HubInstanceState.STOPPED);
        inst.setWebsites(List.of("bilibili"));
        inst.setMaxPending(5);
        return inst;
    }

    private static String prefixed(HubErrorCodes code) {
        return code.getDomain() + "." + code.name();
    }

}
