package fun.fengwk.openclihub.core;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapperScan;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Core module auto-configuration.
 *
 * @author fengwk
 */
@BaseMapperScan
@ComponentScan
@Configuration
@EnableConfigurationProperties(OpenCliHubProperties.class)
public class CoreAutoConfiguration {

    /**
     * UTC wall clock shared by every audit / state / execution timestamp producer.
     * Tests may override this bean with a fixed clock for deterministic assertions.
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }
}
