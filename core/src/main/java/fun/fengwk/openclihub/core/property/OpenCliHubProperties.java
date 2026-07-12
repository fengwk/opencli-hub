package fun.fengwk.openclihub.core.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration owned by the Hub.
 *
 * @author fengwk
 */
@ConfigurationProperties(prefix = "opencli.hub")
@Data
public class OpenCliHubProperties {

    private String dataDir = "/data/opencli-hub";
    private OpenCli opencli = new OpenCli();
    private Browser browser = new Browser();
    private Vnc vnc = new Vnc();
    private Execution execution = new Execution();
    private Resource resource = new Resource();

    @Data
    public static class OpenCli {

        private String binary = "opencli";
        private String workdir = "/opt/opencli";
        private String extensionDir = "/opt/opencli/extension";

    }

    @Data
    public static class Browser {

        private String binary = "/usr/bin/google-chrome-stable";
        private int screenWidth = 1600;
        private int screenHeight = 900;
        private int screenDepth = 24;
        private long startupTimeoutMillis = 60000L;

    }

    @Data
    public static class Vnc {

        private long startupTimeoutMillis = 10000L;

    }

    @Data
    public static class Execution {

        private long defaultTimeoutMillis = 600000L;
        private long maxTimeoutMillis = 1800000L;
        private long processStopGraceMillis = 3000L;
        private int maxCaptureChars = 65535;
        private int defaultMaxPending = 5;

    }

    @Data
    public static class Resource {

        private String rootDir = "/data/opencli-hub/resources";
        private long maxFileSize = 104857600L;
        private long maxRequestSize = 524288000L;

    }

}
