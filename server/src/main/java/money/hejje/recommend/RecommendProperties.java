package money.hejje.recommend;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param minScore Hejje Score below which a valid signal is WAIT rather than TRADE
 * @param caution  thresholds of the PRD 15 cautions (docs/decisions.md)
 */
@ConfigurationProperties("hejje.recommend")
public record RecommendProperties(@DefaultValue("70") int minScore, @DefaultValue Caution caution) {

    /**
     * @param vixRisePct        VIX up more than this on the day → VIX_RISING
     * @param newsOpposingScore |news bias| at or beyond this against the signal's direction → NEWS_OPPOSING
     */
    public record Caution(@DefaultValue("5") double vixRisePct, @DefaultValue("0.4") double newsOpposingScore) {
    }
}
