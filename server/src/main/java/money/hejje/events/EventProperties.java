package money.hejje.events;

import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Event calendar settings ({@code hejje.events.*}, config/events.yaml, docs/events.md).
 *
 * @param enabled           the whole module: off means no events, risk LOW with an "unavailable" line, no refresh
 * @param refreshOnStartup  pull every enabled source after boot (and daily at {@code refresh-cron})
 * @param horizonDays       how far ahead sources are pulled and the next event is searched
 * @param risk              proximity rules
 * @param computed          holidays, expiries and rebalance dates
 * @param curated           curated macro YAML files
 * @param nse               optional NSE corporate-action / board-meeting fetcher
 */
@ConfigurationProperties("hejje.events")
public record EventProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean refreshOnStartup,
        @DefaultValue("60") int horizonDays,
        @DefaultValue Risk risk,
        @DefaultValue Computed computed,
        @DefaultValue Curated curated,
        @DefaultValue Nse nse) {

    /**
     * @param macroHighWithinMinutes a macro event starting within this many minutes (or in progress) is HIGH; the rest of the day MEDIUM
     * @param macroInProgressMinutes how long a timed macro event counts as in progress after its start
     * @param earningsHeavyCount     results events on a day at or above which the regime environment is EARNINGS_HEAVY
     */
    public record Risk(@DefaultValue("60") int macroHighWithinMinutes, @DefaultValue("30") int macroInProgressMinutes,
            @DefaultValue("5") int earningsHeavyCount) {
    }

    /** @param expiryUnderlyings underlyings whose option expiries become FNO_EXPIRY events; @param indexRebalanceDates INDEX_REBALANCE dates */
    public record Computed(@DefaultValue("true") boolean enabled, @DefaultValue({"NIFTY", "BANKNIFTY"}) List<String> expiryUnderlyings,
            List<LocalDate> indexRebalanceDates) {
        public Computed {
            indexRebalanceDates = indexRebalanceDates == null ? List.of() : List.copyOf(indexRebalanceDates);
        }
    }

    /** @param files YAML resources ({@code events: [{type, title, date, time?, end_date?, confidence?, symbol?}]}) */
    public record Curated(@DefaultValue("true") boolean enabled, @DefaultValue("classpath:events/macro-2026.yaml") List<String> files) {
    }

    /** Best-effort fetch of NSE's corporate-action and board-meeting feeds; off by default (README rule 7). */
    public record Nse(@DefaultValue("false") boolean enabled, @DefaultValue("https://www.nseindia.com") String baseUrl, @DefaultValue("5") int timeoutSeconds) {
    }
}
