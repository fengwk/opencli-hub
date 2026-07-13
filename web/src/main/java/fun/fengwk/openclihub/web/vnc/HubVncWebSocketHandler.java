package fun.fengwk.openclihub.web.vnc;

import fun.fengwk.openclihub.core.instance.runtime.HubInstanceLifecycleService;
import fun.fengwk.openclihub.core.instance.runtime.HubInstanceRuntimeSnapshot;
import fun.fengwk.openclihub.core.instance.service.HubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/**
 * Per-session binary proxy from the public VNC WebSocket endpoint to a runtime's loopback
 * x11vnc socket.
 *
 * <p>Only a runtime snapshot determines the target port; no client-supplied host or port is
 * accepted. A bounded reader executor supplies one TCP-to-WebSocket reader per active session.
 * Closing either endpoint removes the bridge, closes the other endpoint, and returns its slot.
 *
 * @author fengwk
 */
@Slf4j
@Component
public class HubVncWebSocketHandler extends BinaryWebSocketHandler {

    private static final Pattern ENDPOINT_PATH = Pattern.compile("^/api/instances/(\\d+)/vnc$");
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final int MAX_SESSIONS = 32;
    private static final int MAX_BINARY_FRAME_BYTES = 1024 * 1024;
    private static final CloseStatus MESSAGE_TOO_BIG = new CloseStatus(1009, "Message too big");
    private static final int CONNECT_TIMEOUT_MILLIS = 1000;
    private static final int TCP_READ_BUFFER_BYTES = 8192;

    private final HubInstanceService instanceService;
    private final HubInstanceLifecycleService lifecycleService;
    private final Map<String, SessionBridge> bridges = new ConcurrentHashMap<>();
    private final Object shutdownMonitor = new Object();
    private final Semaphore connectionSlots = new Semaphore(MAX_SESSIONS);
    private volatile boolean shuttingDown;
    private final ExecutorService readExecutor = Executors.newFixedThreadPool(
        MAX_SESSIONS, new VncReaderThreadFactory());

