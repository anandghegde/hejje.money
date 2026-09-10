package money.hejje.broker.dhan;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * DhanHQ v2 settings ({@code hejje.broker.dhan.*}, docs/broker-dhan.md). The client id is required; the API key and
 * secret (consent login) are optional — without them an access token generated on web.dhan.co is pasted instead.
 * Secrets come from the environment only and are never logged.
 */
@ConfigurationProperties("hejje.broker.dhan")
public record DhanProperties(String clientId, String appId, String appSecret, @DefaultValue("https://api.dhan.co/v2") String baseUrl,
        @DefaultValue("https://auth.dhan.co") String authUrl,
        @DefaultValue("https://images.dhan.co/api-data/api-scrip-master.csv") String instrumentsUrl,
        @DefaultValue("PT5S") Duration connectTimeout, @DefaultValue("PT10S") Duration readTimeout,
        @DefaultValue("PT1S") Duration quotePollInterval) {

    public boolean consentConfigured() {
        return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
    }
}
