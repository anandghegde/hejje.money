package money.hejje.context;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * PRD 19 Strategy Context Card for one version on one instrument.
 *
 * @param netImpact Σ deltas of the context adjusters (regime, event, news; technical fit is listed but is not "context")
 * @param items     technical fit, market regime, news bias, event risk, sector — in that order
 */
public record StrategyContext(UUID versionId, UUID instrumentId, Instant asOf, ContextItem technicalFit, ContextItem marketRegime, ContextItem newsBias,
        ContextItem eventRisk, ContextItem sector, String nextEvent, int netImpact, List<ContextItem> items) {

    public StrategyContext {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
