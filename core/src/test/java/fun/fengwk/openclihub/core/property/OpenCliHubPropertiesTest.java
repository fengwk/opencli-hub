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

}
