package money.hejje.backtest.experiments;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import money.hejje.common.Timeframe;

/** The data every variant of an experiment runs on (empty instruments = the strategy's universe). */
public record Dataset(List<UUID> instrumentIds, LocalDate from, LocalDate to, Timeframe timeframe, int slippageBps) {

    public Dataset {
        instrumentIds = instrumentIds == null ? List.of() : List.copyOf(instrumentIds);
    }
}
