package money.hejje.recommend;

import java.util.List;
import java.util.Map;

/**
 * PRD section 8: market header, Best Hejje, ranked opportunities, or the No-Trade state.
 *
 * @param header   index quotes and VIX (regime, breadth and event risk are null until Phase 3)
 * @param best     the top TRADE recommendation, or null
 * @param ranked   every recommendation ordered by decision then score
 * @param noTrade  the No-Trade message when nothing qualifies, else null
 */
public record TodayView(Map<String, Object> header, Recommendation best, List<Recommendation> ranked, String noTrade) {
}
