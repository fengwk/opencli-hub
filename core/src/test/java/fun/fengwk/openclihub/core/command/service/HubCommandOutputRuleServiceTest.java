package fun.fengwk.openclihub.core.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandArg;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import fun.fengwk.openclihub.core.command.repo.HubCommandOutputRuleRepository;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.command.HubCommandAccess;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the output rule service. The catalog is stubbed so the matrix of
 * validation rules (catalog compatibility, file-name safety, FILE/DIRECTORY shape) can
 * be exercised without spawning Spring; an in-memory repository keeps the cache and the
 * persistence layer in sync the way JDBC would.
 *
 * @author fengwk
 */
class HubCommandOutputRuleServiceTest {

    private InMemoryOutputRuleRepository repository;
    private OpenCliCommandCatalog catalog;
    private HubCommandOutputRuleService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOutputRuleRepository();
        catalog = stubCatalog();
        service = new HubCommandOutputRuleService(repository, catalog);
    }

    private static OpenCliCommandCatalog stubCatalog() {
        OpenCliCommandCatalog catalog = org.mockito.Mockito.mock(OpenCliCommandCatalog.class);
        org.mockito.Mockito.when(catalog.findPublicCommand("chatgpt", "image"))
            .thenReturn(Optional.of(commandWithArgs("chatgpt", "image",
                arg("prompt", true, true, false),
                arg("op", false, true, false),
                arg("sd", false, false, false))));
        org.mockito.Mockito.when(catalog.findPublicCommand("missing", "cmd"))
            .thenReturn(Optional.empty());
        return catalog;
    }

    @Test
    void shouldInsertDirectoryRuleForValueAcceptingArgument() {
        HubCommandOutputRule rule = service.upsert("chatgpt/image", "op",
            HubCommandOutputTargetType.DIRECTORY, null);
        assertThat(rule).isNotNull();
        assertThat(rule.getCommandKey()).isEqualTo("chatgpt/image");
        assertThat(rule.getTargetType()).isEqualTo(HubCommandOutputTargetType.DIRECTORY);
        assertThat(rule.getFileName()).isNull();
        assertThat(repository.findByCommandKey("chatgpt/image")).isPresent();
    }

    @Test
    void shouldRequireFileNameForFileTarget() {
        assertThatThrownBy(() -> service.upsert("chatgpt/image", "op",
            HubCommandOutputTargetType.FILE, null))
            .isInstanceOf(OpenCliCommandPolicyException.class)
            .satisfies(ex -> assertThat(((OpenCliCommandPolicyException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID));
    }

    @Test
    void shouldRejectFileNameOnDirectoryTarget() {
        assertThatThrownBy(() -> service.upsert("chatgpt/image", "op",
            HubCommandOutputTargetType.DIRECTORY, "leak.txt"))
            .isInstanceOf(OpenCliCommandPolicyException.class)
            .satisfies(ex -> assertThat(((OpenCliCommandPolicyException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID));
    }

    @Test
    void shouldRejectUnsafeFileName() {
        for (String bad : List.of("../escape.txt", "a/b.txt", "..", ".", "with space.txt")) {
            assertThatThrownBy(() -> service.upsert("chatgpt/image", "op",
                HubCommandOutputTargetType.FILE, bad))
                .as("fileName should be rejected: " + bad)
                .isInstanceOf(OpenCliCommandPolicyException.class);
        }
    }

    @Test
    void shouldRejectUnknownCommand() {
        assertThatThrownBy(() -> service.upsert("missing/cmd", "x",
            HubCommandOutputTargetType.DIRECTORY, null))
            .isInstanceOf(OpenCliCommandPolicyException.class)
            .satisfies(ex -> assertThat(((OpenCliCommandPolicyException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_RESOURCE_OUTPUT_RULE_INVALID));
    }

    @Test
    void shouldRejectUnknownArgument() {
        assertThatThrownBy(() -> service.upsert("chatgpt/image", "nope",
            HubCommandOutputTargetType.DIRECTORY, null))
            .isInstanceOf(OpenCliCommandPolicyException.class)
            .satisfies(ex -> assertThat(((OpenCliCommandPolicyException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_OUTPUT_RULE_ARGUMENT_NOT_FOUND));
    }

    @Test
    void shouldRejectArgumentWithoutValue() {
        // `sd` is a boolean flag without valueRequired; using it as a managed output
        // argument makes no sense, so the validator must reject it.
        assertThatThrownBy(() -> service.upsert("chatgpt/image", "sd",
            HubCommandOutputTargetType.DIRECTORY, null))
            .isInstanceOf(OpenCliCommandPolicyException.class)
            .satisfies(ex -> assertThat(((OpenCliCommandPolicyException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_OUTPUT_RULE_ARGUMENT_NOT_FOUND));
    }

    @Test
    void shouldRejectManagementCommandKey() {
        assertThatThrownBy(() -> service.upsert("daemon/anything", "x",
            HubCommandOutputTargetType.DIRECTORY, null))
            .isInstanceOf(OpenCliCommandPolicyException.class);
    }

    @Test
    void shouldUpdateExistingRuleInsteadOfInserting() {
        HubCommandOutputRule existing = new HubCommandOutputRule();
        existing.setId(2002L);
        existing.setCommandKey("chatgpt/image");
        existing.setArgumentName("op");
        existing.setTargetType(HubCommandOutputTargetType.DIRECTORY);
        repository.addDirectly(existing);
        // Force cache load so the service knows the entry already exists.
        service.findByCommandKey("chatgpt/image");

        HubCommandOutputRule updated = service.upsert("chatgpt/image", "op",
            HubCommandOutputTargetType.FILE, "result.png");
        assertThat(updated.getTargetType()).isEqualTo(HubCommandOutputTargetType.FILE);
        assertThat(updated.getFileName()).isEqualTo("result.png");
        // Repository now sees the updated values, not the stale DIRECTORY row.
        assertThat(repository.findByCommandKey("chatgpt/image").orElseThrow().getTargetType())
            .isEqualTo(HubCommandOutputTargetType.FILE);
    }

    @Test
    void shouldDeleteExistingRuleAndClearCache() {
        HubCommandOutputRule existing = new HubCommandOutputRule();
        existing.setId(2002L);
        existing.setCommandKey("chatgpt/image");
        existing.setArgumentName("op");
        existing.setTargetType(HubCommandOutputTargetType.DIRECTORY);
        repository.addDirectly(existing);
        // Force cache load.
        service.findByCommandKey("chatgpt/image");

        assertThat(service.delete("chatgpt/image")).isTrue();
        assertThat(service.findByCommandKey("chatgpt/image")).isEmpty();
        assertThat(repository.findByCommandKey("chatgpt/image")).isEmpty();
    }

    private static OpenCliCommand commandWithArgs(String site, String name, OpenCliCommandArg... args) {
        OpenCliCommand cmd = new OpenCliCommand();
        cmd.setSite(site);
        cmd.setName(name);
        cmd.setCommandKey(site + "/" + name);
        cmd.setBrowser(true);
        cmd.setAccess(HubCommandAccess.WRITE);
        cmd.setArgs(new ArrayList<>(List.of(args)));
        return cmd;
    }

    private static OpenCliCommandArg arg(String name, boolean required, boolean valueRequired, boolean positional) {
        OpenCliCommandArg a = new OpenCliCommandArg();
        a.setName(name);
        a.setType("str");
        a.setRequired(required);
        a.setValueRequired(valueRequired);
        a.setPositional(positional);
        return a;
    }

    /**
     * Test-only repository mirroring the JDBC contract; the service cache stays in
     * sync with the backing store across {@code refresh()} calls.
     */
    private static final class InMemoryOutputRuleRepository implements HubCommandOutputRuleRepository {

        final java.util.LinkedHashMap<String, HubCommandOutputRule> byKey = new java.util.LinkedHashMap<>();
        final AtomicLong idGen = new AtomicLong(2000L);

        void addDirectly(HubCommandOutputRule rule) {
            byKey.put(rule.getCommandKey(), rule);
        }

        @Override
        public long generateId() {
            return idGen.incrementAndGet();
        }

        @Override
        public boolean add(HubCommandOutputRule rule) {
            if (byKey.containsKey(rule.getCommandKey())) {
                return false;
            }
            rule.setCreateTime(java.time.LocalDateTime.now());
            rule.setUpdateTime(rule.getCreateTime());
            byKey.put(rule.getCommandKey(), rule);
            return true;
        }

        @Override
        public boolean update(HubCommandOutputRule rule) {
            if (!byKey.containsKey(rule.getCommandKey())) {
                return false;
            }
            rule.setUpdateTime(java.time.LocalDateTime.now());
            byKey.put(rule.getCommandKey(), rule);
            return true;
        }

        @Override
        public boolean deleteById(long id) {
            return byKey.entrySet().removeIf(e -> e.getValue().getId() == id);
        }

        @Override
        public boolean deleteByCommandKey(String commandKey) {
            return byKey.remove(commandKey) != null;
        }

        @Override
        public HubCommandOutputRule findById(long id) {
            return byKey.values().stream().filter(r -> r.getId() == id).findFirst().orElse(null);
        }

        @Override
        public Optional<HubCommandOutputRule> findByCommandKey(String commandKey) {
            return Optional.ofNullable(byKey.get(commandKey));
        }

        @Override
        public List<HubCommandOutputRule> listAll() {
            return new ArrayList<>(byKey.values());
        }
    }

}