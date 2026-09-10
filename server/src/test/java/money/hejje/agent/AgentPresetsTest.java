package money.hejje.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import money.hejje.common.security.AgentPresets;
import org.junit.jupiter.api.Test;

class AgentPresetsTest {

    @Test
    void presetsAreNarrowAndNeverExecuteCloseChangeRiskOrAdminister() {
        assertThat(AgentPresets.scopes("research")).containsExactly("market:read", "strategies:read");
        assertThat(AgentPresets.scopes("execution")).containsExactly("market:read", "strategies:read", "orders:prepare");
        for (String preset : AgentPresets.names()) {
            assertThat(AgentPresets.scopes(preset)).doesNotContainAnyElementsOf(AgentPresets.NEVER_IN_A_PRESET);
        }
        assertThat(AgentPresets.NEVER_IN_A_PRESET).contains("orders:execute", "positions:close", "risk:write", "admin");
        assertThatThrownBy(() -> AgentPresets.scopes("god")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown preset");
    }
}
