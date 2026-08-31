package fun.fengwk.openclihub.core.execution.service;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.core.execution.runtime.HubExecutionConcurrencyMode;
import fun.fengwk.openclihub.share.model.command.HubCommandAccess;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;

/**
 * Fail-safe classifier determining whether an execution command is parallel-safe or exclusive.
 *
 * @author fengwk
 */
public final class HubExecutionConcurrencyClassifier {

    private static final String BACKGROUND_WINDOW_MODE = "background";

    private HubExecutionConcurrencyClassifier() {
    }

    /**
     * Classifies a command execution into {@link HubExecutionConcurrencyMode#PARALLEL_SAFE}
     * or {@link HubExecutionConcurrencyMode#EXCLUSIVE}.
     *
     * <p>Only commands strictly meeting all of the following conditions are {@code PARALLEL_SAFE}:
     * <ul>
     *   <li>command != null and command.browser == true</li>
     *   <li>siteSession (null treated as EPHEMERAL) == EPHEMERAL</li>
     *   <li>access == READ (null is not READ)</li>
     *   <li>defaultWindowMode is null or exactly "background"</li>
     *   <li>effective outputRule == null</li>
     * </ul>
     * Everything else is fail-safe classified as {@code EXCLUSIVE}.
     */
    public static HubExecutionConcurrencyMode classify(
        OpenCliCommand command,
        HubCommandOutputRule effectiveOutputRule) {
        if (command == null) {
            return HubExecutionConcurrencyMode.EXCLUSIVE;
        }
        if (!command.isBrowser()) {
            return HubExecutionConcurrencyMode.EXCLUSIVE;
        }
        SiteSessionMode sessionMode = command.getSiteSession() == null
            ? SiteSessionMode.EPHEMERAL
            : command.getSiteSession();
        if (sessionMode != SiteSessionMode.EPHEMERAL) {
            return HubExecutionConcurrencyMode.EXCLUSIVE;
        }
        if (command.getAccess() != HubCommandAccess.READ) {
            return HubExecutionConcurrencyMode.EXCLUSIVE;
        }
        String windowMode = command.getDefaultWindowMode();
        if (windowMode != null && !BACKGROUND_WINDOW_MODE.equals(windowMode)) {
            return HubExecutionConcurrencyMode.EXCLUSIVE;
        }
        if (effectiveOutputRule != null) {
            return HubExecutionConcurrencyMode.EXCLUSIVE;
        }
        return HubExecutionConcurrencyMode.PARALLEL_SAFE;
    }

}
