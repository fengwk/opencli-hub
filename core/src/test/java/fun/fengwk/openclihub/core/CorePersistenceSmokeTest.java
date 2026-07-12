package fun.fengwk.openclihub.core;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.execution.repo.HubExecutionRepository;
import fun.fengwk.openclihub.core.execution.service.model.HubExecution;
import fun.fengwk.openclihub.core.instance.repo.HubInstanceRepository;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.share.model.execution.HubExecutionStatus;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import java.time.LocalDateTime;
import java.util.List;
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

    @Test
    void shouldInitializeAllTablesAndRoundTripCoreAggregates() {
        assertThat(tableCount("hub_instance")).isOne();
        assertThat(tableCount("hub_execution")).isOne();
        assertThat(tableCount("hub_command_blacklist")).isOne();
        assertThat(tableCount("hub_command_output_rule")).isOne();

        LocalDateTime now = LocalDateTime.now();
        HubInstance instance = new HubInstance();
        instance.setId(1001L);
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
        execution.setId(2001L);
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
        assertThat(executionRepository.add(execution)).isTrue();
        HubExecution saved = executionRepository.findById(2001L);
        assertThat(saved.getArgv()).containsExactly("bilibili", "hot", "--limit", "5");
        assertThat(saved.isReuseInstance()).isTrue();
    }

    private int tableCount(String tableName) {
        return jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables where table_name = ?",
            Integer.class,
            tableName);
    }

}
