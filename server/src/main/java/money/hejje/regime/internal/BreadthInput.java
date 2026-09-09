package money.hejje.regime.internal;

import java.util.List;

/**
 * Per-constituent observations for breadth. {@code vwap} is null when no intraday bars exist (historical labelling
 * uses daily closes only, so only advance/decline is available there).
 */
record BreadthInput(int universeSize, List<Constituent> constituents) {

    record Constituent(String symbol, double prevClose, double last, Double vwap) {
    }

    static BreadthInput none(int universeSize) {
        return new BreadthInput(universeSize, List.of());
    }
}
