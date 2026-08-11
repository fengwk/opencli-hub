package fun.fengwk.openclihub.core.opencli.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link HttpOpenCliDaemonClient} against a real loopback HTTP server. Verifies the
 * X-OpenCLI header is sent and the JSON is parsed back into {@link OpenCliDaemonStatus}.
 */
class HttpOpenCliDaemonClientTest {

    private FakeHttpServer server;
    private HttpOpenCliDaemonClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeHttpServer();
        server.start();
        OpenCliHubProperties props = new OpenCliHubProperties();
        URI base = URI.create("http://127.0.0.1:" + server.boundPort());
        client = new HttpOpenCliDaemonClient(props, base);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void shouldFetchStatusAndParseProfiles() {
        server.setStatusBody("{\"ok\":true,\"pid\":42,\"daemonVersion\":\"v1.8.6\","
            + "\"profiles\":[{\"contextId\":\"ctx-a\",\"extensionConnected\":true,"
            + "\"extensionVersion\":\"v1.0.22\",\"lastSeenAt\":1783872000123}],"
            + "\"capabilities\":[\"session-lease-v1\",\"session-recover-v1\"],"
            + "\"sessionLeases\":[{\"contextId\":\"ctx-a\",\"surface\":\"adapter\","
            + "\"session\":\"site:chatgpt\",\"runId\":\"run-1\","
            + "\"owner\":\"opencli-hub:i1:e1\",\"pendingCount\":1,\"state\":\"ACTIVE\"}]}");
        OpenCliDaemonStatus status = client.fetchStatus();
        assertThat(status.getPid()).isEqualTo(42L);
        assertThat(status.getDaemonVersion()).isEqualTo("v1.8.6");
        assertThat(status.connectedContextIds()).containsExactly("ctx-a");
        assertThat(status.getProfiles().get(0).getLastSeenAt()).isEqualTo(1783872000123L);
        assertThat(status.getCapabilities())
            .containsExactly("session-lease-v1", "session-recover-v1");
        assertThat(status.getSessionLeases()).hasSize(1);
        assertThat(status.getSessionLeases().get(0).getOwner()).isEqualTo("opencli-hub:i1:e1");
        assertThat(status.getSessionLeases().get(0).getState()).isEqualTo("ACTIVE");
        assertThat(status.getSessionLeases().get(0).getRunId()).isEqualTo("run-1");
        assertThat(server.lastHeaders()).contains("x-opencli: 1");
        assertThat(server.lastHeaders()).noneMatch(header -> header.startsWith("upgrade:"));
    }

    @Test
    void shouldPostSessionLeaseRecoverWithOpenCliHeaderAndJsonBody() {
        server.setRecoverBody(
            "{\"ok\":true,\"result\":\"RECOVERED\",\"runId\":\"run-1\","
                + "\"tabReset\":true,\"cancelledPending\":2}");
        OpenCliSessionLeaseRecoverRequest request = new OpenCliSessionLeaseRecoverRequest();
        request.setContextId("ctx-a");
        request.setSurface("adapter");
        request.setSession("site:chatgpt");
        request.setExpectedRunId("run-1");
        request.setMode(OpenCliSessionLeaseRecoverRequest.MODE_CANCEL_AND_RESET);
        request.setReason("hub_execution_timeout");

        OpenCliSessionLeaseRecoverResponse response = client.recoverSessionLease(request);

        assertThat(response.getOk()).isTrue();
        assertThat(response.getResult()).isEqualTo("RECOVERED");
        assertThat(response.getRunId()).isEqualTo("run-1");
        assertThat(response.getTabReset()).isTrue();
        assertThat(response.getCancelledPending()).isEqualTo(2);
        assertThat(server.lastMethodAndPath()).isEqualTo("POST /session-leases/recover");
        assertThat(server.lastHeaders()).contains("x-opencli: 1");
        assertThat(server.lastHeaders()).anyMatch(h -> h.startsWith("content-type: application/json"));
        assertThat(server.lastBody()).contains("\"expectedRunId\":\"run-1\"");
        assertThat(server.lastBody()).contains("\"mode\":\"CANCEL_AND_RESET\"");
        assertThat(server.lastBody()).contains("\"reason\":\"hub_execution_timeout\"");
    }

