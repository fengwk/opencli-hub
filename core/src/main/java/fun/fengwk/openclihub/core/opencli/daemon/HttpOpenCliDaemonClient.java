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

    /** Bounded timeout for CAS recovery so a stalled daemon cannot block Hub cleanup. */
    static final Duration DEFAULT_RECOVERY_TIMEOUT = Duration.ofSeconds(7);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenCliHubProperties properties;
    private final Duration requestTimeout;
    private final Duration recoveryTimeout;
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
            newHttp11Client(),
            ProcessProcessRunner.DEFAULT,
            Duration.ofSeconds(2),
            DEFAULT_RECOVERY_TIMEOUT,
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
            DEFAULT_RECOVERY_TIMEOUT,
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
        this(properties,
            baseUri,
            objectMapper,
            httpClient,
            processRunner,
            requestTimeout,
            DEFAULT_RECOVERY_TIMEOUT,
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
        Duration recoveryTimeout,
        Duration bootstrapTimeout,
        Duration bootstrapPoll) {
        this.properties = properties;
        this.baseUri = baseUri;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.processRunner = processRunner;
        this.requestTimeout = requestTimeout;
        this.recoveryTimeout = recoveryTimeout;
        this.bootstrapTimeout = bootstrapTimeout;
        this.bootstrapPoll = bootstrapPoll;
    }

    private static int defaultPort() {
        // 19825 is the OpenCLI daemon's documented default.
        return 19825;
    }

    private static HttpClient newHttp11Client() {
        // OpenCLI treats JDK HttpClient's clear-text HTTP/2 Upgrade header as a WebSocket
        // upgrade and returns HTTP 400. Force plain HTTP/1.1 for the loopback REST endpoint.
        return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    @Override
    public OpenCliDaemonStatus fetchStatus() {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/status"))
            .timeout(requestTimeout)
            .header(X_OPEN_CLI_HEADER, X_OPEN_CLI_VALUE)
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> response = send(request, "/status");
        if (response.statusCode() / 100 != 2) {
            throw new OpenCliDaemonException(
                "daemon /status returned HTTP " + response.statusCode());
        }
        try {
            OpenCliDaemonStatus status = objectMapper.readValue(response.body(), OpenCliDaemonStatus.class);
            if (status == null) {
                throw new OpenCliDaemonException("daemon /status returned empty body");
            }
            if (status.getProfiles() == null) {
                status.setProfiles(java.util.List.of());
            }
            if (status.getCapabilities() == null) {
                status.setCapabilities(java.util.List.of());
            }
            if (status.getSessionLeases() == null) {
                status.setSessionLeases(java.util.List.of());
            }
            return status;
        } catch (IOException ex) {
            throw new OpenCliDaemonException("failed to parse daemon /status JSON", ex);
        }
    }

    @Override
    public OpenCliSessionLeaseRecoverResponse recoverSessionLease(
        OpenCliSessionLeaseRecoverRequest request) {
        if (request == null) {
            throw new OpenCliDaemonException("session lease recovery request is required");
        }
        String body;
        try {
            body = objectMapper.writeValueAsString(request);
        } catch (IOException ex) {
            throw new OpenCliDaemonException("failed to serialize session lease recovery request", ex);
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(baseUri.resolve("/session-leases/recover"))
            .timeout(recoveryTimeout)
            .header(X_OPEN_CLI_HEADER, X_OPEN_CLI_VALUE)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = send(httpRequest, "/session-leases/recover");
        if (response.statusCode() / 100 != 2) {
            throw new OpenCliDaemonException(
                "daemon /session-leases/recover returned HTTP " + response.statusCode());
        }
        try {
            OpenCliSessionLeaseRecoverResponse parsed = objectMapper.readValue(
                response.body(), OpenCliSessionLeaseRecoverResponse.class);
            if (!isValidRecoverResponse(parsed)) {
                throw new OpenCliDaemonException(
                    "daemon /session-leases/recover returned an invalid response body");
            }
            return parsed;
        } catch (IOException ex) {
            throw new OpenCliDaemonException(
                "failed to parse daemon /session-leases/recover JSON", ex);
        }
    }

    private HttpResponse<String> send(HttpRequest request, String endpoint) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new OpenCliDaemonException(
                "daemon " + endpoint + " connection failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OpenCliDaemonException("interrupted while calling " + endpoint, ex);
        }
    }

    private static boolean isValidRecoverResponse(OpenCliSessionLeaseRecoverResponse response) {
        return response != null
            && response.getOk() != null
            && OpenCliSessionLeaseRecoverResponse.isKnownResult(response.getResult())
            && response.getTabReset() != null
            && response.getCancelledPending() != null;
    }

    @Override
    public void ensureRunning() {
        OpenCliDaemonStatus initial = fetchStatusSafe();
        if (hasValidPid(initial)) {
            return;
        }
        triggerRestart();
        long deadline = System.nanoTime() + bootstrapTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            OpenCliDaemonStatus status = fetchStatusSafe();
            if (hasValidPid(status)) {
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
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new OpenCliDaemonException("interrupted while waiting for daemon restart", ex);
        }
    }

    private OpenCliDaemonStatus fetchStatusSafe() {
        try {
            return fetchStatus();
        } catch (OpenCliDaemonException ex) {
            if (Thread.currentThread().isInterrupted()) {
                throw ex;
            }
            return null;
        }
    }

    private static boolean hasValidPid(OpenCliDaemonStatus status) {
        return status != null && status.getPid() != null && status.getPid() > 0L;
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
