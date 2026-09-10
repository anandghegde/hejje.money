package money.hejje.agent.internal.tools;

import static money.hejje.agent.internal.tools.ToolSupport.rupees;
import static money.hejje.agent.internal.tools.ToolSupport.schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import money.hejje.agent.AgentTool;
import money.hejje.agent.AgentToolProvider;
import money.hejje.agent.ToolContext;
import money.hejje.agent.ToolException;
import money.hejje.analytics.AnalyticsService;
import money.hejje.analytics.PnlBucket;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.common.time.HejjeClock;
import org.springframework.stereotype.Component;

/** Performance tools ({@code market:read}, as {@code GET /analytics/pnl}): realized P&L of closed round trips grouped by a dimension. */
@Component
public class AnalyticsTools implements AgentToolProvider {

    public record PnlInput(String groupBy, String from, String to) {}

    public record Bucket(String key, String label, int trades, int wins, BigDecimal grossPnl, BigDecimal fees, BigDecimal netPnl, double winRate, Double averageR) {}

    public record PnlBreakdown(String mode, String groupBy, LocalDate from, LocalDate to, int trades, BigDecimal netPnl, List<Bucket> buckets) {}

    static final List<String> GROUPS = List.of("strategy", "version", "instrument", "weekday", "hour", "regime");

    private final AnalyticsService analytics;
    private final HejjeClock clock;

    AnalyticsTools(AnalyticsService analytics, HejjeClock clock) {
        this.analytics = analytics;
        this.clock = clock;
    }

    @Override
    public List<AgentTool> tools() {
        return List.of(AgentTool.of("get_pnl_breakdown", "Realized P&L of closed round trips (net of fees, rupees) grouped by strategy, version, instrument, "
                + "weekday, hour or market regime, between two dates (default today, at most 92 days).", ScopeCatalog.MARKET_READ, schema("""
                        {"type":"object","properties":{"groupBy":{"type":"string","enum":%s},"from":{"type":"string","format":"date"},
                         "to":{"type":"string","format":"date"}},"additionalProperties":false}""".formatted(
                        GROUPS.stream().map(g -> "\"" + g + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]")))),
                PnlInput.class, PnlBreakdown.class, this::pnl));
    }

    PnlBreakdown pnl(PnlInput in, ToolContext ctx) {
        String groupBy = in.groupBy() == null ? "strategy" : in.groupBy();
        LocalDate from = ToolSupport.date(in.from(), clock.today());
        LocalDate to = ToolSupport.date(in.to(), from.isAfter(clock.today()) ? from : clock.today());
        if (to.isBefore(from) || from.plusDays(92).isBefore(to)) {
            throw ToolException.invalid("to must be on or after from and at most 92 days later");
        }
        ExecutionMode mode = analytics.mode();
        List<PnlBucket> buckets = analytics.pnl(groupBy, mode, from.atStartOfDay(clock.zone()).toInstant(), to.plusDays(1).atStartOfDay(clock.zone()).toInstant());
        Money net = buckets.stream().map(PnlBucket::netPnl).reduce(Money.ZERO, Money::plus);
        return new PnlBreakdown(mode.name(), groupBy, from, to, buckets.stream().mapToInt(PnlBucket::trades).sum(), rupees(net),
                buckets.stream().map(b -> new Bucket(b.key(), b.label(), b.trades(), b.wins(), rupees(b.grossPnl()), rupees(b.fees()), rupees(b.netPnl()), b.winRate(),
                        b.averageR())).toList());
    }
}
