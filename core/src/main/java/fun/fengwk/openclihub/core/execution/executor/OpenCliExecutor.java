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
     * can block the other; both are truncated to the configured capture cap.
     */
    OpenCliExecutionResult execute(HubInstance instance, List<String> hubManagedArgv, long timeoutMillis);

}
