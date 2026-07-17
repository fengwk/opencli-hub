package fun.fengwk.openclihub.web.vnc;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.util.List;
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

    private static final String VNC_ENDPOINT = "/api/instances/{id}/vnc";

    private final HubVncWebSocketHandler handler;
    private final OpenCliHubProperties hubProperties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        List<String> allowedOrigins = hubProperties.getVnc().getAllowedOrigins();
        if (allowedOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalArgumentException("VNC allowed origins must not contain '*'");
        }
        registry.addHandler(handler, VNC_ENDPOINT)
            .setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }

}
