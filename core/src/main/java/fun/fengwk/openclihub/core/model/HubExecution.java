package fun.fengwk.openclihub.core.model;

import fun.fengwk.openclihub.share.model.HubExecutionStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class HubExecution {

    private long id;
    private long instanceId;
    private String instanceCode;
    private String commandKey;
    private List<String> argv;
    private HubExecutionStatus status;
    private Integer exitCode;
    private String stdout;
    private String stderr;
    private String errorMessage;
    private long timeoutMillis;
    private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public static HubExecution createPending(
        long id,
        HubInstance instance,
        String commandKey,
        List<String> argv,
        long timeoutMillis) {
        HubExecution execution = new HubExecution();
        execution.setId(id);
        execution.setInstanceId(instance.getId());
        execution.setInstanceCode(instance.getCode());
        execution.setCommandKey(commandKey);
        execution.setArgv(new ArrayList<>(argv));
        execution.setStatus(HubExecutionStatus.PENDING);
        execution.setTimeoutMillis(timeoutMillis);
        execution.setQueuedAt(LocalDateTime.now());
        return execution;
    }

    public void markRunning() {
        status = HubExecutionStatus.RUNNING;
        startedAt = LocalDateTime.now();
    }

    public void markFinished(OpenCliExecutionResult result) {
        if (result == null) {
            return;
        }
        exitCode = result.getExitCode();
        stdout = result.getStdout();
        stderr = result.getStderr();
        errorMessage = result.getErrorMessage();
        status = result.getExitCode() == 0 ? HubExecutionStatus.SUCCEEDED : HubExecutionStatus.FAILED;
        finishedAt = LocalDateTime.now();
    }

    public void markFailed(String newErrorMessage) {
        errorMessage = newErrorMessage;
        status = HubExecutionStatus.FAILED;
        finishedAt = LocalDateTime.now();
    }

    public long getQueuedMillis() {
        if (queuedAt == null || startedAt == null) {
            return 0L;
        }
        return Duration.between(queuedAt, startedAt).toMillis();
    }

    public long getDurationMillis() {
        if (startedAt == null || finishedAt == null) {
            return 0L;
        }
        return Duration.between(startedAt, finishedAt).toMillis();
    }

}
