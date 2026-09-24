package money.hejje.calibration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Calibration settings ({@code hejje.calibration.*}, plan M9.2, docs/calibration.md). The horizons and the pass bar are
 * pre-registered: change them only with a note in docs/calibration.md, before looking at results.
 *
 * @param entryHorizonMinutes     {@link LabelRule#ENTRY_1R} window
 * @param directionHorizonMinutes {@link LabelRule#DIRECTION} window
 * @param exitHorizonMinutes      {@link LabelRule#EXIT} window
 * @param minBucketCount          a bucket with fewer labelled predictions shows no rate
 * @param minLabelled             pass bar: labelled predictions
 * @param minSessions             pass bar: distinct sessions they come from
 * @param maxEce                  pass bar: expected calibration error
 * @param noDataAfterDays         a prediction still without candles this many days after its session is labelled NONE
 */
@ConfigurationProperties("hejje.calibration")
public record CalibrationProperties(
        @DefaultValue("30") int entryHorizonMinutes,
        @DefaultValue("60") int directionHorizonMinutes,
        @DefaultValue("15") int exitHorizonMinutes,
        @DefaultValue("20") int minBucketCount,
        @DefaultValue("300") int minLabelled,
        @DefaultValue("15") int minSessions,
        @DefaultValue("0.07") double maxEce,
        @DefaultValue("5") int noDataAfterDays) {}
