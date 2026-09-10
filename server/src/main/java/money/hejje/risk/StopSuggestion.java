package money.hejje.risk;

import java.math.BigDecimal;
import money.hejje.common.Price;

/**
 * A suggested initial stop for a new position: the entry it was measured from, the stop itself, what it is based on
 * ({@code ATR}, {@code PERCENT} when no bars are available, or {@code MAX_DISTANCE} when the raw level was clamped to
 * the {@code maxStopDistancePct} limit), the ATR used (null without bars) and the distance in percent of the entry.
 */
public record StopSuggestion(Price entry, Price stop, String basis, BigDecimal atr, BigDecimal distancePct, BigDecimal maxDistancePct) {
}
