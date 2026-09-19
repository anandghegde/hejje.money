package money.hejje.sim;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What a replay session plays (plan M7.2, {@code POST /sim/sessions}). Sessions come from {@code dates} or the trading
 * days of {@code from..to}; instruments from Hejje {@code instruments} symbols or a named {@code universe} (only
 * {@code nifty50}). Money is in rupees; null limits keep the SIM risk limits as they are. {@code bots} lists the session's
 * bots as {@code {"botId": "…"}} (plan M7.3): validated, and an LLM bot on days before its knowledge cutoff is flagged.
 */
public record SimSessionSpec(List<LocalDate> dates, LocalDate from, LocalDate to, List<String> instruments, String universe,
        Long capitalRupees, Long riskPerTradeRupees, Long lossHaltRupees, Integer maxPositions, List<Map<String, Object>> bots) {

    public SimSessionSpec {
        dates = dates == null ? List.of() : List.copyOf(dates);
        instruments = instruments == null ? List.of() : List.copyOf(instruments);
        bots = bots == null ? List.of() : List.copyOf(bots);
    }
}
