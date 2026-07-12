package fun.fengwk.openclihub.core.model;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class OpenCliExecutionResult {

    private int exitCode;
    private String stdout;
    private String stderr;
    private String errorMessage;

    public static OpenCliExecutionResult success(int exitCode, String stdout, String stderr) {
        OpenCliExecutionResult result = new OpenCliExecutionResult();
        result.setExitCode(exitCode);
        result.setStdout(stdout);
        result.setStderr(stderr);
        return result;
    }

    public static OpenCliExecutionResult failed(int exitCode, String stdout, String stderr, String errorMessage) {
        OpenCliExecutionResult result = success(exitCode, stdout, stderr);
        result.setErrorMessage(errorMessage);
        return result;
    }

}
