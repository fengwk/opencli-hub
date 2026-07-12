package fun.fengwk.openclihub.core.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author fengwk
 */
@ConfigurationProperties(prefix = "opencli.hub")
@Data
public class OpenCliHubProperties {

    private Execution execution = new Execution();

    @Data
    public static class Execution {

        private String binary = "opencli";
        private String workdir = ".";
        private long defaultTimeoutMillis = 120000L;
        private long maxTimeoutMillis = 600000L;
        private int maxCaptureChars = 65535;

    }

}
