package money.hejje.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The docs/decisions.md decision table, every row. */
class DecisionRulesTest {

    static final List<String> NO_BLOCKS = List.of();
    static final List<Caution> NONE = List.of();
    static final List<Caution> ONE = List.of(new Caution("VIX_RISING", "India VIX up 6.0% on the day"));

    @Test
    void hardBlocksAlwaysAvoid() {
        assertThat(DecisionRules.decide(true, List.of("killSwitch: stopped"), false, 95, 70, NONE)).isEqualTo(Decision.AVOID);
        assertThat(DecisionRules.decide(true, List.of("eventRule: results today"), true, 95, 70, ONE)).isEqualTo(Decision.AVOID);
        assertThat(DecisionRules.decide(true, List.of("riskPerTrade: too big"), false, null, 70, NONE)).isEqualTo(Decision.AVOID);
    }

    @Test
    void noSignalOrOutsideWindowWaits() {
        assertThat(DecisionRules.decide(false, NO_BLOCKS, false, 95, 70, NONE)).isEqualTo(Decision.WAIT);
        assertThat(DecisionRules.decide(false, List.of("stale"), false, 95, 70, NONE)).isEqualTo(Decision.WAIT); // blocks without a signal are irrelevant
        assertThat(DecisionRules.decide(true, NO_BLOCKS, true, 95, 70, NONE)).isEqualTo(Decision.WAIT);
    }

    @Test
    void scoreGatesTradeAndCautionsDowngrade() {
        assertThat(DecisionRules.decide(true, NO_BLOCKS, false, null, 70, NONE)).isEqualTo(Decision.WAIT);
        assertThat(DecisionRules.decide(true, NO_BLOCKS, false, 69, 70, NONE)).isEqualTo(Decision.WAIT);
        assertThat(DecisionRules.decide(true, NO_BLOCKS, false, 70, 70, NONE)).isEqualTo(Decision.TRADE);
        assertThat(DecisionRules.decide(true, NO_BLOCKS, false, 70, 70, ONE)).isEqualTo(Decision.TRADE_WITH_CAUTION);
        assertThat(DecisionRules.decide(true, NO_BLOCKS, false, 69, 70, ONE)).isEqualTo(Decision.WAIT); // a caution never promotes
        assertThat(Decision.TRADE.ordinal()).isLessThan(Decision.TRADE_WITH_CAUTION.ordinal());
        assertThat(Decision.TRADE_WITH_CAUTION.ordinal()).isLessThan(Decision.WAIT.ordinal());
    }
}
