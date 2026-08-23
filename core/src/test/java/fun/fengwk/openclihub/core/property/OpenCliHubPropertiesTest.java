package fun.fengwk.openclihub.core.property;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenCliHubPropertiesTest {

    /** An unset Origin policy must remain empty so Spring retains its same-origin default. */
    @Test
    void shouldDefaultVncAllowedOriginsToEmpty() {
        OpenCliHubProperties properties = new OpenCliHubProperties();

        assertThat(properties.getVnc().getAllowedOrigins()).isEmpty();
    }

    /** Long JSON command results need enough headroom to remain parseable by the Hub. */
    @Test
    void shouldDefaultExecutionCaptureToOneMiCharacters() {
        OpenCliHubProperties properties = new OpenCliHubProperties();

        assertThat(properties.getExecution().getMaxCaptureChars()).isEqualTo(1_048_576);
    }

}
