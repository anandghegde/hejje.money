package money.hejje.options.internal;

import java.util.UUID;
import money.hejje.options.OptionsProperties;
import money.hejje.strategy.OptionsPaperEvidence;
import org.springframework.stereotype.Component;

/** Closed paper options positions per version, for the options lifecycle rule before LIVE (plan M5.4). */
@Component
class OptionsPaperHistory implements OptionsPaperEvidence {

    private final OptionsStore store;
    private final OptionsProperties properties;

    OptionsPaperHistory(OptionsStore store, OptionsProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public int closedPaperPositions(UUID versionId) {
        return store.closedPaper(versionId);
    }

    @Override
    public int requiredPaperPositions() {
        return properties.minPaperTrades();
    }
}
