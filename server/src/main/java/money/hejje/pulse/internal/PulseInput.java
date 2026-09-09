package money.hejje.pulse.internal;

import java.util.List;
import money.hejje.regime.RegimeSnapshot;

/**
 * Everything the rules read, already reduced to numbers; {@code null} means unavailable.
 *
 * @param indexSessionAverage equal-weighted mean of the index's typical prices so far today (the index "VWAP")
 * @param indexRocPct         momentum: index change over the last {@code rocBars} M5 bars, percent
 * @param relativeVolume      nearest future's cumulative volume so far vs the average at this time over prior sessions
 * @param futuresBasisPct     (nearest future − index) / index × 100
 */
record PulseInput(RegimeSnapshot regime, Double indexLast, Double indexPrevClose, Double indexSessionAverage, Double indexRocPct, int indexBars,
        Double vixLast, Double vixPrevClose, Double relativeVolume, Double futuresBasisPct, List<SectorObservation> sectors) {

    record SectorObservation(String name, String symbol, Double changePct) {
    }

    Double indexChangePct() {
        return indexLast == null || indexPrevClose == null || indexPrevClose == 0 ? null : 100.0 * (indexLast - indexPrevClose) / indexPrevClose;
    }
}
