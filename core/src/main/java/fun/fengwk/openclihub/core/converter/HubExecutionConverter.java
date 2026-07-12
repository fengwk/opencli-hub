package fun.fengwk.openclihub.core.converter;

import fun.fengwk.openclihub.core.model.HubExecution;
import fun.fengwk.openclihub.share.model.HubExecutionDTO;
import org.springframework.stereotype.Component;

/**
 * @author fengwk
 */
@Component
public class HubExecutionConverter {

    public HubExecutionDTO convert(HubExecution execution) {
        if (execution == null) {
            return null;
        }
        HubExecutionDTO dto = new HubExecutionDTO();
        dto.setId(execution.getId());
        dto.setInstanceId(execution.getInstanceId());
        dto.setInstanceCode(execution.getInstanceCode());
        dto.setCommandKey(execution.getCommandKey());
        dto.setArgv(execution.getArgv());
        dto.setStatus(execution.getStatus());
        dto.setExitCode(execution.getExitCode());
        dto.setStdout(execution.getStdout());
        dto.setStderr(execution.getStderr());
        dto.setErrorMessage(execution.getErrorMessage());
        dto.setTimeoutMillis(execution.getTimeoutMillis());
        dto.setQueuedMillis(execution.getQueuedMillis());
        dto.setDurationMillis(execution.getDurationMillis());
        dto.setQueuedAt(execution.getQueuedAt());
        dto.setStartedAt(execution.getStartedAt());
        dto.setFinishedAt(execution.getFinishedAt());
        return dto;
    }

}
