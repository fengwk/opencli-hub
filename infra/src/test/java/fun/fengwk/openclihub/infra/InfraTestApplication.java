package fun.fengwk.openclihub.infra;

import fun.fengwk.openclihub.core.CoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author fengwk
 */
@SpringBootApplication(exclude = CoreAutoConfiguration.class)
public class InfraTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(InfraTestApplication.class, args);
    }

}
