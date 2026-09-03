package fun.fengwk.openclihub.core.property;

import java.util.ArrayList;
import java.util.List;
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
    private Runtime runtime = new Runtime();
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
        private List<String> allowedOrigins = new ArrayList<>();

    }

    /**
     * Runtime / process launcher tunables. Named separately from Browser/Vnc to keep their
     * concerns independent and to leave room for future tweaks without churning the public
     * properties tree.
     */
    @Data
    public static class Runtime {

        /**
         * Enables asynchronous startup recovery of persisted instances.
         */
        private boolean startupRecoveryEnabled = true;

        /**
         * Bounded wait for API start/create/restart: covers both the queue behind another
         * in-flight start and the startup recovery barrier. On timeout the caller receives
         * INSTANCE_START_RECOVERY_IN_PROGRESS or INSTANCE_BUSY.
         */
        private long startCoordinationTimeoutMillis = 60000L;

        /**
         * Starting X display number for allocation. The scanner skips already in-use numbers.
         */
        private int displayBase = 99;

        /**
         * Starting VNC TCP port for allocation. The allocator walks the port space until a
         * free one on 127.0.0.1 is found.
         */
        private int vncPortBase = 5900;

        /**
         * Hard upper bound for the VNC port scan to keep scanning bounded.
         */
        private int vncPortMax = 5999;

        /**
         * Grace period for {@code destroy()} before falling back to descendant kill and
         * {@code destroyForcibly()}.
         */
        private long processStopGraceMillis = 3000L;

        /**
         * Polling interval for readiness checks (Xvfb socket, x11vnc TCP, daemon extension).
         */
        private long readinessPollMillis = 50L;

    }

    @Data
    public static class Execution {

        private long defaultTimeoutMillis = 600000L;
        private long maxTimeoutMillis = 1800000L;
        private long processStopGraceMillis = 3000L;
        private int maxCaptureChars = 1_048_576;
        private int defaultMaxPending = 5;
        private int defaultMaxConcurrency = 1;
        private long parallelStartStaggerMinMillis = 3000L;
        private long parallelStartStaggerMaxMillis = 5000L;

    }

    @Data
    public static class Resource {

        private String rootDir = "/data/opencli-hub/resources";
        private long maxFileSize = 104857600L;
        private long maxRequestSize = 524288000L;

    }

}
