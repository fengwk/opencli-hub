package fun.fengwk.openclihub.core.execution.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Verifies OPENCLI_RUN_OWNER is built only from Hub instance/execution ids with exact format.
 */
class OpenCliRunOwnerTest {

    @Test
    void shouldBuildExactOwnerFormat() {
        assertThat(OpenCliRunOwner.of("instance-a", "execution-b"))
            .isEqualTo("opencli-hub:instance-a:execution-b");
        assertThat(OpenCliRunOwner.ENV_NAME).isEqualTo("OPENCLI_RUN_OWNER");
    }

    @Test
    void shouldRejectBlankIds() {
        assertThatThrownBy(() -> OpenCliRunOwner.of(" ", "exec"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenCliRunOwner.of("inst", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

}
