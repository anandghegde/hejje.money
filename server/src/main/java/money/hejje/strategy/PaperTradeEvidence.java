package money.hejje.strategy;

import java.util.UUID;

/**
 * Closed paper trades of a version, supplied by the signals module: "a new strategy version is never automatic" (PRD 49,
 * plan M5.2) is enforced by requiring {@code hejje.auto.min-paper-trades} of them before an AUTO deployment at autonomy 4-5.
 */
public interface PaperTradeEvidence {

    int closedPaperTrades(UUID versionId);
}
