package money.hejje.strategy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import money.hejje.strategy.StrategyEvidence;
import money.hejje.strategy.VersionStatus;
import org.junit.jupiter.api.Test;

class StrategyLifecycleTest {

    static StrategyEvidence evidence(boolean backtest, boolean validated) {
        return new StrategyEvidence() {
            public boolean hasBacktest(UUID id) { return backtest; }
            public boolean hasValidatedBacktest(UUID id) { return validated; }
        };
    }

    @Test
    void draftCannotJumpToLive() {
        StrategyLifecycle lifecycle = new StrategyLifecycle(evidence(true, true));
        assertThat(lifecycle.reject(UUID.randomUUID(), VersionStatus.DRAFT, VersionStatus.LIVE)).contains("not allowed");
        assertThat(lifecycle.reject(UUID.randomUUID(), VersionStatus.DRAFT, VersionStatus.PAPER)).contains("not allowed");
        assertThat(lifecycle.reject(UUID.randomUUID(), VersionStatus.BACKTESTED, VersionStatus.LIVE)).contains("not allowed");
    }

    @Test
    void forwardMovesNeedEvidence() {
        StrategyLifecycle none = new StrategyLifecycle(evidence(false, false));
        assertThat(none.reject(UUID.randomUUID(), VersionStatus.DRAFT, VersionStatus.BACKTESTED)).contains("backtest is required");
        StrategyLifecycle backtested = new StrategyLifecycle(evidence(true, false));
        assertThat(backtested.reject(UUID.randomUUID(), VersionStatus.DRAFT, VersionStatus.BACKTESTED)).isNull();
        assertThat(backtested.reject(UUID.randomUUID(), VersionStatus.BACKTESTED, VersionStatus.VALIDATED)).contains("out-of-sample");
        StrategyLifecycle validated = new StrategyLifecycle(evidence(true, true));
        assertThat(validated.reject(UUID.randomUUID(), VersionStatus.BACKTESTED, VersionStatus.VALIDATED)).isNull();
        assertThat(validated.reject(UUID.randomUUID(), VersionStatus.VALIDATED, VersionStatus.PAPER)).isNull();
        assertThat(validated.reject(UUID.randomUUID(), VersionStatus.PAPER, VersionStatus.LIVE)).isNull();
    }

    @Test
    void pauseAndRetire() {
        StrategyLifecycle lifecycle = new StrategyLifecycle(evidence(false, false));
        assertThat(lifecycle.reject(UUID.randomUUID(), VersionStatus.PAPER, VersionStatus.PAUSED)).isNull();
        assertThat(lifecycle.reject(UUID.randomUUID(), VersionStatus.LIVE, VersionStatus.PAUSED)).isNull();
        assertThat(lifecycle.reject(UUID.randomUUID(), VersionStatus.PAUSED, VersionStatus.LIVE)).isNull();
        assertThat(lifecycle.reject(UUID.randomUUID(), VersionStatus.DRAFT, VersionStatus.PAUSED)).contains("not allowed");
        for (VersionStatus s : VersionStatus.values()) {
            if (s != VersionStatus.RETIRED) {
                assertThat(lifecycle.reject(UUID.randomUUID(), s, VersionStatus.RETIRED)).isNull();
            }
        }
        assertThat(lifecycle.reject(UUID.randomUUID(), VersionStatus.RETIRED, VersionStatus.DRAFT)).contains("not allowed");
        assertThat(lifecycle.reject(UUID.randomUUID(), VersionStatus.PAPER, VersionStatus.PAPER)).contains("already");
    }

    @Test
    void optionsStrategiesGoStraightToPaperAndNeedPaperHistoryForLive() throws Exception {
        money.hejje.strategy.StrategyDefinition def = new DefinitionParser().parse(java.nio.file.Files.readString(java.nio.file.Path.of("../strategies/nifty_orb_call_buy.yaml")));
        java.util.function.Function<VersionStatus, money.hejje.strategy.StrategyVersion> at = status -> new money.hejje.strategy.StrategyVersion(UUID.randomUUID(),
                UUID.randomUUID(), 1, "", def, "h", "note", null, "test", java.time.Instant.EPOCH, status);
        StrategyLifecycle lifecycle = new StrategyLifecycle(evidence(true, true));
        assertThat(lifecycle.reject(at.apply(VersionStatus.DRAFT), VersionStatus.PAPER)).isNull();
        assertThat(lifecycle.reject(at.apply(VersionStatus.DRAFT), VersionStatus.BACKTESTED)).contains("cannot be backtested");
        assertThat(lifecycle.reject(at.apply(VersionStatus.PAPER), VersionStatus.VALIDATED)).contains("cannot be backtested");
        assertThat(lifecycle.reject(at.apply(VersionStatus.PAPER), VersionStatus.LIVE)).contains("needs 30 closed paper options positions").contains("has 0");
        assertThat(lifecycle.reject(at.apply(VersionStatus.PAPER), VersionStatus.PAUSED)).isNull();
        assertThat(lifecycle.reject(at.apply(VersionStatus.DRAFT), VersionStatus.LIVE)).contains("not allowed");
    }
}