    @Test
    void shouldThrowWhenRecoverResponseIsNonTwoXx() {
        server.setRecoverStatusCode(503);
        server.setRecoverBody(
            "{\"ok\":false,\"result\":\"RESET_FAILED\",\"tabReset\":false,"
                + "\"cancelledPending\":0,\"errorCode\":\"session_recovery_failed\"}");
        OpenCliSessionLeaseRecoverRequest request = new OpenCliSessionLeaseRecoverRequest();
        request.setContextId("ctx");
        request.setSurface("adapter");
        request.setSession("site:x");
        request.setExpectedRunId("run");
        request.setMode(OpenCliSessionLeaseRecoverRequest.MODE_CANCEL_AND_RESET);
        request.setReason("hub_execution_timeout");

        assertThatThrownBy(() -> client.recoverSessionLease(request))
            .isInstanceOf(OpenCliDaemonException.class)
            .hasMessageContaining("HTTP 503");
    }

    @Test
    void shouldThrowWhenRecoverResponseBodyIsInvalid() {
        server.setRecoverBody("{\"ok\":true}");
        OpenCliSessionLeaseRecoverRequest request = new OpenCliSessionLeaseRecoverRequest();
        request.setContextId("ctx");
        request.setSurface("adapter");
        request.setSession("site:x");
        request.setExpectedRunId("run");
        request.setMode(OpenCliSessionLeaseRecoverRequest.MODE_CANCEL_AND_RESET);
        request.setReason("hub_execution_timeout");

        assertThatThrownBy(() -> client.recoverSessionLease(request))
            .isInstanceOf(OpenCliDaemonException.class)
            .hasMessageContaining("invalid response");
    }

    @Test
    void shouldThrowWhenRecoverResultIsUnknownEnum() {
        // Protocol only accepts RECOVERED|ALREADY_FREE|STILL_ACTIVE|OWNER_CHANGED|RESET_FAILED.
        server.setRecoverBody(
            "{\"ok\":true,\"result\":\"REPLAYED\",\"tabReset\":false,\"cancelledPending\":0}");
        OpenCliSessionLeaseRecoverRequest request = new OpenCliSessionLeaseRecoverRequest();
        request.setContextId("ctx");
        request.setSurface("adapter");
        request.setSession("site:x");
        request.setExpectedRunId("run");
        request.setMode(OpenCliSessionLeaseRecoverRequest.MODE_CANCEL_AND_RESET);
        request.setReason("hub_execution_timeout");

        assertThatThrownBy(() -> client.recoverSessionLease(request))
            .isInstanceOf(OpenCliDaemonException.class)
            .hasMessageContaining("invalid response");
    }

    @Test
    void shouldAcceptEachKnownRecoverResultEnum() {
        for (String result : OpenCliSessionLeaseRecoverResponse.VALID_RESULTS) {
            server.setRecoverBody(
                "{\"ok\":true,\"result\":\"" + result + "\",\"tabReset\":false,\"cancelledPending\":0}");
            OpenCliSessionLeaseRecoverRequest request = new OpenCliSessionLeaseRecoverRequest();
            request.setContextId("ctx");
            request.setSurface("adapter");
            request.setSession("site:x");
            request.setExpectedRunId("run");
            request.setMode(OpenCliSessionLeaseRecoverRequest.MODE_CANCEL_AND_RESET);
            request.setReason("hub_execution_timeout");

            OpenCliSessionLeaseRecoverResponse response = client.recoverSessionLease(request);
            assertThat(response.getResult()).isEqualTo(result);
            assertThat(response.getOk()).isTrue();
            assertThat(response.getTabReset()).isFalse();
            assertThat(response.getCancelledPending()).isZero();
        }
    }

    @Test
    void shouldPostBindActiveTabWithAdapterPersistentCommand() throws Exception {
        server.setCommandBody("{\"id\":\"unused\",\"ok\":true}");

        OpenCliDaemonCommandResponse response = client.bindActiveTab("ctx-bind", "site:chatgpt-agent");

        assertThat(response.getOk()).isTrue();
        assertThat(server.lastMethodAndPath()).isEqualTo("POST /command");
        assertThat(server.lastHeaders()).contains("x-opencli: 1");
        assertThat(server.lastHeaders()).anyMatch(h -> h.startsWith("content-type: application/json"));
        var request = new ObjectMapper().readTree(server.lastBody());
        assertThat(request.get("id").asText()).isNotBlank();
        assertThat(request.get("action").asText()).isEqualTo("bind");
        assertThat(request.get("session").asText()).isEqualTo("site:chatgpt-agent");
        assertThat(request.get("surface").asText()).isEqualTo("adapter");
        assertThat(request.get("siteSession").asText()).isEqualTo("persistent");
        assertThat(request.get("contextId").asText()).isEqualTo("ctx-bind");
    }

