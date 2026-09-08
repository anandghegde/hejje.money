package money.hejje.market.internal;

import money.hejje.system.ReadinessCheck;
import org.springframework.stereotype.Component;

/**
 * Readiness line {@code marketData}. During the session a healthy stream is OK and a gap is BLOCKING (STALE); outside
 * the session market data is not required, so the check is SKIPPED.
 */
@Component
class MarketDataReadiness implements ReadinessCheck {

    private final MarketDataStreamer streamer;

    MarketDataReadiness(MarketDataStreamer streamer) {
        this.streamer = streamer;
    }

    @Override
    public String name() {
        return "marketData";
    }

    @Override
    public CheckResult result() {
        MarketDataStreamer.MarketDataState state = streamer.state();
        if (!state.required()) {
            return CheckResult.skipped(state.detail());
        }
        return state.healthy() ? CheckResult.ok(state.detail()) : CheckResult.blocking("STALE: " + state.detail());
    }
}
