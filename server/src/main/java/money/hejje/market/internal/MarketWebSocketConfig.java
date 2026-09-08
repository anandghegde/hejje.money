package money.hejje.market.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import money.hejje.common.event.TickBus;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the client market WebSocket at {@code /ws/market}. Auth is enforced by the bearer filter (?token=). */
@Configuration
@EnableWebSocket
class MarketWebSocketConfig implements WebSocketConfigurer {

    private final TickBus bus;
    private final ObjectMapper json;

    MarketWebSocketConfig(TickBus bus, ObjectMapper json) {
        this.bus = bus;
        this.json = json;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new MarketWebSocketHandler(bus, json), "/ws/market").setAllowedOriginPatterns("*");
    }
}
