package fun.fengwk.openclihub.core.execution.executor;

import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import java.util.List;

/**
 * Executes a previously validated and Hub-managed OpenCLI argument list. The Hub is
 * responsible for producing the complete {@code hubManagedArgv} (including {@code --profile},
 * any managed output argument and {@code --format json}); this interface is a thin process
 * wrapper that prepends the configured OpenCLI binary and launches it via
 * {@code ProcessBuilder}.
 *
 * @author fengwk
 */
public interface OpenCliExecutor {

    /**
     * Run {@code hubManagedArgv} against {@code instance} within {@code timeoutMillis} and
     * return a captured process result. Stdout and stderr are read concurrently so neither
     * can block the other; process exit and both stream drains share one deadline, and both
     * streams are truncated to the configured capture cap.
     *
     * <p>{@code executionId} is the Hub execution id used only to build
     * {@code OPENCLI_RUN_OWNER=opencli-hub:&lt;instanceId&gt;:&lt;executionId&gt;} for the
     * spawned process. It must never come from user argv.
     */
    OpenCliExecutionResult execute(
        HubInstance instance, List<String> hubManagedArgv, long timeoutMillis, String executionId);

}
