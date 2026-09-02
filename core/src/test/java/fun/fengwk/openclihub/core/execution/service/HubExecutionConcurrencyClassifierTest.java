package fun.fengwk.openclihub.core.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.execution.runtime.HubExecutionConcurrencyMode;
import fun.fengwk.openclihub.share.model.command.HubCommandAccess;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link HubExecutionConcurrencyClassifier}.
 * Validates the simplified fail-safe classification matrix:
 * <ul>
 *   <li>Browser commands with EPHEMERAL session are PARALLEL_SAFE regardless of access or window mode.</li>
 *   <li>Browser commands with PERSISTENT session are EXCLUSIVE.</li>
 *   <li>Null command, non-browser command, or null session fails safe to EXCLUSIVE.</li>
 * </ul>
 *
 * @author fengwk
 */
class HubExecutionConcurrencyClassifierTest {

    private static OpenCliCommand command(boolean browser,
                                          HubCommandAccess access,
                                          SiteSessionMode siteSession,
                                          String windowMode) {
        OpenCliCommand command = new OpenCliCommand();
        command.setBrowser(browser);
        command.setAccess(access);
        command.setSiteSession(siteSession);
        command.setDefaultWindowMode(windowMode);
        return command;
    }

    @ParameterizedTest
    @EnumSource(value = HubCommandAccess.class)
    void shouldClassifyEphemeralBrowserCommandAsParallelSafeRegardlessOfAccess(HubCommandAccess access) {
        OpenCliCommand cmd = command(true, access, SiteSessionMode.EPHEMERAL, null);
        assertThat(HubExecutionConcurrencyClassifier.classify(cmd))
            .isEqualTo(HubExecutionConcurrencyMode.PARALLEL_SAFE);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"background", "foreground", "new-tab", "custom"})
    void shouldClassifyEphemeralBrowserCommandAsParallelSafeRegardlessOfWindowMode(String windowMode) {
        OpenCliCommand cmd = command(true, HubCommandAccess.WRITE, SiteSessionMode.EPHEMERAL, windowMode);
        assertThat(HubExecutionConcurrencyClassifier.classify(cmd))
            .isEqualTo(HubExecutionConcurrencyMode.PARALLEL_SAFE);
    }

    @Test
    void shouldClassifyEphemeralBrowserCommandWithNullAccessAsParallelSafe() {
        OpenCliCommand cmd = command(true, null, SiteSessionMode.EPHEMERAL, null);
        assertThat(HubExecutionConcurrencyClassifier.classify(cmd))
            .isEqualTo(HubExecutionConcurrencyMode.PARALLEL_SAFE);
    }

    @ParameterizedTest
    @EnumSource(value = HubCommandAccess.class)
    void shouldClassifyPersistentBrowserCommandAsExclusive(HubCommandAccess access) {
        OpenCliCommand cmd = command(true, access, SiteSessionMode.PERSISTENT, null);
        assertThat(HubExecutionConcurrencyClassifier.classify(cmd))
            .isEqualTo(HubExecutionConcurrencyMode.EXCLUSIVE);
    }

    @Test
    void shouldClassifyNullCommandAsExclusive() {
        assertThat(HubExecutionConcurrencyClassifier.classify(null))
            .isEqualTo(HubExecutionConcurrencyMode.EXCLUSIVE);
    }

    @Test
    void shouldClassifyNonBrowserCommandAsExclusive() {
        OpenCliCommand cmd = command(false, HubCommandAccess.READ, SiteSessionMode.EPHEMERAL, null);
        assertThat(HubExecutionConcurrencyClassifier.classify(cmd))
            .isEqualTo(HubExecutionConcurrencyMode.EXCLUSIVE);
    }

    @Test
    void shouldClassifyNullSessionAsExclusive() {
        OpenCliCommand cmd = command(true, HubCommandAccess.READ, null, null);
        assertThat(HubExecutionConcurrencyClassifier.classify(cmd))
            .isEqualTo(HubExecutionConcurrencyMode.EXCLUSIVE);
    }

}
