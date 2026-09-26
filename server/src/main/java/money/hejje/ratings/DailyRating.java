package money.hejje.ratings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * One stock's price/volume ratings for one session (plan M8.2, docs/ratings.md). Everything is a function of D1 candles
 * up to and including {@code sessionDate}'s close. A value that lacks history is null.
 *
 * @param rsRaw           weighted return over one to four quarters
 * @param rsRating        1-99 percentile of {@code rsRaw} within the universe that day
 * @param adRaw           volume-weighted close location over the accumulation/distribution window, -1..+1
 * @param adGrade         {@code A+ .. E} from the universe percentile of {@code adRaw}
 * @param offHighPct      percent below the 252-session high (0 at the high)
 * @param offLowPct       percent above the 252-session low
 * @param volVsAvg50Pct   the session's volume versus the mean of the previous 50 sessions, in percent
 * @param upDownVolRatio  volume on up closes / volume on down closes over 50 sessions
 * @param avgTurnoverCr   mean daily turnover over 50 sessions in crore rupees
 * @param changePct       close versus the previous close
 * @param techComposite   1-99 technical composite (RS, A/D, group, off-high); not O'Neil's Composite, which needs EPS and SMR
 * @param evidence        partial-history flags and the component percentiles
 * @param surveillance    the NSE surveillance measure as of the session, attached when read (not stored with the row, not
 *                        in the hash); null when no surveillance list has been fetched yet
 */
public record DailyRating(LocalDate sessionDate, UUID instrumentId, String engineVersion, String symbol, Double rsRaw, Integer rsRating,
        Double adRaw, String adGrade, Double offHighPct, Double offLowPct, Double volVsAvg50Pct, Double upDownVolRatio, Double avgTurnoverCr,
        BigDecimal close, Double changePct, String groupId, Integer groupRank, Integer techComposite, Map<String, Object> evidence,
        Surveillance surveillance) {

    public DailyRating withSurveillance(Surveillance s) {
        return new DailyRating(sessionDate, instrumentId, engineVersion, symbol, rsRaw, rsRating, adRaw, adGrade, offHighPct, offLowPct, volVsAvg50Pct,
                upDownVolRatio, avgTurnoverCr, close, changePct, groupId, groupRank, techComposite, evidence, s);
    }
}
