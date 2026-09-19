package money.hejje.harness;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One bot's result in one finished SIM session (plan M7.5). Money in paise; {@code tradeRs} are the trades' R multiples net
 * of costs; {@code snapshot} is the harness snapshot at the end (equity curve, trades, decisions, costs), which the TUI opens
 * read-only; {@code decisionsHash} is SHA-256 over the bot's decisions in the session.
 */
public record SimReport(UUID id, UUID sessionId, String botName, String botVersion, String botKind, List<LocalDate> sessionDates, long capitalPaise,
        int trades, int wins, Double expectancyR, Double profitFactor, long maxDrawdownPaise, long netPnlPaise, long winPaise, long lossPaise,
        long frictionPaise, List<Double> tradeRs, String decisionsHash, String resultHash, Map<String, Object> snapshot, Instant createdAt) {

    public SimReport {
        sessionDates = List.copyOf(sessionDates);
        tradeRs = List.copyOf(tradeRs);
    }
}
