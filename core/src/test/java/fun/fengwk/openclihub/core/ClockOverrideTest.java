package fun.fengwk.openclihub.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Verifies the Clock bean contract: the auto-configuration default is {@code Clock.systemUTC()}
 * and a test-declared Clock bean overrides it via {@code @ConditionalOnMissingBean} without
 * leaving a second injectable Clock in the context.
 *
 * @author fengwk
 */
@SpringBootTest(classes = CoreTestApplication.class)
class ClockOverrideTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-02T03:04:05Z");

    @Autowired
    private Clock clock;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void shouldUseTestDeclaredClockInsteadOfProductionDefault() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(clock.instant()).isEqualTo(FIXED_INSTANT);
        assertThat(LocalDateTime.now(clock))
            .isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
    }

    /** Exactly one Clock bean may exist so injection is unambiguous in tests and the app. */
    @Test
    void shouldExposeSingleClockBean() {
        assertThat(applicationContext.getBeansOfType(Clock.class)).hasSize(1);
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        public Clock clock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }
}
