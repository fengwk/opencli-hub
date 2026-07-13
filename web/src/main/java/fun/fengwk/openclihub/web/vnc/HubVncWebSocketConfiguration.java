package fun.fengwk.openclihub.web.vnc;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the Instance VNC WebSocket endpoint.
 *
 * @author fengwk
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class HubVncWebSocketConfiguration implements WebSocketConfigurer {

    private final HubVncWebSocketHandler handler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/instances/{id}/vnc");
    }

}
