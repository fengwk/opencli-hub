package fun.fengwk.openclihub.core.opencli.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
            + "\"extensionVersion\":\"v1.0.22\",\"lastSeenAt\":1783872000123}]}");
        OpenCliDaemonStatus status = client.fetchStatus();
        assertThat(status.getPid()).isEqualTo(42L);
        assertThat(status.getDaemonVersion()).isEqualTo("v1.8.6");
        assertThat(status.connectedContextIds()).containsExactly("ctx-a");
        assertThat(status.getProfiles().get(0).getLastSeenAt()).isEqualTo(1783872000123L);
        assertThat(server.lastHeaders()).contains("x-opencli: 1");
        assertThat(server.lastHeaders()).noneMatch(header -> header.startsWith("upgrade:"));
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
        java.util.concurrent.atomic.AtomicReference<List<String>> argv =
            new java.util.concurrent.atomic.AtomicReference<>();
        HttpOpenCliDaemonClient.ProcessProcessRunner runner =
            new HttpOpenCliDaemonClient.ProcessProcessRunner() {
                @Override
                public Process start(String[] command, String workdir) {
                    argv.set(List.of(command));
                    starts.incrementAndGet();
                    server.setStatusBody("{\"ok\":true,\"pid\":84,\"daemonVersion\":\"1.8.6\"}");
                    server.setStatusCode(200);
                    try {
                        return new ProcessBuilder("true").start();
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }

                @Override
                public int waitFor(Process process, java.time.Duration timeout)
                    throws InterruptedException {
                    assertThat(process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isTrue();
                    return process.exitValue();
                }
            };
        OpenCliHubProperties props = new OpenCliHubProperties();
        props.getOpencli().setBinary("/opt/opencli/bin/opencli");
        HttpOpenCliDaemonClient localClient = new HttpOpenCliDaemonClient(
            props,
            URI.create("http://127.0.0.1:" + server.boundPort()),
            new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
            java.net.http.HttpClient.newHttpClient(),
            runner,
            java.time.Duration.ofSeconds(1),
            java.time.Duration.ofSeconds(1),
            java.time.Duration.ofMillis(10));

        localClient.ensureRunning();

        assertThat(starts).hasValue(1);
        assertThat(argv.get()).containsExactly("/opt/opencli/bin/opencli", "daemon", "restart");
        assertThat(server.lastHeaders()).contains("x-opencli: 1");
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
                new com.fasterxml.jackson.databind.ObjectMapper(),
                java.net.http.HttpClient.newHttpClient(),
                HttpOpenCliDaemonClient.ProcessProcessRunner.DEFAULT,
                java.time.Duration.ofMillis(200),
                java.time.Duration.ofMillis(200),
                java.time.Duration.ofMillis(20));
            assertThatThrownBy(localClient::fetchStatus)
                .isInstanceOf(OpenCliDaemonException.class);
        }
    }

    /**
     * Tiny HTTP/1.1 loopback server that returns a configurable body for GET /status and
     * echoes POST /shutdown. Sufficient for {@link HttpOpenCliDaemonClient} contract tests.
     */
    private static class FakeHttpServer {

        private final java.net.ServerSocket serverSocket;
        private final java.util.concurrent.atomic.AtomicInteger statusCode = new AtomicInteger(200);
        private volatile String statusBody = "{}";
        private volatile List<String> lastHeaders = List.of();
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

        List<String> lastHeaders() {
            return lastHeaders;
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
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    headers.add(line.toLowerCase(java.util.Locale.ROOT));
                }
                lastHeaders = List.copyOf(headers);
                boolean isStatus = requestLine.startsWith("GET") && requestLine.contains("/status");
                if (isStatus) {
                    int code = statusCode.get();
                    String reason = code == 200 ? "OK" : "ERROR";
                    out.write(("HTTP/1.1 " + code + " " + reason + "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + statusBody.length() + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(statusBody.getBytes(StandardCharsets.UTF_8));
                } else {
                    String body = "{\"ok\":false}";
                    out.write(("HTTP/1.1 404 Not Found\r\n"
                        + "Content-Length: " + body.length() + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
            } catch (IOException ignored) {
            }
        }
    }

}