    public HubVncWebSocketHandler(
        HubInstanceService instanceService,
        HubInstanceLifecycleService lifecycleService) {
        this.instanceService = instanceService;
        this.lifecycleService = lifecycleService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (shuttingDown) {
            closeSession(session, CloseStatus.GOING_AWAY);
            return;
        }

        final int vncPort;
        try {
            vncPort = resolveVncPort(session.getUri());
        } catch (RuntimeException ex) {
            log.debug("Rejecting VNC WebSocket connection: {}", ex.getMessage());
            closeSession(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        if (!connectionSlots.tryAcquire()) {
            closeSession(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(LOOPBACK_HOST, vncPort), CONNECT_TIMEOUT_MILLIS);
            if (!session.isOpen()) {
                closeSocket(socket);
                connectionSlots.release();
                return;
            }

            SessionBridge bridge = new SessionBridge(session, socket);
            synchronized (shutdownMonitor) {
                if (shuttingDown) {
                    closeSocket(socket);
                    connectionSlots.release();
                    closeSession(session, CloseStatus.GOING_AWAY);
                    return;
                }
                if (bridges.putIfAbsent(session.getId(), bridge) != null) {
                    closeSocket(socket);
                    connectionSlots.release();
                    closeSession(session, CloseStatus.SERVER_ERROR);
                    return;
                }
            }
            try {
                readExecutor.execute(() -> forwardTcpToWebSocket(bridge));
            } catch (RejectedExecutionException ex) {
                closeBridge(session, CloseStatus.SERVER_ERROR);
            }
        } catch (IOException ex) {
            closeSocket(socket);
            connectionSlots.release();
            log.debug("Unable to connect VNC WebSocket to loopback port {}: {}", vncPort,
                ex.getMessage());
            closeSession(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SessionBridge bridge = bridges.get(session.getId());
        if (bridge == null) {
            closeSession(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        ByteBuffer payload = message.getPayload().asReadOnlyBuffer();
        if (payload.remaining() > MAX_BINARY_FRAME_BYTES) {
            closeBridge(session, MESSAGE_TOO_BIG);
            return;
        }
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);

        try {
            synchronized (bridge.tcpWriteLock) {
                OutputStream output = bridge.socket.getOutputStream();
                output.write(bytes);
                output.flush();
            }
        } catch (IOException ex) {
            log.debug("VNC TCP write failed for session {}: {}", session.getId(), ex.getMessage());
            closeBridge(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        closeBridge(session, CloseStatus.NOT_ACCEPTABLE);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("VNC WebSocket transport failed for session {}: {}", session.getId(),
            exception.getMessage());
        closeBridge(session, CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closeBridge(session, status);
    }

    @PreDestroy
    void shutdown() {
        List<SessionBridge> bridgesToClose;
        synchronized (shutdownMonitor) {
            shuttingDown = true;
            bridgesToClose = List.copyOf(bridges.values());
        }
        for (SessionBridge bridge : bridgesToClose) {
            closeBridge(bridge.session, CloseStatus.GOING_AWAY);
        }
        readExecutor.shutdownNow();
    }

    private int resolveVncPort(URI uri) {
        if (uri == null) {
            throw HubErrorCodes.INSTANCE_VNC_UNAVAILABLE.asThrowable("VNC endpoint path is required");
        }
        Matcher matcher = ENDPOINT_PATH.matcher(uri.getPath());
        if (!matcher.matches()) {
            throw HubErrorCodes.INSTANCE_VNC_UNAVAILABLE.asThrowable("Invalid VNC endpoint path");
        }

        long instanceId = Long.parseLong(matcher.group(1));
        HubInstance instance = instanceService.get(instanceId);
        if (!instance.isRunning()) {
            throw HubErrorCodes.INSTANCE_NOT_RUNNING.asThrowable("Instance is not running");
        }
        HubInstanceRuntimeSnapshot snapshot = lifecycleService.getSnapshot(instanceId);
        Integer vncPort = snapshot.getVncPort();
        if (!snapshot.isRegistered() || vncPort == null || vncPort <= 0 || vncPort > 65535) {
            throw HubErrorCodes.INSTANCE_VNC_UNAVAILABLE.asThrowable("VNC runtime is unavailable");
        }
        return vncPort;
    }

    private void forwardTcpToWebSocket(SessionBridge bridge) {
        CloseStatus closeStatus = CloseStatus.NORMAL;
        try (InputStream input = bridge.socket.getInputStream()) {
            byte[] buffer = new byte[TCP_READ_BUFFER_BYTES];
            int length;
            while (bridge.session.isOpen() && (length = input.read(buffer)) != -1) {
                if (length > 0) {
                    sendBinary(bridge, Arrays.copyOf(buffer, length));
                }
            }
        } catch (IOException ex) {
            if (bridge.session.isOpen()) {
                closeStatus = CloseStatus.SERVER_ERROR;
                log.debug("VNC TCP read failed for session {}: {}", bridge.session.getId(),
                    ex.getMessage());
            }
        } finally {
            closeBridge(bridge.session, closeStatus);
        }
    }

    private void sendBinary(SessionBridge bridge, byte[] bytes) throws IOException {
        synchronized (bridge.webSocketSendLock) {
            if (!bridge.session.isOpen()) {
                throw new IOException("WebSocket session is closed");
            }
            bridge.session.sendMessage(new BinaryMessage(ByteBuffer.wrap(bytes)));
        }
    }

    private void closeBridge(WebSocketSession session, CloseStatus status) {
        SessionBridge bridge = bridges.remove(session.getId());
        if (bridge != null) {
            closeSocket(bridge.socket);
            connectionSlots.release();
        }
        closeSession(session, status);
    }

    private void closeSession(WebSocketSession session, CloseStatus status) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.close(status);
        } catch (IOException ex) {
            log.debug("Failed to close VNC WebSocket session {}: {}", session.getId(),
                ex.getMessage());
        }
    }

    private void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ex) {
            log.debug("Failed to close VNC TCP socket: {}", ex.getMessage());
        }
    }

    private static final class SessionBridge {

        private final WebSocketSession session;
        private final Socket socket;
        private final Object webSocketSendLock = new Object();
        private final Object tcpWriteLock = new Object();

        private SessionBridge(WebSocketSession session, Socket socket) {
            this.session = session;
            this.socket = socket;
        }

    }

    private static final class VncReaderThreadFactory implements java.util.concurrent.ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "hub-vnc-reader-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }

    }

}
