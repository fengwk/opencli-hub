package fun.fengwk.openclihub.core.execution.executor;

/**
 * Builds the Hub-owned {@code OPENCLI_RUN_OWNER} value injected into OpenCLI processes.
 *
 * <p>The value is constructed only from Hub {@code instanceId} and {@code executionId}
 * and must never be taken from user argv.
 *
 * @author fengwk
 */
public final class OpenCliRunOwner {

    public static final String ENV_NAME = "OPENCLI_RUN_OWNER";
    private static final String PREFIX = "opencli-hub:";

    private OpenCliRunOwner() {
    }

    /**
     * @return {@code opencli-hub:<instanceId>:<executionId>}
     * @throws IllegalArgumentException when either id is blank
     */
    public static String of(String instanceId, String executionId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId is required for OPENCLI_RUN_OWNER");
        }
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId is required for OPENCLI_RUN_OWNER");
        }
        return PREFIX + instanceId + ":" + executionId;
    }

}
