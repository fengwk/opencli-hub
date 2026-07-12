package fun.fengwk.openclihub.core.execution.executor;

import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import java.util.List;

/**
 * Executes a previously validated and normalized OpenCLI argument list.
 *
 * @author fengwk
 */
public interface OpenCliExecutor {

    OpenCliExecutionResult execute(HubInstance instance, List<String> normalizedArgv, long timeoutMillis);

}
