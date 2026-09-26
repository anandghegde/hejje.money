package money.hejje.ratings;

import java.time.LocalDate;

/**
 * A stock's NSE surveillance measure as of a session (docs/ratings.md, "Surveillance"). Display only: it changes no score,
 * list or universe.
 *
 * @param flag   {@code NONE}, {@code ASM_LT_<stage>}, {@code ASM_ST_<stage>} or {@code GSM_<stage>}
 * @param code   NSE's code for the measure (e.g. {@code LTASM - I (13)}, {@code IBC - Receipt & GSM 0 (62)}); null for NONE
 * @param asOf   the session the lists were fetched for
 * @param stale  the lists are older than the session (that day's fetch failed or has not run)
 */
public record Surveillance(String flag, String code, LocalDate asOf, boolean stale) {
}
