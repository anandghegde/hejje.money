package money.hejje.broker.zerodha;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Zerodha Kite Connect settings ({@code hejje.broker.zerodha.*}).
 *
 * @param apiKey         Kite app API key (env {@code HEJJE_KITE_API_KEY})
 * @param apiSecret      Kite app API secret (env {@code HEJJE_KITE_API_SECRET}); never logged or returned
 * @param baseUrl        Kite REST base URL; only changed in tests (WireMock)
 * @param connectTimeout HTTP connect timeout
 * @param readTimeout    HTTP read timeout; the outcome of a timed-out transactional call is UNKNOWN
 */
@ConfigurationProperties("hejje.broker.zerodha")
public record KiteProperties(
        String apiKey,
        String apiSecret,
        @DefaultValue("https://api.kite.trade") String baseUrl,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout) {

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && apiSecret != null && !apiSecret.isBlank();
    }
}
