package fun.fengwk.openclihub.core.opencli.daemon;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * JDK {@link HttpClient} backed implementation that talks to {@code http://127.0.0.1:19825}.
 *
 * <p>The HTTP body is parsed as JSON via Jackson. No additional HTTP dependency is needed for
 * short intra-container requests.
 *
 * @author fengwk
 */
@Slf4j
public class HttpOpenCliDaemonClient implements OpenCliDaemonClient {

    /** Required by the daemon to distinguish clients from browsers. */
    static final String X_OPEN_CLI_HEADER = "X-OpenCLI";
    static final String X_OPEN_CLI_VALUE = "1";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenCliHubProperties properties;
    private final Duration requestTimeout;
    private final Duration bootstrapTimeout;
    private final Duration bootstrapPoll;
    private final URI baseUri;
    private final ProcessProcessRunner processRunner;

    public HttpOpenCliDaemonClient(OpenCliHubProperties properties) {
        this(properties, URI.create("http://127.0.0.1:" + defaultPort()));
    }

    /** Test wiring: lets callers point the client at a non-default loopback port. */
    public HttpOpenCliDaemonClient(OpenCliHubProperties properties, URI baseUri) {
        this(properties,
            baseUri,
            new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
            HttpClient.newHttpClient(),
            ProcessProcessRunner.DEFAULT,
            Duration.ofSeconds(2),
            Duration.ofSeconds(30),
            Duration.ofMillis(100));
    }

    public HttpOpenCliDaemonClient(
        OpenCliHubProperties properties,
        ObjectMapper objectMapper,
        HttpClient httpClient,
        ProcessProcessRunner processRunner,
        Duration requestTimeout,
        Duration bootstrapTimeout,
        Duration bootstrapPoll) {
        this(properties,
            URI.create("http://127.0.0.1:" + defaultPort()),
            objectMapper,
            httpClient,
            processRunner,
            requestTimeout,
            bootstrapTimeout,
            bootstrapPoll);
    }

    public HttpOpenCliDaemonClient(
        OpenCliHubProperties properties,
        URI baseUri,
        ObjectMapper objectMapper,
        HttpClient httpClient,
        ProcessProcessRunner processRunner,
        Duration requestTimeout,
        Duration bootstrapTimeout,
        Duration bootstrapPoll) {
        this.properties = properties;
        this.baseUri = baseUri;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.processRunner = processRunner;
        this.requestTimeout = requestTimeout;
        this.bootstrapTimeout = bootstrapTimeout;
        this.bootstrapPoll = bootstrapPoll;
    }

    private static int defaultPort() {
        // 19825 is the OpenCLI daemon's documented default.
        return 19825;
    }

    @Override
    public OpenCliDaemonStatus fetchStatus() {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/status"))
            .timeout(requestTimeout)
            .header(X_OPEN_CLI_HEADER, X_OPEN_CLI_VALUE)
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new OpenCliDaemonException("daemon /status connection failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OpenCliDaemonException("interrupted while reading /status", ex);
        }
        if (response.statusCode() / 100 != 2) {
            throw new OpenCliDaemonException(
                "daemon /status returned HTTP " + response.statusCode());
        }
        try {
            OpenCliDaemonStatus status = objectMapper.readValue(response.body(), OpenCliDaemonStatus.class);
            if (status != null && status.getProfiles() == null) {
                status.setProfiles(java.util.List.of());
            }
            return status;
        } catch (IOException ex) {
            throw new OpenCliDaemonException("failed to parse daemon /status JSON", ex);
        }
    }

    @Override
    public void ensureRunning() {
        OpenCliDaemonStatus initial = fetchStatusSafe();
        if (initial != null && initial.getPid() != null) {
            return;
        }
        triggerRestart();
        long deadline = System.nanoTime() + bootstrapTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            OpenCliDaemonStatus status = fetchStatusSafe();
            if (status != null && status.getPid() != null) {
                log.info("OpenCLI daemon is up: pid={}, version={}",
                    status.getPid(), status.getDaemonVersion());
                return;
            }
            sleepForBootstrap(bootstrapPoll.toMillis());
        }
        throw new OpenCliDaemonException(
            "OpenCLI daemon did not come up within " + bootstrapTimeout.toMillis() + " ms");
    }

    private void triggerRestart() {
        String binary = properties.getOpencli().getBinary();
        String workdir = properties.getOpencli().getWorkdir();
        Process p = processRunner.start(new String[] { binary, "daemon", "restart" }, workdir);
        try {
            int code = processRunner.waitFor(p, Duration.ofSeconds(15));
            if (code != 0) {
                throw new OpenCliDaemonException(
                    "`opencli daemon restart` exited with non-zero code " + code);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OpenCliDaemonException("interrupted while waiting for daemon restart", ex);
        }
    }

    private OpenCliDaemonStatus fetchStatusSafe() {
        try {
            return fetchStatus();
        } catch (OpenCliDaemonException ex) {
            return null;
        }
    }

    private static void sleepForBootstrap(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OpenCliDaemonException("interrupted while waiting for daemon startup", ex);
        }
    }

    /**
     * Indirection so tests can replace the OS process invocation without depending on
     * {@code java.lang.ProcessBuilder} working in the JVM used by the unit test runner.
     */
    public interface ProcessProcessRunner {

        Process start(String[] argv, String workdir);

        int waitFor(Process process, Duration timeout) throws InterruptedException;

        ProcessProcessRunner DEFAULT = new ProcessProcessRunner() {
            @Override
            public Process start(String[] argv, String workdir) {
                try {
                    ProcessBuilder builder = new ProcessBuilder(argv);
                    if (workdir != null && !workdir.isBlank()) {
                        builder.directory(java.nio.file.Path.of(workdir).toFile());
                    }
                    builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                    return builder.start();
                } catch (IOException ex) {
                    throw new OpenCliDaemonException(
                        "failed to execute `" + String.join(" ", argv) + "`: " + ex.getMessage(), ex);
                }
            }

            @Override
            public int waitFor(Process process, Duration timeout) throws InterruptedException {
                if (process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return process.exitValue();
                }
                process.destroyForcibly();
                throw new OpenCliDaemonException(
                    "daemon restart command did not exit within " + timeout.toMillis() + " ms");
            }
        };

    }

}
