package fun.fengwk.openclihub.core.execution.repo.impl;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.execution.executor.OpenCliExecutionResult;
import fun.fengwk.openclihub.core.execution.repo.HubExecutionRepository;
import fun.fengwk.openclihub.core.execution.service.model.HubExecution;
import fun.fengwk.openclihub.share.model.execution.HubExecutionStatus;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end hub_execution lifecycle against the shared H2 schema and the current
 * generated mapper XML (no mocks): insert PENDING -&gt; CAS RUNNING -&gt; reload -&gt;
 * terminal update. Audit columns must survive every hop: gmt_create stays at the queued
 * time while gmt_modified tracks the latest state, and the domain reload must carry the
 * audit fields back so update() never writes null into the NOT NULL gmt_create column.
 *
 * @author fengwk
 */
@SpringBootTest(classes = fun.fengwk.openclihub.core.CoreTestApplication.class)
class MybatisHubExecutionRepositoryH2Test {

    @Autowired
    private HubExecutionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPreserveAuditColumnsAcrossPendingRunningAndTerminalLifecycle() {
        String instanceId = repository.generateId();
        String id = repository.generateId();
        LocalDateTime queuedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        LocalDateTime runningAt = queuedAt.plusSeconds(5);
        LocalDateTime finishedAt = queuedAt.plusSeconds(10);

        // Insert PENDING: audit times equal the queue time.
        HubExecution execution = new HubExecution();
        execution.setId(id);
        execution.setInstanceId(instanceId);
        execution.setInstanceCode("lifecycle-h2");
        execution.setCommandKey("bilibili/hot");
        execution.setSite("bilibili");
        execution.setSiteSession(SiteSessionMode.EPHEMERAL);
        execution.setArgv(List.of("bilibili", "hot"));
        execution.setStatus(HubExecutionStatus.PENDING);
        execution.setTimeoutMillis(60_000L);
        execution.setQueuedAt(queuedAt);
        execution.setCreateTime(queuedAt);
        execution.setUpdateTime(queuedAt);
        assertThat(repository.add(execution)).isTrue();
        assertThat(gmtCreate(id)).isEqualTo(queuedAt);
        assertThat(gmtModified(id)).isEqualTo(queuedAt);

        // CAS PENDING -> RUNNING writes started_at/gmt_modified and must not touch gmt_create.
        assertThat(repository.markRunningIfPending(id, runningAt)).isTrue();
        assertThat(gmtCreate(id)).isEqualTo(queuedAt);
        assertThat(gmtModified(id)).isEqualTo(runningAt);

        // Reload: the domain must carry both audit fields back.
        HubExecution loaded = repository.findById(id);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getStatus()).isEqualTo(HubExecutionStatus.RUNNING);
        assertThat(loaded.getQueuedAt()).isEqualTo(queuedAt);
        assertThat(loaded.getStartedAt()).isEqualTo(runningAt);
        assertThat(loaded.getCreateTime()).isEqualTo(queuedAt);
        assertThat(loaded.getUpdateTime()).isEqualTo(runningAt);

        // Worker path: transition the reloaded aggregate to terminal and persist it.
        OpenCliExecutionResult result = new OpenCliExecutionResult();
        result.setExitCode(0);
        result.setStdout("{\"items\":[]}");
        loaded.markFinished(result, finishedAt);
        assertThat(repository.update(loaded)).isTrue();

        // Terminal update rewrites gmt_create verbatim and advances gmt_modified.
        assertThat(gmtCreate(id)).isEqualTo(queuedAt);
        assertThat(gmtModified(id)).isEqualTo(finishedAt);

        // Final reload must round-trip everything, including the audit columns.
        HubExecution finalLoad = repository.findById(id);
        assertThat(finalLoad.getStatus()).isEqualTo(HubExecutionStatus.SUCCEEDED);
        assertThat(finalLoad.getFinishedAt()).isEqualTo(finishedAt);
        assertThat(finalLoad.getCreateTime()).isEqualTo(queuedAt);
        assertThat(finalLoad.getUpdateTime()).isEqualTo(finishedAt);
    }

    private LocalDateTime gmtCreate(String id) {
        return jdbcTemplate.queryForObject(
            "select gmt_create from hub_execution where id = ?", LocalDateTime.class, id);
    }

    private LocalDateTime gmtModified(String id) {
        return jdbcTemplate.queryForObject(
            "select gmt_modified from hub_execution where id = ?", LocalDateTime.class, id);
    }
}
