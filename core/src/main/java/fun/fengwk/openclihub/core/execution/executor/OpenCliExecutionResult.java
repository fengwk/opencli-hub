package fun.fengwk.openclihub.core.execution.executor;

import lombok.Data;

/**
 * Captured process result returned by the local OpenCLI executor.
 *
 * @author fengwk
 */
@Data
public class OpenCliExecutionResult {

    private int exitCode;
    private String stdout;
    private boolean stdoutTruncated;
    private String stderr;
    private boolean stderrTruncated;
    private String errorMessage;
    private boolean timedOut;

}
