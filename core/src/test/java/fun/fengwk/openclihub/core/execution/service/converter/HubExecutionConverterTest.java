package fun.fengwk.openclihub.core.execution.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.execution.service.model.HubExecution;
import fun.fengwk.openclihub.share.model.execution.HubExecutionDTO;
import fun.fengwk.openclihub.share.model.execution.HubExecutionStatus;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import fun.fengwk.openclihub.share.model.resource.HubResourceItemDTO;
import fun.fengwk.openclihub.share.model.resource.HubResourceSource;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HubExecutionConverter}: domain → wire DTO mapping, including
 * the new {@code reuseInstance} flag and resource list pass-through.
 */
class HubExecutionConverterTest {

    /**
     * Confirms every domain field flows into the DTO, including the {@code reuseInstance}
     * flag introduced by M5.
     */
    @Test
    void shouldCopyAllFieldsAndResources() {
        HubExecution domain = new HubExecution();
        domain.setId(9001L);
        domain.setInstanceId(7L);
        domain.setInstanceCode("worker-7");
        domain.setCommandKey("bilibili/hot");
        domain.setSite("bilibili");
        domain.setSiteSession(SiteSessionMode.PERSISTENT);
        domain.setReuseInstance(true);
        domain.setArgv(List.of("bilibili", "hot", "--limit", "5"));
        domain.setStatus(HubExecutionStatus.SUCCEEDED);
        domain.setExitCode(0);
        domain.setStdout("{\"ok\":true}");
        domain.setStdoutTruncated(false);
        domain.setStderr("");
        domain.setStderrTruncated(false);
        domain.setErrorMessage(null);
        domain.setTimeoutMillis(600000L);
        LocalDateTime q = LocalDateTime.of(2026, 7, 12, 12, 0);
        LocalDateTime s = q.plusSeconds(2);
        LocalDateTime f = q.plusSeconds(7);
        domain.setQueuedAt(q);
        domain.setStartedAt(s);
        domain.setFinishedAt(f);

        HubResourceItemDTO item = new HubResourceItemDTO();
        item.setDate("2026-07-12");
        item.setGroup("execution-9001");
        item.setRelativePath("report.png");
        item.setResourcePath("/resources/2026-07-12/execution-9001/report.png");
        item.setFileName("report.png");
        item.setSource(HubResourceSource.EXECUTION);
        item.setMimeType("image/png");
        item.setSize(42L);
        item.setModifiedAt(LocalDateTime.of(2026, 7, 12, 12, 0, 5));

        HubExecutionDTO dto = new HubExecutionConverter().toDTO(domain, List.of(item));

        assertThat(dto.getId()).isEqualTo(9001L);
        assertThat(dto.getInstanceId()).isEqualTo(7L);
        assertThat(dto.getInstanceCode()).isEqualTo("worker-7");
        assertThat(dto.getCommandKey()).isEqualTo("bilibili/hot");
        assertThat(dto.getSite()).isEqualTo("bilibili");
        assertThat(dto.getSiteSession()).isEqualTo(SiteSessionMode.PERSISTENT);
        assertThat(dto.isReuseInstance()).isTrue();
        assertThat(dto.getArgv())
            .containsExactly("bilibili", "hot", "--limit", "5");
        assertThat(dto.getStatus()).isEqualTo(HubExecutionStatus.SUCCEEDED);
        assertThat(dto.getExitCode()).isZero();
        assertThat(dto.getStdout()).isEqualTo("{\"ok\":true}");
        assertThat(dto.getStderr()).isEmpty();
        assertThat(dto.getTimeoutMillis()).isEqualTo(600000L);
        assertThat(dto.getResources()).hasSize(1);
        assertThat(dto.getResources().get(0).getFileName()).isEqualTo("report.png");
        // queuedMillis / durationMillis — public setters via Lombok.
        assertThat(dto.getQueuedAt()).isEqualTo(q);
        assertThat(dto.getStartedAt()).isEqualTo(s);
        assertThat(dto.getFinishedAt()).isEqualTo(f);
    }

    /**
     * A null domain must produce a null DTO so callers can rely on a single null-channel.
     */
    @Test
    void shouldReturnNullWhenDomainIsNull() {
        assertThat(new HubExecutionConverter().toDTO(null, List.of())).isNull();
    }

}
