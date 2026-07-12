package fun.fengwk.openclihub.core.execution.service.model;

import fun.fengwk.openclihub.core.execution.executor.OpenCliExecutionResult;
import fun.fengwk.openclihub.share.model.execution.HubExecutionStatus;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Persisted OpenCLI execution aggregate.
 *
 * @author fengwk
 */
@Data
public class HubExecution {

    private long id;
    private Long instanceId;
    private String instanceCode;
    private String commandKey;
    private String site;
    private SiteSessionMode siteSession;
    private List<String> argv = List.of();
    private HubExecutionStatus status;
    private Integer exitCode;
    private String stdout;
    private boolean stdoutTruncated;
    private String stderr;
    private boolean stderrTruncated;
    private String errorMessage;
    private long timeoutMillis;
    private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public void setArgv(List<String> argv) {
        this.argv = argv == null ? List.of() : new ArrayList<>(argv);
    }

    public void markRunning(LocalDateTime now) {
        status = HubExecutionStatus.RUNNING;
        startedAt = now;
    }

    public void markFinished(OpenCliExecutionResult result, LocalDateTime now) {
        exitCode = result.getExitCode();
        stdout = result.getStdout();
        stdoutTruncated = result.isStdoutTruncated();
        stderr = result.getStderr();
        stderrTruncated = result.isStderrTruncated();
        errorMessage = result.getErrorMessage();
        status = result.isTimedOut()
            ? HubExecutionStatus.TIMED_OUT
            : result.getExitCode() == 0 ? HubExecutionStatus.SUCCEEDED : HubExecutionStatus.FAILED;
        finishedAt = now;
    }

    public long getQueuedMillis() {
        return durationMillis(queuedAt, startedAt);
    }

    public long getDurationMillis() {
        return durationMillis(startedAt, finishedAt);
    }

    private long durationMillis(LocalDateTime start, LocalDateTime end) {
        return start == null || end == null ? 0L : Duration.between(start, end).toMillis();
    }

}
