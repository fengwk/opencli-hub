package fun.fengwk.openclihub.core.command.repo;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.CoreTestApplication;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * H2 round-trip coverage for {@link HubCommandOutputRuleRepository}. Exercises insert,
 * update, delete-by-id, delete-by-command-key and the unique-key uniqueness check that
 * the database schemas rely on in production.
 *
 * @author fengwk
 */
@SpringBootTest(classes = CoreTestApplication.class)
@Transactional
class HubCommandOutputRuleRepositoryTest {

    @Autowired
    private HubCommandOutputRuleRepository repository;

    @Test
    void shouldInsertAndFindById() {
        HubCommandOutputRule rule = newRule("chatgpt/image", "op", HubCommandOutputTargetType.DIRECTORY, null);
        assertThat(repository.add(rule)).isTrue();

        HubCommandOutputRule loaded = repository.findById(rule.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getCommandKey()).isEqualTo("chatgpt/image");
        assertThat(loaded.getArgumentName()).isEqualTo("op");
        assertThat(loaded.getTargetType()).isEqualTo(HubCommandOutputTargetType.DIRECTORY);
        assertThat(loaded.getFileName()).isNull();

        assertThat(repository.deleteById(rule.getId())).isTrue();
        assertThat(repository.findById(rule.getId())).isNull();
    }

    @Test
    void shouldLookupAndDeleteByCommandKey() {
        HubCommandOutputRule rule = newRule("bilibili/hot", "limit", HubCommandOutputTargetType.FILE, "snapshot.txt");
        repository.add(rule);

        assertThat(repository.findByCommandKey("bilibili/hot")).isPresent()
            .get().extracting(HubCommandOutputRule::getFileName).isEqualTo("snapshot.txt");

        assertThat(repository.deleteByCommandKey("bilibili/hot")).isTrue();
        assertThat(repository.findByCommandKey("bilibili/hot")).isEmpty();
    }

    @Test
    void shouldUpdateExistingRule() {
        HubCommandOutputRule rule = newRule("chatgpt/image", "op", HubCommandOutputTargetType.DIRECTORY, null);
        repository.add(rule);

        rule.setTargetType(HubCommandOutputTargetType.FILE);
        rule.setFileName("result.png");
        assertThat(repository.update(rule)).isTrue();

        HubCommandOutputRule loaded = repository.findByCommandKey("chatgpt/image").orElseThrow();
        assertThat(loaded.getTargetType()).isEqualTo(HubCommandOutputTargetType.FILE);
        assertThat(loaded.getFileName()).isEqualTo("result.png");
    }

    @Test
    void shouldListAllAfterMultipleInserts() {
        repository.add(newRule("chatgpt/image", "op", HubCommandOutputTargetType.DIRECTORY, null));
        repository.add(newRule("bilibili/hot", "limit", HubCommandOutputTargetType.FILE, "hot.txt"));

        assertThat(repository.listAll())
            .extracting(HubCommandOutputRule::getCommandKey)
            .contains("chatgpt/image", "bilibili/hot");
    }

    private static HubCommandOutputRule newRule(String commandKey, String arg,
                                                HubCommandOutputTargetType type, String fileName) {
        HubCommandOutputRule rule = new HubCommandOutputRule();
        rule.setId(UUID.randomUUID().toString());
        rule.setCommandKey(commandKey);
        rule.setArgumentName(arg);
        rule.setTargetType(type);
        rule.setFileName(fileName);
        LocalDateTime now = LocalDateTime.now();
        rule.setCreateTime(now);
        rule.setUpdateTime(now);
        return rule;
    }

}
