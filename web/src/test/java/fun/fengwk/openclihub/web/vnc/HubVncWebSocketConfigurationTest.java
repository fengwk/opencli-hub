package fun.fengwk.openclihub.web.vnc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

class HubVncWebSocketConfigurationTest {

    /** Registration must pass every configured Origin unchanged to Spring's exact matcher. */
    @Test
    void shouldRegisterConfiguredOriginsExactly() {
        HubVncWebSocketHandler handler = mock(HubVncWebSocketHandler.class);
        OpenCliHubProperties properties = new OpenCliHubProperties();
        properties.getVnc().setAllowedOrigins(
            List.of("https://opencli.example", "https://admin.example"));
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(handler, "/api/instances/{id}/vnc")).thenReturn(registration);

        new HubVncWebSocketConfiguration(handler, properties).registerWebSocketHandlers(registry);

        verify(registry).addHandler(handler, "/api/instances/{id}/vnc");
        verify(registration).setAllowedOrigins("https://opencli.example", "https://admin.example");
    }

    /** An empty registration list keeps Spring's built-in same-origin check fail-closed. */
    @Test
    void shouldKeepEmptyAllowlistWhenNoOriginsAreConfigured() {
        HubVncWebSocketHandler handler = mock(HubVncWebSocketHandler.class);
        OpenCliHubProperties properties = new OpenCliHubProperties();
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(handler, "/api/instances/{id}/vnc")).thenReturn(registration);

        new HubVncWebSocketConfiguration(handler, properties).registerWebSocketHandlers(registry);

        assertThat(properties.getVnc().getAllowedOrigins()).isEmpty();
        verify(registration).setAllowedOrigins();
    }

    /** Invalid wildcard syntax must fail before an endpoint is added to the registry. */
    @Test
    void shouldRejectWildcardOriginsBeforeRegisteringHandler() {
        HubVncWebSocketHandler handler = mock(HubVncWebSocketHandler.class);
        OpenCliHubProperties properties = new OpenCliHubProperties();
        properties.getVnc().setAllowedOrigins(List.of("https://*.example"));
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);

        assertThatThrownBy(
            () -> new HubVncWebSocketConfiguration(handler, properties)
                .registerWebSocketHandlers(registry))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not contain '*'");
        verifyNoInteractions(registry);
    }

}
