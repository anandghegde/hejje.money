package money.hejje.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Does trading more each day hurt (plan M9.7)? Expectancy per trade (R and rupees net of costs), count and win rate,
 * bucketed by how many trades were taken that day, by the trade's sequence number within its day, and by entry hour.
 * Every rate comes with its count; {@code expectancyR} is over the trades that have an R (a known stop).
 */
public record PaceReport(String mode, LocalDate from, LocalDate to, String strategy, int trades, List<Row> byTradesThatDay, List<Row> bySequence,
        List<Row> byHour) {

    public record Row(String bucket, int trades, int wins, Double winRate, Double expectancyR, int withR, BigDecimal expectancyRupees, BigDecimal netPnl) {}
}
