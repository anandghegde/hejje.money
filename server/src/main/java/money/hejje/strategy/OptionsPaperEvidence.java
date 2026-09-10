package money.hejje.strategy;

import java.util.UUID;

/**
 * Paper history of an options strategy, supplied by the options module (plan M5.4): options strategies cannot be
 * backtested, so LIVE requires {@link #requiredPaperPositions()} closed paper options positions of the version.
 */
public interface OptionsPaperEvidence {

    int closedPaperPositions(UUID versionId);

    int requiredPaperPositions();
}
