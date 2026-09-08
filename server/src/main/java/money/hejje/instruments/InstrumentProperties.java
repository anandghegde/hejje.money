package money.hejje.instruments;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Instrument module settings ({@code hejje.instruments.*}).
 *
 * @param syncOnStartup run the instrument sync once when the server starts (dev convenience)
 * @param syncCron      cron (IST) for the daily sync; only runs on trading days
 */
@ConfigurationProperties("hejje.instruments")
public record InstrumentProperties(
        @DefaultValue("false") boolean syncOnStartup,
        @DefaultValue("0 0 8 * * MON-FRI") String syncCron) {
}
