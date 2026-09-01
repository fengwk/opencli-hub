package fun.fengwk.openclihub.core.instance.repo.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.instance.repo.HubInstanceRepository;
import fun.fengwk.openclihub.core.instance.repo.impl.model.HubInstanceDO;
import fun.fengwk.openclihub.core.instance.service.impl.HubInstanceServiceImpl;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.instance.service.validation.CatalogWebsiteLookup;
import fun.fengwk.openclihub.core.instance.service.validation.HubInstanceValidator;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import fun.fengwk.openclihub.share.model.instance.HubInstanceUpdateDTO;
import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end repository round-trip against the H2 schema, plus targeted unique-key and
 * ordering verifications. Runs in the shared {@code CoreTestApplication} context so the
 * generated mapper XML is exercised exactly as it would be in production.
 *
 * <p>All insert / update paths use the same repository the service layer depends on,
 * which makes this the authoritative test that the test H2 schema constraints
 * and the Auto Mapper generated SQL agree with the domain model.
 *
 * <p>Codes are randomized per test to coexist with {@link fun.fengwk.openclihub.core.CorePersistenceSmokeTest}
 * on the shared in-memory database.
 */
@SpringBootTest(classes = fun.fengwk.openclihub.core.CoreTestApplication.class)
class MybatisHubInstanceRepositoryH2Test {

    /** Fixed UTC clock so service audit writes are deterministic and exactly assertable. */
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

