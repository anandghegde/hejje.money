package money.hejje.backtest.experiments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VariantDeltaTest {

    static Map<String, Object> base() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("name", "orb");
        tree.put("entry", new LinkedHashMap<>(Map.of("all", List.of("close > opening_range_high(15m)", "relative_volume(20) > 1.2"))));
        tree.put("stop", new LinkedHashMap<>(Map.of("type", "opening_range_low")));
        tree.put("target", new LinkedHashMap<>(Map.of("type", "risk_multiple", "value", 2)));
        tree.put("max_trades_per_day", 1);
        return tree;
    }

    @Test
    void mergePatchReplacesMergesAndRemoves() {
        Map<String, Object> delta = new HashMap<>();
        delta.put("target", Map.of("value", 3));
        delta.put("max_trades_per_day", null);
        delta.put("stop", Map.of("type", "percent", "value", 0.5));
        Map<String, Object> out = VariantDelta.apply(base(), delta);
        assertThat((Map<String, Object>) out.get("target")).containsEntry("type", "risk_multiple").containsEntry("value", 3);
        assertThat(out).doesNotContainKey("max_trades_per_day");
        assertThat((Map<String, Object>) out.get("stop")).containsEntry("type", "percent").containsEntry("value", 0.5);
        assertThat(base()).containsEntry("max_trades_per_day", 1); // the base is untouched
    }

    @Test
    void entryAndExitAdditionsAppendWithoutRestatingTheList() {
        Map<String, Object> out = VariantDelta.apply(base(), Map.of("entry_add", List.of("close > vwap"), "exit_add", List.of("close crosses_below vwap")));
        assertThat((List<Object>) ((Map<String, Object>) out.get("entry")).get("all")).containsExactly("close > opening_range_high(15m)", "relative_volume(20) > 1.2",
                "close > vwap");
        assertThat((Map<String, Object>) out.get("exit")).containsEntry("any", List.of("close crosses_below vwap"));
        // a list in a merge patch replaces the whole list
        Map<String, Object> replaced = VariantDelta.apply(base(), Map.of("entry", Map.of("all", List.of("close > vwap"))));
        assertThat((List<Object>) ((Map<String, Object>) replaced.get("entry")).get("all")).containsExactly("close > vwap");
    }

    @Test
    void renamingIsRefusedAndNumbersAreTheParameters() {
        assertThatThrownBy(() -> VariantDelta.apply(base(), Map.of("name", "other"))).hasMessageContaining("cannot rename");
        assertThat(VariantDelta.numbers(base())).containsExactly(Map.entry("entry.all[0]#0", 15.0), Map.entry("entry.all[1]#0", 20.0),
                Map.entry("entry.all[1]#1", 1.2), Map.entry("max_trades_per_day", 1.0), Map.entry("target.value", 2.0));
    }
}
