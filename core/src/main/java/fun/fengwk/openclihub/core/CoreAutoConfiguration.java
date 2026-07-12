package fun.fengwk.openclihub.core;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapperScan;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
}
