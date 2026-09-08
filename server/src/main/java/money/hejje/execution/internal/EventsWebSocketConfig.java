package money.hejje.execution.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers {@code /ws/events}. {@code @EnableWebSocket} is declared by the market module's config. */
@Configuration
class EventsWebSocketConfig implements WebSocketConfigurer {

    private final EventsWebSocketHandler handler;

    EventsWebSocketConfig(EventsWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/events").setAllowedOriginPatterns("*");
    }
}
