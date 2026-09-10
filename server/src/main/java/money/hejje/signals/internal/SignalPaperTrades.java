package money.hejje.signals.internal;

import java.util.UUID;
import money.hejje.strategy.PaperTradeEvidence;
import org.springframework.stereotype.Component;

/** Closed paper strategy positions per version, for the strategy module's AUTO qualification check (plan M5.2). */
@Component
class SignalPaperTrades implements PaperTradeEvidence {

    private final SignalStore store;

    SignalPaperTrades(SignalStore store) {
        this.store = store;
    }

    @Override
    public int closedPaperTrades(UUID versionId) {
        return store.closedPaperTrades(versionId);
    }
}
