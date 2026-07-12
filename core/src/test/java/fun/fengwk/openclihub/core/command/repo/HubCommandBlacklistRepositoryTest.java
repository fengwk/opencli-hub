package fun.fengwk.openclihub.core.command.repo;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.CoreTestApplication;
import fun.fengwk.openclihub.core.command.service.model.HubCommandBlacklist;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * H2 round-trip coverage for {@link HubCommandBlacklistRepository}. The shared
 * {@code local-h2} schema is used so generated Auto Mapper SQL is exercised on the same
 * MySQL-compatibility DDL that production ships.
 *
 * @author fengwk
 */
@SpringBootTest(classes = CoreTestApplication.class)
@Transactional
class HubCommandBlacklistRepositoryTest {

    @Autowired
    private HubCommandBlacklistRepository repository;

    @Test
    void shouldInsertFindAndDeleteById() {
        HubCommandBlacklist blacklist = new HubCommandBlacklist();
        blacklist.setId(repository.generateId());
        blacklist.setCommandKey("bilibili/hot");
        blacklist.setReason("blocked for QA");
        blacklist.setCreateTime(LocalDateTime.now());
        blacklist.setUpdateTime(LocalDateTime.now());
        assertThat(repository.add(blacklist)).isTrue();

        HubCommandBlacklist loaded = repository.findById(blacklist.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getCommandKey()).isEqualTo("bilibili/hot");
        assertThat(loaded.getReason()).isEqualTo("blocked for QA");
        assertThat(loaded.getCreateTime()).isNotNull();

        assertThat(repository.deleteById(blacklist.getId())).isTrue();
        assertThat(repository.findById(blacklist.getId())).isNull();
    }

    @Test
    void shouldLookupByCommandKey() {
        HubCommandBlacklist blacklist = new HubCommandBlacklist();
        blacklist.setId(repository.generateId());
        blacklist.setCommandKey("chatgpt/image");
        blacklist.setReason("write gated");
        blacklist.setCreateTime(LocalDateTime.now());
        blacklist.setUpdateTime(LocalDateTime.now());
        repository.add(blacklist);

        assertThat(repository.findByCommandKey("chatgpt/image")).isPresent()
            .get().extracting(HubCommandBlacklist::getReason).isEqualTo("write gated");
        assertThat(repository.findByCommandKey("nope/none")).isEmpty();

        assertThat(repository.deleteByCommandKey("chatgpt/image")).isTrue();
        assertThat(repository.findByCommandKey("chatgpt/image")).isEmpty();
    }

    @Test
    void shouldRejectUpdatesForMissingIds() {
        HubCommandBlacklist orphan = new HubCommandBlacklist();
        orphan.setId(repository.generateId());
        orphan.setCommandKey("does/not/exist");
        // updateById relies on Auto Mapper generated SQL; with no row present it must
        // return false so the caller can surface EXECUTION_PERSIST_FAILED.
        assertThat(repository.update(orphan)).isFalse();
    }

}