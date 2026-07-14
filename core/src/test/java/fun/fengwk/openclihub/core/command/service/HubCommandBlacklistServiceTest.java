package fun.fengwk.openclihub.core.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.openclihub.core.command.repo.HubCommandBlacklistRepository;
import fun.fengwk.openclihub.core.command.service.model.HubCommandBlacklist;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the blacklist service: lazy load, idempotent blacklist, refresh()
 * semantics and invalid-input rejection. The {@link InMemoryBlacklist} acts as a real
 * repository so the service cache and persistence stay in sync the way a JDBC-backed
 * repository would behave.
 *
 * @author fengwk
 */
class HubCommandBlacklistServiceTest {

    private InMemoryBlacklist repository;
    private HubCommandBlacklistService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryBlacklist();
        service = new HubCommandBlacklistService(repository);
    }

    @Test
    void shouldLoadAllEntriesFromRepositoryOnFirstAccess() {
        HubCommandBlacklist entry = entry("bilibili/hot", "blocked");
        repository.addDirectly(entry);

        List<HubCommandBlacklist> all = service.listAll();

        assertThat(all).containsExactly(entry);
        // Second call must not hit the repository again because the cache is now warm.
        repository.listAllCallCount = 0;
        service.listAll();
        assertThat(repository.listAllCallCount).isZero();
    }

    @Test
    void shouldPersistNewEntryAndExposeItThroughTheCache() {
        HubCommandBlacklist persisted = service.blacklist("chatgpt/image", "write gated");

        assertThat(persisted).isNotNull();
        assertThat(persisted.getCommandKey()).isEqualTo("chatgpt/image");
        assertThat(persisted.getReason()).isEqualTo("write gated");
        assertThat(service.findByCommandKey("chatgpt/image")).isPresent();
        // After a successful insert the underlying row exists in the repository.
        assertThat(repository.findByCommandKey("chatgpt/image")).isPresent();
    }

    @Test
    void shouldTreatBlacklistOfExistingKeyAsIdempotent() {
        repository.addDirectly(entry("bilibili/hot", "blocked"));
        // Force the cache to learn about the pre-existing entry so the blacklist
        // method short-circuits instead of inserting a duplicate row.
        service.findByCommandKey("bilibili/hot");

        HubCommandBlacklist existing = service.blacklist("bilibili/hot", "another reason");
        assertThat(existing.getReason()).isEqualTo("blocked");
        // The repository must not see another insert attempt because the cache already
        // knew about the entry.
        assertThat(repository.addCount).isZero();
    }

    @Test
    void shouldUnblacklistExistingEntryAndRefreshCache() {
        repository.addDirectly(entry("bilibili/hot", "blocked"));
        // Force initial cache load.
        service.findByCommandKey("bilibili/hot");

        assertThat(service.unblacklist("bilibili/hot")).isTrue();
        assertThat(service.findByCommandKey("bilibili/hot")).isEmpty();
        assertThat(repository.findByCommandKey("bilibili/hot")).isEmpty();
    }

    @Test
    void shouldRejectUnblacklistOfUnknownEntry() {
        // Trigger initial cache load.
        service.findByCommandKey("trigger");
        assertThat(service.unblacklist("unknown/cmd")).isFalse();
        assertThat(repository.deleteCount).isZero();
    }

    @Test
    void shouldRejectBlankCommandKey() {
        assertThatThrownBy(() -> service.blacklist(null, null))
            .isInstanceOf(OpenCliCommandPolicyException.class)
            .satisfies(ex -> assertThat(((OpenCliCommandPolicyException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID));
        assertThatThrownBy(() -> service.blacklist("noSlash", "x"))
            .isInstanceOf(OpenCliCommandPolicyException.class);
    }

    @Test
    void shouldPropagateRepositoryFailureAsPolicyException() {
        repository.failNextAdd = true;
        assertThatThrownBy(() -> service.blacklist("bilibili/hot", "reason"))
            .isInstanceOf(OpenCliCommandPolicyException.class)
            .satisfies(ex -> assertThat(((OpenCliCommandPolicyException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.EXECUTION_PERSIST_FAILED));
    }

    @Test
    void shouldLoadOnceAndDetectExistingEntryOnColdCache() {
        // The repository already holds a row but the service cache is cold. A naive
        // implementation that treats `cache.isEmpty()` as "not loaded" would miss this
        // and try to insert a duplicate. The explicit loaded flag must trigger a
        // single refresh() so blacklist() sees the existing entry.
        repository.addDirectly(entry("bilibili/hot", "blocked"));
        assertThat(repository.listAllCallCount).isZero();

        service.blacklist("bilibili/hot", "another reason");

        assertThat(repository.listAllCallCount).isEqualTo(1);
        assertThat(repository.addCount).isZero();
        assertThat(service.findByCommandKey("bilibili/hot").orElseThrow().getReason())
            .isEqualTo("blocked");
    }

    @Test
    void shouldLoadOnceAndUnblacklistExistingEntryOnColdCache() {
        // Cold cache + populated repository: unblacklist() must observe the row and
        // remove it. listAll() runs twice: once for the initial cache load, once for
        // the post-mutation refresh(); both are correct.
        repository.addDirectly(entry("chatgpt/image", "write gated"));
        assertThat(repository.listAllCallCount).isZero();

        assertThat(service.unblacklist("chatgpt/image")).isTrue();

        assertThat(repository.listAllCallCount).isEqualTo(2);
        assertThat(repository.findByCommandKey("chatgpt/image")).isEmpty();
    }

    @Test
    void shouldLoadOnceWhenDatabaseIsLegitimatelyEmpty() {
        // The "empty cache means not loaded" heuristic would re-hit listAll() on every
        // call against an empty database. The explicit loaded flag must short-circuit
        // after the first refresh.
        int before = repository.listAllCallCount;

        service.listAll();
        int afterFirst = repository.listAllCallCount;

        service.listAll();
        service.findByCommandKey("anything");
        service.snapshot();
        int afterMore = repository.listAllCallCount;

        assertThat(afterFirst - before).isEqualTo(1);
        assertThat(afterMore).isEqualTo(afterFirst);
    }

    private static HubCommandBlacklist entry(String commandKey, String reason) {
        HubCommandBlacklist b = new HubCommandBlacklist();
        b.setId("1001");
        b.setCommandKey(commandKey);
        b.setReason(reason);
        b.setCreateTime(java.time.LocalDateTime.now());
        b.setUpdateTime(java.time.LocalDateTime.now());
        return b;
    }

    /**
     * Test-only repository that keeps a backing list so the cache stays consistent
     * with the persisted state across {@code refresh()} calls.
     */
    private static final class InMemoryBlacklist implements HubCommandBlacklistRepository {

        final java.util.LinkedHashMap<String, HubCommandBlacklist> byKey = new java.util.LinkedHashMap<>();
        int addCount = 0;
        int deleteCount = 0;
        int listAllCallCount = 0;
        boolean failNextAdd = false;

        void addDirectly(HubCommandBlacklist entry) {
            byKey.put(entry.getCommandKey(), entry);
        }

        @Override
        public String generateId() {
            return UUID.randomUUID().toString();
        }

        @Override
        public boolean add(HubCommandBlacklist blacklist) {
            addCount += 1;
            if (failNextAdd) {
                failNextAdd = false;
                return false;
            }
            blacklist.setCreateTime(java.time.LocalDateTime.now());
            blacklist.setUpdateTime(blacklist.getCreateTime());
            byKey.put(blacklist.getCommandKey(), blacklist);
            return true;
        }

        @Override
        public boolean update(HubCommandBlacklist blacklist) {
            if (!byKey.containsKey(blacklist.getCommandKey())) {
                return false;
            }
            blacklist.setUpdateTime(java.time.LocalDateTime.now());
            byKey.put(blacklist.getCommandKey(), blacklist);
            return true;
        }

        @Override
        public boolean deleteById(String id) {
            return byKey.entrySet().removeIf(e -> e.getValue().getId().equals(id));
        }

        @Override
        public boolean deleteByCommandKey(String commandKey) {
            deleteCount += 1;
            return byKey.remove(commandKey) != null;
        }

        @Override
        public HubCommandBlacklist findById(String id) {
            return byKey.values().stream().filter(b -> b.getId().equals(id)).findFirst().orElse(null);
        }

        @Override
        public Optional<HubCommandBlacklist> findByCommandKey(String commandKey) {
            return Optional.ofNullable(byKey.get(commandKey));
        }

        @Override
        public List<HubCommandBlacklist> listAll() {
            listAllCallCount += 1;
            return new ArrayList<>(byKey.values());
        }
    }

}
