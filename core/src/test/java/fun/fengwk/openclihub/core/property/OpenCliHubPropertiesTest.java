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

    /** Newly created instances default to serial command execution unless configured otherwise. */
    @Test
    void shouldDefaultExecutionMaxConcurrencyToOne() {
        OpenCliHubProperties properties = new OpenCliHubProperties();

        assertThat(properties.getExecution().getDefaultMaxConcurrency()).isEqualTo(1);
    }

    /** Parallel execution start stagger defaults to 3000ms minimum and 5000ms maximum. */
    @Test
    void shouldDefaultExecutionParallelStartStaggerRangeTo3000And5000() {
        OpenCliHubProperties properties = new OpenCliHubProperties();

        assertThat(properties.getExecution().getParallelStartStaggerMinMillis()).isEqualTo(3000L);
        assertThat(properties.getExecution().getParallelStartStaggerMaxMillis()).isEqualTo(5000L);
    }

}