    @Autowired
    private HubInstanceRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldRoundTripAllPersistedFields() {
        // End-to-end: insert through the repository, read it back, assert every field
        // including the JSON-serialized websites survives the round trip.
        String id = repository.generateId();
        String code = uniqueCode("repo-a");
        HubInstance instance = new HubInstance();
        instance.setId(id);
        instance.setCode(code);
        instance.setDisplayName("Repo A");
        instance.setContextId("ctx-" + code);
        instance.setState(HubInstanceState.RUNNING);
        instance.setWebsites(List.of("bilibili", "chatgpt"));
        instance.setMaxPending(5);
        instance.setMaxConcurrency(3);
        instance.setPriority(7);
        instance.setProxyMode(HubProxyMode.CUSTOM);
        instance.setProxyServer("http://proxy.example:8080");
        LocalDateTime now = LocalDateTime.now();
        instance.setStateChangedAt(now);
        instance.setCreateTime(now);
        instance.setUpdateTime(now);

        assertThat(repository.add(instance)).isTrue();

        HubInstance loaded = repository.findById(id);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getCode()).isEqualTo(code);
        assertThat(loaded.getDisplayName()).isEqualTo("Repo A");
        assertThat(loaded.getContextId()).isEqualTo("ctx-" + code);
        assertThat(loaded.getState()).isEqualTo(HubInstanceState.RUNNING);
        assertThat(loaded.getWebsites()).containsExactly("bilibili", "chatgpt");
        assertThat(loaded.getMaxPending()).isEqualTo(5);
        assertThat(loaded.getMaxConcurrency()).isEqualTo(3);
        assertThat(loaded.getPriority()).isEqualTo(7);
        assertThat(loaded.getProxyMode()).isEqualTo(HubProxyMode.CUSTOM);
        assertThat(loaded.getProxyServer()).isEqualTo("http://proxy.example:8080");
    }

    @Test
    void shouldReturnInstancesOrderedByCreationTimeThenId() {
        // Creation time is the primary order and id is the deterministic tie-break.
        String idA = repository.generateId();
        String idB = repository.generateId();
        String idC = repository.generateId();
        LocalDateTime firstTime = LocalDateTime.now().minusSeconds(2);
        LocalDateTime tiedTime = firstTime.plusSeconds(1);
        HubInstance first = build(idC, uniqueCode("repo-c"), HubInstanceState.STOPPED);
        first.setCreateTime(firstTime);
        HubInstance tiedA = build(idA, uniqueCode("repo-a"), HubInstanceState.RUNNING);
        tiedA.setCreateTime(tiedTime);
        HubInstance tiedB = build(idB, uniqueCode("repo-b"), HubInstanceState.STARTING);
        tiedB.setCreateTime(tiedTime);
        repository.add(tiedB);
        repository.add(first);
        repository.add(tiedA);

        List<String> ids = repository.listAll().stream()
            .filter(inst -> inst.getId().equals(idA) || inst.getId().equals(idB) || inst.getId().equals(idC))
            .map(HubInstance::getId)
            .toList();

        assertThat(ids.get(0)).isEqualTo(idC);
        assertThat(ids.subList(1, 3)).isSorted();
    }

    @Test
    void shouldFindByCodeAndContextId() {
        // findByCode/findByContextId are the only lookup paths the service layer uses
        // for pre-checks; both must work and a null contextId must return null.
        String id = repository.generateId();
        String code = uniqueCode("find-by-code");
        repository.add(build(id, code, HubInstanceState.RUNNING));

        assertThat(repository.findByCode(code)).isNotNull();
        assertThat(repository.findByContextId(null)).isNull();
    }

    @Test
    void shouldEnforceUniqueCodeAtDatabaseLevel() {
        // Pre-check path: the service short-circuits on a duplicate code via findByCode.
        // Race path: a concurrent insert that bypasses the pre-check must still hit the
        // unique index and produce a DuplicateKeyException for the service to translate.
        String code = uniqueCode("dup-code");
        String first = repository.generateId();
        repository.add(build(first, code, HubInstanceState.RUNNING));

        // Pre-check catches it before we hit the constraint.
        assertThatThrownBy(() -> serviceCreate(build(repository.generateId(),
            code, HubInstanceState.RUNNING)))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code").isEqualTo(prefixed(HubErrorCodes.INSTANCE_CODE_CONFLICT));

        // Forcing the unique-key violation directly to confirm error mapping survives
        // a race condition where the pre-check passes but the insert loses.
        HubInstance racer = build(repository.generateId(), code, HubInstanceState.RUNNING);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into hub_instance (id, code, display_name, context_id, state, "
                    + "websites_json, max_pending, last_error_message, state_changed_at, "
                    + "gmt_create, gmt_modified, version) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                racer.getId(), racer.getCode(), racer.getDisplayName(), racer.getContextId(),
                racer.getState().name(), "[]", racer.getMaxPending(), null,
                LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(), 0L))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void shouldNormalizeEmptyWebsitesArrayOnRoundTrip() {
        // Empty website list is allowed at the storage layer (the service rejects it as
        // an argument error before persisting), so the JSON deserializer must round-trip
        // an empty array back into an empty list without throwing.
        String id = repository.generateId();
        HubInstance instance = new HubInstance();
        instance.setId(id);
        instance.setCode(uniqueCode("empty-websites"));
        instance.setDisplayName("Empty");
        instance.setState(HubInstanceState.STOPPED);
        instance.setWebsites(List.of());
        instance.setMaxPending(5);
        LocalDateTime now = LocalDateTime.now();
        instance.setStateChangedAt(now);
        instance.setCreateTime(now);
        instance.setUpdateTime(now);
        repository.add(instance);

        assertThat(repository.findById(id).getWebsites()).isEmpty();
    }

    /** Service audit timestamps must reach both the API model and the gmt_modified column. */
    @Test
    void shouldPersistServiceAuditTimestampUpdatesAndPreserveCreateTime() {
        String id = repository.generateId();
        HubInstance instance = build(id, uniqueCode("audit-time"), HubInstanceState.STOPPED);
        LocalDateTime originalCreateTime = LocalDateTime.of(2025, 1, 2, 3, 4);
        instance.setCreateTime(originalCreateTime);
        instance.setUpdateTime(originalCreateTime);
        assertThat(repository.add(instance)).isTrue();
        HubInstanceServiceImpl service = newService();

        HubInstanceUpdateDTO update = new HubInstanceUpdateDTO();
        update.setCode(instance.getCode());
        update.setDisplayName("Audit Updated");
        update.setWebsites(List.of("bilibili"));
        update.setMaxPending(7);
        HubInstance updated = service.update(id, update);

        HubInstance loaded = repository.findById(id);
        LocalDateTime modifiedColumn = jdbcTemplate.queryForObject(
            "select gmt_modified from hub_instance where id = ?",
            LocalDateTime.class,
            id);
        assertThat(updated.getCreateTime()).isEqualTo(originalCreateTime);
        assertThat(loaded.getCreateTime()).isEqualTo(originalCreateTime);
        assertThat(loaded.getUpdateTime()).isEqualTo(FIXED_NOW);
        assertThat(modifiedColumn).isEqualTo(FIXED_NOW);

        service.updateState(id, HubInstanceState.RUNNING, null);
        loaded = repository.findById(id);
        assertThat(loaded.getUpdateTime()).isEqualTo(FIXED_NOW);
        assertThat(loaded.getUpdateTime()).isEqualTo(loaded.getStateChangedAt());

        service.bindContextId(id, "ctx-" + instance.getCode());
        loaded = repository.findById(id);
        assertThat(loaded.getContextId()).isEqualTo("ctx-" + instance.getCode());
        assertThat(loaded.getUpdateTime()).isEqualTo(FIXED_NOW);
        assertThat(loaded.getCreateTime()).isEqualTo(originalCreateTime);
    }

    @Test
    void shouldDefaultMaxConcurrencyToOneWhenMissingInStorage() {
        // Simulates a legacy row where max_concurrency was not set at insert time.
        String id = repository.generateId();
        String code = uniqueCode("legacy-concurrency");
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
            insert into hub_instance (
                id, code, display_name, context_id, state, websites_json,
                max_pending, priority, proxy_mode, proxy_server,
                last_error_message, state_changed_at, gmt_create, gmt_modified, version
            ) values (?, ?, ?, ?, ?, ?, ?, 0, 'INHERIT', null, null, ?, ?, ?, 0)
            """,
            id, code, "Legacy", null, HubInstanceState.STOPPED.name(), "[]",
            5, now, now, now);

        HubInstance loaded = repository.findById(id);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getMaxConcurrency()).isEqualTo(1);
    }

    @Test
    void shouldFallbackToMaxConcurrencyOneWhenDoHasNull() throws Exception {
        // Verify fromDO handles null maxConcurrency in HubInstanceDO safely.
        HubInstanceDO source = new HubInstanceDO();
        source.setId("test-null-mc");
        source.setCode("test-null-mc");
        source.setDisplayName("Test");
        source.setState("STOPPED");
        source.setMaxPending(5);
        source.setMaxConcurrency(null);

        java.lang.reflect.Method fromDoMethod = MybatisHubInstanceRepository.class
            .getDeclaredMethod("fromDO", HubInstanceDO.class);
        fromDoMethod.setAccessible(true);
        HubInstance instance = (HubInstance) fromDoMethod.invoke(repository, source);

        assertThat(instance.getMaxConcurrency()).isEqualTo(1);
    }

    private HubInstance build(String id, String code, HubInstanceState state) {
        HubInstance instance = new HubInstance();
        instance.setId(id);
        instance.setCode(code);
        instance.setDisplayName("Display " + code);
        instance.setState(state);
        instance.setWebsites(List.of("bilibili"));
        instance.setMaxPending(5);
        LocalDateTime now = LocalDateTime.now();
        instance.setStateChangedAt(now);
        instance.setCreateTime(now);
        instance.setUpdateTime(now);
        return instance;
    }

    private static String uniqueCode(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String prefixed(HubErrorCodes code) {
        return code.getDomain() + "." + code.name();
    }

    /**
     * Runs the full service layer so we exercise both the pre-check and the underlying
     * constraint behavior. Reuses a fresh validator bound to a small in-memory website set.
     */
    private void serviceCreate(HubInstance instance) {
        newService().create(instance);
    }

    private HubInstanceServiceImpl newService() {
        CatalogWebsiteLookup lookup = () -> Set.of("bilibili");
        HubInstanceValidator validator = new HubInstanceValidator(lookup);
        Clock clock = Clock.fixed(
            FIXED_NOW.atZone(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        return new HubInstanceServiceImpl(repository, validator, clock);
    }

}