    /** The transport forwards the caller's session contract instead of hardcoding one. */
    @Test
    void shouldForwardCallerSuppliedSessionInBindCommand() throws Exception {
        server.setCommandBody("{\"id\":\"unused\",\"ok\":true}");

        client.bindActiveTab("ctx-bind", "site:custom-session");

        var request = new ObjectMapper().readTree(server.lastBody());
        assertThat(request.get("session").asText()).isEqualTo("site:custom-session");
        assertThat(request.get("surface").asText()).isEqualTo("adapter");
        assertThat(request.get("siteSession").asText()).isEqualTo("persistent");
    }

    @Test
    void shouldRejectBlankSessionInBindCommand() {
        assertThatThrownBy(() -> client.bindActiveTab("ctx-bind", " "))
            .isInstanceOf(OpenCliDaemonException.class)
            .hasMessageContaining("session is required");
    }

    @Test
    void shouldReturnCommandLevelBindFailureFromSuccessfulHttpResponse() {
        server.setCommandBody("{\"id\":\"unused\",\"ok\":false,"
            + "\"errorCode\":\"bound_tab_not_found\",\"error\":\"No tab\","
            + "\"errorHint\":\"Focus the tab\"}");

        OpenCliDaemonCommandResponse response = client.bindActiveTab("ctx-bind", "site:chatgpt-agent");

        assertThat(response.getOk()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("bound_tab_not_found");
        assertThat(response.getError()).isEqualTo("No tab");
        assertThat(response.getErrorHint()).isEqualTo("Focus the tab");
    }

    @Test
    void shouldThrowWhenBindCommandIsNonTwoXx() {
        server.setCommandStatusCode(503);

        assertThatThrownBy(() -> client.bindActiveTab("ctx-bind", "site:chatgpt-agent"))
            .isInstanceOf(OpenCliDaemonException.class)
            .hasMessageContaining("HTTP 503");
    }

    @Test
    void shouldThrowWhenBindCommandResponseBodyIsInvalid() {
        server.setCommandBody("{\"ok\":true}");

        assertThatThrownBy(() -> client.bindActiveTab("ctx-bind", "site:chatgpt-agent"))
            .isInstanceOf(OpenCliDaemonException.class)
            .hasMessageContaining("invalid bind response");
    }

    @Test
    void shouldThrowWhenStatusIsNonTwoXx() {
        server.setStatusCode(500);
        assertThatThrownBy(() -> client.fetchStatus())
            .isInstanceOf(OpenCliDaemonException.class);
    }

    @Test
    void shouldRestartDaemonAndWaitForAuthenticatedStatus() {
        server.setStatusCode(503);
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<List<String>> argv = new AtomicReference<>();
        HttpOpenCliDaemonClient.ProcessProcessRunner runner =
            new HttpOpenCliDaemonClient.ProcessProcessRunner() {
                @Override
                public Process start(String[] command, String workdir) {
                    argv.set(List.of(command));
                    starts.incrementAndGet();
                    server.setStatusBody("{\"ok\":true,\"pid\":84,\"daemonVersion\":\"1.8.6\"}");
                    server.setStatusCode(200);
                    return startProcess("true");
                }

                @Override
                public int waitFor(Process process, Duration timeout)
                    throws InterruptedException {
                    assertThat(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
                        .isTrue();
                    return process.exitValue();
                }
            };
        OpenCliHubProperties props = new OpenCliHubProperties();
        props.getOpencli().setBinary("/opt/opencli/bin/opencli");
        HttpOpenCliDaemonClient localClient = newClient(props, runner);

        localClient.ensureRunning();

        assertThat(starts).hasValue(1);
        assertThat(argv.get()).containsExactly("/opt/opencli/bin/opencli", "daemon", "restart");
        assertThat(server.lastHeaders()).contains("x-opencli: 1");
    }

    @Test
    void shouldRestartWhenStatusPidIsNotPositive() {
        // A syntactically valid status with pid zero must not be accepted as a live daemon.
        server.setStatusBody("{\"ok\":true,\"pid\":0,\"daemonVersion\":\"1.8.6\"}");
        AtomicInteger starts = new AtomicInteger();
        HttpOpenCliDaemonClient.ProcessProcessRunner runner =
            new HttpOpenCliDaemonClient.ProcessProcessRunner() {
                @Override
                public Process start(String[] command, String workdir) {
                    starts.incrementAndGet();
                    server.setStatusBody("{\"ok\":true,\"pid\":84,\"daemonVersion\":\"1.8.6\"}");
                    return startProcess("true");
                }

                @Override
                public int waitFor(Process process, Duration timeout)
                    throws InterruptedException {
                    assertThat(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
                    return process.exitValue();
                }
            };
        HttpOpenCliDaemonClient localClient = newClient(new OpenCliHubProperties(), runner);

        localClient.ensureRunning();

        assertThat(starts).hasValue(1);
    }

    @Test
    void shouldTerminateRestartCommandWhenCallerIsInterrupted() throws Exception {
        // Interrupting daemon bootstrap must stop the accepted restart process and preserve the flag.
        server.setStatusCode(503);
        AtomicReference<Process> processRef = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        HttpOpenCliDaemonClient.ProcessProcessRunner runner =
            new HttpOpenCliDaemonClient.ProcessProcessRunner() {
                @Override
                public Process start(String[] command, String workdir) {
                    Process process = startProcess("sleep", "30");
                    processRef.set(process);
                    started.countDown();
                    return process;
                }

                @Override
                public int waitFor(Process process, Duration timeout)
                    throws InterruptedException {
                    return process.waitFor();
                }
            };
        HttpOpenCliDaemonClient localClient = newClient(new OpenCliHubProperties(), runner);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                localClient.ensureRunning();
            } catch (Throwable ex) {
                failure.set(ex);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "daemon-restart-interrupt-test");
        caller.start();

        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(2000L);

            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(OpenCliDaemonException.class);
            assertThat(interrupted).isTrue();
            Process process = processRef.get();
            assertThat(process).isNotNull();
            assertThat(process.waitFor(2, TimeUnit.SECONDS)).isTrue();
            assertThat(process.isAlive()).isFalse();
        } finally {
            caller.interrupt();
            caller.join(2000L);
            Process process = processRef.get();
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void shouldThrowOnConnectionRefused() throws IOException {
        // Bind to a port and immediately close it; the client must then fail.
        try (java.net.ServerSocket s = new java.net.ServerSocket()) {
            s.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = s.getLocalPort();
            // s closes here.
            OpenCliHubProperties props = new OpenCliHubProperties();
            URI base = URI.create("http://127.0.0.1:" + port);
            HttpOpenCliDaemonClient localClient = new HttpOpenCliDaemonClient(
                props,
                base,
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                HttpOpenCliDaemonClient.ProcessProcessRunner.DEFAULT,
                Duration.ofMillis(200),
                Duration.ofMillis(200),
                Duration.ofMillis(20));
            assertThatThrownBy(localClient::fetchStatus)
                .isInstanceOf(OpenCliDaemonException.class);
        }
    }

    private HttpOpenCliDaemonClient newClient(
        OpenCliHubProperties properties,
        HttpOpenCliDaemonClient.ProcessProcessRunner runner) {
        return new HttpOpenCliDaemonClient(
            properties,
            URI.create("http://127.0.0.1:" + server.boundPort()),
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
            runner,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofMillis(10));
    }

    private static Process startProcess(String... command) {
        try {
            return new ProcessBuilder(command).start();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start test process", ex);
        }
    }

    /**
     * Tiny HTTP/1.1 loopback server that returns a configurable body for GET /status and
     * POST /session-leases/recover. Sufficient for {@link HttpOpenCliDaemonClient} contract tests.
     */
    private static class FakeHttpServer {

        private final java.net.ServerSocket serverSocket;
        private final java.util.concurrent.atomic.AtomicInteger statusCode = new AtomicInteger(200);
        private final java.util.concurrent.atomic.AtomicInteger recoverStatusCode =
            new java.util.concurrent.atomic.AtomicInteger(200);
        private final java.util.concurrent.atomic.AtomicInteger commandStatusCode =
            new java.util.concurrent.atomic.AtomicInteger(200);
        private volatile String statusBody = "{}";
        private volatile String recoverBody =
            "{\"ok\":true,\"result\":\"RECOVERED\",\"tabReset\":false,\"cancelledPending\":0}";
        private volatile String commandBody = "{\"id\":\"unused\",\"ok\":true}";
        private volatile List<String> lastHeaders = List.of();
        private volatile String lastMethodAndPath = "";
        private volatile String lastBody = "";
        private final java.util.concurrent.ExecutorService acceptor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "fake-daemon-acceptor");
                t.setDaemon(true);
                return t;
            });
        private final java.util.concurrent.ExecutorService handlers =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "fake-daemon-handler");
                t.setDaemon(true);
                return t;
            });
        private volatile boolean running;

        FakeHttpServer() throws IOException {
            this.serverSocket = new java.net.ServerSocket();
            this.serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        }

        int boundPort() {
            return serverSocket.getLocalPort();
        }

        void start() {
            running = true;
            acceptor.submit(this::acceptLoop);
        }

        void stop() {
            running = false;
            acceptor.shutdownNow();
            handlers.shutdownNow();
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }

        void setStatusBody(String body) {
            this.statusBody = body;
        }

        void setStatusCode(int code) {
            this.statusCode.set(code);
        }

        void setRecoverBody(String body) {
            this.recoverBody = body;
        }

        void setRecoverStatusCode(int code) {
            this.recoverStatusCode.set(code);
        }

        void setCommandBody(String body) {
            this.commandBody = body;
        }

        void setCommandStatusCode(int code) {
            this.commandStatusCode.set(code);
        }

        List<String> lastHeaders() {
            return lastHeaders;
        }

        String lastMethodAndPath() {
            return lastMethodAndPath;
        }

        String lastBody() {
            return lastBody;
        }

        private void acceptLoop() {
            while (running) {
                try {
                    var sock = serverSocket.accept();
                    handlers.submit(() -> handle(sock));
                } catch (IOException ex) {
                    return;
                }
            }
        }

        private void handle(java.net.Socket sock) {
            try (sock;
                 var in = new java.io.BufferedReader(
                     new java.io.InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));
                 var out = sock.getOutputStream()) {
                String requestLine = in.readLine();
                if (requestLine == null) {
                    return;
                }
                List<String> headers = new java.util.ArrayList<>();
                String line;
                int contentLength = 0;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    headers.add(line.toLowerCase(java.util.Locale.ROOT));
                    String lower = line.toLowerCase(java.util.Locale.ROOT);
                    if (lower.startsWith("content-length:")) {
                        contentLength = Integer.parseInt(lower.substring("content-length:".length()).trim());
                    }
                }
                lastHeaders = List.copyOf(headers);
                char[] bodyChars = new char[Math.max(0, contentLength)];
                int read = 0;
                while (read < bodyChars.length) {
                    int n = in.read(bodyChars, read, bodyChars.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                lastBody = new String(bodyChars, 0, read);
                String[] parts = requestLine.split(" ");
                lastMethodAndPath = parts.length >= 2 ? parts[0] + " " + parts[1] : requestLine;
                boolean isStatus = requestLine.startsWith("GET") && requestLine.contains("/status");
                boolean isRecover = requestLine.startsWith("POST")
                    && requestLine.contains("/session-leases/recover");
                boolean isCommand = requestLine.startsWith("POST")
                    && requestLine.contains("/command");
                if (isStatus) {
                    writeJson(out, statusCode.get(), statusBody);
                } else if (isRecover) {
                    writeJson(out, recoverStatusCode.get(), recoverBody);
                } else if (isCommand) {
                    writeJson(out, commandStatusCode.get(), withCommandId(commandBody, lastBody));
                } else {
                    writeJson(out, 404, "{\"ok\":false}");
                }
            } catch (IOException ignored) {
            }
        }

        private static String withCommandId(String responseBody, String requestBody) {
            String marker = Character.toString('"') + "unused" + '"';
            if (!responseBody.contains(marker)) {
                return responseBody;
            }
            String idKey = Character.toString('"') + "id" + '"' + ":" + '"';
            int start = requestBody.indexOf(idKey);
            if (start < 0) {
                return responseBody;
            }
            start += idKey.length();
            int end = requestBody.indexOf('"', start);
            if (end < 0) {
                return responseBody;
            }
            return responseBody.replace(marker,
                Character.toString('"') + requestBody.substring(start, end) + '"');
        }

        private static void writeJson(java.io.OutputStream out, int code, String body) throws IOException {
            String reason = code == 200 ? "OK" : "ERROR";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        }
    }

}
