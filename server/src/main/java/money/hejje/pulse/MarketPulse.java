package money.hejje.pulse;

import java.util.List;

/**
 * PRD 16.2 label table. {@code globalContext} is {@code NEUTRAL} until a data source exists.
 */
public record MarketPulse(String regime, String volatility, String breadth, List<SectorStrength> sectors, String globalContext) {

    public MarketPulse {
        sectors = sectors == null ? List.of() : List.copyOf(sectors);
    }
}
