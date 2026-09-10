package money.hejje.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import money.hejje.common.Product;
import money.hejje.common.Side;
import org.junit.jupiter.api.Test;

/** Position-aware intents (PRD 33, plan M5.3). */
class ExecutionPlannerTest {

    static final UUID INFY = UUID.randomUUID();

    @Test
    void prdExampleShortFiftyToLongHundredBuysOneFifty() {
        ExecutionPlanner.Plan p = ExecutionPlanner.plan(INFY, Product.MIS, null, -50, 100);
        assertThat(p.side()).isEqualTo(Side.BUY);
        assertThat(p.quantity()).isEqualTo(150);
        assertThat(p.delta()).isEqualTo(150);
        assertThat(p.noop()).isFalse();
    }

    @Test
    void reducingAndFlippingSell() {
        assertThat(ExecutionPlanner.plan(INFY, Product.MIS, null, 30, -20)).extracting(ExecutionPlanner.Plan::side, ExecutionPlanner.Plan::quantity)
                .containsExactly(Side.SELL, 50);
        assertThat(ExecutionPlanner.plan(INFY, Product.MIS, null, 100, 40)).extracting(ExecutionPlanner.Plan::side, ExecutionPlanner.Plan::quantity)
                .containsExactly(Side.SELL, 60);
    }

    @Test
    void fromFlatAndToFlat() {
        assertThat(ExecutionPlanner.plan(INFY, Product.MIS, null, 0, 10).side()).isEqualTo(Side.BUY);
        assertThat(ExecutionPlanner.plan(INFY, Product.MIS, null, 0, -10)).extracting(ExecutionPlanner.Plan::side, ExecutionPlanner.Plan::quantity)
                .containsExactly(Side.SELL, 10);
        assertThat(ExecutionPlanner.plan(INFY, Product.MIS, null, -25, 0)).extracting(ExecutionPlanner.Plan::side, ExecutionPlanner.Plan::quantity)
                .containsExactly(Side.BUY, 25);
    }

    @Test
    void equalPositionsAreANoOp() {
        ExecutionPlanner.Plan p = ExecutionPlanner.plan(INFY, Product.MIS, null, 100, 100);
        assertThat(p.noop()).isTrue();
        assertThat(p.side()).isNull();
        assertThat(p.quantity()).isZero();
    }
}
