package fun.fengwk.openclihub.core.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.core.execution.runtime.HubExecutionConcurrencyMode;
import fun.fengwk.openclihub.share.model.command.HubCommandAccess;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HubExecutionConcurrencyClassifier}.
 * Validates fail-safe command concurrency classification.
 */
class HubExecutionConcurrencyClassifierTest {

    /**
     * Browser read command with ephemeral session, background window and no output rule is PARALLEL_SAFE.
     */
    @Test
    void shouldClassifyEphemeralReadBackgroundCommandAsParallelSafe() {
        OpenCliCommand command = new OpenCliCommand();
        command.setBrowser(true);
        command.setAccess(HubCommandAccess.READ);
        command.setSiteSession(SiteSessionMode.EPHEMERAL);
        command.setDefaultWindowMode("background");

        HubExecutionConcurrencyMode mode = HubExecutionConcurrencyClassifier.classify(command, null);

        assertThat(mode).isEqualTo(HubExecutionConcurrencyMode.PARALLEL_SAFE);
    }

    /**
     * Null siteSession defaults to EPHEMERAL and null defaultWindowMode is allowed for PARALLEL_SAFE.
     */
    @Test
    void shouldTreatNullSessionAndNullWindowModeAsParallelSafeWhenOtherConditionsMet() {
        OpenCliCommand command = new OpenCliCommand();
        command.setBrowser(true);
        command.setAccess(HubCommandAccess.READ);
        command.setSiteSession(null);
        command.setDefaultWindowMode(null);

        HubExecutionConcurrencyMode mode = HubExecutionConcurrencyClassifier.classify(command, null);

        assertThat(mode).isEqualTo(HubExecutionConcurrencyMode.PARALLEL_SAFE);
    }

    /**
     * Null command must fail-safe to EXCLUSIVE.
     */
    @Test
    void shouldClassifyNullCommandAsExclusive() {
        HubExecutionConcurrencyMode mode = HubExecutionConcurrencyClassifier.classify(null, null);

        assertThat(mode).isEqualTo(HubExecutionConcurrencyMode.EXCLUSIVE);
    }

    /**
     * Non-browser command (browser=false) must be EXCLUSIVE.
     */
    @Test
    void shouldClassifyNonBrowserCommandAsExclusive() {
        OpenCliCommand command = new OpenCliCommand();
        command.setBrowser(false);
        command.setAccess(HubCommandAccess.READ);
        command.setSiteSession(SiteSessionMode.EPHEMERAL);

        HubExecutionConcurrencyMode mode = HubExecutionConcurrencyClassifier.classify(command, null);

        assertThat(mode).isEqualTo(HubExecutionConcurrencyMode.EXCLUSIVE);
    }

    /**
     * Persistent site session command must be EXCLUSIVE to protect browser session state.
     */
    @Test
    void shouldClassifyPersistentSessionAsExclusive() {
        OpenCliCommand command = new OpenCliCommand();
        command.setBrowser(true);
        command.setAccess(HubCommandAccess.READ);
        command.setSiteSession(SiteSessionMode.PERSISTENT);

        HubExecutionConcurrencyMode mode = HubExecutionConcurrencyClassifier.classify(command, null);

        assertThat(mode).isEqualTo(HubExecutionConcurrencyMode.EXCLUSIVE);
    }

    /**
     * Write access command must be EXCLUSIVE.
     */
    @Test
    void shouldClassifyWriteAccessAsExclusive() {
        OpenCliCommand command = new OpenCliCommand();
        command.setBrowser(true);
        command.setAccess(HubCommandAccess.WRITE);
        command.setSiteSession(SiteSessionMode.EPHEMERAL);

        HubExecutionConcurrencyMode mode = HubExecutionConcurrencyClassifier.classify(command, null);

        assertThat(mode).isEqualTo(HubExecutionConcurrencyMode.EXCLUSIVE);
    }

    /**
     * Null access is not READ and must fail-safe to EXCLUSIVE.
     */
    @Test
    void shouldClassifyNullAccessAsExclusive() {
        OpenCliCommand command = new OpenCliCommand();
        command.setBrowser(true);
        command.setAccess(null);
        command.setSiteSession(SiteSessionMode.EPHEMERAL);

        HubExecutionConcurrencyMode mode = HubExecutionConcurrencyClassifier.classify(command, null);

        assertThat(mode).isEqualTo(HubExecutionConcurrencyMode.EXCLUSIVE);
    }

    /**
     * Foreground window mode must be EXCLUSIVE to avoid multi-window focus disruption.
     */
    @Test
    void shouldClassifyForegroundWindowModeAsExclusive() {
        OpenCliCommand command = new OpenCliCommand();
        command.setBrowser(true);
        command.setAccess(HubCommandAccess.READ);
        command.setSiteSession(SiteSessionMode.EPHEMERAL);
        command.setDefaultWindowMode("foreground");

        HubExecutionConcurrencyMode mode = HubExecutionConcurrencyClassifier.classify(command, null);

        assertThat(mode).isEqualTo(HubExecutionConcurrencyMode.EXCLUSIVE);
    }

    /** Blank window mode is not the explicit background mode and therefore fails safe. */
    @Test
    void shouldClassifyBlankWindowModeAsExclusive() {
        OpenCliCommand command = new OpenCliCommand();
        command.setBrowser(true);
        command.setAccess(HubCommandAccess.READ);
        command.setSiteSession(SiteSessionMode.EPHEMERAL);
        command.setDefaultWindowMode(" ");

        HubExecutionConcurrencyMode mode = HubExecutionConcurrencyClassifier.classify(command, null);

        assertThat(mode).isEqualTo(HubExecutionConcurrencyMode.EXCLUSIVE);
    }

    /** Non-canonical metadata must not be normalized into a parallel-safe classification. */
    @Test
    void shouldClassifyUppercaseWindowModeAsExclusive() {
        OpenCliCommand command = new OpenCliCommand();
        command.setBrowser(true);
        command.setAccess(HubCommandAccess.READ);
        command.setSiteSession(SiteSessionMode.EPHEMERAL);
        command.setDefaultWindowMode("BACKGROUND");

        HubExecutionConcurrencyMode mode = HubExecutionConcurrencyClassifier.classify(command, null);

        assertThat(mode).isEqualTo(HubExecutionConcurrencyMode.EXCLUSIVE);
    }

    /**
     * Any command with an effective output rule must be EXCLUSIVE to safely capture output files.
     */
    @Test
    void shouldClassifyCommandWithOutputRuleAsExclusive() {
        OpenCliCommand command = new OpenCliCommand();
        command.setBrowser(true);
        command.setAccess(HubCommandAccess.READ);
        command.setSiteSession(SiteSessionMode.EPHEMERAL);
        command.setDefaultWindowMode("background");

        HubCommandOutputRule outputRule = new HubCommandOutputRule();
        outputRule.setArgumentName("output");
        outputRule.setTargetType(HubCommandOutputTargetType.FILE);

        HubExecutionConcurrencyMode mode = HubExecutionConcurrencyClassifier.classify(command, outputRule);

        assertThat(mode).isEqualTo(HubExecutionConcurrencyMode.EXCLUSIVE);
    }

}
