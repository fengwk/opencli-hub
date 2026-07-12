package fun.fengwk.openclihub.core.command.validator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Coverage for the Hub-exclusive argument list. Every entry listed in the design document
 * and every OpenCLI root option consumed globally must be flagged as reserved.
 *
 * @author fengwk
 */
class OpenCliReservedArgumentsTest {

    @Test
    void shouldRecognizeAllLongReservedNames() {
        for (String name : new String[] {
            "--profile", "--format", "--site-session", "--keep-tab",
            "--window", "--trace", "--verbose", "--help", "--version",
        }) {
            assertThat(OpenCliReservedArguments.isReserved(name))
                .as("long name should be reserved: " + name)
                .isTrue();
        }
    }

    @Test
    void shouldRecognizeShortReservedNames() {
        for (String name : new String[] { "-f", "-v", "-h", "-V" }) {
            assertThat(OpenCliReservedArguments.isReserved(name))
                .as("short name should be reserved: " + name)
                .isTrue();
        }
    }

    @Test
    void shouldRecognizeInlineValueReservedNames() {
        assertThat(OpenCliReservedArguments.isReserved("--format=json")).isTrue();
        assertThat(OpenCliReservedArguments.isReserved("--profile=foo")).isTrue();
        assertThat(OpenCliReservedArguments.isReserved("-f=json")).isTrue();
    }

    @Test
    void shouldNotFlagNonReservedNames() {
        assertThat(OpenCliReservedArguments.isReserved("--limit")).isFalse();
        assertThat(OpenCliReservedArguments.isReserved("--prompt")).isFalse();
        assertThat(OpenCliReservedArguments.isReserved("bilibili")).isFalse();
        assertThat(OpenCliReservedArguments.isReserved(null)).isFalse();
    }

}
