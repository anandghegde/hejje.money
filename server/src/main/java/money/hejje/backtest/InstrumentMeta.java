package money.hejje.backtest;

import java.math.BigDecimal;
import java.util.UUID;
import money.hejje.common.InstrumentType;

/** The instrument facts the engine needs: type (cost segment), lot size and tick size. */
public record InstrumentMeta(UUID id, String symbol, InstrumentType type, int lotSize, BigDecimal tickSize) {
    public InstrumentMeta {
        if (lotSize < 1) {
            lotSize = 1;
        }
        if (tickSize == null || tickSize.signum() <= 0) {
            tickSize = new BigDecimal("0.05");
        }
    }
}
