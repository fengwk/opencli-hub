package fun.fengwk.openclihub.core.execution.service.converter;

import fun.fengwk.openclihub.core.execution.service.model.HubExecution;
import fun.fengwk.openclihub.share.model.execution.HubExecutionDTO;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Stateless converter from the {@link HubExecution} domain aggregate to the wire
 * {@link HubExecutionDTO} exposed to web callers and tests.
 *
 * @author fengwk
 */
@Component
public class HubExecutionConverter {

    public HubExecutionDTO toDTO(HubExecution execution, List<fun.fengwk.openclihub.share.model.resource.HubResourceItemDTO> resources) {
        if (execution == null) {
            return null;
        }
        HubExecutionDTO dto = new HubExecutionDTO();
        dto.setId(execution.getId());
        dto.setInstanceId(execution.getInstanceId());
        dto.setInstanceCode(execution.getInstanceCode());
        dto.setCommandKey(execution.getCommandKey());
        dto.setSite(execution.getSite());
        dto.setSiteSession(execution.getSiteSession());
        dto.setReuseInstance(execution.isReuseInstance());
        dto.setArgv(execution.getArgv());
        dto.setStatus(execution.getStatus());
        dto.setExitCode(execution.getExitCode());
        dto.setStdout(execution.getStdout());
        dto.setStdoutTruncated(execution.isStdoutTruncated());
        dto.setStderr(execution.getStderr());
        dto.setStderrTruncated(execution.isStderrTruncated());
        dto.setErrorMessage(execution.getErrorMessage());
        dto.setTimeoutMillis(execution.getTimeoutMillis());
        dto.setQueuedMillis(execution.getQueuedMillis());
        dto.setDurationMillis(execution.getDurationMillis());
        dto.setQueuedAt(execution.getQueuedAt());
        dto.setStartedAt(execution.getStartedAt());
        dto.setFinishedAt(execution.getFinishedAt());
        dto.setResources(resources);
        return dto;
    }

}
