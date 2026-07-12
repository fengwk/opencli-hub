package fun.fengwk.openclihub.core.command.validator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Argument type coercion and parsing coverage.
 *
 * @author fengwk
 */
class OpenCliArgumentTypeTest {

    @Test
    void shouldRecognizeAllTypeSpellings() {
        assertThat(OpenCliArgumentType.of("int")).isEqualTo(OpenCliArgumentType.INT);
        assertThat(OpenCliArgumentType.of("integer")).isEqualTo(OpenCliArgumentType.INT);
        assertThat(OpenCliArgumentType.of("float")).isEqualTo(OpenCliArgumentType.FLOAT);
        assertThat(OpenCliArgumentType.of("number")).isEqualTo(OpenCliArgumentType.FLOAT);
        assertThat(OpenCliArgumentType.of("bool")).isEqualTo(OpenCliArgumentType.BOOLEAN);
        assertThat(OpenCliArgumentType.of("boolean")).isEqualTo(OpenCliArgumentType.BOOLEAN);
        assertThat(OpenCliArgumentType.of("string")).isEqualTo(OpenCliArgumentType.STRING);
        assertThat(OpenCliArgumentType.of("str")).isEqualTo(OpenCliArgumentType.STRING);
        assertThat(OpenCliArgumentType.of("unknown")).isEqualTo(OpenCliArgumentType.STRING);
        assertThat(OpenCliArgumentType.of(null)).isEqualTo(OpenCliArgumentType.STRING);
    }

    @Test
    void shouldAcceptValidTypeValues() {
        assertThat(OpenCliArgumentType.INT.accepts("123")).isTrue();
        assertThat(OpenCliArgumentType.INT.accepts("-5")).isTrue();
        assertThat(OpenCliArgumentType.INT.accepts("abc")).isFalse();
        assertThat(OpenCliArgumentType.FLOAT.accepts("1.25")).isTrue();
        assertThat(OpenCliArgumentType.FLOAT.accepts("abc")).isFalse();
        assertThat(OpenCliArgumentType.BOOLEAN.accepts("true")).isTrue();
        assertThat(OpenCliArgumentType.BOOLEAN.accepts("false")).isTrue();
        assertThat(OpenCliArgumentType.BOOLEAN.accepts("TRUE")).isTrue();
        assertThat(OpenCliArgumentType.BOOLEAN.accepts("yes")).isFalse();
        assertThat(OpenCliArgumentType.STRING.accepts("anything")).isTrue();
        assertThat(OpenCliArgumentType.STRING.accepts(null)).isFalse();
    }

}