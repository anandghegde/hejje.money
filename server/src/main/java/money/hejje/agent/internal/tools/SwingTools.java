package money.hejje.agent.internal.tools;

import static money.hejje.agent.internal.tools.MarketContextTools.NO_INPUT;

import java.util.List;
import money.hejje.agent.AgentTool;
import money.hejje.agent.AgentToolProvider;
import money.hejje.agent.ToolContext;
import money.hejje.agent.internal.tools.MarketContextTools.NoInput;
import money.hejje.common.ExecutionMode;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.swing.SwingBookRow;
import money.hejje.swing.SwingEntries;
import money.hejje.swing.SwingLimits;
import money.hejje.swing.SwingService;
import org.springframework.stereotype.Component;

/** Read-only tools over the swing book (plan M11.6): its positions with their GTTs, today's watched setups, its overnight risk. */
@Component
public class SwingTools implements AgentToolProvider {

    public record SwingBook(String mode, boolean paperOnly, List<SwingBookRow> positions, List<SwingEntries.Watched> setups) {}

    public record SwingRisk(String mode, SwingService.OvernightRisk overnight, SwingLimits limits) {}

    private final SwingService swing;
    private final SwingEntries entries;
    private final HejjeProperties properties;

    SwingTools(SwingService swing, SwingEntries entries, HejjeProperties properties) {
        this.swing = swing;
        this.entries = entries;
        this.properties = properties;
    }

    @Override
    public List<AgentTool> tools() {
        return List.of(
                AgentTool.of("get_swing_book", "The swing book: open delivery (CNC) positions with entry date, sessions held, entry, the stop in force, goal, "
                        + "R, unrealized P&L and the state of the broker-side GTT stop (ACTIVE, MISSING, NONE), plus the base setups watched today with "
                        + "their trigger state. Swing trading is PAPER-only.", ScopeCatalog.MARKET_READ, NO_INPUT, NoInput.class, SwingBook.class, this::book),
                AgentTool.of("get_swing_risk", "The swing book's overnight risk: per position quantity × (stop distance + gap allowance) at the last price, "
                        + "the total against its budget, capital deployed, and the swing limits.", ScopeCatalog.RISK_READ, NO_INPUT, NoInput.class, SwingRisk.class,
                        this::risk));
    }

    SwingBook book(NoInput in, ToolContext ctx) {
        ExecutionMode mode = properties.mode();
        return new SwingBook(mode.name(), true, swing.book(mode), entries.watched());
    }

    SwingRisk risk(NoInput in, ToolContext ctx) {
        ExecutionMode mode = properties.mode();
        return new SwingRisk(mode.name(), swing.overnightRisk(mode), swing.limits(mode));
    }
}
