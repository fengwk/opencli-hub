package fun.fengwk.openclihub.core;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.openclihub.core.command.repo.HubCommandBlacklistRepository;
import fun.fengwk.openclihub.core.command.repo.HubCommandOutputRuleRepository;
import fun.fengwk.openclihub.core.command.service.model.HubCommandBlacklist;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.core.execution.repo.HubExecutionRepository;
import fun.fengwk.openclihub.core.execution.service.model.HubExecution;
import fun.fengwk.openclihub.core.instance.repo.HubInstanceRepository;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
import fun.fengwk.openclihub.share.model.execution.HubExecutionStatus;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import fun.fengwk.openclihub.share.util.HubIds;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies that SQL initialization, generated mapper XML and repository conversion work together.
 * A repository round trip covers more of the persistence baseline than a context-only smoke test.
 *
 * @author fengwk
 */
@SpringBootTest(classes = CoreTestApplication.class)
class CorePersistenceSmokeTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HubInstanceRepository instanceRepository;

    @Autowired
    private HubExecutionRepository executionRepository;

    @Autowired
    private HubCommandBlacklistRepository blacklistRepository;

    @Autowired
    private HubCommandOutputRuleRepository outputRuleRepository;

    @Autowired
    private Clock clock;

    /** Production repositories must all use the same canonical JDK UUID contract. */
    @Test
    void shouldGenerateCanonicalUuidForEveryPersistentAggregate() {
        assertThat(HubIds.isCanonicalUuid(instanceRepository.generateId())).isTrue();
        assertThat(HubIds.isCanonicalUuid(executionRepository.generateId())).isTrue();
        assertThat(HubIds.isCanonicalUuid(blacklistRepository.generateId())).isTrue();
        assertThat(HubIds.isCanonicalUuid(outputRuleRepository.generateId())).isTrue();
    }

    /** The shared audit clock bean must be UTC so persisted wall times are timezone-stable. */
    @Test
    void shouldProvideUtcClockBean() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    /** UUID policy IDs must round-trip through generated MyBatis SQL and VARCHAR columns. */
    @Test
    void shouldPersistUuidCommandPolicyIds() {
        LocalDateTime now = LocalDateTime.now();
        String blacklistId = blacklistRepository.generateId();
        HubCommandBlacklist blacklist = new HubCommandBlacklist();
        blacklist.setId(blacklistId);
        blacklist.setCommandKey("test/blacklist-" + blacklistId);
        blacklist.setReason("uuid migration test");
        blacklist.setCreateTime(now);
        blacklist.setUpdateTime(now);
        assertThat(blacklistRepository.add(blacklist)).isTrue();
        assertThat(blacklistRepository.findById(blacklistId).getId()).isEqualTo(blacklistId);

        String outputRuleId = outputRuleRepository.generateId();
        HubCommandOutputRule outputRule = new HubCommandOutputRule();
        outputRule.setId(outputRuleId);
        outputRule.setCommandKey("test/output-" + outputRuleId);
        outputRule.setArgumentName("output");
        outputRule.setTargetType(HubCommandOutputTargetType.DIRECTORY);
        outputRule.setCreateTime(now);
        outputRule.setUpdateTime(now);
        assertThat(outputRuleRepository.add(outputRule)).isTrue();
        assertThat(outputRuleRepository.findById(outputRuleId).getId()).isEqualTo(outputRuleId);

        assertThat(blacklistRepository.deleteById(blacklistId)).isTrue();
        assertThat(outputRuleRepository.deleteById(outputRuleId)).isTrue();
    }

    /**
     * Mapper pagination must order by the queued wall time ({@code queued_at desc}) with the
     * opaque ID as the stable tie-break — derived by AutoMapper 1.0.0 via {@code @MethodExpr}.
     * Distinct started/finished times prove the order key is {@code queued_at}, not activity time.
     */
    @Test
    void shouldPageExecutionsByQueuedTimeThenOpaqueId() {
        String instanceId = UUID.randomUUID().toString();
        String idA = executionRepository.generateId();
        String idB = executionRepository.generateId();
        String idC = executionRepository.generateId();
        LocalDateTime tiedTime = LocalDateTime.now().withNano(123_000_000);
        assertThat(executionRepository.add(execution(idB, instanceId, tiedTime))).isTrue();
        assertThat(executionRepository.add(execution(idC, instanceId, tiedTime.minusSeconds(1)))).isTrue();
        assertThat(executionRepository.add(execution(idA, instanceId, tiedTime))).isTrue();

        List<String> expected = new ArrayList<>(List.of(idA, idB));
        expected.sort(Comparator.reverseOrder());
        expected.add(idC);
        List<String> actual = executionRepository.page(new PageQuery(1, 10), instanceId)
            .getResults().stream().map(HubExecution::getId).toList();

        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void shouldInitializeAllTablesAndRoundTripCoreAggregates() {
        assertThat(tableCount("hub_instance")).isOne();
        assertThat(tableCount("hub_execution")).isOne();
        assertThat(tableCount("hub_command_blacklist")).isOne();
        assertThat(tableCount("hub_command_output_rule")).isOne();

        LocalDateTime now = LocalDateTime.now();
        HubInstance instance = new HubInstance();
        instance.setId("1001");
        instance.setCode("bilibili-a");
        instance.setDisplayName("Bilibili A");
        instance.setContextId("context-a");
        instance.setState(HubInstanceState.RUNNING);
        instance.setWebsites(List.of("bilibili"));
        instance.setMaxPending(5);
        instance.setStateChangedAt(now);
        instance.setCreateTime(now);
        instance.setUpdateTime(now);
        assertThat(instanceRepository.add(instance)).isTrue();
        assertThat(instanceRepository.findByContextId("context-a").getWebsites()).containsExactly("bilibili");

        HubExecution execution = new HubExecution();
        execution.setId("2001");
        execution.setInstanceId(instance.getId());
        execution.setInstanceCode(instance.getCode());
        execution.setCommandKey("bilibili/hot");
        execution.setSite("bilibili");
        execution.setSiteSession(SiteSessionMode.EPHEMERAL);
        execution.setArgv(List.of("bilibili", "hot", "--limit", "5"));
        execution.setReuseInstance(true);
        execution.setStatus(HubExecutionStatus.PENDING);
        execution.setTimeoutMillis(600000L);
        execution.setQueuedAt(now);
        execution.setCreateTime(now);
        execution.setUpdateTime(now);
        assertThat(executionRepository.add(execution)).isTrue();
        HubExecution saved = executionRepository.findById("2001");
        assertThat(saved.getArgv()).containsExactly("bilibili", "hot", "--limit", "5");
        assertThat(saved.isReuseInstance()).isTrue();
    }

    private static HubExecution execution(String id, String instanceId, LocalDateTime queuedAt) {
        HubExecution execution = new HubExecution();
        execution.setId(id);
        execution.setInstanceId(instanceId);
        execution.setInstanceCode("ordering-test");
        execution.setCommandKey("bilibili/hot");
        execution.setSite("bilibili");
        execution.setSiteSession(SiteSessionMode.EPHEMERAL);
        execution.setArgv(List.of("bilibili", "hot"));
        execution.setStatus(HubExecutionStatus.SUCCEEDED);
        execution.setTimeoutMillis(60_000L);
        execution.setQueuedAt(queuedAt);
        // Activity times deliberately differ from the queue time so the page order can
        // only come from queued_at (the derived order key), never from started/finished.
        execution.setStartedAt(queuedAt.plusSeconds(60));
        execution.setFinishedAt(queuedAt.plusSeconds(120));
        execution.setCreateTime(queuedAt);
        execution.setUpdateTime(queuedAt);
        return execution;
    }

    private int tableCount(String tableName) {
        return jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables where table_name = ?",
            Integer.class,
            tableName);
    }

}
