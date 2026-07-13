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
