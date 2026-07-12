package fun.fengwk.openclihub.core;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * @author fengwk
 */
@ComponentScan
@Configuration
@EnableConfigurationProperties(OpenCliHubProperties.class)
public class CoreAutoConfiguration {
}
