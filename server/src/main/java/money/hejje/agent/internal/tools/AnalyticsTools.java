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
import money.hejje.analytics.AdherenceReport;
import money.hejje.analytics.AnalyticsService;
import money.hejje.analytics.CounterfactualReport;
import money.hejje.analytics.LossReport;
import money.hejje.analytics.PerformanceMath;
import money.hejje.analytics.SlippageReport;
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

    static final List<String> GROUPS = List.of("strategy", "version", "instrument", "weekday", "hour", "regime", "family", "eventContext", "newsBias", "exitReason");

    private final AnalyticsService analytics;
    private final HejjeClock clock;

    AnalyticsTools(AnalyticsService analytics, HejjeClock clock) {
        this.analytics = analytics;
        this.clock = clock;
    }

    @Override
    public List<AgentTool> tools() {
        List<AgentTool> tools = new java.util.ArrayList<>(investigationTools());
        tools.add(pnlTool());
        return tools;
    }

    AgentTool pnlTool() {
        return AgentTool.of("get_pnl_breakdown", "Realized P&L of closed round trips (net of fees, rupees) grouped by strategy, version, instrument, "
                + "weekday, hour, market regime, strategy family, event risk at entry (eventContext), news bias at entry (newsBias) or exit reason, between two "
                + "dates (default today, at most 92 days).", ScopeCatalog.MARKET_READ, schema("""
                        {"type":"object","properties":{"groupBy":{"type":"string","enum":%s},"from":{"type":"string","format":"date"},
                         "to":{"type":"string","format":"date"}},"additionalProperties":false}""".formatted(
                        GROUPS.stream().map(g -> "\"" + g + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]")))),
                PnlInput.class, PnlBreakdown.class, this::pnl);
    }

    public record PeriodInput(String from, String to) {}

    public record ExcludeInput(List<String> regimes, List<String> trends, List<String> families, List<String> strategies, List<String> events, List<String> news,
            List<Integer> hours, List<String> instruments) {}

    public record CounterfactualInput(String from, String to, ExcludeInput exclude) {}

    static final String PERIOD_PROPS = "\"from\":{\"type\":\"string\",\"format\":\"date\",\"description\":\"Default the first day of this month\"},"
            + "\"to\":{\"type\":\"string\",\"format\":\"date\",\"description\":\"Default today\"}";

    List<AgentTool> investigationTools() {
        String strings = "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"maxItems\":20}";
        return List.of(
                AgentTool.of("get_loss_attribution", "Where the period's losses came from (default month to date): totals, and per strategy family, strategy, "
                        + "trend, regime, event risk at entry, news bias at entry, exit reason, hour and instrument each bucket's share of all losses; plus family × "
                        + "trend combinations and a templated headline.", ScopeCatalog.MARKET_READ,
                        schema("{\"type\":\"object\",\"properties\":{" + PERIOD_PROPS + "},\"additionalProperties\":false}"), PeriodInput.class,
                        LossReport.class, (in, c) -> analytics.losses(analytics.mode(), period(in.from(), in.to())[0], period(in.from(), in.to())[1])),
                AgentTool.of("get_slippage_stats", "Entry and exit slippage in basis points (mean, median, p90, worst; positive = worse) with its estimated cost "
                        + "in rupees and per strategy, for the period (default month to date).", ScopeCatalog.MARKET_READ,
                        schema("{\"type\":\"object\",\"properties\":{" + PERIOD_PROPS + "},\"additionalProperties\":false}"), PeriodInput.class,
                        SlippageReport.class, (in, c) -> analytics.slippage(analytics.mode(), period(in.from(), in.to())[0], period(in.from(), in.to())[1])),
                AgentTool.of("get_rule_adherence", "Rule adherence of reviewed trades (mean %, fully adherent, invalid setups, manual exits, net P&L of adherent "
                        + "vs partly adherent trades, per strategy) for the period (default month to date).", ScopeCatalog.MARKET_READ,
                        schema("{\"type\":\"object\",\"properties\":{" + PERIOD_PROPS + "},\"additionalProperties\":false}"), PeriodInput.class,
                        AdherenceReport.class, (in, c) -> analytics.adherence(analytics.mode(), period(in.from(), in.to())[0], period(in.from(), in.to())[1])),
                AgentTool.of("run_counterfactual", "SIMULATED what-if over the period's actual trades: remove the trades matching every given category (e.g. "
                        + "families [MEAN_REVERSION] and trends [STRONG_UP]) and recompute net P&L, max drawdown, win rate and profit factor. Returns the actual "
                        + "figures alongside and basis SIMULATED; always present it as hypothetical.", ScopeCatalog.MARKET_READ, schema("""
                                {"type":"object","properties":{%s,"exclude":{"type":"object","properties":{"regimes":%s,"trends":%s,"families":%s,"strategies":%s,
                                 "events":%s,"news":%s,"hours":{"type":"array","items":{"type":"integer","minimum":0,"maximum":23},"maxItems":24},"instruments":%s},
                                 "additionalProperties":false}},"required":["exclude"],"additionalProperties":false}"""
                                .formatted(PERIOD_PROPS, strings, strings, strings, strings, strings, strings, strings)),
                        CounterfactualInput.class, CounterfactualReport.class, this::counterfactual));
    }

    CounterfactualReport counterfactual(CounterfactualInput in, ToolContext ctx) {
        ExcludeInput e = in.exclude();
        PerformanceMath.CounterfactualFilter filter = new PerformanceMath.CounterfactualFilter(e.regimes(), e.trends(), e.families(), e.strategies(), e.events(),
                e.news(), e.hours(), e.instruments());
        if (filter.isEmpty()) {
            throw ToolException.invalid("exclude needs at least one non-empty category");
        }
        LocalDate[] p = period(in.from(), in.to());
        return analytics.counterfactual(analytics.mode(), p[0], p[1], filter);
    }

    /** Month to date by default; at most 92 days. */
    LocalDate[] period(String fromText, String toText) {
        LocalDate to = ToolSupport.date(toText, clock.today());
        LocalDate from = ToolSupport.date(fromText, to.withDayOfMonth(1));
        if (to.isBefore(from) || from.plusDays(92).isBefore(to)) {
            throw ToolException.invalid("to must be on or after from and at most 92 days later");
        }
        return new LocalDate[] {from, to};
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
