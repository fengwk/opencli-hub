package fun.fengwk.openclihub.core.execution.service;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.execution.runtime.HubExecutionConcurrencyMode;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;

/**
 * Fail-safe classifier determining whether an execution command is parallel-safe or exclusive.
 *
 * <p>Classification contract:
 * <ul>
 *   <li>A {@code null} command, non-browser command, or unresolvable/null session fails safe to {@link HubExecutionConcurrencyMode#EXCLUSIVE}.</li>
 *   <li>For browser commands with {@link SiteSessionMode#PERSISTENT}, classification is {@link HubExecutionConcurrencyMode#EXCLUSIVE}.</li>
 *   <li>For browser commands with {@link SiteSessionMode#EPHEMERAL}, classification is {@link HubExecutionConcurrencyMode#PARALLEL_SAFE}, bounded by instance {@code maxConcurrency}.</li>
 * </ul>
 *
 * @author fengwk
 */
public final class HubExecutionConcurrencyClassifier {

    private HubExecutionConcurrencyClassifier() {
    }

    /**
     * Classifies a command execution into {@link HubExecutionConcurrencyMode#PARALLEL_SAFE}
     * or {@link HubExecutionConcurrencyMode#EXCLUSIVE}.
     *
     * @param command resolved command metadata; {@code null}, non-browser, or null session fails safe to exclusive
     * @return concurrency mode
     */
    public static HubExecutionConcurrencyMode classify(OpenCliCommand command) {
        if (command == null || !command.isBrowser() || command.getSiteSession() == null) {
            return HubExecutionConcurrencyMode.EXCLUSIVE;
        }
        if (command.getSiteSession() == SiteSessionMode.EPHEMERAL) {
            return HubExecutionConcurrencyMode.PARALLEL_SAFE;
        }
        return HubExecutionConcurrencyMode.EXCLUSIVE;
    }

}
