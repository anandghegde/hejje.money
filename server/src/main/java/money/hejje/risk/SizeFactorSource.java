package money.hejje.risk;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Tells the risk module whether a session has a market-wide macro event (plan M9.7). Implemented by the events module
 * (the calendar, including news-detected risk events), so risk and its callers need no dependency on events.
 */
public interface SizeFactorSource {

    /** The title of the day's market-wide macro event, if any (the first one when there are several). */
    Optional<String> macroEvent(LocalDate date);
}
