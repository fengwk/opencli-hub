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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    /** Fixed UTC clock so audit times written by the service are exactly assertable. */
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

    private InMemoryOutputRuleRepository repository;
    private OpenCliCommandCatalog catalog;
    private HubCommandOutputRuleService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOutputRuleRepository();
        catalog = stubCatalog();
        service = new HubCommandOutputRuleService(repository, catalog, fixedClock());
    }

    private static Clock fixedClock() {
        return Clock.fixed(FIXED_NOW.atZone(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    }

    private static OpenCliCommandCatalog stubCatalog() {
        OpenCliCommandCatalog catalog = org.mockito.Mockito.mock(OpenCliCommandCatalog.class);
        org.mockito.Mockito.when(catalog.findPublicCommand("chatgpt", "image"))
            .thenReturn(Optional.of(commandWithArgs("chatgpt", "image",
                arg("prompt", true, true, false),
                arg("op", false, false, false),
                booleanFlag("sd"))));
        org.mockito.Mockito.when(catalog.findPublicCommand("chatgpt", "post"))
            .thenReturn(Optional.of(commandWithArgs("chatgpt", "post",
                arg("file", true, true, true))));
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
        // The service is the audit-time owner: both columns equal the injected clock.
        assertThat(rule.getCreateTime()).isEqualTo(FIXED_NOW);
        assertThat(rule.getUpdateTime()).isEqualTo(FIXED_NOW);
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
        existing.setId("2002");
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
    void shouldKeepCachedRuleUnchangedWhenUpdateFails() {
        // A failed database update must not leak the attempted values into the live cache.
        HubCommandOutputRule existing = new HubCommandOutputRule();
        existing.setId("2002");
        existing.setCommandKey("chatgpt/image");
        existing.setArgumentName("op");
        existing.setTargetType(HubCommandOutputTargetType.DIRECTORY);
        repository.addDirectly(existing);
        service.findByCommandKey("chatgpt/image");
        repository.failUpdates = true;

        assertThatThrownBy(() -> service.upsert("chatgpt/image", "op",
            HubCommandOutputTargetType.FILE, "result.png"))
            .isInstanceOf(OpenCliCommandPolicyException.class);

        HubCommandOutputRule cached = service.findByCommandKey("chatgpt/image").orElseThrow();
        assertThat(cached.getTargetType()).isEqualTo(HubCommandOutputTargetType.DIRECTORY);
        assertThat(cached.getFileName()).isNull();
    }

    @Test
    void shouldDeleteExistingRuleAndClearCache() {
        HubCommandOutputRule existing = new HubCommandOutputRule();
        existing.setId("2002");
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

    @Test
    void shouldLoadOnceAndUpdateExistingRuleOnColdCache() {
        // Cold cache + populated repository: upsert() must observe the existing row and
        // update it instead of inserting a duplicate (which would violate the unique
        // constraint on command_key). listAll() runs twice: once for the initial
        // cache load, once for the post-mutation refresh().
        HubCommandOutputRule existing = new HubCommandOutputRule();
        existing.setId("2002");
        existing.setCommandKey("chatgpt/image");
        existing.setArgumentName("op");
        existing.setTargetType(HubCommandOutputTargetType.DIRECTORY);
        repository.addDirectly(existing);
        assertThat(repository.listAllCallCount).isZero();

        HubCommandOutputRule updated = service.upsert("chatgpt/image", "op",
            HubCommandOutputTargetType.FILE, "result.png");

        assertThat(repository.listAllCallCount).isEqualTo(2);
        assertThat(updated.getId()).isEqualTo("2002");
        assertThat(updated.getTargetType()).isEqualTo(HubCommandOutputTargetType.FILE);
        assertThat(updated.getFileName()).isEqualTo("result.png");
    }

    @Test
    void shouldLoadOnceAndDeleteExistingRuleOnColdCache() {
        HubCommandOutputRule existing = new HubCommandOutputRule();
        existing.setId("2002");
        existing.setCommandKey("chatgpt/image");
        existing.setArgumentName("op");
        existing.setTargetType(HubCommandOutputTargetType.DIRECTORY);
        repository.addDirectly(existing);
        assertThat(repository.listAllCallCount).isZero();

        assertThat(service.delete("chatgpt/image")).isTrue();
        assertThat(repository.listAllCallCount).isEqualTo(2);
        assertThat(repository.findByCommandKey("chatgpt/image")).isEmpty();
    }

    @Test
    void shouldLoadOnceWhenOutputRuleTableIsLegitimatelyEmpty() {
        // The explicit loaded flag must short-circuit listAll() after the first
        // refresh, even when the table is genuinely empty.
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

    @Test
    void shouldRejectPositionalArgument() {
        // `file` accepts a value but is positional: Hub injects managed outputs as named
        // options, so a positional argument can never receive the managed value.
        assertThatThrownBy(() -> service.upsert("chatgpt/post", "file",
            HubCommandOutputTargetType.FILE, "out.txt"))
            .isInstanceOf(OpenCliCommandPolicyException.class)
            .satisfies(ex -> assertThat(((OpenCliCommandPolicyException) ex).getErrorCode())
                .isEqualTo(HubErrorCodes.OPENCLI_OUTPUT_RULE_ARGUMENT_NOT_FOUND));
    }

    @Test
    void shouldAcceptRequiredNamedValueArgument() {
        // `prompt` is a named value-accepting argument (required, valueRequired) and is a
        // legal output argument.
        HubCommandOutputRule rule = service.upsert("chatgpt/image", "prompt",
            HubCommandOutputTargetType.DIRECTORY, null);
        assertThat(rule).isNotNull();
        assertThat(rule.getArgumentName()).isEqualTo("prompt");
        assertThat(repository.findByCommandKey("chatgpt/image")).isPresent();
    }

    @Test
    void shouldKeepPreviousSnapshotWhenReloadFails() {
        repository.addDirectly(rule("chatgpt/image", "op", HubCommandOutputTargetType.DIRECTORY, null));
        // Warm cache.
        service.findByCommandKey("chatgpt/image");
        // Both the explicit reload and the read-path retry fail so the assertion below
        // observes the fail-closed read, not an already-recovered cache.
        repository.failListAllCalls = 2;

        assertThatThrownBy(service::refresh).isInstanceOf(IllegalStateException.class);

        // A failed reload must never degrade the cache into an empty fail-open state:
        // the read path retries the reload and fails closed instead of falling back to
        // a synthetic rule.
        assertThatThrownBy(() -> service.findByCommandKey("chatgpt/image"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRecoverAfterReloadFailure() {
        repository.addDirectly(rule("chatgpt/image", "op", HubCommandOutputTargetType.DIRECTORY, null));
        // Warm cache.
        service.findByCommandKey("chatgpt/image");
        // The database now holds a second row but the reload fails once.
        repository.addDirectly(rule("bilibili/hot", "limit", HubCommandOutputTargetType.DIRECTORY, null));
        repository.failListAllCalls = 1;

        assertThatThrownBy(service::refresh).isInstanceOf(IllegalStateException.class);

        // The repository recovered; the next read must converge to the persisted state
        // instead of staying stale forever.
        assertThat(service.findByCommandKey("bilibili/hot")).isPresent();
        assertThat(service.findByCommandKey("chatgpt/image")).isPresent();
    }

    @Test
    void shouldConvergeWhenRefreshFailsAfterPersist() {
        repository.addDirectly(rule("chatgpt/image", "op", HubCommandOutputTargetType.DIRECTORY, null));
        // Warm cache.
        service.findByCommandKey("chatgpt/image");
        repository.failListAllCalls = 1;

        // The update is persisted, then the post-mutation reload fails: the mutation
        // reports the failure, yet the cache must not remain permanently inconsistent.
        assertThatThrownBy(() -> service.upsert("chatgpt/image", "op",
            HubCommandOutputTargetType.FILE, "result.png"))
            .isInstanceOf(IllegalStateException.class);

        assertThat(repository.findByCommandKey("chatgpt/image").orElseThrow().getTargetType())
            .isEqualTo(HubCommandOutputTargetType.FILE);
        assertThat(service.findByCommandKey("chatgpt/image").orElseThrow().getTargetType())
            .isEqualTo(HubCommandOutputTargetType.FILE);
    }

    @Test
    void shouldKeepPreviousSnapshotWhenCatalogLookupFails() {
        repository.addDirectly(rule("chatgpt/image", "op", HubCommandOutputTargetType.DIRECTORY, null));
        // Warm cache.
        service.findByCommandKey("chatgpt/image");
        org.mockito.Mockito.when(catalog.findPublicCommand("chatgpt", "image"))
            .thenThrow(new IllegalStateException("simulated catalog failure"));

        assertThatThrownBy(() -> service.upsert("chatgpt/image", "op",
            HubCommandOutputTargetType.FILE, "result.png"))
            .isInstanceOf(IllegalStateException.class);

        // Validation failed before any cache mutation: the old rule is still served.
        assertThat(service.findByCommandKey("chatgpt/image").orElseThrow().getTargetType())
            .isEqualTo(HubCommandOutputTargetType.DIRECTORY);
    }

    @Test
    void shouldExposeOnlyCompleteSnapshotsDuringConcurrentReload() throws Exception {
        repository.addDirectly(rule("chatgpt/image", "op", HubCommandOutputTargetType.DIRECTORY, null));
        // Warm cache: snapshot is {chatgpt/image}.
        service.findByCommandKey("chatgpt/image");
        // The database now holds a second row; the reload must publish
        // {chatgpt/image, bilibili/hot} as one atomic replacement.
        repository.addDirectly(rule("bilibili/hot", "limit", HubCommandOutputTargetType.DIRECTORY, null));
        repository.blockListAll = true;

        Thread reloader = new Thread(() -> {
            try {
                service.refresh();
            } catch (RuntimeException ignored) {
                // Reload failure is covered by the dedicated failure tests.
            }
        });
        reloader.start();

        List<String> invalidObservations = Collections.synchronizedList(new ArrayList<>());
        int readers = 4;
        int iterations = 1000;
        CountDownLatch done = new CountDownLatch(readers);
        Thread[] readerThreads = new Thread[readers];
        try {
            assertThat(repository.reloadStarted.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < readers; i++) {
                readerThreads[i] = new Thread(() -> {
                    try {
                        for (int j = 0; j < iterations; j++) {
                            List<HubCommandOutputRule> seen = service.listAll();
                            if (!isCompleteOutputRuleSnapshot(seen)) {
                                invalidObservations.add(seen.stream()
                                    .map(HubCommandOutputRule::getCommandKey)
                                    .toList()
                                    .toString());
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
                readerThreads[i].start();
            }
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            repository.releaseReload.countDown();
        }
        reloader.join(5000);
        assertThat(reloader.isAlive()).isFalse();

        assertThat(invalidObservations).isEmpty();
        assertThat(service.findByCommandKey("bilibili/hot")).isPresent();
    }

    private static boolean isCompleteOutputRuleSnapshot(List<HubCommandOutputRule> seen) {
        if (seen.size() == 1) {
            return "chatgpt/image".equals(seen.get(0).getCommandKey());
        }
        if (seen.size() == 2) {
            return seen.stream().anyMatch(r -> "chatgpt/image".equals(r.getCommandKey()))
                && seen.stream().anyMatch(r -> "bilibili/hot".equals(r.getCommandKey()));
        }
        return false;
    }

    private static HubCommandOutputRule rule(String commandKey, String argumentName,
                                             HubCommandOutputTargetType targetType, String fileName) {
        HubCommandOutputRule rule = new HubCommandOutputRule();
        rule.setId("2002");
        rule.setCommandKey(commandKey);
        rule.setArgumentName(argumentName);
        rule.setTargetType(targetType);
        rule.setFileName(fileName);
        return rule;
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

    private static OpenCliCommandArg booleanFlag(String name) {
        OpenCliCommandArg arg = arg(name, false, false, false);
        arg.setType("boolean");
        return arg;
    }

    /**
     * Test-only repository mirroring the JDBC contract; the service cache stays in
     * sync with the backing store across {@code refresh()} calls.
     */
    private static final class InMemoryOutputRuleRepository implements HubCommandOutputRuleRepository {

        final java.util.LinkedHashMap<String, HubCommandOutputRule> byKey = new java.util.LinkedHashMap<>();
        int listAllCallCount = 0;
        int failListAllCalls = 0;
        boolean failUpdates;
        volatile boolean blockListAll = false;
        final CountDownLatch reloadStarted = new CountDownLatch(1);
        final CountDownLatch releaseReload = new CountDownLatch(1);

        void addDirectly(HubCommandOutputRule rule) {
            byKey.put(rule.getCommandKey(), rule);
        }

        @Override
        public String generateId() {
            return UUID.randomUUID().toString();
        }

        @Override
        public boolean add(HubCommandOutputRule rule) {
            if (byKey.containsKey(rule.getCommandKey())) {
                return false;
            }
            // Faithful to the production repository contract: audit times are supplied by
            // the service and must persist verbatim.
            byKey.put(rule.getCommandKey(), rule);
            return true;
        }

        @Override
        public boolean update(HubCommandOutputRule rule) {
            if (failUpdates || !byKey.containsKey(rule.getCommandKey())) {
                return false;
            }
            byKey.put(rule.getCommandKey(), rule);
            return true;
        }

        @Override
        public boolean deleteById(String id) {
            return byKey.entrySet().removeIf(e -> e.getValue().getId().equals(id));
        }

        @Override
        public boolean deleteByCommandKey(String commandKey) {
            return byKey.remove(commandKey) != null;
        }

        @Override
        public HubCommandOutputRule findById(String id) {
            return byKey.values().stream().filter(r -> r.getId().equals(id)).findFirst().orElse(null);
        }

        @Override
        public Optional<HubCommandOutputRule> findByCommandKey(String commandKey) {
            return Optional.ofNullable(byKey.get(commandKey));
        }

        @Override
        public List<HubCommandOutputRule> listAll() {
            listAllCallCount += 1;
            if (failListAllCalls > 0) {
                failListAllCalls -= 1;
                throw new IllegalStateException("simulated repository listAll failure");
            }
            if (blockListAll) {
                reloadStarted.countDown();
                try {
                    releaseReload.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while reload blocked", ex);
                }
            }
            return new ArrayList<>(byKey.values());
        }
    }

}
