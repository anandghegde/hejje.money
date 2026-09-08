package money.hejje.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderStateMachineTest {

    static Stream<Arguments> legalEdges() {
        return OrderState.TRANSITIONS.entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(to -> Arguments.of(e.getKey(), to)));
    }

    @ParameterizedTest
    @MethodSource("legalEdges")
    void everyLegalEdgeIsAccepted(OrderState from, OrderState to) {
        assertThat(OrderStateMachine.isLegal(from, to)).isTrue();
        assertThat(OrderStateMachine.require(from, to)).isEqualTo(to);
    }

    @Test
    void illegalTransitionsAreRejected() {
        assertThat(OrderStateMachine.isLegal(OrderState.FILLED, OrderState.OPEN)).isFalse();
        assertThat(OrderStateMachine.isLegal(OrderState.READY, OrderState.FILLED)).isFalse();
        assertThat(OrderStateMachine.isLegal(OrderState.CANCELLED, OrderState.OPEN)).isFalse();
        assertThatThrownBy(() -> OrderStateMachine.require(OrderState.READY, OrderState.FILLED))
                .isInstanceOfSatisfying(IllegalTransition.class, e -> {
                    assertThat(e.from()).isEqualTo(OrderState.READY);
                    assertThat(e.to()).isEqualTo(OrderState.FILLED);
                });
    }

    @Test
    void terminalStatesHaveNoOutgoingEdges() {
        for (OrderState terminal : List.of(OrderState.FILLED, OrderState.CANCELLED, OrderState.REJECTED, OrderState.RISK_REJECTED)) {
            assertThat(OrderState.TRANSITIONS.getOrDefault(terminal, Set.of())).isEmpty();
            assertThat(terminal.isTerminal()).isTrue();
        }
    }

    @Test
    void everyStateHasATransitionEntry() {
        for (OrderState state : OrderState.values()) {
            assertThat(OrderState.TRANSITIONS).containsKey(state);
        }
    }
}
