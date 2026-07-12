package fun.fengwk.openclihub.core.executor;

import fun.fengwk.openclihub.core.model.HubInstance;
import fun.fengwk.openclihub.core.model.OpenCliExecutionResult;
import java.util.List;

/**
 * @author fengwk
 */
public interface OpenCliExecutor {

    OpenCliExecutionResult execute(HubInstance instance, List<String> argv, long timeoutMillis);

}
