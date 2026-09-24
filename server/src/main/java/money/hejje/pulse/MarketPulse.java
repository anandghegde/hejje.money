package money.hejje.pulse;

import java.util.List;

/**
 * PRD 16.2 label table. {@code globalContext} is {@code NEUTRAL} until a data source exists. {@code marketCondition} is the
 * daily market call of the last closed session with the sentence behind it (plan M8.3).
 */
public record MarketPulse(String regime, String volatility, String breadth, List<SectorStrength> sectors, String globalContext,
        String marketCondition, String marketConditionEvidence) {

    public MarketPulse {
        sectors = sectors == null ? List.of() : List.copyOf(sectors);
    }
}
