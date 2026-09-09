package money.hejje.recommend;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param minScore Hejje Score below which a valid signal is WAIT rather than TRADE */
@ConfigurationProperties("hejje.recommend")
public record RecommendProperties(@DefaultValue("70") int minScore) {
}
