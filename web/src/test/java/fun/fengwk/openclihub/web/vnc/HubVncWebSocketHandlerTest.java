package fun.fengwk.openclihub.web.vnc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
            SessionFixture fixture = newSession(11L);

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
        when(instanceService.get(11L)).thenReturn(instance);
        when(lifecycleService.getSnapshot(11L)).thenReturn(HubInstanceRuntimeSnapshot.absent());
        handler = new HubVncWebSocketHandler(instanceService, lifecycleService);
        SessionFixture fixture = newSession(11L);

        handler.afterConnectionEstablished(fixture.session);

        assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), fixture.awaitClose().getCode());
        verify(lifecycleService).getSnapshot(11L);
    }

    /** Text is not RFB data and must be rejected instead of being converted or forwarded. */
    @Test
    void shouldRejectTextFramesAndCloseTcpPeer() throws Exception {
        try (FakeVncServer server = new FakeVncServer()) {
            handler = newHandler(server.getPort());
            SessionFixture fixture = newSession(11L);
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
            SessionFixture fixture = newSession(11L);
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
            SessionFixture fixture = newSession(11L);
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
        SessionFixture fixture = newSession(11L);

        handler.afterConnectionEstablished(fixture.session);

        assertEquals(CloseStatus.SERVER_ERROR.getCode(), fixture.awaitClose().getCode());
    }

    /** A WebSocket send failure must tear down its TCP peer and unblock the reader thread. */
    @Test
    void shouldCloseTcpPeerWhenWebSocketSendFails() throws Exception {
        try (FakeVncServer server = new FakeVncServer()) {
            handler = newHandler(server.getPort());
            SessionFixture fixture = newSession(11L);
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
            SessionFixture fixture = newSession(11L);
            handler.afterConnectionEstablished(fixture.session);
            server.awaitConnected();

            handler.shutdown();

            assertEquals(CloseStatus.GOING_AWAY.getCode(), fixture.awaitClose().getCode());
            assertTrue(server.awaitPeerClosed());
        }
    }

    /** The first close reason must win even if shutdown races with the reader thread's socket error. */
    @Test
    void shouldPreserveShutdownCloseStatusWhenReaderRaces() throws Exception {
        try (FakeVncServer server = new FakeVncServer()) {
            handler = newHandler(server.getPort());
            CountDownLatch shutdownCloseEntered = new CountDownLatch(1);
            CountDownLatch allowShutdownClose = new CountDownLatch(1);
            SessionFixture fixture = newSession(11L, status -> {
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
            Thread.sleep(100L);
            allowShutdownClose.countDown();
            shutdownThread.join(2000L);
            assertTrue(!shutdownThread.isAlive(), "shutdown thread did not finish");

            assertEquals(CloseStatus.GOING_AWAY.getCode(), fixture.awaitClose().getCode());
        }
    }

    private HubVncWebSocketHandler newHandler(int vncPort) {
        HubInstanceService instanceService = mock(HubInstanceService.class);
        HubInstanceLifecycleService lifecycleService = mock(HubInstanceLifecycleService.class);
        when(instanceService.get(11L)).thenReturn(runningInstance());
        when(lifecycleService.getSnapshot(11L))
            .thenReturn(new HubInstanceRuntimeSnapshot(true, 99, vncPort, 0, 0));
        return new HubVncWebSocketHandler(instanceService, lifecycleService);
    }

    private HubInstance runningInstance() {
        HubInstance instance = new HubInstance();
        instance.setId(11L);
        instance.setState(HubInstanceState.RUNNING);
        return instance;
    }

    private SessionFixture newSession(long instanceId) {
        return newSession(instanceId, ignored -> {
        });
    }

    private SessionFixture newSession(long instanceId, Consumer<CloseStatus> beforeClose) {
        WebSocketSession session = mock(WebSocketSession.class);
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        CountDownLatch closeLatch = new CountDownLatch(1);
        BlockingQueue<byte[]> binaryMessages = new LinkedBlockingQueue<>();
        when(session.getId()).thenReturn("session-" + instanceId);
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

}
