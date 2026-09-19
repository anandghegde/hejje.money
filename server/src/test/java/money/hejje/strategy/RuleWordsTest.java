package money.hejje.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import money.hejje.strategy.dsl.ConditionParser;
import org.junit.jupiter.api.Test;

class RuleWordsTest {

    @Test
    void conditionsReadAsSentences() {
        assertThat(RuleWords.condition(ConditionParser.parse("close > opening_range_high(15m)"))).isEqualTo("the close is above the 15-minute opening-range high");
        assertThat(RuleWords.condition(ConditionParser.parse("close < vwap * 0.997"))).isEqualTo("the close is below VWAP × 0.997");
        assertThat(RuleWords.condition(ConditionParser.parse("close > close[1]"))).isEqualTo("the close is above the close one bar earlier");
        assertThat(RuleWords.condition(ConditionParser.parse("ema(9) crosses_above ema(21)"))).isEqualTo("the 9-bar EMA crosses above the 21-bar EMA");
        assertThat(RuleWords.condition(ConditionParser.parse("relative_volume(20) >= 1.5"))).isEqualTo("relative volume (20 sessions) is at or above 1.5");
        assertThat(RuleWords.condition(ConditionParser.parse("rsi(14) <= 30"))).isEqualTo("RSI(14) is at or below 30");
        assertThat(RuleWords.condition(ConditionParser.parse("session_low == session_open"))).isEqualTo("today's low so far equals today's open");
        assertThat(RuleWords.condition(ConditionParser.parse("close crosses_above cpr_top"))).isEqualTo("the close crosses above the top of the central pivot range");
        assertThat(RuleWords.condition(ConditionParser.parse("close crosses_below supertrend(10, 3)"))).isEqualTo("the close crosses below Supertrend(10, 3)");
        assertThat(RuleWords.condition(ConditionParser.parse("opening_return(30m) > 0")))
                .isEqualTo("the % return from the previous close to the close 30 minutes after the open is above 0");
    }

    @Test
    void bundledOrbReadsAsRules() throws Exception {
        String yaml = Files.readString(Path.of("../strategies/nifty_orb.yaml"));
        StrategyDefinition d = new money.hejje.strategy.internal.DefinitionParser().parse(yaml);
        List<String> words = RuleWords.describe(d);
        assertThat(words.get(0)).startsWith("Long on ").endsWith(", 5-minute bars.");
        assertThat(words).contains("Enter when all of these hold at a bar close:", "• the close is above the 15-minute opening-range high", "• the close is above VWAP",
                "• relative volume (20 sessions) is above 1.2", "Stop: below the opening-range low.", "Target: 2R (2 × the risk).",
                "New entries only between 09:30 and 12:00; force exit at 15:10.", "At most 1 trade a day.",
                "Risks 2000.00 rupees a trade; the quantity is sized from the stop distance.", "No new entries within 15 minutes of a high-risk event.");
        assertThat(words).anyMatch(w -> w.startsWith("Prefers trending sessions; avoids ranging sessions"));
    }
}
