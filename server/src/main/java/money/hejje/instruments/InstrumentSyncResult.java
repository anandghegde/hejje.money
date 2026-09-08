package money.hejje.instruments;

import java.time.Instant;

/**
 * Outcome of one instrument master sync.
 *
 * @param broker      broker code the master came from
 * @param received    rows received from the broker (after normalization)
 * @param upserted    instruments inserted or refreshed
 * @param deactivated instruments that were active and are no longer in the master
 * @param activeAfter active instruments after the sync
 * @param syncedAt    when the sync ran
 */
public record InstrumentSyncResult(String broker, int received, int upserted, int deactivated, long activeAfter, Instant syncedAt) {
}
