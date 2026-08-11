package fun.fengwk.openclihub.web.vnc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fun.fengwk.openclihub.core.instance.runtime.HubInstanceLifecycleService;
import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntimeSnapshot;
import fun.fengwk.openclihub.core.instance.service.HubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Exercises the VNC bridge against a real loopback TCP peer rather than a socket mock.
 */
class HubVncWebSocketHandlerTest {

    private HubVncWebSocketHandler handler;

    @AfterEach
    void tearDown() {
        if (handler != null) {
            handler.shutdown();
        }
    }

    /** Binary frames must pass unchanged in both directions between WebSocket and loopback TCP. */
    @Test
    void shouldForwardBinaryFramesBidirectionally() throws Exception {
        try (FakeVncServer server = new FakeVncServer()) {
            handler = newHandler(server.getPort());
            SessionFixture fixture = newSession("11");

            handler.afterConnectionEstablished(fixture.session);
            server.awaitConnected();

            byte[] clientBytes = {1, 2, 3, 4};
            handler.handleBinaryMessage(fixture.session, new BinaryMessage(clientBytes));
            assertArrayEquals(clientBytes, server.read(clientBytes.length));

            byte[] backendBytes = {9, 8, 7};
            server.write(backendBytes);
            assertArrayEquals(backendBytes, fixture.awaitBinary());
        }
    }

