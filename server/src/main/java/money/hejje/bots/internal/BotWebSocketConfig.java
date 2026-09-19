package money.hejje.bots.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import money.hejje.bots.BotHub;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers {@code /ws/bot}; the bearer filter authenticates the {@code ?token=} (plan M7.3). */
@Configuration
@EnableWebSocket
class BotWebSocketConfig implements WebSocketConfigurer {

    private final BotHub hub;
    private final ObjectMapper json;

    BotWebSocketConfig(BotHub hub, ObjectMapper json) {
        this.hub = hub;
        this.json = json;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new BotWebSocketHandler(hub, json), "/ws/bot").setAllowedOriginPatterns("*");
    }
}