    /** A non-running or runtime-less instance must be rejected before a TCP bridge is created. */
    @Test
    void shouldRejectUnavailableRuntimeEndpoint() throws Exception {
        HubInstanceService instanceService = mock(HubInstanceService.class);
        HubInstanceLifecycleService lifecycleService = mock(HubInstanceLifecycleService.class);
        HubInstance instance = runningInstance();
        when(instanceService.get("11")).thenReturn(instance);
        when(lifecycleService.getSnapshot("11")).thenReturn(HubInstanceRuntimeSnapshot.absent());
        handler = new HubVncWebSocketHandler(instanceService, lifecycleService);
        SessionFixture fixture = newSession("11");

        handler.afterConnectionEstablished(fixture.session);

        assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), fixture.awaitClose().getCode());
        verify(lifecycleService).getSnapshot("11");
    }

    /** Text is not RFB data and must be rejected instead of being converted or forwarded. */
    @Test
    void shouldRejectTextFramesAndCloseTcpPeer() throws Exception {
        try (FakeVncServer server = new FakeVncServer()) {
            handler = newHandler(server.getPort());
            SessionFixture fixture = newSession("11");
            handler.afterConnectionEstablished(fixture.session);
            server.awaitConnected();

            handler.handleTextMessage(fixture.session, new org.springframework.web.socket.TextMessage("text"));

            assertEquals(CloseStatus.NOT_ACCEPTABLE.getCode(), fixture.awaitClose().getCode());
            assertTrue(server.awaitPeerClosed());
        }
    }

    /** A client close must close the paired TCP socket so the reader thread can terminate. */
    @Test
    void shouldCloseTcpPeerWhenClientCloses() throws Exception {
        try (FakeVncServer server = new FakeVncServer()) {
            handler = newHandler(server.getPort());
            SessionFixture fixture = newSession("11");
            handler.afterConnectionEstablished(fixture.session);
            server.awaitConnected();

            fixture.open.set(false);
            handler.afterConnectionClosed(fixture.session, CloseStatus.NORMAL);

            assertTrue(server.awaitPeerClosed());
        }
    }

    /** Backend EOF must close the WebSocket and release the bridge without waiting for a client frame. */
    @Test
    void shouldCloseWebSocketWhenBackendReachesEof() throws Exception {
        try (FakeVncServer server = new FakeVncServer()) {
            handler = newHandler(server.getPort());
            SessionFixture fixture = newSession("11");
            handler.afterConnectionEstablished(fixture.session);
            server.awaitConnected();

            server.closePeer();

            assertEquals(CloseStatus.NORMAL.getCode(), fixture.awaitClose().getCode());
        }
    }

    /** A refused loopback connection must close the WebSocket and not retain a bridge slot. */
    @Test
    void shouldCloseWebSocketWhenLoopbackVncConnectFails() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            unusedPort = socket.getLocalPort();
        }
        handler = newHandler(unusedPort);
        SessionFixture fixture = newSession("11");

        handler.afterConnectionEstablished(fixture.session);

        assertEquals(CloseStatus.SERVER_ERROR.getCode(), fixture.awaitClose().getCode());
    }

    /** A WebSocket send failure must tear down its TCP peer and unblock the reader thread. */
    @Test
    void shouldCloseTcpPeerWhenWebSocketSendFails() throws Exception {
        try (FakeVncServer server = new FakeVncServer()) {
            handler = newHandler(server.getPort());
            SessionFixture fixture = newSession("11");
            doThrow(new IOException("client disconnected"))
                .when(fixture.session).sendMessage(any(WebSocketMessage.class));
            handler.afterConnectionEstablished(fixture.session);
            server.awaitConnected();

            server.write(new byte[] {5});

            assertEquals(CloseStatus.SERVER_ERROR.getCode(), fixture.awaitClose().getCode());
            assertTrue(server.awaitPeerClosed());
        }
    }

    /** Application shutdown must close an active backend socket before its reader is interrupted. */
    @Test
    void shouldCloseTcpPeerOnShutdown() throws Exception {
        try (FakeVncServer server = new FakeVncServer()) {
            handler = newHandler(server.getPort());
            SessionFixture fixture = newSession("11");
            handler.afterConnectionEstablished(fixture.session);
            server.awaitConnected();

            handler.shutdown();

            assertEquals(CloseStatus.GOING_AWAY.getCode(), fixture.awaitClose().getCode());
            assertTrue(server.awaitPeerClosed());
        }
    }

    /** The first close reason must win when shutdown races with another terminal callback. */
    @Test
    void shouldPreserveShutdownCloseStatusWhenTransportErrorRaces() throws Exception {
        try (FakeVncServer server = new FakeVncServer()) {
            handler = newHandler(server.getPort());
            CountDownLatch shutdownCloseEntered = new CountDownLatch(1);
            CountDownLatch allowShutdownClose = new CountDownLatch(1);
            SessionFixture fixture = newSession("11", status -> {
                if (status.getCode() == CloseStatus.GOING_AWAY.getCode()) {
                    shutdownCloseEntered.countDown();
                    try {
                        assertTrue(allowShutdownClose.await(2, TimeUnit.SECONDS),
                            "Timed out waiting to release GOING_AWAY close");
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                    }
                }
            });
            handler.afterConnectionEstablished(fixture.session);
            server.awaitConnected();

            Thread shutdownThread = new Thread(handler::shutdown, "hub-vnc-shutdown-test");
            shutdownThread.start();

            assertTrue(shutdownCloseEntered.await(2, TimeUnit.SECONDS),
                "GOING_AWAY close did not start");
            assertTrue(server.awaitPeerClosed(), "TCP peer was not closed during shutdown");
            handler.handleTransportError(fixture.session, new IOException("simulated concurrent failure"));
            allowShutdownClose.countDown();
            shutdownThread.join(2000L);
            assertFalse(shutdownThread.isAlive(), "shutdown thread did not finish");

            assertEquals(CloseStatus.GOING_AWAY.getCode(), fixture.awaitClose().getCode());
            verify(fixture.session).close(any(CloseStatus.class));
        }
    }

    /** The connection slot semaphore is the hard cap: 32 live sessions fit, the 33rd is rejected without a TCP attempt, and a normal close returns its slot. */
    @Test
    void shouldEnforceConnectionSlotLimitAndReleaseOnNormalClose() throws Exception {
        try (MultiPeerVncServer server = new MultiPeerVncServer()) {
            handler = newHandler(server.getPort());
            List<SessionFixture> live = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                SessionFixture fixture = newSession("live-" + i, "11", ignored -> {
                });
                handler.afterConnectionEstablished(fixture.session);
                live.add(fixture);
            }
            server.awaitPeerCount(32);

            SessionFixture extra = newSession("extra", "11", ignored -> {
            });
            handler.afterConnectionEstablished(extra.session);
            assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), extra.awaitClose().getCode());
            assertEquals(32, server.peerCount());

            // A normal close must return the slot: the replacement session can connect now.
            live.get(0).open.set(false);
            handler.afterConnectionClosed(live.get(0).session, CloseStatus.NORMAL);
            SessionFixture replacement = newSession("replacement", "11", ignored -> {
            });
            handler.afterConnectionEstablished(replacement.session);
            server.awaitPeerCount(33);

            byte[] backendBytes = {4, 2, 0};
            server.writeTo(32, backendBytes);
            assertArrayEquals(backendBytes, replacement.awaitBinary());
        }
    }

    /** Failed connect attempts must release their slot exactly once: afterwards 32 live sessions still fit and the 33rd is still rejected (no leak, no over-release). */
    @Test
    void shouldRestoreSlotExactlyOnceAfterConnectFailures() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            unusedPort = socket.getLocalPort();
        }
        HubInstanceService instanceService = mock(HubInstanceService.class);
        HubInstanceLifecycleService lifecycleService = mock(HubInstanceLifecycleService.class);
        when(instanceService.get(anyString())).thenReturn(runningInstance());
        when(lifecycleService.getSnapshot("11"))
            .thenReturn(new HubInstanceRuntimeSnapshot(true, 99, unusedPort, 0, 0));
        handler = new HubVncWebSocketHandler(instanceService, lifecycleService);

        for (int i = 0; i < 5; i++) {
            SessionFixture fixture = newSession("failed-" + i, "11", ignored -> {
            });
            handler.afterConnectionEstablished(fixture.session);
            assertEquals(CloseStatus.SERVER_ERROR.getCode(), fixture.awaitClose().getCode());
        }

        try (MultiPeerVncServer server = new MultiPeerVncServer()) {
            // Point the same handler at a live port for the healthy phase.
            when(lifecycleService.getSnapshot("11"))
                .thenReturn(new HubInstanceRuntimeSnapshot(true, 99, server.getPort(), 0, 0));
            for (int i = 0; i < 32; i++) {
                SessionFixture fixture = newSession("live-" + i, "11", ignored -> {
                });
                handler.afterConnectionEstablished(fixture.session);
            }
            server.awaitPeerCount(32);

            SessionFixture extra = newSession("extra", "11", ignored -> {
            });
            handler.afterConnectionEstablished(extra.session);
            assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), extra.awaitClose().getCode());
        }
    }

    /** A transport error followed by the framework's repeated close callback must release the slot exactly once. */
    @Test
    void shouldReleaseSlotExactlyOnceOnTransportErrorAndRepeatedClose() throws Exception {
        try (MultiPeerVncServer server = new MultiPeerVncServer()) {
            handler = newHandler(server.getPort());
            SessionFixture doomed = newSession("doomed", "11", ignored -> {
            });
            handler.afterConnectionEstablished(doomed.session);
            server.awaitPeerCount(1);

            handler.handleTransportError(doomed.session, new IOException("simulated transport failure"));
            assertEquals(CloseStatus.SERVER_ERROR.getCode(), doomed.awaitClose().getCode());
            // The framework may deliver afterConnectionClosed again after a transport error.
            handler.afterConnectionClosed(doomed.session, CloseStatus.SERVER_ERROR);

            for (int i = 0; i < 32; i++) {
                SessionFixture fixture = newSession("live-" + i, "11", ignored -> {
                });
                handler.afterConnectionEstablished(fixture.session);
            }
            server.awaitPeerCount(33);

            SessionFixture extra = newSession("extra", "11", ignored -> {
            });
            handler.afterConnectionEstablished(extra.session);
            assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), extra.awaitClose().getCode());
        }
    }

    private HubVncWebSocketHandler newHandler(int vncPort) {
        HubInstanceService instanceService = mock(HubInstanceService.class);
        HubInstanceLifecycleService lifecycleService = mock(HubInstanceLifecycleService.class);
        when(instanceService.get("11")).thenReturn(runningInstance());
        when(lifecycleService.getSnapshot("11"))
            .thenReturn(new HubInstanceRuntimeSnapshot(true, 99, vncPort, 0, 0));
        return new HubVncWebSocketHandler(instanceService, lifecycleService);
    }

    private HubInstance runningInstance() {
        HubInstance instance = new HubInstance();
        instance.setId("11");
        instance.setState(HubInstanceState.RUNNING);
        return instance;
    }

    private SessionFixture newSession(String instanceId) {
        return newSession(instanceId, ignored -> {
        });
    }

    private SessionFixture newSession(String instanceId, Consumer<CloseStatus> beforeClose) {
        return newSession("session-" + instanceId, instanceId, beforeClose);
    }

    /** Builds a session with an explicit session id but the shared stubbed instance id. */
    private SessionFixture newSession(String sessionId, String instanceId, Consumer<CloseStatus> beforeClose) {
        WebSocketSession session = mock(WebSocketSession.class);
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        CountDownLatch closeLatch = new CountDownLatch(1);
        BlockingQueue<byte[]> binaryMessages = new LinkedBlockingQueue<>();
        when(session.getId()).thenReturn(sessionId);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/api/instances/" + instanceId + "/vnc"));
        when(session.isOpen()).thenAnswer(ignored -> open.get());
        try {
            doAnswer(invocation -> {
                WebSocketMessage<?> message = invocation.getArgument(0);
                ByteBuffer payload = ((BinaryMessage) message).getPayload().asReadOnlyBuffer();
                byte[] bytes = new byte[payload.remaining()];
                payload.get(bytes);
                binaryMessages.add(bytes);
                return null;
            }).when(session).sendMessage(any(WebSocketMessage.class));
            doAnswer(invocation -> {
                CloseStatus status = invocation.getArgument(0);
                beforeClose.accept(status);
                closeStatus.set(status);
                open.set(false);
                closeLatch.countDown();
                return null;
            }).when(session).close(any(CloseStatus.class));
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
        return new SessionFixture(session, open, closeStatus, closeLatch, binaryMessages);
    }

    private static final class SessionFixture {

        private final WebSocketSession session;
        private final AtomicBoolean open;
        private final AtomicReference<CloseStatus> closeStatus;
        private final CountDownLatch closeLatch;
        private final BlockingQueue<byte[]> binaryMessages;

        private SessionFixture(
            WebSocketSession session,
            AtomicBoolean open,
            AtomicReference<CloseStatus> closeStatus,
            CountDownLatch closeLatch,
            BlockingQueue<byte[]> binaryMessages) {
            this.session = session;
            this.open = open;
            this.closeStatus = closeStatus;
            this.closeLatch = closeLatch;
            this.binaryMessages = binaryMessages;
        }

        private CloseStatus awaitClose() throws InterruptedException {
            assertTrue(closeLatch.await(2, TimeUnit.SECONDS), "WebSocket was not closed");
            return closeStatus.get();
        }

        private byte[] awaitBinary() throws InterruptedException {
            byte[] bytes = binaryMessages.poll(2, TimeUnit.SECONDS);
            assertTrue(bytes != null, "No binary frame was forwarded to WebSocket");
            return bytes;
        }

    }

    private static final class FakeVncServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
        private final CountDownLatch connected = new CountDownLatch(1);
        private volatile Socket peer;

        private FakeVncServer() throws IOException {
            serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            acceptExecutor.execute(() -> {
                try {
                    peer = serverSocket.accept();
                    peer.setSoTimeout(2000);
                    connected.countDown();
                } catch (IOException ignored) {
                    // close() deliberately unblocks accept during test cleanup.
                }
            });
        }

        private int getPort() {
            return serverSocket.getLocalPort();
        }

        private void awaitConnected() throws InterruptedException {
            assertTrue(connected.await(2, TimeUnit.SECONDS), "VNC TCP peer did not connect");
        }

        private byte[] read(int length) throws IOException, InterruptedException {
            awaitConnected();
            return peer.getInputStream().readNBytes(length);
        }

        private void write(byte[] bytes) throws IOException, InterruptedException {
            awaitConnected();
            peer.getOutputStream().write(bytes);
            peer.getOutputStream().flush();
        }

        private boolean awaitPeerClosed() throws IOException, InterruptedException {
            awaitConnected();
            return peer.getInputStream().read() == -1;
        }

        private void closePeer() throws IOException, InterruptedException {
            awaitConnected();
            peer.close();
        }

        @Override
        public void close() throws IOException {
            if (peer != null) {
                peer.close();
            }
            serverSocket.close();
            acceptExecutor.shutdownNow();
        }

    }

    /** Accepts many loopback peers so tests can fill the handler's connection slot limit. */
    private static final class MultiPeerVncServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
        private final List<Socket> peers = Collections.synchronizedList(new ArrayList<>());

        private MultiPeerVncServer() throws IOException {
            serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            acceptExecutor.execute(() -> {
                try {
                    while (true) {
                        Socket peer = serverSocket.accept();
                        peer.setSoTimeout(2000);
                        peers.add(peer);
                    }
                } catch (IOException ignored) {
                    // close() deliberately unblocks accept during test cleanup.
                }
            });
        }

        private int getPort() {
            return serverSocket.getLocalPort();
        }

        private int peerCount() {
            return peers.size();
        }

        private void awaitPeerCount(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (peers.size() < count && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(peers.size() >= count, "accepted peers=" + peers.size() + " expected=" + count);
        }

        private void writeTo(int index, byte[] bytes) throws IOException {
            Socket peer = peers.get(index);
            peer.getOutputStream().write(bytes);
            peer.getOutputStream().flush();
        }

        @Override
        public void close() throws IOException {
            for (Socket peer : peers) {
                peer.close();
            }
            serverSocket.close();
            acceptExecutor.shutdownNow();
        }

    }

}
